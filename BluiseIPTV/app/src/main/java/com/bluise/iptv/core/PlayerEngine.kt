package com.bluise.iptv.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
class PlayerEngine {

    companion object {

        private const val TAG = "PlayerEngine"

        private const val DEFAULT_USER_AGENT =
            "ExoPlayerLib/1.11.1 (Linux; Android) AndroidXMedia3/1.11.1"

        private const val PROBE_TIMEOUT_SEC = 5L

        /**
         * Cookie conflict rule (same cookie name M3U me bhi ho aur server ne bhi bheji ho):
         *  false -> server ki refreshed cookie jeetegi (same playback session ki). Long streams ke liye best.
         *  true  -> M3U ki cookie hamesha jeetegi (purane code jaisa behavior).
         */
        private const val STATIC_COOKIE_WINS = false

        private val mainHandler = Handler(Looper.getMainLooper())
        private val playbackGeneration = AtomicInteger(0)

        @Volatile
        private var activeProbeCall: Call? = null

        // ---------------- Cookies ----------------

        /**
         * Ek playback session ka cookie container.
         * Har playChannel() par naya banta hai, isliye ek channel ki cookie (jaise Akamai __hdnea__,
         * jiska acl channel-specific hota hai) doosre channel tak kabhi nahi pahunchti.
         * Probe + playback + DRM license request, sab ek hi jar share karte hain.
         */
        private class ChannelCookieJar : CookieJar {
            private val store = CopyOnWriteArrayList<Cookie>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val now = System.currentTimeMillis()
                synchronized(store) {
                    store.removeAll { it.expiresAt <= now }
                    cookies.forEach { incoming ->
                        val isValidTime = incoming.expiresAt > now
                        val isAkamaiPoison = incoming.name.equals("hdntl", ignoreCase = true) &&
                            incoming.value.equals("expired", ignoreCase = true)
                        if (isValidTime && !isAkamaiPoison) {
                            store.removeAll {
                                it.name == incoming.name &&
                                    it.domain == incoming.domain &&
                                    it.path == incoming.path
                            }
                            store.add(incoming)
                        }
                    }
                }
            }

            // matches() domain, path aur secure rules check karta hai
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val now = System.currentTimeMillis()
                return store.filter { it.expiresAt > now && it.matches(url) }
            }
        }

        // Global jar: sirf un hisson ke liye jo PlayerEngine.okHttpClient direct use karte hain.
        // Channel playback isko use NAHI karta (har playback ka apna ChannelCookieJar hota hai).
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

        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
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
         *
         * Cookie: is playback ka apna `jar` use hota hai (global nahi), aur Cookie header
         * M3U cookie + jar cookie ka merge hota hai (rule: STATIC_COOKIE_WINS).
         */
        private fun buildChannelClient(
            headers: Map<String, String>,
            jar: CookieJar,
            callTimeoutSec: Long = 0L
        ): OkHttpClient {
            val builder = okHttpClient.newBuilder()
                .cookieJar(jar)
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

        // M3U (static) cookie + is session ke jar ki cookie merge. Conflict rule: STATIC_COOKIE_WINS.
        private fun mergeCookieHeaders(staticCookie: String, jarCookie: String?): String {
            if (jarCookie.isNullOrBlank() || jarCookie == staticCookie) return staticCookie
            val merged = LinkedHashMap<String, String>()
            // Baad wali list same name par pehli ko overwrite karti hai
            val ordered = if (STATIC_COOKIE_WINS) listOf(jarCookie, staticCookie)
            else listOf(staticCookie, jarCookie)
            ordered.forEach { raw ->
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

        // ---------------- Player ----------------

        fun createPlayer(context: Context): ExoPlayer {
            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

            val trackSelector = DefaultTrackSelector(context).apply {
                parameters = DefaultTrackSelector.Parameters.Builder(context)
                    .setTunnelingEnabled(false)
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
         * Active probe cancel karta hai aur pending callbacks hatata hai.
         */
        fun release() {
            playbackGeneration.incrementAndGet()
            activeProbeCall?.cancel()
            activeProbeCall = null
            mainHandler.removeCallbacksAndMessages(null)
        }

        fun playChannel(context: Context, player: ExoPlayer, channel: Channel) {
            activeProbeCall?.cancel()
            activeProbeCall = null
            val currentGeneration = playbackGeneration.incrementAndGet()

            val streamUrl = channel.url.trim()
            if (streamUrl.isEmpty()) return

            launchPlayback(context, player, channel, streamUrl, currentGeneration)
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

            // Is playback ka apna cookie jar: probe + playback + DRM teeno share karenge
            val jar = ChannelCookieJar()

            Log.d(TAG, "PLAY ${channel.name} url=$streamUrl hasCookie=${headers.containsKey("Cookie")}")

            if (knownMimeType != null || isDirectStreamOrMacPortal(streamUrl)) {
                startPlayback(context, player, channel, headers, userAgent, streamUrl, knownMimeType, jar, generation)
            } else {
                probeAndPlay(context, player, channel, headers, userAgent, streamUrl, jar, generation)
            }
        }

        private fun probeAndPlay(
            context: Context,
            player: ExoPlayer,
            channel: Channel,
            headers: Map<String, String>,
            userAgent: String,
            url: String,
            jar: ChannelCookieJar,
            generation: Int
        ) {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) -> requestBuilder.header(key, value) }

            // Probe me bhi redirect par headers jaane chahiye + chhota timeout taaki zap slow na ho
            val call = buildChannelClient(headers, jar, PROBE_TIMEOUT_SEC).newCall(requestBuilder.build())
            activeProbeCall = call

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (generation != playbackGeneration.get()) return
                    Log.w(TAG, "Probe failed for $url: ${e.message}")
                    mainHandler.post {
                        if (generation == playbackGeneration.get()) {
                            startPlayback(context, player, channel, headers, userAgent, url, null, jar, generation)
                        }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (generation != playbackGeneration.get()) return

                        val (resolvedUrl, resolvedMime) = detectUrlAndMimeFromResponse(url, res)

                        mainHandler.post {
                            if (generation == playbackGeneration.get()) {
                                startPlayback(context, player, channel, headers, userAgent, resolvedUrl, resolvedMime, jar, generation)
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
            jar: ChannelCookieJar,
            generation: Int
        ) {
            if (generation != playbackGeneration.get()) return

            // Har hop (302 redirect ke baad bhi) par headers lagte hain, aur cookie isi session ke jar se
            val channelClient = buildChannelClient(headers, jar)

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
                        .setMinPlaybackSpeed(1f)
                        .setMaxPlaybackSpeed(1f)
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
