package com.bluise.iptv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import okhttp3.*
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// 🔥 SPEED OPTIMIZATION IMPORTS
import javax.net.SocketFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

class PlayerEngine {

    companion object {

        private const val TAG = "PlayerEngine"

        // 🔥 FIX (#16): valid Android version + version numbers consistent with current stack
        private const val DEFAULT_UA =
            "OTT Navigator/1.7.6 (Linux;Android 14) ExoPlayerLib/2.19.1"

        @JvmStatic
        @Volatile
        var isProxyEnabled = false

        private val mainHandler = Handler(Looper.getMainLooper())

        private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

        // 🔥 FIX (#17): bounded cookie store — stale/empty/expired host entries are now
        // actively removed instead of silently accumulating forever.
        private val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val validCookies = cookies.filter { cookie ->
                    val isExpired = cookie.expiresAt < System.currentTimeMillis()
                    val isAkamaiPoison = cookie.name.lowercase() == "hdntl" && cookie.value.lowercase() == "expired"
                    !isExpired && !isAkamaiPoison
                }
                if (validCookies.isNotEmpty()) {
                    cookieStore[url.host] = validCookies.toMutableList()
                } else {
                    cookieStore.remove(url.host)
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val cookies = cookieStore[url.host] ?: return emptyList()
                val stillValid = cookies.filter { it.expiresAt > System.currentTimeMillis() }
                when {
                    stillValid.isEmpty() -> cookieStore.remove(url.host)
                    stillValid.size != cookies.size -> cookieStore[url.host] = stillValid.toMutableList()
                }
                return stillValid
            }
        }

        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 15
        }

        // ============================================================
        // 🔥 Tuned Socket Factory — OS level TCP buffer size increase
        // ============================================================
        private val tunedSocketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = Socket().apply {
                tcpNoDelay = true
                receiveBufferSize = 256 * 1024
                sendBufferSize = 64 * 1024
            }
            override fun createSocket(host: String?, port: Int): Socket =
                createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: InetAddress?, port: Int): Socket =
                createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                createSocket().apply { connect(InetSocketAddress(address, port)) }
        }

        // HTTP/2 PREFERRED, HTTP/1.1 as automatic fallback.
        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .socketFactory(tunedSocketFactory)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .hostnameVerifier { _, _ -> true }
            .cookieJar(cookieJar)
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Connection", "keep-alive")
                    .build()
                chain.proceed(request)
            }
            .build()

        // ============================================================
        // Fast Load Error/Retry Policy
        // ============================================================
        private val fastLoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                return when (loadErrorInfo.errorCount) {
                    1 -> 200L
                    2 -> 500L
                    else -> 1000L
                }
            }
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 3
        }

        private fun updateProxyState(context: Context) {
            val prefs    = context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
            val newState = prefs.getBoolean("vps_proxy_enabled", true)
            if (newState != isProxyEnabled) {
                isProxyEnabled = newState
                Log.d(TAG, "UI Proxy Switch changed to: $isProxyEnabled")
            }
        }

        private fun clearCookiesForUrl(url: String) {
            try {
                val host = url.toHttpUrl().host
                cookieStore.remove(host)
            } catch (e: Exception) {
                Log.e(TAG, "clearCookiesForUrl failed for $url: ${e.message}")
            }
        }

        @Volatile private var resolverVersion  = 0
        private val activeResolverLock = Any()
        private var activeResolverCall: Call?  = null

        // 🔥 FIX (#2 & #4): per-player tracking so old listeners/DRM sessions are released
        // on channel switch instead of leaking.
        private val activeListeners = ConcurrentHashMap<Int, Player.Listener>()
        private val activeDrmManagers = ConcurrentHashMap<Int, DefaultDrmSessionManager>()

        private val resolverExecutor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "resolver-worker").apply { isDaemon = true }
        }

        fun createPlayer(context: Context): ExoPlayer {
            updateProxyState(context)

            // 🔥 FIX (#11): AV-sync PTS-shift hack removed entirely — it was a hardcoded
            // single-device workaround that also risked conflicting with hardware tunneling.
            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

            // Tunneling kept TRUE as requested
            val trackSelector = DefaultTrackSelector(context).apply {
                parameters = DefaultTrackSelector.Parameters.Builder(context)
                    .setTunnelingEnabled(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(false)
                    .setAllowAudioMixedChannelCountAdaptiveness(false)
                    .setViewportSizeToPhysicalDisplaySize(context, true)
                    .setAudioOffloadPreferences(
                        AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                            .build()
                    )
                    .build()
            }

            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(20_000, 40_000, 500, 5_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                // 🔥 FIX (#9): removed no-op setTargetBufferBytes(C.LENGTH_UNSET) — it's already
                // the default behavior when setPrioritizeTimeOverSizeThresholds(true) is set.
                .build()

            val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
                .setResetOnNetworkTypeChange(true)
                .build()

            return ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true
                    )
                    setHandleAudioBecomingNoisy(true)
                }
        }

        fun playChannel(context: Context, player: ExoPlayer, channel: Channel) {
            updateProxyState(context)

            synchronized(activeResolverLock) {
                activeResolverCall?.cancel()
                activeResolverCall = null
            }
            val currentVersion = ++resolverVersion

            val actualUrl = channel.url.trim()
            clearCookiesForUrl(actualUrl)

            val headers = channel.getHeadersMap().toMutableMap()
            if (!channel.cookie.isNullOrBlank()) headers["Cookie"] = channel.cookie!!

            if (headers.containsKey("Referer") && !headers.containsKey("Origin")) {
                try {
                    val refUrl = headers["Referer"]!!
                    val uri    = java.net.URI(refUrl)
                    headers["Origin"] = "${uri.scheme}://${uri.host}"
                } catch (e: Exception) {
                    Log.e(TAG, "Origin header derivation failed: ${e.message}")
                }
            }

            val userAgent = headers.getOrElse("User-Agent") { DEFAULT_UA }
            headers["User-Agent"] = userAgent

            val forcedMime = detectMimeType(actualUrl)

            if (isMacBasedIptv(actualUrl)) {
                startPlayback(context, player, channel, headers, userAgent, actualUrl, null, currentVersion)
                return
            }

            when {
                isDirectTs(actualUrl) -> startPlayback(context, player, channel, headers, userAgent, actualUrl, null, currentVersion)
                needsResolution(actualUrl) -> resolveAndPlay(context, player, channel, headers, userAgent, actualUrl, forcedMime, currentVersion)
                else -> startPlayback(context, player, channel, headers, userAgent, actualUrl, forcedMime, currentVersion)
            }
        }

        private fun detectMimeType(url: String): String? {
            val lower = url.lowercase()
            return when {
                lower.contains(".mpd") || lower.contains("=mpd")  -> MimeTypes.APPLICATION_MPD
                lower.contains(".m3u8") || lower.contains("=m3u8") -> MimeTypes.APPLICATION_M3U8
                lower.endsWith(".ts")  || lower.contains(".ts?") || lower.contains("=ts")  -> null
                else -> null
            }
        }

        private fun isDirectTs(url: String): Boolean {
            val lower = url.lowercase()
            return lower.endsWith(".ts") || lower.contains(".ts?") || lower.contains("=ts")
        }

        private fun needsResolution(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains(".php") || lower.contains("vercel.app") || run {
                try { !java.net.URL(url).path.contains(".") } catch (e: Exception) { false }
            }
        }

        private fun isMacBasedIptv(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("mac=") && (
                lower.contains(".php") || lower.contains("stream=") || lower.contains("extension=ts") || lower.contains("extension=m3u8") || lower.contains("type=")
            )
        }

        private fun resolveAndPlay(
            context: Context, player: ExoPlayer, channel: Channel, headers: MutableMap<String, String>,
            userAgent: String, url: String, originalMime: String?, version: Int
        ) {
            resolverExecutor.execute {
                try {
                    if (version != resolverVersion) return@execute

                    val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
                    val call = okHttpClient.newCall(request)

                    synchronized(activeResolverLock) { activeResolverCall = call }

                    call.execute().use { response ->
                        if (version != resolverVersion) return@use

                        val finalUrl = response.request.url.toString()
                        val cType    = response.header("Content-Type", "")?.lowercase() ?: ""
                        val bodyStr  = response.body?.string()?.trim() ?: ""

                        val (resolvedUrl, resolvedMime) = resolveUrlAndMime(url, finalUrl, cType, bodyStr, originalMime)

                        mainHandler.post {
                            if (version != resolverVersion) return@post
                            startPlayback(context, player, channel, headers, userAgent, resolvedUrl, resolvedMime, version)
                        }
                    }
                } catch (e: Exception) {
                    if (version != resolverVersion) return@execute
                    // 🔥 FIX (#3): no more silent failure — log what actually went wrong
                    Log.e(TAG, "URL resolution failed for $url: ${e.message}", e)
                    mainHandler.post {
                        if (version != resolverVersion) return@post
                        startPlayback(context, player, channel, headers, userAgent, url, originalMime ?: MimeTypes.APPLICATION_M3U8, version)
                    }
                } finally {
                    synchronized(activeResolverLock) {
                        if (activeResolverCall?.request()?.url?.toString() == url) activeResolverCall = null
                    }
                }
            }
        }

        private fun resolveUrlAndMime(originalUrl: String, finalUrl: String, contentType: String, body: String, originalMime: String?): Pair<String, String?> {
            if ((contentType.contains("text") || contentType.contains("html")) && body.startsWith("http") && !body.contains("\n") && !body.contains("#EXTM3U")) {
                return body to detectMimeType(body)
            }
            if (body.startsWith("#EXTM3U")) return originalUrl to MimeTypes.APPLICATION_M3U8
            when {
                contentType.contains("video/mp2t") || contentType.contains("video/mpeg") -> return finalUrl to null
                contentType.contains("application/dash+xml") -> return finalUrl to MimeTypes.APPLICATION_MPD
                contentType.contains("mpegurl") || contentType.contains("m3u8") -> return finalUrl to MimeTypes.APPLICATION_M3U8
            }
            detectMimeType(finalUrl)?.let { return finalUrl to it }
            return originalUrl to (originalMime ?: MimeTypes.APPLICATION_M3U8)
        }

        private fun startPlayback(
            context: Context, player: ExoPlayer, channel: Channel, headers: Map<String, String>,
            userAgent: String, streamUrl: String, forcedMimeType: String?, version: Int
        ) {
            if (version != resolverVersion) return

            // 🔥 FIX (#2 & #4): release previous listener + DRM session manager tied to THIS
            // player before starting new playback — prevents listener/DRM leaks on fast
            // channel switching (very common in IPTV zapping).
            val playerKey = System.identityHashCode(player)
            activeListeners.remove(playerKey)?.let { player.removeListener(it) }
            activeDrmManagers.remove(playerKey)?.release()

            // 🔥 FIX (#5): reuse the shared okHttpClient directly. The previous per-channel
            // client + network interceptor was re-applying headers that
            // setDefaultRequestProperties() already sets — pure duplicate work on every
            // segment request, rebuilt on every single channel switch.
            val httpFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)

            var drmSessionManager: DefaultDrmSessionManager? = null
            var drmConfigUuid: UUID? = null

            val licenseUrl = channel.drmLicenseUrl?.trim() ?: ""
            val drmScheme  = channel.drmScheme?.trim()?.lowercase() ?: ""

            val clearkeys = mutableMapOf<String, String>()
            val hexRegex = Regex("^[0-9a-fA-F]+$")

            fun extractPairs(input: String?) {
                if (input.isNullOrBlank() || input.startsWith("http") || input.startsWith("{")) return
                val items = input.split(",")
                for (item in items) {
                    val delimIdx = item.indexOfFirst { it == ':' || it == '=' }
                    if (delimIdx != -1) {
                        val kId = item.substring(0, delimIdx).replace("-", "").trim()
                        val kVal = item.substring(delimIdx + 1).trim()
                        // 🔥 FIX (#15): require a proper 16-byte hex key (32 hex chars) for
                        // both KID and key — previously `length >= 16` accepted garbage/partial
                        // strings as valid DRM keys.
                        if (kId.length == 32 && kVal.length == 32 && hexRegex.matches(kId) && hexRegex.matches(kVal)) {
                            clearkeys[kId] = kVal
                        }
                    }
                }
            }

            extractPairs(channel.drmKey)
            extractPairs(channel.drmLicenseUrl)

            if (!channel.drmKeyId.isNullOrBlank() && !channel.drmKey.isNullOrBlank()) {
                val kId = channel.drmKeyId.replace("-", "").trim()
                val kVal = channel.drmKey.trim()
                if (kId.length == 32 && kVal.length == 32 && hexRegex.matches(kId) && hexRegex.matches(kVal) && !kVal.contains(":")) {
                    clearkeys[kId] = kVal
                }
            }

            val hasLicense = licenseUrl.isNotEmpty()
            val hasLocalKeys = clearkeys.isNotEmpty()

            try {
                when {
                    hasLicense && licenseUrl.startsWith("{") && licenseUrl.contains("\"keys\"") -> {
                        val cb = LocalClearKeyCallback(emptyMap(), licenseUrl)
                        drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true).build(cb)
                        drmConfigUuid = C.CLEARKEY_UUID
                    }

                    hasLocalKeys -> {
                        val cb = LocalClearKeyCallback(clearkeys, null)
                        drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true).build(cb)
                        drmConfigUuid = C.CLEARKEY_UUID
                    }

                    hasLicense && (drmScheme == "clearkey" || isClearKeyUrl(licenseUrl)) -> {
                        val cb = HttpClearKeyUrlCallback(licenseUrl, okHttpClient, headers)
                        drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true).build(cb)
                        drmConfigUuid = C.CLEARKEY_UUID
                    }

                    hasLicense && drmScheme == "widevine" -> {
                        val cb = HttpMediaDrmCallback(licenseUrl, httpFactory)
                        headers.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
                        drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true).build(cb)
                        drmConfigUuid = C.WIDEVINE_UUID
                    }

                    // 🔥 FIX (#14): only silently default to ClearKey when NO scheme was given
                    // at all (common for simple IPTV playlists). An explicitly set but
                    // unsupported scheme no longer gets force-fit into ClearKey.
                    hasLicense && drmScheme.isBlank() -> {
                        Log.w(TAG, "DRM scheme not specified for $licenseUrl — defaulting to ClearKey")
                        val cb = HttpClearKeyUrlCallback(licenseUrl, okHttpClient, headers)
                        drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true).build(cb)
                        drmConfigUuid = C.CLEARKEY_UUID
                    }

                    hasLicense -> {
                        Log.e(TAG, "Unsupported DRM scheme '$drmScheme' for $licenseUrl — skipping DRM setup")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DRM Configuration failed: ${e.message}", e)
            }

            // 🔥 FIX (#7): targetOffsetMs (was 45s) must stay below loadControl's maxBufferMs
            // (40s) — otherwise the player targets a live cushion bigger than it's allowed
            // to buffer, causing it to constantly fight to catch up.
            val liveConfig = MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(30_000)
                .setMinPlaybackSpeed(0.96f)
                .setMaxPlaybackSpeed(1.04f)
                .build()

            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl).setLiveConfiguration(liveConfig)
            forcedMimeType?.let { mediaItemBuilder.setMimeType(it) }
            drmConfigUuid?.let { mediaItemBuilder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(it).build()) }

            val mediaSourceFactory = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(httpFactory)
                .setLoadErrorHandlingPolicy(fastLoadErrorHandlingPolicy)
            drmSessionManager?.let { mgr -> mediaSourceFactory.setDrmSessionManagerProvider { mgr } }
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

            var debugListener: Player.Listener? = null
            debugListener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 🔥 FIX (#3): log the real error instead of swallowing it
                    Log.e(TAG, "Playback error: ${error.errorCodeName} - ${error.message}", error)
                    debugListener?.let {
                        player.removeListener(it)
                        activeListeners.remove(playerKey, it)
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                        debugListener?.let {
                            player.removeListener(it)
                            activeListeners.remove(playerKey, it)
                        }
                    }
                }
            }

            try {
                player.addListener(debugListener)
                activeListeners[playerKey] = debugListener
                drmSessionManager?.let { activeDrmManagers[playerKey] = it }

                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = true
            } catch (e: Exception) {
                // 🔥 FIX (#3): log + clean up on failure instead of silently swallowing
                Log.e(TAG, "startPlayback failed: ${e.message}", e)
                debugListener?.let {
                    player.removeListener(it)
                    activeListeners.remove(playerKey, it)
                }
                activeDrmManagers.remove(playerKey)?.release()
            }
        }

        private fun isClearKeyUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("clearkey") || lower.contains("key.php") || lower.contains("plkey") || lower.contains("temp.webplay")
        }

    }

    private class LocalClearKeyCallback(
        private val keysMap: Map<String, String>,
        private val inlineJson: String? = null
    ) : MediaDrmCallback {

        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest) = MediaDrmCallback.Response(ByteArray(0))

        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
            if (inlineJson != null) return MediaDrmCallback.Response(inlineJson.toByteArray(Charsets.UTF_8))

            val keysJsonArray = keysMap.entries.joinToString(",") { (kid, key) ->
                val kidB64 = hexToBase64Url(kid)
                val keyB64 = hexToBase64Url(key)
                """{"kty":"oct","k":"$keyB64","kid":"$kidB64"}"""
            }

            val json = """{"keys":[$keysJsonArray],"type":"temporary"}"""
            return MediaDrmCallback.Response(json.toByteArray(Charsets.UTF_8))
        }

        private fun hexToBase64Url(hex: String): String {
            val clean = hex.replace("-", "").trim()
            if (clean.length != 32) return clean
            return try {
                val bytes = ByteArray(16) { i ->
                    ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
                }
                Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            } catch (e: Exception) { clean }
        }
    }

    private class HttpClearKeyUrlCallback(
        private val licenseUrl: String,
        private val client: OkHttpClient,
        private val headers: Map<String, String>
    ) : MediaDrmCallback {

        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest) = MediaDrmCallback.Response(ByteArray(0))

        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
            val requestData = request.data
            val body = requestData.toRequestBody("application/json".toMediaType())

            val host = try {
                licenseUrl.toHttpUrl().host
            } catch (e: Exception) {
                ""
            }

            // NOTE: ClearKey license POST kept on HTTP/1.1 deliberately —
            // some license servers don't handle HTTP/2 POST bodies reliably.
            val http1Client = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

            val userAgent = headers["User-Agent"] ?: PlayerEngine.DEFAULT_UA

            val reqBuilder = Request.Builder()
                .url(licenseUrl)
                .post(body)
                .header("Content-Type", "application/json")
                .header("Connection", "keep-alive")
                .header("User-Agent", userAgent)

            if (host.isNotEmpty()) {
                reqBuilder.header("Host", host)
            }

            http1Client.newCall(reqBuilder.build()).execute().use { response ->
                val responseBytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) throw IOException("ClearKey POST failed: ${response.code}")
                return MediaDrmCallback.Response(responseBytes)
            }
        }
    }
}
