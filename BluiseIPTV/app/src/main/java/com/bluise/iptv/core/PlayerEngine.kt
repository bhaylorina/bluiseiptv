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

    // ── Proxy state (Kept for UI switch compatibility so app doesn't crash) ──
    @JvmStatic
    @Volatile
    var isProxyEnabled = false

    // ── Main thread handler ───────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Cookie store ──────────────────────────────────────────────────────
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies.toMutableList()
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    // ── OkHttp dispatcher ─────────────────────────────────────────────────
    val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 15
    }

    // ── Problematic hosts that send fake ENDLIST ──────────────────────────
    private val FAKE_ENDLIST_HOSTS = setOf(
        "bd.drmlive.net"
    )

    // ── IPv4 Forcing DNS ──────────────────────────────────────────────────
    private val ipv4Dns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val addrs = Dns.SYSTEM.lookup(hostname)
            // Hamesha IPv4 (Inet4Address) ko priority do
            val v4 = addrs.filter { it is Inet4Address }
            return if (v4.isNotEmpty()) v4 else addrs
        }
    }

    // ── Main OkHttp client ────────────────────────────────────────────────
        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
        // Yahan se .dns(ipv4Dns) hata diya hai
        .connectTimeout(5, TimeUnit.SECONDS) // 10s se 5s kar diya
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .hostnameVerifier { _, _ -> true }
        .cookieJar(cookieJar)


                // ── Network interceptor — runs AFTER OkHttp adds its own headers ──
        .addNetworkInterceptor { chain ->
            var request = chain.request()
            val builder = request.newBuilder()
                .header("Connection", "keep-alive")
            
            // Yahan se isMpd aur Accept-Encoding wala logic pura hata diya hai!
            
            request = builder.build()
            chain.proceed(request)
        }

        // ── Application interceptor ───────────────────────────────────────
        .addInterceptor { chain ->
            var request = chain.request()
            val url     = request.url
            val urlStr  = url.toString()
            val host    = url.host

            // ── Cache buster — only for Vercel, NOT all M3U8 ──────────────
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

            // ── Block known fake URL patterns before network call ─────────
            if (urlStr.contains("sourcefail", ignoreCase = true)) {
                throw IOException("Fake source URL detected - retrying")
            }

            val response = chain.proceed(request)

            // ── Host-specific fake ENDLIST detection ──────────────────────
            val isM3u8 = urlStr.contains(".m3u8", ignoreCase = true)
            val isBadHost = FAKE_ENDLIST_HOSTS.any { 
                host.contains(it, ignoreCase = true) 
            }

            if (isM3u8 && isBadHost && response.isSuccessful) {
                val cType = response.header("Content-Type", "")?.lowercase() ?: ""
                if (cType.contains("text") || cType.contains("mpegurl") || cType.isEmpty()) {
                    val body = response.body
                    if (body != null) {
                        val originalCT = body.contentType()
                        val content    = body.string()
                        
                        // Check for fake ENDLIST patterns
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

    // ── Proxy state manager (Safely modified to prevent UI crashes) ───────
    private fun updateProxyState(context: Context) {
        val prefs    = context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
        val newState = prefs.getBoolean("vps_proxy_enabled", true)
        if (newState != isProxyEnabled) {
            isProxyEnabled = newState
            Log.d(TAG, "UI Proxy Switch changed to: $isProxyEnabled (DNS is forced IPv4)")
        }
    }

    // ── Cookie cleaner ────────────────────────────────────────────────────
    private fun clearCookiesForUrl(url: String) {
        try {
            val host = url.toHttpUrl().host
            cookieStore.remove(host)
        } catch (e: Exception) {
            Log.w(TAG, "Cookie clear skipped for: $url")
        }
    }

    // ── Active resolver tracking — prevents stale channel from loading ────
    @Volatile private var resolverVersion  = 0
    private val activeResolverLock = Any()
    private var activeResolverCall: Call?  = null

    // ── Single-thread executor for resolvers (FIX C1) ─────────────────────
    private val resolverExecutor = Executors.newCachedThreadPool { r ->
    Thread(r, "resolver-worker").apply { isDaemon = true }
}


    // ─────────────────────────────────────────────────────────────────────
    // PART 1 — PLAYER FACTORY
    // ─────────────────────────────────────────────────────────────────────
    fun createPlayer(context: Context): ExoPlayer {
        updateProxyState(context)

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
            .setEnableDecoderFallback(true)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    forceEnableMediaCodecAsynchronousQueueing()
                }
            }

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTunnelingEnabled(false)
                .setExceedAudioConstraintsIfNecessary(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedChannelCountAdaptiveness(true)
                .setAudioOffloadPreferences(
                    AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                        )
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
                setVideoChangeFrameRateStrategy(
                    C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                )
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PART 2 — CHANNEL LOADER
    // ─────────────────────────────────────────────────────────────────────
        fun playChannel(context: Context, player: ExoPlayer, channel: Channel) {
        updateProxyState(context)

        // ── Cancel any in-flight resolver from a previous channel click (FIX C3) ──
        synchronized(activeResolverLock) {
            activeResolverCall?.cancel()
            activeResolverCall = null
        }
        val currentVersion = ++resolverVersion  // bump version for this request

        val actualUrl = channel.url.trim()
        clearCookiesForUrl(actualUrl)

        // Build unified headers
        val headers = channel.getHeadersMap().toMutableMap()

        // Add Cookie from channel if present
        if (!channel.cookie.isNullOrBlank()) {
            headers["Cookie"] = channel.cookie!!
        }

        // Add Origin from Referer if missing
        if (headers.containsKey("Referer") && !headers.containsKey("Origin")) {
            try {
                val refUrl = headers["Referer"]!!
                val uri    = java.net.URI(refUrl)
                val origin = "${uri.scheme}://${uri.host}"
                headers["Origin"] = origin
            } catch (e: Exception) { /* skip */ }
        }

        // PHP streams — auto Referer if missing
        if (!headers.containsKey("Referer") &&
            actualUrl.contains(".php", ignoreCase = true)) {
            headers["Referer"] = "https://www.google.com/"
            headers["Origin"]  = "https://www.google.com"
        }

        // Ensure User-Agent always present
        val userAgent = headers.getOrElse("User-Agent") { DEFAULT_UA }
        headers["User-Agent"] = userAgent

        Log.d(TAG, "▶ Playing: ${channel.name}")
        Log.d(TAG, "  URL: $actualUrl")
        Log.d(TAG, "  DRM Scheme: ${channel.drmScheme}")
        Log.d(TAG, "  DRM License: ${channel.drmLicenseUrl}")
        Log.d(TAG, "  DRM KeyId: ${channel.drmKeyId}")
        Log.d(TAG, "  DRM Key: ${channel.drmKey}")
        Log.d(TAG, "  Headers: $headers")

        val forcedMime = detectMimeType(actualUrl)

        // ── 🚀 FAST TRACK: MAC-based IPTV (30% of playlist) ───────────────
        if (isMacBasedIptv(actualUrl)) {
            Log.d(TAG, "⚡ Fast track: MAC-based IPTV detected")
            
            // Direct playback - no delay, no resolver
            // OkHttp will auto-follow 302 redirect to TS stream
            startPlayback(
                context, player, channel,
                headers, userAgent, actualUrl, 
                null,  // null MIME = progressive TS (fastest)
                currentVersion
            )
            return  // Skip rest of logic
        }

        // ── Standard path for other streams ───────────────────────────────
        when {
            isDirectTs(actualUrl) -> {
                startPlayback(
                    context, player, channel,
                    headers, userAgent, actualUrl, null,
                    currentVersion
                )
            }
            needsResolution(actualUrl) -> {
                resolveAndPlay(
                    context, player, channel,
                    headers, userAgent, actualUrl, forcedMime,
                    currentVersion
                )
            }
            else -> {
                startPlayback(
                    context, player, channel,
                    headers, userAgent, actualUrl, forcedMime,
                    currentVersion
                )
            }
        }
    }


    // ── MIME detector ─────────────────────────────────────────────────────
    private fun detectMimeType(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains(".mpd") ||
            lower.contains("=mpd")  -> MimeTypes.APPLICATION_MPD

            lower.contains(".m3u8") ||
            lower.contains("=m3u8") ||
            lower.contains("vercel.app") -> MimeTypes.APPLICATION_M3U8

            lower.endsWith(".ts")  ||
            lower.contains(".ts?") ||
            lower.contains("=ts")  -> null

            else -> null
        }
    }

    // ── URL type checks ───────────────────────────────────────────────────
    private fun isDirectTs(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".ts") ||
               lower.contains(".ts?") ||
               lower.contains("=ts")
    }

    private fun needsResolution(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".php") ||
               lower.contains("vercel.app") ||
               run {
                   try {
                       val path = java.net.URL(url).path
                       !path.contains(".")
                   } catch (e: Exception) { false }
               }
    }

    // ── Fast track for MAC-based IPTV links ───────────────────────────────
    private fun isMacBasedIptv(url: String): Boolean {
        val lower = url.lowercase()
        // Match patterns like:
        // - play.php?mac=XX:XX:XX:XX:XX:XX&stream=12345
        // - live.php?mac=XX:XX:XX:XX:XX:XX&extension=ts
        // - get.php?username=X&password=Y&type=m3u_plus&mac=XX
        return lower.contains("mac=") && (
            lower.contains(".php") ||
            lower.contains("stream=") ||
            lower.contains("extension=ts") ||
            lower.contains("extension=m3u8") ||
            lower.contains("type=")
        )
    }

    // ── Background resolver (FIX C1 - uses single thread executor) ────────
    private fun resolveAndPlay(
        context: Context,
        player: ExoPlayer,
        channel: Channel,
        headers: MutableMap<String, String>,
        userAgent: String,
        url: String,
        originalMime: String?,
        version: Int
    ) {
        resolverExecutor.execute {
            try {
                // Abort early if already stale
                if (version != resolverVersion) {
                    Log.d(TAG, "Resolver: request already stale for ${channel.name}")
                    return@execute
                }

                val request = Request.Builder()
                    .url(url)
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .build()

                val call = okHttpClient.newCall(request)
                
                // Track call for cancellation (FIX C3)
                synchronized(activeResolverLock) {
                    activeResolverCall = call
                }

                call.execute().use { response ->

                    // Abort if user already clicked another channel
                    if (version != resolverVersion) {
                        Log.d(TAG, "Resolver: stale response discarded for ${channel.name}")
                        return@use
                    }

                    val finalUrl = response.request.url.toString()
                    val cType    = response.header("Content-Type", "")
                                       ?.lowercase() ?: ""
                    val bodyStr  = response.body?.string()?.trim() ?: ""

                    Log.d(TAG, "Resolved: $url -> $finalUrl | CT: $cType")

                    val (resolvedUrl, resolvedMime) = resolveUrlAndMime(
                        originalUrl  = url,
                        finalUrl     = finalUrl,
                        contentType  = cType,
                        body         = bodyStr,
                        originalMime = originalMime
                    )

                    mainHandler.post {
                        // Final guard on main thread
                        if (version != resolverVersion) return@post
                        startPlayback(
                            context, player, channel,
                            headers, userAgent, resolvedUrl, resolvedMime,
                            version
                        )
                    }
                }
            } catch (e: Exception) {
                // Cancelled calls throw IOException — don't log as warning
                if (version != resolverVersion) return@execute

                Log.w(TAG, "Resolution failed: ${e.message}")
                mainHandler.post {
                    if (version != resolverVersion) return@post
                    startPlayback(
                        context, player, channel,
                        headers, userAgent, url,
                        originalMime ?: MimeTypes.APPLICATION_M3U8,
                        version
                    )
                }
            } finally {
                // Clear active call reference (FIX m4)
                synchronized(activeResolverLock) {
                    if (activeResolverCall?.request()?.url?.toString() == url) {
                        activeResolverCall = null
                    }
                }
            }
        }
    }

    // ── URL + MIME resolution logic ───────────────────────────────────────
    private fun resolveUrlAndMime(
        originalUrl: String,
        finalUrl: String,
        contentType: String,
        body: String,
        originalMime: String?
    ): Pair<String, String?> {

        if ((contentType.contains("text") || contentType.contains("html")) &&
            body.startsWith("http") &&
            !body.contains("\n") &&
            !body.contains("#EXTM3U")) {
            return body to detectMimeType(body)
        }

        if (body.startsWith("#EXTM3U")) {
            return originalUrl to MimeTypes.APPLICATION_M3U8
        }

        when {
            contentType.contains("video/mp2t") ||
            contentType.contains("video/mpeg") ->
                return finalUrl to null

            contentType.contains("application/dash+xml") ->
                return finalUrl to MimeTypes.APPLICATION_MPD

            contentType.contains("mpegurl") ||
            contentType.contains("m3u8") ->
                return finalUrl to MimeTypes.APPLICATION_M3U8
        }

        detectMimeType(finalUrl)?.let {
            return finalUrl to it
        }

        return originalUrl to (originalMime ?: MimeTypes.APPLICATION_M3U8)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PART 3 — PLAYBACK ENGINE
    // ─────────────────────────────────────────────────────────────────────
    private fun startPlayback(
        context: Context,
        player: ExoPlayer,
        channel: Channel,
        headers: Map<String, String>,
        userAgent: String,
        streamUrl: String,
        forcedMimeType: String?,
        version: Int
    ) {
        // Final stale-request guard inside playback engine
        if (version != resolverVersion) {
            Log.d(TAG, "startPlayback: stale request ignored for ${channel.name}")
            return
        }

        Log.d(TAG, "startPlayback: $streamUrl | MIME: $forcedMimeType")
        Log.d(TAG, "  DRM License: ${channel.drmLicenseUrl}")
        Log.d(TAG, "  DRM Scheme: ${channel.drmScheme}")

        // Use main OkHttpClient with headers via interceptor
        val channelClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val builder  = original.newBuilder()
                headers.forEach { (key, value) -> builder.header(key, value) }
                chain.proceed(builder.build())
            }
            .build()

        val httpFactory = OkHttpDataSource.Factory(channelClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)

        // ── DRM setup ─────────────────────────────────────────────────────
        var drmSessionManager: DefaultDrmSessionManager? = null
        var drmConfigUuid: UUID? = null

        val licenseUrl = channel.drmLicenseUrl?.trim() ?: ""
        val drmScheme  = channel.drmScheme?.trim()?.lowercase() ?: ""
        val hasKeyId   = !channel.drmKeyId.isNullOrBlank()
        val hasKey     = !channel.drmKey.isNullOrBlank()
        val hasLicense = licenseUrl.isNotEmpty()

        Log.d(TAG, "DRM decision — scheme:$drmScheme " +
                   "hasLicense:$hasLicense hasKeyId:$hasKeyId hasKey:$hasKey")

        when {
            // ── Case 1: Inline JSON ClearKey blob ─────────────────────────
            hasLicense &&
            licenseUrl.startsWith("{") &&
            licenseUrl.contains("\"keys\"") -> {
                Log.d(TAG, "DRM: Inline JSON ClearKey")
                val cb = LocalClearKeyCallback(licenseUrl)
                drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER
                    )
                    .setMultiSession(true)
                    .build(cb)
                drmConfigUuid = C.CLEARKEY_UUID
            }

            // ── Case 2: Local Kid + Key pair ──────────────────────────────
            hasKeyId && hasKey -> {
                Log.d(TAG, "DRM: Local Kid+Key pair")
                val cb = LocalClearKeyCallback(
                    channel.drmKeyId!!.replace("-", ""),
                    channel.drmKey!!
                )
                drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER
                    )
                    .setMultiSession(true)
                    .build(cb)
                drmConfigUuid = C.CLEARKEY_UUID
            }

            // ── Case 3: Remote ClearKey URL ───────────────────────────────
            hasLicense && (
                drmScheme == "clearkey" ||
                isClearKeyUrl(licenseUrl)
            ) -> {
                Log.d(TAG, "DRM: Remote ClearKey POST — $licenseUrl")
                val cb = HttpClearKeyUrlCallback(
                    licenseUrl, okHttpClient, headers
                )
                drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER
                    )
                    .setMultiSession(true)
                    .build(cb)
                drmConfigUuid = C.CLEARKEY_UUID
            }

            // ── Case 4: Widevine remote ───────────────────────────────────
            hasLicense && drmScheme == "widevine" -> {
                Log.d(TAG, "DRM: Widevine — $licenseUrl")
                val cb = HttpMediaDrmCallback(licenseUrl, httpFactory)
                headers.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
                drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER
                    )
                    .setMultiSession(true)
                    .build(cb)
                drmConfigUuid = C.WIDEVINE_UUID
            }

            // ── Case 5: License present, scheme unknown → ClearKey POST ───
            hasLicense -> {
                Log.d(TAG, "DRM: Unknown scheme → ClearKey POST")
                val cb = HttpClearKeyUrlCallback(
                    licenseUrl, okHttpClient, headers
                )
                drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER
                    )
                    .setMultiSession(true)
                    .build(cb)
                drmConfigUuid = C.CLEARKEY_UUID
            }

            // ── Case 6: No DRM ────────────────────────────────────────────
            else -> Log.d(TAG, "DRM: None")
        }

        // ── Build MediaItem ───────────────────────────────────────────────
        val liveConfig = MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(25_000)  // 15 seconds target offset (aapke hisaab se)
               // .setMinOffsetMs(10_000)     // Kam se kam 10 second edge se door rahega
               // .setMaxOffsetMs(25_000)     // Agar net slow hua toh 25 sec tak peeche jane dega
               // .setMinPlaybackSpeed(0.97f) // Player ko achanak slow/stutter hone se rokega
               // .setMaxPlaybackSpeed(1.03f) // Player ko achanak fast forward hone se rokega
                .build()

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(streamUrl)
            .setLiveConfiguration(liveConfig)

        forcedMimeType?.let { mediaItemBuilder.setMimeType(it) }
        drmConfigUuid?.let {
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(it).build()
            )
        }

        // ── Build MediaSource ─────────────────────────────────────────────
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
        drmSessionManager?.let { mgr ->
            mediaSourceFactory.setDrmSessionManagerProvider { mgr }
        }

        val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

        // ── Debug listener with proper cleanup (FIX M3) ───────────────────
        var debugListener: Player.Listener? = null
        debugListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "PlaybackError: ${error.errorCodeName} — ${error.message}")
                error.cause?.let {
                    Log.e(TAG, "  Cause: ${it.javaClass.simpleName}: ${it.message}")
                }
                // Remove listener on error
                debugListener?.let { player.removeListener(it) }
            }
            
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE      -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY     -> "READY"
                    Player.STATE_ENDED     -> "ENDED"
                    else                   -> "UNKNOWN"
                }
                Log.d(TAG, "PlayerState: $name")
                // Remove listener on terminal states
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
            Log.e(TAG, "Failed to start playback: ${e.message}", e)
            // Clean up listener on failure
            debugListener?.let { player.removeListener(it) }
        }
        }


    // ── ClearKey URL detector ─────────────────────────────────────────────
    private fun isClearKeyUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("clearkey")     ||
               lower.contains("key.php")      ||
               lower.contains("plkey")        ||
               lower.contains("temp.webplay")
    }

} // END companion object

// ─────────────────────────────────────────────────────────────────────────
// LOCAL CLEARKEY CALLBACK
// ─────────────────────────────────────────────────────────────────────────
private class LocalClearKeyCallback(
    private val keyIdOrJson: String,
    private val key: String? = null
) : MediaDrmCallback {

    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest
    ) = MediaDrmCallback.Response(ByteArray(0))

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest
    ): MediaDrmCallback.Response {
        // Full JSON blob — return as-is
        if (keyIdOrJson.trim().startsWith("{")) {
            return MediaDrmCallback.Response(
                keyIdOrJson.toByteArray(Charsets.UTF_8)
            )
        }

        val kidB64 = hexToBase64Url(keyIdOrJson)
        val keyB64 = hexToBase64Url(key ?: "")

        val json = """{"keys":[{"kty":"oct","k":"$keyB64","kid":"$kidB64"}],"type":"temporary"}"""
        Log.d("ClearKey", "Local response: $json")
        return MediaDrmCallback.Response(json.toByteArray(Charsets.UTF_8))
    }

    private fun hexToBase64Url(hex: String): String {
        val clean = hex.replace("-", "").trim()
        if (clean.length != 32) {
            Log.w("ClearKey", "Unexpected hex length (${clean.length}): $clean")
            return clean
        }
        return try {
            val bytes = ByteArray(16) { i ->
                ((Character.digit(clean[i * 2], 16) shl 4) +
                 Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
            Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        } catch (e: Exception) {
            Log.e("ClearKey", "hexToBase64Url failed: ${e.message}", e)
            clean
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// REMOTE CLEARKEY CALLBACK — HTTP/1.1 POST
// ─────────────────────────────────────────────────────────────────────────
private class HttpClearKeyUrlCallback(
    private val licenseUrl: String,
    private val client: OkHttpClient,
    private val headers: Map<String, String>
) : MediaDrmCallback {

    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest
    ) = MediaDrmCallback.Response(ByteArray(0))

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest
    ): MediaDrmCallback.Response {

        val requestData = request.data
        Log.d("ClearKey", "POST to: $licenseUrl")
        Log.d("ClearKey", "Request body: ${String(requestData)}")

        val body = requestData.toRequestBody(
            "application/json".toMediaType()
        )

        val host = licenseUrl.toHttpUrl().host

        // Force HTTP/1.1 — HTTP/2 causes 302 redirect on some servers
        val http1Client = client.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()

        val req = Request.Builder()
            .url(licenseUrl)
            .post(body)
            .header("Host", host)
            .header("Content-Type", "application/json")
            .header("Connection", "keep-alive")
            .header(
                "User-Agent",
                "Dalvik/2.1.0 (Linux; U; Android 12; Generic Build/SQ3A.220705.004)"
            )
            .build()

        http1Client.newCall(req).execute().use { response ->
            val responseBytes = response.body?.bytes() ?: ByteArray(0)

            Log.d("ClearKey", "Response ${response.code}: ${String(responseBytes)}")

            if (!response.isSuccessful) {
                throw IOException(
                    "ClearKey POST failed: ${response.code} — ${String(responseBytes)}"
                )
            }

            return MediaDrmCallback.Response(responseBytes)
        }
    }
}
}
