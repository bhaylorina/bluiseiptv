package com.bluise.iptv.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLDecoder
import java.util.regex.Pattern

class IptvParser {

    companion object {
        private const val TAG = "IptvParser"

        // Precompiled regex — never inside loop
        private val ATTR_PATTERN = Pattern.compile("""([a-zA-Z0-9_.\-]+)="([^"]*)"""")
        private val KEYID_PATTERN = Pattern.compile("keyid=([a-fA-F0-9\\-]+)", Pattern.CASE_INSENSITIVE)
        private val KEY_PATTERN = Pattern.compile("(?:^|[&\\s])key=([a-fA-F0-9]+)", Pattern.CASE_INSENSITIVE)
        private val KID_KEY_COLON = Pattern.compile("^([a-fA-F0-9\\-]{32,36})[=:]([a-fA-F0-9]{32})$")

        private val PIPE_HEADER_MAP = mapOf(
            "user-agent"      to "User-Agent",
            "useragent"       to "User-Agent",
            "referer"         to "Referer",
            "referrer"        to "Referer",
            "cookie"          to "Cookie",
            "origin"          to "Origin",
            "authorization"   to "Authorization",
            "x-forwarded-for" to "X-Forwarded-For"
        )

        private val UA_ALIASES = mapOf(
            "@cloudplay" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "@chrome"    to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "@android"   to "Dalvik/2.1.0 (Linux; U; Android 10; Generic)",
            "@vlc"       to "VLC/3.0.18 LibVLC/3.0.18"
        )
    }

    private var currentTitle: String?      = null
    private var currentLogo: String?       = null
    private var currentGroup: String?      = null
    private var currentLicenseKey: String? = null
    private var currentKeyId: String?      = null
    private var currentKey: String?        = null
    private var currentUa: String?         = null
    private var currentCookie: String?     = null
    private var currentReferer: String?    = null
    private var currentDrmScheme: String?  = null
    private val currentHeaders             = HashMap<String, String>()
    private var insideExtInf               = false

    fun parse(input: InputStream): ArrayList<Channel> {
        val bytes = input.use { it.readBytes() }
        val text  = bytes.toString(Charsets.UTF_8).trimStart()

        return when {
            text.startsWith("[") || text.startsWith("{") ->
                parseJson(text.byteInputStream(Charsets.UTF_8))
            else ->
                parseM3U(text.byteInputStream(Charsets.UTF_8))
        }
    }

    fun parseM3U(input: InputStream): ArrayList<Channel> {
        val channels = ArrayList<Channel>(1000)
        resetState()

        try {
            input.use { stream ->
                stream.bufferedReader(Charsets.UTF_8)
                    .lineSequence()
                    .forEach { line ->
                        val trim = line.trim()
                        if (trim.isNotEmpty()) {
                            processLine(trim, channels)
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "M3U parse failed: ${e.message}", e)
        }
        return channels
    }

    private fun processLine(line: String, channels: ArrayList<Channel>) {
        when {
            line.startsWith("#EXTINF:")    -> handleExtInf(line)
            line.startsWith("#EXTGRP:")    -> handleExtGrp(line)
            line.startsWith("#EXTHTTP:")   -> handleExtHttp(line)
            line.startsWith("#KODIPROP:")  -> handleKodiProp(line)
            line.startsWith("#EXTVLCOPT:") -> handleVlcOpt(line)
            line.startsWith("#EXTM3U")     -> { }
            line.startsWith("#")           -> { }
            else                           -> handleUrl(line, channels)
        }
    }

    private fun handleExtInf(line: String) {
        insideExtInf = true
        val lastComma = line.lastIndexOf(',')
        currentTitle = if (lastComma != -1 && lastComma < line.length - 1) {
            line.substring(lastComma + 1).trim().ifEmpty { null }
        } else null

        val matcher = ATTR_PATTERN.matcher(line)
        while (matcher.find()) {
            val attrKey = matcher.group(1)?.lowercase()?.trim() ?: continue
            val attrVal = matcher.group(2)?.trim() ?: continue
            if (attrVal.isEmpty()) continue

            when (attrKey) {
                "tvg-logo", "logo"          -> currentLogo = attrVal
                "group-title"               -> currentGroup = attrVal
                "tvg-name"                  -> {
                    if (currentTitle.isNullOrEmpty()) currentTitle = attrVal
                }
                "keyid", "kid", "drm-keyid" -> currentKeyId = attrVal.replace("-", "")
                
                // 🔥 FIX: Handles both license_key and licence_key for EXTINF tags
                "key", "license_key", "licence_key", "drm-key" -> currentKey = attrVal
                
                "user-agent"                -> if (currentUa == null) currentUa = attrVal
            }
        }
    }

    private fun handleExtGrp(line: String) {
        val grp = line.substringAfter(":").trim()
        if (grp.isNotEmpty() && currentGroup.isNullOrEmpty()) {
            currentGroup = grp
        }
    }

    private fun handleExtHttp(line: String) {
        val jsonStr = line.removePrefix("#EXTHTTP:").trim()
        if (jsonStr.isEmpty()) return

        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "").trim()
                if (v.isEmpty()) continue

                when (k.lowercase()) {
                    "user-agent"          -> if (currentUa == null) currentUa = v
                    "referer", "referrer" -> if (currentReferer == null) currentReferer = v
                    "cookie"              -> if (currentCookie == null) currentCookie = v
                    "origin"              -> currentHeaders.putIfAbsent("Origin", v)
                }
            }
        } catch (e: Exception) { }
    }

    private fun handleKodiProp(line: String) {
        val prop  = line.removePrefix("#KODIPROP:").trim()
        val eqIdx = prop.indexOf('=')
        if (eqIdx == -1) return

        val propKey = prop.substring(0, eqIdx).trim().lowercase()
        val propVal = prop.substring(eqIdx + 1).trim()
        if (propVal.isEmpty()) return

        when (propKey) {
            // 🔥 FIX: Added both 'license' and 'licence' spellings to keep it safe for all providers
            "inputstream.adaptive.license_type", 
            "inputstream.adaptive.licence_type"   -> currentDrmScheme = propVal
            
            "inputstream.adaptive.license_key", 
            "inputstream.adaptive.licence_key"    -> parseLicenseKeyValue(propVal)
            
            "inputstream.adaptive.stream_headers" -> parsePipeParams(propVal)
            "http-user-agent", "http_user_agent"  -> if (currentUa == null) currentUa = propVal
            "http-referer",    "http_referer"     -> if (currentReferer == null) currentReferer = propVal
        }
    }

    private fun handleVlcOpt(line: String) {
        val opt   = line.removePrefix("#EXTVLCOPT:").trim()
        val eqIdx = opt.indexOf('=')
        if (eqIdx == -1) return

        val optKey = opt.substring(0, eqIdx).trim().lowercase()
        val optVal = opt.substring(eqIdx + 1).trim()
        if (optVal.isEmpty()) return

        when (optKey) {
            "http-user-agent", "http_user_agent", "sout-http-user-agent" -> if (currentUa == null) currentUa = optVal
            "http-referrer", "http-referer", "http_referrer", "http_referer" -> if (currentReferer == null) currentReferer = optVal
            "http-cookie", "http_cookie" -> if (currentCookie == null) currentCookie = optVal
        }
    }

    private fun handleUrl(line: String, channels: ArrayList<Channel>) {
        val pipeIdx = line.indexOf('|')
        val rawUrl  = if (pipeIdx != -1) line.substring(0, pipeIdx).trim() else line.trim()

        if (rawUrl.isEmpty()) {
            resetState()
            return
        }

        if (!isValidScheme(rawUrl)) {
            resetState()
            return
        }

        if (pipeIdx != -1) {
            parsePipeParams(line.substring(pipeIdx + 1))
        }

        val finalTitle = currentTitle?.takeIf { it.isNotBlank() } ?: rawUrl.substringAfterLast("/").substringBefore("?").substringBefore(".").ifEmpty { "Unknown" }
        val finalGroup = currentGroup?.takeIf { it.isNotBlank() } ?: "Others"

        channels.add(
            Channel(
                name          = finalTitle,
                url           = rawUrl,
                logoUrl       = currentLogo,
                group         = finalGroup,
                userAgent     = currentUa,
                cookie        = currentCookie,
                referer       = currentReferer,
                drmLicenseUrl = currentLicenseKey,
                drmKeyId      = currentKeyId,
                drmKey        = currentKey,
                drmScheme     = currentDrmScheme,
                customHeaders = if (currentHeaders.isEmpty()) null else HashMap(currentHeaders)
            )
        )
        resetState()
    }

    private fun parseLicenseKeyValue(value: String) {
        when {
            value.startsWith("{") && value.contains("\"keys\"") -> {
                currentLicenseKey = value
                currentKeyId      = null
                currentKey        = null
            }
            value.contains("keyid=", ignoreCase = true) && value.contains("key=", ignoreCase = true) -> {
                val mKid = KEYID_PATTERN.matcher(value)
                if (mKid.find()) currentKeyId = mKid.group(1)?.replace("-", "")
                val mKey = KEY_PATTERN.matcher(value)
                if (mKey.find()) currentKey = mKey.group(1)
            }
            KID_KEY_COLON.matcher(value).matches() -> {
                val m = KID_KEY_COLON.matcher(value)
                if (m.matches()) {
                    currentKeyId = m.group(1)?.replace("-", "")
                    currentKey   = m.group(2)
                }
            }
            value.contains("|") && value.startsWith("http") -> {
                currentLicenseKey = value.substringBefore("|").trim()
            }
            value.startsWith("http://") || value.startsWith("https://") -> {
                currentLicenseKey = value
            }
            else -> {
                currentLicenseKey = value
            }
        }
    }

    private fun parsePipeParams(paramStr: String) {
        paramStr.split("&").forEach { part ->
            val eqIdx = part.indexOf('=')
            if (eqIdx == -1) return@forEach

            val rawKey = part.substring(0, eqIdx).trim()
            val rawValue  = part.substring(eqIdx + 1).trim()
            if (rawKey.isEmpty() || rawValue.isEmpty()) return@forEach

            // 🔥 FIX: Decode URL-encoded headers (like %20, %3A, %3D) safely for JioTV
            val value = try {
                URLDecoder.decode(rawValue, "UTF-8")
            } catch (e: Exception) {
                rawValue
            }

            val canonical = PIPE_HEADER_MAP[rawKey.lowercase()] ?: rawKey

            when (canonical) {
                "User-Agent" -> if (currentUa == null)     currentUa      = value
                "Referer"    -> if (currentReferer == null) currentReferer = value
                "Cookie"     -> if (currentCookie == null)  currentCookie  = value
                else         -> currentHeaders[canonical] = value
            }
        }
    }

    fun parseJson(input: InputStream): ArrayList<Channel> {
        val channels = ArrayList<Channel>(1000)

        try {
            val text = input.use { it.readBytes() }.toString(Charsets.UTF_8).trim()

            val array: JSONArray = when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> {
                    val obj = JSONObject(text)
                    when {
                        obj.has("channels") -> obj.getJSONArray("channels")
                        obj.has("data")     -> obj.getJSONArray("data")
                        obj.has("playlist") -> obj.getJSONArray("playlist")
                        obj.has("items")    -> obj.getJSONArray("items")
                        else -> return channels
                    }
                }
                else -> return channels
            }

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseJsonChannel(obj)?.let { channels.add(it) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}", e)
        }
        return channels
    }

    private fun parseJsonChannel(obj: JSONObject): Channel? {
        val streamUrl = listOf("mpd_url", "url", "stream_url", "hls_url", "link")
            .firstNotNullOfOrNull { key -> obj.optString(key, "").trim().takeIf { it.isNotBlank() } }

        if (streamUrl.isNullOrBlank()) return null
        if (!isValidScheme(streamUrl)) return null

        val name = obj.optString("name", "").trim().ifBlank {
            streamUrl.substringAfterLast("/").substringBefore("?").substringBefore(".").ifEmpty { "Unknown" }
        }

        val group = obj.optString("group", "").trim().ifBlank {
            obj.optString("category", "").trim().ifBlank { "Others" }
        }

        val logo = obj.optString("logo", "").trim().ifBlank {
            obj.optString("logo_url", "").trim().ifBlank { null }
        }

        val rawUa = obj.optString("user_agent", "").trim().ifBlank { obj.optString("user-agent", "").trim().ifBlank { null } }
        val userAgent = rawUa?.let { resolveUaAlias(it) }

        // 🔥 FIX: Covers license_url, drm_license_url AND licence_url
        val licenseUrl = obj.optString("license_url", "").trim().ifBlank { 
            obj.optString("drm_license_url", "").trim().ifBlank { 
                obj.optString("licence_url", "").trim().ifBlank { null } 
            } 
        }

        val typeField = obj.optString("type", "").trim().lowercase()
        val drmScheme: String? = obj.optString("drm_scheme", "").trim().ifBlank { null }
            ?: when {
                typeField == "widevine"                          -> "widevine"
                typeField == "clearkey"                          -> "clearkey"
                typeField == "dash" && !licenseUrl.isNullOrBlank() -> "clearkey"
                else                                             -> null
            }

        var parsedLicenseUrl: String? = licenseUrl
        var parsedKeyId: String? = obj.optString("key_id", "").trim().ifBlank { obj.optString("kid", "").trim().ifBlank { null } }?.replace("-", "")
        var parsedKey: String? = obj.optString("key", "").trim().ifBlank { null }

        // 🔥 FIX: Covers license_key AND licence_key for JSON
        val rawLicenseKey = obj.optString("license_key", "").trim().ifBlank { 
            obj.optString("licence_key", "").trim().ifBlank { null } 
        }
        
        if (rawLicenseKey != null && licenseUrl == null) {
            val (lu, ki, k) = parseRawLicenseKey(rawLicenseKey)
            if (lu  != null) parsedLicenseUrl = lu
            if (ki  != null) parsedKeyId      = ki
            if (k   != null) parsedKey        = k
        }

        var cookie: String?  = null
        var referer: String? = null
        val extraHeaders     = HashMap<String, String>()

        val headersObj = obj.optJSONObject("headers")
        if (headersObj != null) {
            val hKeys = headersObj.keys()
            while (hKeys.hasNext()) {
                val hk = hKeys.next()
                val hv = headersObj.optString(hk, "").trim()
                if (hv.isEmpty() || hv.equals("null", ignoreCase = true)) continue

                when (hk.lowercase().replace("_", "-")) {
                    "cookie"                -> cookie  = hv
                    "referer", "referrer"   -> referer = hv
                    "user-agent"            -> Unit
                    "origin"                -> extraHeaders["Origin"]          = hv
                    "authorization"         -> extraHeaders["Authorization"]   = hv
                    "x-forwarded-for"       -> extraHeaders["X-Forwarded-For"] = hv
                    else                    -> extraHeaders[hk] = hv
                }
            }

            val uaFromHeader = headersObj.optString("user-agent", "").trim().ifBlank {
                headersObj.optString("user_agent", "").trim().ifBlank { null }
            }
            val finalUa = userAgent ?: uaFromHeader?.let { resolveUaAlias(it) }

            if (cookie == null) cookie = obj.optString("cookie", "").trim().ifBlank { null }
            if (referer == null) referer = obj.optString("referer", "").trim().ifBlank { obj.optString("referrer", "").trim().ifBlank { null } }

            return Channel(
                name          = name,
                url           = streamUrl,
                logoUrl       = logo,
                group         = group,
                userAgent     = finalUa,
                cookie        = cookie,
                referer       = referer,
                drmLicenseUrl = parsedLicenseUrl,
                drmKeyId      = parsedKeyId,
                drmKey        = parsedKey,
                drmScheme     = drmScheme,
                customHeaders = if (extraHeaders.isEmpty()) null else extraHeaders
            )
        }

        if (cookie == null) cookie = obj.optString("cookie", "").trim().ifBlank { null }
        if (referer == null) referer = obj.optString("referer", "").trim().ifBlank { obj.optString("referrer", "").trim().ifBlank { null } }

        return Channel(
            name          = name,
            url           = streamUrl,
            logoUrl       = logo,
            group         = group,
            userAgent     = userAgent,
            cookie        = cookie,
            referer       = referer,
            drmLicenseUrl = parsedLicenseUrl,
            drmKeyId      = parsedKeyId,
            drmKey        = parsedKey,
            drmScheme     = drmScheme,
            customHeaders = if (extraHeaders.isEmpty()) null else extraHeaders
        )
    }

    private fun resolveUaAlias(raw: String): String {
        if (!raw.startsWith("@")) return raw
        return UA_ALIASES[raw.lowercase()] ?: raw
    }

    private fun isValidScheme(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://")  || lower.startsWith("https://") || lower.startsWith("rtmp://")  || lower.startsWith("rtsp://")  || lower.startsWith("rtp://")   || lower.startsWith("udp://")
    }

    private fun parseRawLicenseKey(value: String): Triple<String?, String?, String?> {
        return when {
            value.startsWith("{") && value.contains("\"keys\"") -> Triple(value, null, null)
            value.contains("keyid=", ignoreCase = true) && value.contains("key=",   ignoreCase = true) -> {
                val mKid = KEYID_PATTERN.matcher(value)
                val mKey = KEY_PATTERN.matcher(value)
                Triple(null, if (mKid.find()) mKid.group(1)?.replace("-", "") else null, if (mKey.find()) mKey.group(1) else null)
            }
            KID_KEY_COLON.matcher(value).matches() -> {
                val m = KID_KEY_COLON.matcher(value)
                m.matches()
                Triple(null, m.group(1)?.replace("-", ""), m.group(2))
            }
            value.contains("|") && value.startsWith("http") -> Triple(value.substringBefore("|").trim(), null, null)
            value.startsWith("http://") || value.startsWith("https://") -> Triple(value, null, null)
            
            else -> Triple(value, null, null)
        }
    }

    private fun resetState() {
        currentTitle = null; currentLogo = null; currentGroup = null
        currentLicenseKey = null; currentKeyId = null; currentKey = null
        currentUa = null; currentCookie = null; currentReferer = null; currentDrmScheme = null
        currentHeaders.clear(); insideExtInf = false
    }
                                                   }
                                                   
