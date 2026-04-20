package com.bluise.iptv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.ResponseBody
import okhttp3.ConnectionPool
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PlayerEngine {

    companion object {
        
        //  MASTER LOCK (true = Family/Aapke liye, false = Relatives)
        public const val IS_SPECIAL_EDITION = false 
        
        //  Current Proxy State
        @JvmStatic public var isProxyEnabled = false 

        private val mainHandler = Handler(Looper.getMainLooper())

        //  GLOBAL COOKIE MANAGER (For PHP stability)
        private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
        private val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies.toMutableList()
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: ArrayList()
            }
        }

        //  DoH Client (Keep-Alive Tunnel Setup - No repeated SSL Handshakes!)
        private val bootstrapClient = OkHttpClient.Builder()
            // 5 connections ko 5 minute tak open rakhega (Tunnel zinda rahegi)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES)) 
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
            
        private val dohDns = DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://ripaldns.duckdns.org:8443/dns-query".toHttpUrl())
            .build()

        //  THE FIX: Micro-Cache System (1 Minute Memory - Super Safe)
        private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<java.net.InetAddress>>>()
        private const val CACHE_EXPIRY_MS = 60_000L // Reduced to 1 Minute for extreme safety

        //  SMART DNS ROUTER (Official DoH + Tunnel + Micro-Cache)
        private val vpsDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                
                // Switch: Special Edition ON + Switch ON -> VPS via HTTPS Tunnel
                if (IS_SPECIAL_EDITION && isProxyEnabled) {
                    try {
                        // 1. CHEAT CODE: Sabse pehle apni Cache Memory me check karo
                        val cachedRecord = dnsCache[hostname]
                        if (cachedRecord != null && (System.currentTimeMillis() - cachedRecord.first) < CACHE_EXPIRY_MS) {
                            return cachedRecord.second // 0ms delay!
                        }

                        // 2. Cache me nahi hai toh Tunnel se mango (Keep-Alive hone se SSL Handshake bachega)
                        val adguardAddresses = dohDns.lookup(hostname)
                        
                        // 3. IPv4 filter lagao (Anti-Throttle Shield)
                        val ipv4Only = adguardAddresses.filter { it is Inet4Address }
                        val finalAddresses = if (ipv4Only.isNotEmpty()) ipv4Only else adguardAddresses

                        // 4. Agli baar ke liye 1 minute tak Cache me Save kar lo
                        dnsCache[hostname] = Pair(System.currentTimeMillis(), finalAddresses)

                        return finalAddresses
                    } catch (e: Exception) {
                        e.printStackTrace()
                        throw java.net.UnknownHostException("AdGuard DoH Failed for: $hostname") //  Kill Switch Safe!
                    }
                } 
                
                // Switch OFF hone par -> Direct Internet (Force IPv4)
                val systemAddresses = okhttp3.Dns.SYSTEM.lookup(hostname)
                val ipv4Only = systemAddresses.filter { it is Inet4Address }
                
                return if (ipv4Only.isNotEmpty()) ipv4Only else systemAddresses
            }
        }

        //  OPTIMIZED INTERCEPTOR: The "Juice Squeezer" for 4G Networks
        val okHttpClient = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .dns(vpsDns)                         
            .connectTimeout(8, TimeUnit.SECONDS) 
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)      
           // .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES)) 
            .followRedirects(true)
            .followSslRedirects(true)
            .hostnameVerifier { _, _ -> true }
            .cookieJar(cookieJar) 
            .addNetworkInterceptor { chain -> 
                val request = chain.request().newBuilder().header("Connection", "keep-alive").build()
                chain.proceed(request)
            }
            .addInterceptor { chain ->
                var request = chain.request()
                val url = request.url
                val urlString = url.toString()
                val host = url.host
                val path = url.encodedPath

                // 🚀 1. SUPER-FAST CPU FILTER (Early Exit)
                // MPD aur TS ko bhi target mein daala hai taaki unka operation na ruke
                val isTarget = host.contains("sm-monirul.top", true) || 
                               host.contains("bd.drmlive.net", true) || 
                               path.endsWith(".m3u8", true) ||
                               path.endsWith(".mpd", true) ||
                               urlString.contains(".ts", true)

                // THE LOCK: Agar yeh humari kaam ki link nahi hai, toh yahin se bypass kar do (0.1ms mein!)
                if (!isTarget) {
                    return@addInterceptor chain.proceed(request)
                }

                // 2. TS HOST FIX
                if (urlString.contains(".ts", true)) {
                    request = request.newBuilder().removeHeader("Host").build()
                }
                
                // 3. CACHE BUSTER (Ye zaroori hai taaki stream 39s par na ruke)
                if (urlString.contains(".m3u8", true)) {
                    val urlWithTimestamp = url.newBuilder()
                        .addQueryParameter("_t", System.currentTimeMillis().toString())
                        .build()
                        
                    request = request.newBuilder()
                        .url(urlWithTimestamp)
                        .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                        .build()
                }
                
                // 4. SERVER KO REQUEST BHEJO
                val response = chain.proceed(request)
                
                // 🚀 5. THE ULTIMATE MAGIC FILTER (M3U8 aur Monirul ke liye)
                val isM3u8Target = urlString.contains(".m3u8", ignoreCase = true) || urlString.contains("sm-monirul.top", ignoreCase = true)
                
                if (isM3u8Target && response.isSuccessful) {
                    val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
                    
                    // 🛑 THE MASTER FIX: Agar file M3U8 nahi hai (yaani Video chunk ya Binary Key hai), Bypass karo!
                    if (!contentType.contains("text") && !contentType.contains("mpegurl")) {
                        return@addInterceptor response
                    }

                    // Sirf aur sirf M3U8 Text files yahan aayengi
                    val body = response.body
                    if (body != null) {
                        val content = body.string()
                        
                        // Fake ENDLIST fraud catcher
                        if (content.contains("sourcefail") || content.contains("#EXT-X-ENDLIST")) {
                            throw java.io.IOException("Server sent fake ENDLIST. Triggering ExoPlayer Retry...")
                        }
                        
                        val newBody = ResponseBody.create(body.contentType(), content)
                        return@addInterceptor response.newBuilder().body(newBody).build()
                    }
                }
                
                // 🛡️ 6. THE MPD CLEARKEY PARSER (Aapka original parser)
                if (urlString.contains(".mpd", ignoreCase = true) && response.isSuccessful) {
                    val body = response.body
                    if (body != null) {
                        val contentType = body.contentType()
                        var content = body.string() 
                        
                        content = content.replace("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", "e2719d58-a985-b3c9-781a-b030af78d30e", true)
                        content = content.replace("9a04f079-9840-4286-ab92-e65be0885f95", "e2719d58-a985-b3c9-781a-b030af78d30e", true)
                        content = content.replace(Regex("<cenc:pssh[^>]*>.*?</cenc:pssh>", RegexOption.DOT_MATCHES_ALL), "")
                        content = content.replace(Regex("<pssh[^>]*>.*?</pssh>", RegexOption.DOT_MATCHES_ALL), "")
                        
                        val newBody = ResponseBody.create(contentType, content)
                        return@addInterceptor response.newBuilder().body(newBody).build()
                    }
                }
                
                return@addInterceptor response
            }
            .build()

        // ==========================================
        // PART 1: PLAYER MAKER
        // ==========================================
        fun createPlayer(context: Context): ExoPlayer {
            // App start hote hi memory load karega
            val prefs = context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
            isProxyEnabled = prefs.getBoolean("vps_proxy_enabled", true)
            
            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

            val trackSelector = DefaultTrackSelector(context)
            trackSelector.parameters = trackSelector.buildUponParameters()
                .setTunnelingEnabled(true) 
                .setMaxAudioChannelCount(2)
                .build()

            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(30_000, 100_000, 500, 3_000) 
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA) 
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC) 
                .build()

            val player = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build()

            player.setAudioAttributes(audioAttributes, true)
            player.setHandleAudioBecomingNoisy(true)
            player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)

            return player
        }

        // ==========================================
        // PART 2: CHANNEL LOADER (WITH PROXY FIX)
        // ==========================================
        fun playChannel(context: Context, player: ExoPlayer, channel: Channel) {
            
            // Channel chalne se pehle memory state check
            val prefs = context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
            isProxyEnabled = prefs.getBoolean("vps_proxy_enabled", true)

            player.stop()
            player.clearMediaItems()

            val headers = channel.getHeadersMap().toMutableMap()
            if (!headers.containsKey("Referer") && channel.url.contains(".php", true)) {
                headers["Referer"] = "https://www.google.com/"
            }
            
            val userAgent = channel.userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            val forcedMime = when {
                channel.url.contains(".mpd", ignoreCase = true) || channel.url.contains("=mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                channel.url.contains(".m3u8", ignoreCase = true) || channel.url.contains("=m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                else -> null
            }

            if (channel.url.contains(".ts", true) || channel.url.contains("=ts", true)) {
                startPlayback(context, player, channel, headers, userAgent, channel.url, null)
                return 
            }

            if (channel.url.contains(".php", true) || !channel.url.contains(".")) {
                thread {
                    try {
                        val request = Request.Builder().url(channel.url)
                        headers.forEach { (k, v) -> request.addHeader(k, v) }
                        
                        val response = okHttpClient.newCall(request.build()).execute()
                        val finalUrl = response.request.url.toString()
                        val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
                        
                        if (contentType.contains("text") || contentType.contains("html")) {
                            val body = response.body?.string()?.trim() ?: ""
                            if (body.startsWith("http") && !body.contains("#EXTM3U")) {
                                val bodyMime = when {
                                    body.contains(".mpd", true) || body.contains("=mpd", true) -> MimeTypes.APPLICATION_MPD
                                    body.contains(".m3u8", true) || body.contains("=m3u8", true) -> MimeTypes.APPLICATION_M3U8
                                    else -> null
                                }
                                mainHandler.post { startPlayback(context, player, channel, headers, userAgent, body, bodyMime) }
                                return@thread
                            }
                        }
                        response.close()

                        val isTs = finalUrl.contains(".ts", true) || finalUrl.contains("=ts", true) || contentType.contains("video/mp2t")
                        val dynamicMime = if (isTs) null else (forcedMime ?: MimeTypes.APPLICATION_M3U8)
                        
                        mainHandler.post {
                            startPlayback(context, player, channel, headers, userAgent, channel.url, dynamicMime)
                        }
                    } catch (e: Exception) {
                        mainHandler.post { startPlayback(context, player, channel, headers, userAgent, channel.url, forcedMime ?: MimeTypes.APPLICATION_M3U8) }
                    }
                }
            } else {
                startPlayback(context, player, channel, headers, userAgent, channel.url, forcedMime)
            }
        }

        // ==========================================
        // PART 3: PLAYBACK ENGINE
        // ==========================================
        private fun startPlayback(
            context: Context, 
            player: ExoPlayer, 
            channel: Channel, 
            headers: MutableMap<String, String>, 
            userAgent: String,
            streamUrl: String, 
            forcedMimeType: String?
        ) {
            val httpFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)

            var drmSessionManager: DefaultDrmSessionManager? = null
            var drmConfigurationBuilder: MediaItem.DrmConfiguration.Builder? = null

            if (channel.drmKeyId != null && channel.drmKey != null) {
                val callback = LocalClearKeyCallback(channel.drmKeyId!!, channel.drmKey!!)
                
                drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true)
                    .build(callback)
                    
                drmConfigurationBuilder = MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
            } 
            else if (channel.drmLicenseUrl != null) {
                val callback = HttpMediaDrmCallback(channel.drmLicenseUrl, httpFactory)
                headers.forEach { (k, v) -> callback.setKeyRequestProperty(k, v) }
                val isClearKey = channel.drmLicenseUrl!!.contains("clearkey", true)

                if (isClearKey) {
                    callback.setKeyRequestProperty("Content-Type", "application/json")
                    drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true)
                        .build(callback)
                    drmConfigurationBuilder = MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                } else {
                    drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true)
                        .build(callback)
                    drmConfigurationBuilder = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                }
            }

            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)
            if (forcedMimeType != null) mediaItemBuilder.setMimeType(forcedMimeType)
            if (drmConfigurationBuilder != null) mediaItemBuilder.setDrmConfiguration(drmConfigurationBuilder.build())

            val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
            if (drmSessionManager != null) mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager!! }
            
            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItemBuilder.build()))
            player.prepare() 
            player.playWhenReady = true
        }
    }

    // ==========================================
    // LOCAL CLEARKEY CALLBACK (The Json Provider)
    // ==========================================
    private class LocalClearKeyCallback(val keyId: String, val key: String) : MediaDrmCallback {
        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response {
            return MediaDrmCallback.Response(ByteArray(0))
        }

        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
            val kid = formatKey(keyId)
            val k = formatKey(key)
            val json = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"$k\",\"kid\":\"$kid\"}],\"type\":\"temporary\"}"
            return MediaDrmCallback.Response(json.toByteArray(Charsets.UTF_8))
        }

        private fun formatKey(str: String): String {
            val cleanStr = str.replace("-", "").trim()
            if (cleanStr.length == 32) {
                try {
                    val bArr = ByteArray(16)
                    for (i in 0 until 32 step 2) {
                        bArr[i / 2] = ((Character.digit(cleanStr[i], 16) shl 4) + Character.digit(cleanStr[i + 1], 16)).toByte()
                    }
                    return Base64.encodeToString(bArr, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                } catch (e: Exception) {}
            }
            return cleanStr
        }
    }
}