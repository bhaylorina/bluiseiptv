package com.bluise.iptv.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
class PlayerEngine {

    companion object {

        private const val TAG = "PlayerEngine"
        private const val PROXY_TAG = "WireProxy"

        private const val DEFAULT_USER_AGENT =
            "ExoPlayerLib/1.11.1 (Linux; Android) AndroidXMedia3/1.11.1"

        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = 25344
        private const val PROXY_READY_TIMEOUT_MS = 8000L
        private const val PROXY_POLL_MS = 100L
        private const val PROBE_TIMEOUT_SEC = 5L

        private const val PREFS_NAME = "iptv_settings"
        private const val KEY_WARP_ENABLED = "warp_proxy_enabled"

        // Sirf in domains par WARP proxy lagega
        private val PROXY_HOSTS = setOf("jiotvbpkstb.cdn.jio.com", "jiotvbpkmob.cdn.jio.com")
        private val NO_PROXY_LIST = listOf(Proxy.NO_PROXY)

        private val mainHandler = Handler(Looper.getMainLooper())
        private val playbackGeneration = AtomicInteger(0)

        @Volatile
        private var activeProbeCall: Call? = null

        // ---------------- WARP proxy state ----------------
        private val proxyLock = Any()
        private val proxyStarted = AtomicBoolean(false)   // launching ya running
        @Volatile
        private var proxyReady = false                    // SOCKS port open ho chuka hai
        @Volatile
        private var proxyProcess: Process? = null
        private var proxyLaunchId = 0                     // proxyLock ke andar hi touch karna

        // ---------------- Settings cache ----------------
        // Selector har connection par SharedPreferences nahi padhega, ye cached value use karega
        @Volatile
        private var warpEnabled = true
        private val prefsInitialized = AtomicBoolean(false)
        @Volatile
        private var appContext: Context? = null

        // Strong reference rakhna zaroori hai (SharedPreferences weak ref rakhta hai)
        private val warpPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == KEY_WARP_ENABLED) {
                warpEnabled = prefs.getBoolean(KEY_WARP_ENABLED, true)
                val ctx = appContext ?: return@OnSharedPreferenceChangeListener
                if (warpEnabled) startNativeProxy(ctx) else stopNativeProxy()
            }
        }

        private fun initPrefs(context: Context) {
            if (!prefsInitialized.compareAndSet(false, true)) return
            val app = context.applicationContext
            appContext = app
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            warpEnabled = prefs.getBoolean(KEY_WARP_ENABLED, true)
            prefs.registerOnSharedPreferenceChangeListener(warpPrefListener)
        }

        // ---------------- Cookies ----------------
        private val cookieStore = CopyOnWriteArrayList<Cookie>()

        private val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val now = System.currentTimeMillis()
                synchronized(cookieStore) {
                    cookieStore.removeAll { it.expiresAt <= now }
                    cookies.forEach { incoming ->
                        val isValidTime = incoming.expiresAt > now
                        val isAkamaiPoison = incoming.name.equals("hdntl", ignoreCase = true) &&
                            incoming.value.equals("expired", ignoreCase = true)
                        if (isValidTime && !isAkamaiPoison) {
                            cookieStore.removeAll {
                                it.name == incoming.name &&
                                    it.domain == incoming.domain &&
                                    it.path == incoming.path
                            }
                            cookieStore.add(incoming)
                        }
                    }
                }
            }

            // matches() domain, path aur secure rules check karta hai
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val now = System.currentTimeMillis()
                return cookieStore.filter { it.expiresAt > now && it.matches(url) }
            }
        }

        private val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 15
        }

        private val jioProxySelector = object : ProxySelector() {
            private val localSocks = Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, PROXY_PORT))

            override fun select(uri: URI?): List<Proxy> {
                if (!warpEnabled) return NO_PROXY_LIST
                val host = uri?.host?.lowercase().orEmpty()
                return if (host in PROXY_HOSTS) listOf(localSocks, Proxy.NO_PROXY) else NO_PROXY_LIST
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }

        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .proxySelector(jioProxySelector)
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()

        private val loadErrorPolicy = DefaultLoadErrorHandlingPolicy(3)

        // ---------------- Channel client (headers har hop par, 302 redirect ke baad bhi) ----------------

        /**
         * Network interceptor har hop par chalta hai, isliye 302 redirect ke baad wali request
         * par bhi channel headers dobara lagte hain (Authorization jaisa header cross-host
         * redirect me OkHttp khud hata deta hai).
         * Cookie header jar wale cookies ke saath merge hota hai, overwrite nahi hota.
         */
        private fun buildChannelClient(headers: Map<String, String>, callTimeoutSec: Long = 0L): OkHttpClient {
            val builder = okHttpClient.newBuilder()
            if (callTimeoutSec > 0) builder.callTimeout(callTimeoutSec, TimeUnit.SECONDS)

            return builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                val requestBuilder = request.newBuilder()
                headers.forEach { (key, value) ->
                    when {
                        key.equals("Host", ignoreCase = true) -> Unit
                        key.equals("Cookie", ignoreCase = true) ->
                            requestBuilder.header("Cookie", mergeCookieHeaders(value, request.header("Cookie")))
                        else -> requestBuilder.header(key, value)
                    }
                }
                chain.proceed(requestBuilder.build())
            }.build()
        }

        // Static channel cookie + jar cookie merge. Same name par jar wali value jeetegi.
        private fun mergeCookieHeaders(staticCookie: String, jarCookie: String?): String {
            if (jarCookie.isNullOrBlank() || jarCookie == staticCookie) return staticCookie
            val merged = LinkedHashMap<String, String>()
            listOf(staticCookie, jarCookie).forEach { raw ->
                raw.split(";").forEach { part ->
                    val pair = part.trim()
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        merged[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
                    }
                }
            }
            return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        // ---------------- Native WireProxy (Cloudflare WARP) ----------------

        private fun isProxyStarting(): Boolean = warpEnabled && proxyStarted.get() && !proxyReady

        private fun stopNativeProxy() {
            val process = synchronized(proxyLock) {
                if (!proxyStarted.get() && proxyProcess == null) return
                proxyLaunchId++ // in-flight launch ko cancel kar deta hai
                val p = proxyProcess
                proxyProcess = null
                proxyReady = false
                proxyStarted.set(false)
                p
            }
            if (process != null) {
                Log.d(PROXY_TAG, "Stopping WireProxy process...")
                process.destroy()
            }
        }

        // Launch fail / timeout par state reset + process kill (orphan process nahi bachega)
        private fun failProxyLaunch(launchId: Int, process: Process?) {
            synchronized(proxyLock) {
                if (launchId == proxyLaunchId) {
                    proxyProcess = null
                    proxyReady = false
                    proxyStarted.set(false)
                }
            }
            process?.destroy()
        }

        // Process khud mar gaya to state clear, taaki agla playChannel / createPlayer restart kar sake
        private fun onProxyProcessEnded(process: Process) {
            synchronized(proxyLock) {
                if (proxyProcess === process) {
                    Log.w(PROXY_TAG, "wireproxy process ended")
                    proxyProcess = null
                    proxyReady = false
                    proxyStarted.set(false)
                }
            }
        }

        private fun startNativeProxy(context: Context) {
            initPrefs(context)

            if (!warpEnabled) {
                stopNativeProxy()
                return
            }

            val launchId = synchronized(proxyLock) {
                if (proxyStarted.get()) {
                    val p = proxyProcess
                    if (p == null || p.isAlive) {
                        // launching ya already running
                        return
                    }
                    // process mar chuka hai, restart karenge
                    proxyProcess = null
                }
                proxyReady = false
                proxyStarted.set(true)
                ++proxyLaunchId
            }

            val appCtx = context.applicationContext

            Thread {
                var launched: Process? = null
                try {
                    Log.d(PROXY_TAG, "Preparing wireproxy config...")

                    val confFile = File(appCtx.filesDir, "wireproxy.conf")
                    appCtx.assets.open("wireproxy.conf").use { input ->
                        FileOutputStream(confFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(PROXY_TAG, "Config copied to ${confFile.absolutePath}")

                    val binPath = File(appCtx.applicationInfo.nativeLibraryDir, "libwireproxy.so")
                    Log.d(PROXY_TAG, "Looking for binary at: ${binPath.absolutePath}")

                    if (!binPath.exists()) {
                        Log.e(PROXY_TAG, "libwireproxy.so NOT FOUND!")
                        showToast(appCtx, "WARP proxy failed: binary not found")
                        failProxyLaunch(launchId, null)
                        return@Thread
                    }

                    val madeExecutable = binPath.setExecutable(true, false)
                    Log.d(PROXY_TAG, "setExecutable result: $madeExecutable")

                    Log.d(PROXY_TAG, "Launching wireproxy process...")
                    val process = ProcessBuilder(binPath.absolutePath, "-c", confFile.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    launched = process

                    // Agar is beech stop/restart ho gaya to ye process orphan hoga, kill kar do
                    val registered = synchronized(proxyLock) {
                        if (launchId == proxyLaunchId && proxyStarted.get()) {
                            proxyProcess = process
                            true
                        } else {
                            false
                        }
                    }
                    if (!registered) {
                        process.destroy()
                        return@Thread
                    }

                    // Log reader + exit watcher (stdout band hone ka matlab process khatam)
                    Thread {
                        try {
                            process.inputStream.bufferedReader().forEachLine { line ->
                                Log.d(PROXY_TAG, line)
                            }
                        } catch (e: IOException) {
                            Log.w(PROXY_TAG, "Log stream closed: ${e.message}")
                        }
                        onProxyProcessEnded(process)
                    }.apply {
                        isDaemon = true
                        start()
                    }

                    Thread.sleep(500)
                    if (!process.isAlive) {
                        val exitCode = process.exitValue()
                        Log.e(PROXY_TAG, "wireproxy exited immediately! Exit code: $exitCode")
                        showToast(appCtx, "WARP proxy crashed (code $exitCode)")
                        failProxyLaunch(launchId, process)
                        return@Thread
                    }

                    val portReady = waitForProxyPort(PROXY_HOST, PROXY_PORT, PROXY_READY_TIMEOUT_MS, process)

                    if (portReady) {
                        val marked = synchronized(proxyLock) {
                            if (launchId == proxyLaunchId && proxyStarted.get()) {
                                proxyReady = true
                                true
                            } else {
                                false
                            }
                        }
                        if (marked) {
                            Log.d(PROXY_TAG, "✅ WireProxy (Cloudflare WARP) is UP on $PROXY_HOST:$PROXY_PORT")
                        }
                    } else {
                        Log.e(PROXY_TAG, "⌛ Timed out waiting for SOCKS5 port to open")
                        failProxyLaunch(launchId, process)
                    }

                } catch (e: Exception) {
                    Log.e(PROXY_TAG, "Failed to start native proxy", e)
                    failProxyLaunch(launchId, launched)
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun waitForProxyPort(host: String, port: Int, timeoutMs: Long, process: Process): Boolean {
            val start = System.currentTimeMillis()
            while (process.isAlive && System.currentTimeMillis() - start < timeoutMs) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 300)
                    }
                    return true
                } catch (e: IOException) {
                    Thread.sleep(200)
                }
            }
            return false
        }

        // Proxy start ho raha ho to playback ko ready hone tak hold karta hai (main thread par, polling)
        private fun runWhenProxyReady(generation: Int, deadline: Long, action: () -> Unit) {
            if (generation != playbackGeneration.get()) return
            if (!isProxyStarting() || SystemClock.elapsedRealtime() >= deadline) {
                action()
            } else {
                mainHandler.postDelayed(
                    { runWhenProxyReady(generation, deadline, action) },
                    PROXY_POLL_MS
                )
            }
        }

        private fun showToast(context: Context, message: String) {
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        // ---------------- Player ----------------

        fun createPlayer(context: Context): ExoPlayer {
            // Start (or confirm already started) the WARP proxy
            startNativeProxy(context)

            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

            val trackSelector = DefaultTrackSelector(context).apply {
                parameters = DefaultTrackSelector.Parameters.Builder(context)
                    .setTunnelingEnabled(true)
                    .setViewportSizeToPhysicalDisplaySize(context, true)
                    .build()
            }

            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
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

        /**
         * App / player band karte waqt call karein (e.g. onDestroy jab isFinishing ho).
         * Active probe cancel karta hai, pending callbacks hatata hai aur wireproxy band karta hai.
         * Agla createPlayer() / playChannel() proxy ko dobara start kar dega.
         */
        fun release() {
            playbackGeneration.incrementAndGet()
            activeProbeCall?.cancel()
            activeProbeCall = null
            mainHandler.removeCallbacksAndMessages(null)
            stopNativeProxy()
        }

        fun playChannel(context: Context, player: ExoPlayer, channel: Channel) {
            activeProbeCall?.cancel()
            activeProbeCall = null
            val currentGeneration = playbackGeneration.incrementAndGet()

            val streamUrl = channel.url.trim()
            if (streamUrl.isEmpty()) return

            // Idempotent: proxy mar gaya ho to yahin restart ho jayega
            startNativeProxy(context)

            // Proxy start ho raha ho to uske ready hone tak wait, warna turant play
            val deadline = SystemClock.elapsedRealtime() + PROXY_READY_TIMEOUT_MS
            runWhenProxyReady(currentGeneration, deadline) {
                launchPlayback(context, player, channel, streamUrl, currentGeneration)
            }
        }

        private fun launchPlayback(
            context: Context,
            player: ExoPlayer,
            channel: Channel,
            streamUrl: String,
            generation: Int
        ) {
            val headers = buildHeaders(channel)
            val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT
            val knownMimeType = inferMimeType(streamUrl)

            if (knownMimeType != null || isDirectStreamOrMacPortal(streamUrl)) {
                startPlayback(context, player, channel, headers, userAgent, streamUrl, knownMimeType, generation)
            } else {
                probeAndPlay(context, player, channel, headers, userAgent, streamUrl, generation)
            }
        }

        private fun probeAndPlay(
            context: Context,
            player: ExoPlayer,
            channel: Channel,
            headers: Map<String, String>,
            userAgent: String,
            url: String,
            generation: Int
        ) {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) -> requestBuilder.header(key, value) }

            // Probe me bhi redirect par headers jaane chahiye + chhota timeout taaki zap slow na ho
            val call = buildChannelClient(headers, PROBE_TIMEOUT_SEC).newCall(requestBuilder.build())
            activeProbeCall = call

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (generation != playbackGeneration.get()) return
                    Log.w(TAG, "Probe failed for $url: ${e.message}")
                    mainHandler.post {
                        if (generation == playbackGeneration.get()) {
                            startPlayback(context, player, channel, headers, userAgent, url, null, generation)
                        }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (generation != playbackGeneration.get()) return

                        val (resolvedUrl, resolvedMime) = detectUrlAndMimeFromResponse(url, res)

                        mainHandler.post {
                            if (generation == playbackGeneration.get()) {
                                startPlayback(context, player, channel, headers, userAgent, resolvedUrl, resolvedMime, generation)
                            }
                        }
                    }
                }
            })
        }

        private fun detectUrlAndMimeFromResponse(originalUrl: String, response: Response): Pair<String, String?> {
            val finalUrl = response.request.url.toString()
            val contentType = response.header("Content-Type", "")?.lowercase().orEmpty()

            when {
                contentType.contains("application/dash+xml") -> return finalUrl to MimeTypes.APPLICATION_MPD
                contentType.contains("mpegurl") || contentType.contains("m3u8") -> return finalUrl to MimeTypes.APPLICATION_M3U8
                contentType.contains("video/") || contentType.contains("audio/") ||
                    contentType.contains("application/octet-stream") -> return finalUrl to null
            }

            inferMimeType(finalUrl)?.let { return finalUrl to it }

            val peekedText = runCatching {
                response.peekBody(1024).string().trim()
            }.getOrDefault("")

            return when {
                peekedText.startsWith("#EXTM3U") -> finalUrl to MimeTypes.APPLICATION_M3U8
                peekedText.contains("<MPD") -> finalUrl to MimeTypes.APPLICATION_MPD
                peekedText.startsWith("http://", ignoreCase = true) ||
                    peekedText.startsWith("https://", ignoreCase = true) -> {
                    val targetUrl = peekedText.lineSequence().first().trim()
                    targetUrl to inferMimeType(targetUrl)
                }
                else -> finalUrl to null
            }
        }

        private fun startPlayback(
            context: Context,
            player: ExoPlayer,
            channel: Channel,
            headers: Map<String, String>,
            userAgent: String,
            streamUrl: String,
            forcedMimeType: String?,
            generation: Int
        ) {
            if (generation != playbackGeneration.get()) return

            // Har hop (302 redirect ke baad bhi) par headers lagte hain
            val channelClient = buildChannelClient(headers)

            val httpDataSourceFactory = OkHttpDataSource.Factory(channelClient)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)

            val extractorsFactory = DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                )

            val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
                .setDataSourceFactory(httpDataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)

            val drmSessionManager = buildDrmSessionManager(channel, httpDataSourceFactory, headers)
            if (drmSessionManager != null) {
                mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
            }
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(streamUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(40_000)
                        .setMaxOffsetMs(50_000)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.03f)
                        .build()
                )

            forcedMimeType?.let { mimeType ->
                mediaItemBuilder.setMimeType(mimeType)
            }

            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItemBuilder.build()))
            player.prepare()
            player.playWhenReady = true
        }

        private fun buildHeaders(channel: Channel): Map<String, String> {
            val headers = channel.getHeadersMap().toMutableMap()

            if (!channel.cookie.isNullOrBlank()) {
                headers["Cookie"] = channel.cookie!!
            }

            val referer = headers["Referer"]
            if (!referer.isNullOrBlank() && !headers.containsKey("Origin")) {
                runCatching {
                    val uri = URI(referer)
                    if (uri.scheme != null && uri.host != null) {
                        headers["Origin"] = "${uri.scheme}://${uri.host}"
                    }
                }
            }

            if (headers["User-Agent"].isNullOrBlank()) {
                headers["User-Agent"] = DEFAULT_USER_AGENT
            }

            return headers
        }

        private fun inferMimeType(url: String): String? {
            val uri = Uri.parse(url)
            return when (Util.inferContentType(uri)) {
                C.CONTENT_TYPE_DASH -> MimeTypes.APPLICATION_MPD
                C.CONTENT_TYPE_HLS -> MimeTypes.APPLICATION_M3U8
                C.CONTENT_TYPE_SS -> MimeTypes.APPLICATION_SS
                else -> {
                    val lower = url.lowercase()
                    when {
                        lower.contains(".mpd") || lower.contains("=mpd") -> MimeTypes.APPLICATION_MPD
                        lower.contains(".m3u8") || lower.contains("=m3u8") -> MimeTypes.APPLICATION_M3U8
                        else -> null
                    }
                }
            }
        }

        private fun isDirectStreamOrMacPortal(url: String): Boolean {
            val lower = url.lowercase()
            val isDirectContainer = lower.endsWith(".ts") || lower.contains(".ts?") || lower.contains("=ts") ||
                lower.endsWith(".mp4") || lower.contains(".mp4?") ||
                lower.endsWith(".mkv") || lower.contains(".mkv?")
            val isMacPortal = lower.contains("mac=") &&
                (lower.contains("stream=") || lower.contains("extension=") || lower.contains("type="))
            return isDirectContainer || isMacPortal
        }

        private fun buildDrmSessionManager(
            channel: Channel,
            httpDataSourceFactory: OkHttpDataSource.Factory,
            headers: Map<String, String>
        ): DefaultDrmSessionManager? {
            val licenseUrl = channel.drmLicenseUrl?.trim().orEmpty()
            val drmScheme = channel.drmScheme?.trim()?.lowercase().orEmpty()
            val localKeys = extractClearKeyPairs(channel)

            return runCatching {
                when {
                    licenseUrl.startsWith("{") && licenseUrl.contains("\"keys\"") -> {
                        val callback = LocalMediaDrmCallback(licenseUrl.toByteArray(Charsets.UTF_8))
                        DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true)
                            .build(callback)
                    }

                    localKeys.isNotEmpty() -> {
                        val jwkJson = buildClearKeyJwk(localKeys)
                        val callback = LocalMediaDrmCallback(jwkJson.toByteArray(Charsets.UTF_8))
                        DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true)
                            .build(callback)
                    }

                    licenseUrl.startsWith("http://", ignoreCase = true) ||
                        licenseUrl.startsWith("https://", ignoreCase = true) -> {
                        val targetUuid = if (drmScheme == "widevine") C.WIDEVINE_UUID else C.CLEARKEY_UUID
                        val callback = HttpMediaDrmCallback(licenseUrl, httpDataSourceFactory).apply {
                            headers.forEach { (key, value) -> setKeyRequestProperty(key, value) }
                        }
                        DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(targetUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true)
                            .build(callback)
                    }

                    else -> null
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to configure DRM session manager", e)
            }.getOrNull()
        }

        private fun extractClearKeyPairs(channel: Channel): Map<String, String> {
            val keys = mutableMapOf<String, String>()

            fun parseKeyString(raw: String?) {
                if (raw.isNullOrBlank() || raw.startsWith("http", ignoreCase = true) || raw.startsWith("{")) return
                raw.split(",").forEach { entry ->
                    val delimiterIndex = entry.indexOfFirst { it == ':' || it == '=' }
                    if (delimiterIndex != -1) {
                        val kid = entry.substring(0, delimiterIndex).replace("-", "").trim()
                        val key = entry.substring(delimiterIndex + 1).replace("-", "").trim()
                        if (kid.length >= 16 && key.length >= 16) {
                            keys[kid] = key
                        }
                    }
                }
            }

            parseKeyString(channel.drmKey)
            parseKeyString(channel.drmLicenseUrl)

            if (!channel.drmKeyId.isNullOrBlank() && !channel.drmKey.isNullOrBlank()) {
                val kid = channel.drmKeyId.replace("-", "").trim()
                val key = channel.drmKey.replace("-", "").trim()
                if (kid.isNotEmpty() && key.isNotEmpty() && !key.contains(":") && !key.contains("=")) {
                    keys[kid] = key
                }
            }

            return keys
        }

        private fun buildClearKeyJwk(keysMap: Map<String, String>): String {
            val keysJsonArray = keysMap.entries.joinToString(",") { (kid, key) ->
                val kidB64 = hexToBase64Url(kid)
                val keyB64 = hexToBase64Url(key)
                """{"kty":"oct","k":"$keyB64","kid":"$kidB64"}"""
            }
            return """{"keys":[$keysJsonArray],"type":"temporary"}"""
        }

        private fun hexToBase64Url(input: String): String {
            val clean = input.replace("-", "").trim()
            if (clean.length != 32) return clean
            return runCatching {
                val bytes = ByteArray(16) { i ->
                    ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
                }
                Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            }.getOrDefault(clean)
        }
    }
}
