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
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
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
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.InetSocketAddress
import java.io.IOException
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
class PlayerEngine {

    companion object {

        private const val TAG = "PlayerEngine"

        private const val DEFAULT_USER_AGENT =
            "ExoPlayerLib/1.11.1 (Linux; Android) AndroidXMedia3/1.11.1"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val playbackGeneration = AtomicInteger(0)

        @Volatile
        private var activeProbeCall: Call? = null

        private val cookieStore = ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

        private val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val now = System.currentTimeMillis()
                val validCookies = cookies.filter { cookie ->
                    val isValidTime = cookie.expiresAt > now
                    val isAkamaiPoison = cookie.name.equals("hdntl", ignoreCase = true) &&
                        cookie.value.equals("expired", ignoreCase = true)
                    isValidTime && !isAkamaiPoison
                }
                if (validCookies.isNotEmpty()) {
                    val hostCookies = cookieStore.getOrPut(url.host) { CopyOnWriteArrayList() }
                    validCookies.forEach { incoming ->
                        hostCookies.removeAll { existing -> existing.name == incoming.name }
                        hostCookies.add(incoming)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = cookieStore[url.host] ?: return emptyList()
                val now = System.currentTimeMillis()
                return hostCookies.filter { it.expiresAt > now }
            }
        }

        private val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 15
        }

        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
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

        fun createPlayer(context: Context): ExoPlayer {
            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

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

        fun playChannel(context: Context, player: ExoPlayer, channel: Channel) {
            activeProbeCall?.cancel()
            activeProbeCall = null
            val currentGeneration = playbackGeneration.incrementAndGet()

            val streamUrl = channel.url.trim()
            if (streamUrl.isEmpty()) return

            val headers = buildHeaders(channel)
            val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT
            val knownMimeType = inferMimeType(streamUrl)

            // If MIME type is already known (.m3u8 / .mpd) or it is a direct TS/MP4/Stalker MAC URL, play immediately
            if (knownMimeType != null || isDirectStreamOrMacPortal(streamUrl)) {
                startPlayback(context, player, channel, headers, userAgent, streamUrl, knownMimeType, currentGeneration)
            } else {
                // Extensionless URL (e.g. /play?show=... or .php): safely inspect Content-Type & first 1KB
                probeAndPlay(context, player, channel, headers, userAgent, streamUrl, currentGeneration)
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

            val call = okHttpClient.newCall(requestBuilder.build())
            activeProbeCall = call

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (generation != playbackGeneration.get()) return
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

        /**
         * Safely detects the stream format from the HTTP Content-Type header, redirect URL,
         * or by peeking at most 1024 bytes of the response body (never blocks on infinite TS streams).
         */
        private fun detectUrlAndMimeFromResponse(originalUrl: String, response: Response): Pair<String, String?> {
            val finalUrl = response.request.url.toString()
            val contentType = response.header("Content-Type", "")?.lowercase().orEmpty()

            // 1. Check explicit HTTP Content-Type header first
            when {
                contentType.contains("application/dash+xml") -> return finalUrl to MimeTypes.APPLICATION_MPD
                contentType.contains("mpegurl") || contentType.contains("m3u8") -> return finalUrl to MimeTypes.APPLICATION_M3U8
                contentType.contains("video/") || contentType.contains("audio/") ||
                    contentType.contains("application/octet-stream") -> return finalUrl to null
            }

            // 2. Check if 302 redirect landed on a URL with a known extension
            inferMimeType(finalUrl)?.let { return finalUrl to it }

            // 3. Safely peek up to 1024 bytes (does not consume the full stream)
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

            val channelClient = okHttpClient.newBuilder()
                .addNetworkInterceptor { chain ->
                    val original = chain.request()
                    val requestBuilder = original.newBuilder()
                    headers.forEach { (key, value) ->
                        if (!key.equals("Host", ignoreCase = true)) {
                            requestBuilder.header(key, value)
                        }
                    }
                    chain.proceed(requestBuilder.build())
                }
                .build()

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
