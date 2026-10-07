package com.bluise.iptv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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

class PlayerEngine {

    companion object {

        private const val TAG = "PlayerEngine"

        const val IS_SPECIAL_EDITION = true

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

        @JvmStatic
        @Volatile
        var isProxyEnabled = false

        private val mainHandler = Handler(Looper.getMainLooper())

        private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
        private val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies.toMutableList()
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }

        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 15
        }

        private val FAKE_ENDLIST_HOSTS = setOf("bd.drmlive.net")

        private val ipv4Dns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val addrs = Dns.SYSTEM.lookup(hostname)
                val v4 = addrs.filter { it is Inet4Address }
                return if (v4.isNotEmpty()) v4 else addrs
            }
        }

        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS) 
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .hostnameVerifier { _, _ -> true }
            .cookieJar(cookieJar)
            .addNetworkInterceptor { chain ->
                var request = chain.request()
                val builder = request.newBuilder().header("Connection", "keep-alive")
                request = builder.build()
                chain.proceed(request)
            }
            .addInterceptor { chain ->
                var request = chain.request()
                val url     = request.url
                val urlStr  = url.toString()
                val host    = url.host

                val isVercel = host.contains("vercel.app", ignoreCase = true)
                if (isVercel) {
                    val bustedUrl = url.newBuilder()
                        .addQueryParameter("_t", System.currentTimeMillis().toString())
                        .build()
                    request = request.newBuilder()
                        .url(bustedUrl)
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build()
                }

                if (urlStr.contains("sourcefail", ignoreCase = true)) {
                    throw IOException("Fake source URL detected - retrying")
                }

                val response = chain.proceed(request)

                val isM3u8 = urlStr.contains(".m3u8", ignoreCase = true)
                val isBadHost = FAKE_ENDLIST_HOSTS.any { host.contains(it, ignoreCase = true) }

                if (isM3u8 && isBadHost && response.isSuccessful) {
                    val cType = response.header("Content-Type", "")?.lowercase() ?: ""
                    if (cType.contains("text") || cType.contains("mpegurl") || cType.isEmpty()) {
                        val body = response.body
                        if (body != null) {
                            val originalCT = body.contentType()
                            val content    = body.string()
                            
                            if (content.contains("sourcefail", ignoreCase = true) ||
                                content.contains("#EXT-X-ENDLIST")) {
                                throw IOException("Fake ENDLIST detected - retrying")
                            }
                            
                            return@addInterceptor response.newBuilder()
                                .body(content.toResponseBody(originalCT))
                                .build()
                        }
                    }
                }
                response
            }
            .build()

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
            } catch (e: Exception) { }
        }

        @Volatile private var resolverVersion  = 0
        private val activeResolverLock = Any()
        private var activeResolverCall: Call?  = null

        // 🔥 CRITICAL FIX: Memory Protection for background resolver
        private val resolverExecutor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "resolver-worker").apply { isDaemon = true }
        }

        fun createPlayer(context: Context): ExoPlayer {
            updateProxyState(context)

            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)
                .apply {
                    if (android.os.Build.VERSION.SDK_INT >= 23) forceEnableMediaCodecAsynchronousQueueing()
                }

            val trackSelector = DefaultTrackSelector(context).apply {
                parameters = buildUponParameters()
                    .setTunnelingEnabled(false)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedChannelCountAdaptiveness(true)
                    .setAudioOffloadPreferences(
                        AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                            .build()
                    )
                    .build()
            }

            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(32_000, 65_000, 1_000, 4_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            return ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
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
                    setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
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
                } catch (e: Exception) { }
            }

            if (!headers.containsKey("Referer") && actualUrl.contains(".php", ignoreCase = true)) {
                headers["Referer"] = "https://www.google.com/"
                headers["Origin"]  = "https://www.google.com"
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
                lower.contains(".m3u8") || lower.contains("=m3u8") || lower.contains("vercel.app") -> MimeTypes.APPLICATION_M3U8
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

            val channelClient = okHttpClient.newBuilder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val builder  = original.newBuilder()
                    headers.forEach { (key, value) -> builder.header(key, value) }
                    chain.proceed(builder.build())
                }.build()

            val httpFactory = OkHttpDataSource.Factory(channelClient)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)

            var drmSessionManager: DefaultDrmSessionManager? = null
            var drmConfigUuid: UUID? = null

            val licenseUrl = channel.drmLicenseUrl?.trim() ?: ""
            val drmScheme  = channel.drmScheme?.trim()?.lowercase() ?: ""

            // 🔥 NEW: Multi-Key Extractor Logic
            val clearkeys = mutableMapOf<String, String>()
            
            fun extractPairs(input: String?) {
                if (input.isNullOrBlank() || input.startsWith("http") || input.startsWith("{")) return
                val items = input.split(",")
                for (item in items) {
                    val delimIdx = item.indexOfFirst { it == ':' || it == '=' }
                    if (delimIdx != -1) {
                        val kId = item.substring(0, delimIdx).replace("-", "").trim()
                        val kVal = item.substring(delimIdx + 1).trim()
                        if (kId.length >= 16 && kVal.length >= 16) clearkeys[kId] = kVal
                    }
                }
            }

            extractPairs(channel.drmKey)
            extractPairs(channel.drmLicenseUrl)
            
            if (!channel.drmKeyId.isNullOrBlank() && !channel.drmKey.isNullOrBlank()) {
                val kId = channel.drmKeyId.replace("-", "").trim()
                val kVal = channel.drmKey.trim()
                if (kId.isNotEmpty() && kVal.isNotEmpty() && !kVal.contains(":")) {
                    clearkeys[kId] = kVal
                }
            }

            val hasLicense = licenseUrl.isNotEmpty()
            val hasLocalKeys = clearkeys.isNotEmpty()

            when {
                hasLicense && licenseUrl.startsWith("{") && licenseUrl.contains("\"keys\"") -> {
                    val cb = LocalClearKeyCallback(emptyMap(), licenseUrl)
                    drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true).build(cb)
                    drmConfigUuid = C.CLEARKEY_UUID
                }

                hasLocalKeys -> {
                    Log.d(TAG, "DRM: Extracted Local Keys Count = ${clearkeys.size}")
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

                hasLicense -> {
                    val cb = HttpClearKeyUrlCallback(licenseUrl, okHttpClient, headers)
                    drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true).build(cb)
                    drmConfigUuid = C.CLEARKEY_UUID
                }
            }

            val liveConfig = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(25_000).build()

            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl).setLiveConfiguration(liveConfig)
            forcedMimeType?.let { mediaItemBuilder.setMimeType(it) }
            drmConfigUuid?.let { mediaItemBuilder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(it).build()) }

            val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
            drmSessionManager?.let { mgr -> mediaSourceFactory.setDrmSessionManagerProvider { mgr } }
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

            var debugListener: Player.Listener? = null
            debugListener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    debugListener?.let { player.removeListener(it) }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                        debugListener?.let { player.removeListener(it) }
                    }
                }
            }

            try {
                player.addListener(debugListener)
                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = true
            } catch (e: Exception) {
                debugListener?.let { player.removeListener(it) }
            }
        }

        private fun isClearKeyUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("clearkey") || lower.contains("key.php") || lower.contains("plkey") || lower.contains("temp.webplay")
        }

    } 

    // 🔥 NEW: Multi-Key JSON Generator 
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
            val host = licenseUrl.toHttpUrl().host

            val http1Client = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

            val req = Request.Builder()
                .url(licenseUrl)
                .post(body)
                .header("Host", host)
                .header("Content-Type", "application/json")
                .header("Connection", "keep-alive")
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 12; Generic Build/SQ3A.220705.004)")
                .build()

            http1Client.newCall(req).execute().use { response ->
                val responseBytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) throw IOException("ClearKey POST failed: ${response.code}")
                return MediaDrmCallback.Response(responseBytes)
            }
        }
    }
}
