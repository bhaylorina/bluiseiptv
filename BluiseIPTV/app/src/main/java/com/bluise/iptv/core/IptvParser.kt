package com.bluise.iptv.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.regex.Pattern

class IptvParser {

    companion object {
        private const val TAG = "IptvParser"

        // Precompiled regex — never inside loop
        private val ATTR_PATTERN =
            Pattern.compile("""([a-zA-Z0-9_.\-]+)="([^"]*)"""")
        private val KEYID_PATTERN =
            Pattern.compile("keyid=([a-fA-F0-9\\-]+)", Pattern.CASE_INSENSITIVE)
        private val KEY_PATTERN =
            Pattern.compile("(?:^|[&\\s])key=([a-fA-F0-9]+)", Pattern.CASE_INSENSITIVE)
        private val KID_KEY_COLON =
            Pattern.compile("^([a-fA-F0-9\\-]{32,36})[=:]([a-fA-F0-9]{32})$")

        // Pipe header canonical names
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

        // Known user-agent aliases
        private val UA_ALIASES = mapOf(
            "@cloudplay" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                           "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "@chrome"    to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                           "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "@android"   to "Dalvik/2.1.0 (Linux; U; Android 10; Generic)",
            "@vlc"       to "VLC/3.0.18 LibVLC/3.0.18"
        )
    }

    // ── Parser state — reset after each M3U channel ───────────────────────────
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

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Auto-detects format (M3U or JSON) and parses accordingly.
     * Use this as the single entry point.
     */
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

    // ═══════════════════════════════════════════════════════════════════════════
    // M3U PARSER
    // ═══════════════════════════════════════════════════════════════════════════

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

        Log.d(TAG, "Parsed ${channels.size} channels from M3U")
        return channels
    }

    private fun processLine(line: String, channels: ArrayList<Channel>) {
        when {
            line.startsWith("#EXTINF:")    -> handleExtInf(line)
            line.startsWith("#EXTGRP:")    -> handleExtGrp(line)
            line.startsWith("#EXTHTTP:")   -> handleExtHttp(line)
            line.startsWith("#KODIPROP:")  -> handleKodiProp(line)
            line.startsWith("#EXTVLCOPT:") -> handleVlcOpt(line)
            line.startsWith("#EXTM3U")     -> { /* playlist header — skip */ }
            line.startsWith("#")           -> { /* unknown directive — skip */ }
            else                           -> handleUrl(line, channels)
        }
    }

    // ── #EXTINF ───────────────────────────────────────────────────────────────
    private fun handleExtInf(line: String) {
        // YAHAN SE RESETSTATE() HATA DIYA HAI. Ab Cookie delete nahi hogi.
        insideExtInf = true

        // Channel name = everything after LAST comma
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
                "key", "license_key",
                "drm-key"                   -> currentKey = attrVal
                "user-agent"                -> if (currentUa == null) currentUa = attrVal
            }
        }
    }

    // ── #EXTGRP ───────────────────────────────────────────────────────────────
    private fun handleExtGrp(line: String) {
        val grp = line.substringAfter(":").trim()
        // EXTINF group-title wins — only set if not already set
        if (grp.isNotEmpty() && currentGroup.isNullOrEmpty()) {
            currentGroup = grp
        }
    }

    // ── #EXTHTTP ──────────────────────────────────────────────────────────────
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
        } catch (e: Exception) {
            Log.w(TAG, "EXTHTTP parse error: ${e.message}")
        }
    }

    // ── #KODIPROP ─────────────────────────────────────────────────────────────
    private fun handleKodiProp(line: String) {
        val prop  = line.removePrefix("#KODIPROP:").trim()
        val eqIdx = prop.indexOf('=')
        if (eqIdx == -1) return

        val propKey = prop.substring(0, eqIdx).trim().lowercase()
        val propVal = prop.substring(eqIdx + 1).trim()
        if (propVal.isEmpty()) return

        when (propKey) {
            "inputstream.adaptive.license_type"   -> currentDrmScheme = propVal
            "inputstream.adaptive.license_key"    -> parseLicenseKeyValue(propVal)
            "inputstream.adaptive.stream_headers" -> parsePipeParams(propVal)
            "http-user-agent", "http_user_agent"  -> if (currentUa == null) currentUa = propVal
            "http-referer",    "http_referer"      -> if (currentReferer == null) currentReferer = propVal
        }
    }

    // ── #EXTVLCOPT ────────────────────────────────────────���───────────────────
    private fun handleVlcOpt(line: String) {
        val opt   = line.removePrefix("#EXTVLCOPT:").trim()
        val eqIdx = opt.indexOf('=')
        if (eqIdx == -1) return

        val optKey = opt.substring(0, eqIdx).trim().lowercase()
        val optVal = opt.substring(eqIdx + 1).trim()
        if (optVal.isEmpty()) return

        when (optKey) {
            "http-user-agent",
            "http_user_agent",
            "sout-http-user-agent" -> if (currentUa == null) currentUa = optVal

            "http-referrer",
            "http-referer",
            "http_referrer",
            "http_referer"         -> if (currentReferer == null) currentReferer = optVal

            "http-cookie",
            "http_cookie"          -> if (currentCookie == null) currentCookie = optVal
        }
    }

    // ── URL line ──────────────────────────────────────────────────────────────
    private fun handleUrl(line: String, channels: ArrayList<Channel>) {
        val pipeIdx = line.indexOf('|')
        val rawUrl  = if (pipeIdx != -1) line.substring(0, pipeIdx).trim()
                      else line.trim()

        if (rawUrl.isEmpty()) {
            Log.w(TAG, "Empty URL — skipping")
            resetState()
            insideExtInf = false
            return
        }

        if (!isValidScheme(rawUrl)) {
            Log.w(TAG, "Unknown scheme: $rawUrl — skipping")
            resetState()
            insideExtInf = false
            return
        }

        // Parse pipe params if present: url|Key=Value&Key2=Value2
        if (pipeIdx != -1) {
            parsePipeParams(line.substring(pipeIdx + 1))
        }

        val finalTitle = currentTitle
            ?.takeIf { it.isNotBlank() }
            ?: rawUrl.substringAfterLast("/")
                .substringBefore("?")
                .substringBefore(".")
                .ifEmpty { "Unknown" }

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
                customHeaders = if (currentHeaders.isEmpty()) null
                                else HashMap(currentHeaders)
            )
        )

        resetState()
        insideExtInf = false
    }

    // ── M3U license key parser ────────────────────────────────────────────────
    private fun parseLicenseKeyValue(value: String) {
        when {
            // 1. JSON ClearKey blob: {"keys":[...]}
            value.startsWith("{") && value.contains("\"keys\"") -> {
                currentLicenseKey = value
                currentKeyId      = null
                currentKey        = null
            }

            // 2. keyid=HEX&key=HEX
            value.contains("keyid=", ignoreCase = true) &&
            value.contains("key=",   ignoreCase = true) -> {
                val mKid = KEYID_PATTERN.matcher(value)
                if (mKid.find()) currentKeyId = mKid.group(1)?.replace("-", "")
                val mKey = KEY_PATTERN.matcher(value)
                if (mKey.find()) currentKey = mKey.group(1)
            }

            // 3. kid:key or kid=key (32-char hex pairs)
            KID_KEY_COLON.matcher(value).matches() -> {
                val m = KID_KEY_COLON.matcher(value)
                if (m.matches()) {
                    currentKeyId = m.group(1)?.replace("-", "")
                    currentKey   = m.group(2)
                }
            }

            // 4. url|headers — extract just URL
            value.contains("|") && value.startsWith("http") -> {
                currentLicenseKey = value.substringBefore("|").trim()
            }

            // 5. Remote license URL
            value.startsWith("http://") || value.startsWith("https://") -> {
                currentLicenseKey = value
            }

            // 6. Unknown — store raw
            else -> {
                Log.w(TAG, "Unknown license_key format: $value")
                currentLicenseKey = value
            }
        }
    }

    // ── Pipe / header string parser ───────────────────────────────────────────
    private fun parsePipeParams(paramStr: String) {
        paramStr.split("&").forEach { part ->
            val eqIdx = part.indexOf('=')
            if (eqIdx == -1) return@forEach

            val rawKey = part.substring(0, eqIdx).trim()
            val value  = part.substring(eqIdx + 1).trim()
            if (rawKey.isEmpty() || value.isEmpty()) return@forEach

            val canonical = PIPE_HEADER_MAP[rawKey.lowercase()] ?: rawKey

            when (canonical) {
                "User-Agent" -> if (currentUa == null)     currentUa      = value
                "Referer"    -> if (currentReferer == null) currentReferer = value
                "Cookie"     -> if (currentCookie == null)  currentCookie  = value
                else         -> currentHeaders[canonical] = value
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JSON PARSER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parse JSON playlist.
     *
     * Supported root formats:
     * • Array:   [ { channel }, { channel }, ... ]
     * • Object:  { "channels": [...] }
     * { "data": [...] }
     * { "playlist": [...] }
     * { "items": [...] }
     *
     * Per-channel fields:
     * type         – stream format: "dash" | "hls" | etc.
     * id           – channel id (ignored, informational)
     * name         – display name
     * group        – category / group name
     * language     – language tag (informational)
     * logo         – logo image URL
     * user_agent   – UA string or alias (@cloudplay, @chrome, @android, @vlc)
     * mpd_url      – DASH stream URL  ← checked first
     * url          – generic stream URL
     * stream_url   – alternate stream URL key
     * hls_url      – HLS stream URL
     * link         – alternate stream URL key
     * license_url  – ClearKey / Widevine license server URL
     * license_key  – raw key material (kid:key, keyid=&key=, JSON blob, URL)
     * drm_scheme   – explicit override: "clearkey" | "widevine"
     * key_id / kid – DRM key ID hex
     * key          – DRM key hex
     * headers      – object:
     * cookie, referer/referrer, user-agent/user_agent,
     * origin, authorization, x-forwarded-for, + any custom
     * expires_in   – ignored
     */
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
                        else -> {
                            Log.e(TAG, "JSON object: no recognised array wrapper key")
                            return channels
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "JSON parse: unrecognised root token")
                    return channels
                }
            }

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseJsonChannel(obj)?.let { channels.add(it) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}", e)
        }

        Log.d(TAG, "Parsed ${channels.size} channels from JSON")
        return channels
    }

    // ── Single JSON channel object → Channel ──────────────────────────────────
    private fun parseJsonChannel(obj: JSONObject): Channel? {

        // ── 1. Stream URL (required) ───────────────────────────────────────
        val streamUrl = listOf("mpd_url", "url", "stream_url", "hls_url", "link")
            .firstNotNullOfOrNull { key ->
                obj.optString(key, "").trim().takeIf { it.isNotBlank() }
            }

        if (streamUrl.isNullOrBlank()) {
            Log.w(TAG, "JSON channel missing stream URL — skipping: ${obj.optString("name")}")
            return null
        }

        if (!isValidScheme(streamUrl)) {
            Log.w(TAG, "JSON channel unknown scheme: $streamUrl — skipping")
            return null
        }

        // ── 2. Basic metadata ──────────────────────────────────────────────
        val name = obj.optString("name", "").trim().ifBlank {
            streamUrl.substringAfterLast("/")
                .substringBefore("?")
                .substringBefore(".")
                .ifEmpty { "Unknown" }
        }

        val group = obj.optString("group", "").trim().ifBlank {
            obj.optString("category", "").trim().ifBlank { "Others" }
        }

        val logo = obj.optString("logo", "").trim().ifBlank {
            obj.optString("logo_url", "").trim().ifBlank { null }
        }

        // ── 3. User-Agent ──────────────────────────────────────────────────
        val rawUa = obj.optString("user_agent", "").trim().ifBlank {
            obj.optString("user-agent", "").trim().ifBlank { null }
        }
        val userAgent = rawUa?.let { resolveUaAlias(it) }

        // ── 4. License URL ─────────────────────────────────────────────────
        val licenseUrl = obj.optString("license_url", "").trim().ifBlank {
            obj.optString("drm_license_url", "").trim().ifBlank { null }
        }

        // ── 5. DRM scheme ──────────────────────────────────────────────────
        //
        //  Priority:
        //   a) Explicit "drm_scheme" field  →  use as-is
        //   b) "type" == "widevine"         →  widevine
        //   c) "type" == "clearkey"         →  clearkey
        //   d) "type" == "dash" + license_url present → clearkey
        //      (confirmed by screenshot: license server responds with
        //       {"keys":[{"kty":"oct",...}],"type":"temporary"} = ClearKey)
        //   e) "type" == "dash" + no license_url → no DRM
        //   f) everything else              →  null
        //
        val typeField = obj.optString("type", "").trim().lowercase()
        val drmScheme: String? = obj.optString("drm_scheme", "").trim().ifBlank { null }
            ?: when {
                typeField == "widevine"                          -> "widevine"
                typeField == "clearkey"                          -> "clearkey"
                typeField == "dash" && !licenseUrl.isNullOrBlank() -> "clearkey"
                else                                             -> null
            }

        // ── 6. Key material ────────────────────────────────────────────────
        var parsedLicenseUrl: String? = licenseUrl
        var parsedKeyId: String? = obj.optString("key_id", "").trim().ifBlank {
            obj.optString("kid", "").trim().ifBlank { null }
        }?.replace("-", "")
        var parsedKey: String? = obj.optString("key", "").trim().ifBlank { null }

        // If explicit license_url not present, try to parse license_key field
        val rawLicenseKey = obj.optString("license_key", "").trim().ifBlank { null }
        if (rawLicenseKey != null && licenseUrl == null) {
            val (lu, ki, k) = parseRawLicenseKey(rawLicenseKey)
            if (lu  != null) parsedLicenseUrl = lu
            if (ki  != null) parsedKeyId      = ki
            if (k   != null) parsedKey        = k
        }

        // ── 7. Headers object ──────────────────────────────────────────────
        var cookie: String?  = null
        var referer: String? = null
        val extraHeaders     = HashMap<String, String>()

        val headersObj = obj.optJSONObject("headers")
        if (headersObj != null) {
            val hKeys = headersObj.keys()
            while (hKeys.hasNext()) {
                val hk = hKeys.next()
                val hv = headersObj.optString(hk, "").trim()
                // Skip empty / literal "null"
                if (hv.isEmpty() || hv.equals("null", ignoreCase = true)) continue

                when (hk.lowercase().replace("_", "-")) {
                    "cookie"                -> cookie  = hv
                    "referer", "referrer"   -> referer = hv
                    // UA already parsed from top-level; headers.user-agent is secondary
                    "user-agent"            -> { if (userAgent == null) /* handled below */ Unit }
                    "origin"                -> extraHeaders["Origin"]          = hv
                    "authorization"         -> extraHeaders["Authorization"]   = hv
                    "x-forwarded-for"       -> extraHeaders["X-Forwarded-For"] = hv
                    else                    -> extraHeaders[hk] = hv
                }
            }

            // UA from headers if top-level not present
            val uaFromHeader = headersObj.optString("user-agent", "").trim().ifBlank {
                headersObj.optString("user_agent", "").trim().ifBlank { null }
            }
            val finalUa = userAgent ?: uaFromHeader?.let { resolveUaAlias(it) }

            // ── 8. Top-level cookie/referer fallback ───────────────────────
            if (cookie == null)
                cookie = obj.optString("cookie", "").trim().ifBlank { null }
            if (referer == null)
                referer = obj.optString("referer", "").trim().ifBlank {
                    obj.optString("referrer", "").trim().ifBlank { null }
                }

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

        // ── No headers object — still check top-level fallbacks ────────────
        if (cookie == null)
            cookie = obj.optString("cookie", "").trim().ifBlank { null }
        if (referer == null)
            referer = obj.optString("referer", "").trim().ifBlank {
                obj.optString("referrer", "").trim().ifBlank { null }
            }

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

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Resolve @alias → real UA string */
    private fun resolveUaAlias(raw: String): String {
        if (!raw.startsWith("@")) return raw
        return UA_ALIASES[raw.lowercase()] ?: raw
    }

    /** Common URL scheme validator */
    private fun isValidScheme(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://")  ||
               lower.startsWith("https://") ||
               lower.startsWith("rtmp://")  ||
               lower.startsWith("rtsp://")  ||
               lower.startsWith("rtp://")   ||
               lower.startsWith("udp://")
    }

    /**
     * Parse a raw license_key string into (licenseUrl, keyId, key).
     * Used by JSON parser so state fields are not mutated.
     */
    private fun parseRawLicenseKey(value: String): Triple<String?, String?, String?> {
        return when {
            // 1. JSON ClearKey blob
            value.startsWith("{") && value.contains("\"keys\"") ->
                Triple(value, null, null)

            // 2. keyid=HEX&key=HEX
            value.contains("keyid=", ignoreCase = true) &&
            value.contains("key=",   ignoreCase = true) -> {
                val mKid = KEYID_PATTERN.matcher(value)
                val mKey = KEY_PATTERN.matcher(value)
                Triple(
                    null,
                    if (mKid.find()) mKid.group(1)?.replace("-", "") else null,
                    if (mKey.find()) mKey.group(1) else null
                )
            }

            // 3. kid:key or kid=key
            KID_KEY_COLON.matcher(value).matches() -> {
                val m = KID_KEY_COLON.matcher(value)
                m.matches()
                Triple(null, m.group(1)?.replace("-", ""), m.group(2))
            }

            // 4. URL with pipe headers
            value.contains("|") && value.startsWith("http") ->
                Triple(value.substringBefore("|").trim(), null, null)

            // 5. Plain license URL
            value.startsWith("http://") || value.startsWith("https://") ->
                Triple(value, null, null)

            // 6. Unknown raw value
            else -> {
                Log.w(TAG, "Unknown license_key format: $value")
                Triple(null, null, null)
            }
        }
    }

    // ── Reset all M3U state ───────────────────────────────────────────────────
    private fun resetState() {
        currentTitle      = null
        currentLogo       = null
        currentGroup      = null
        currentLicenseKey = null
        currentKeyId      = null
        currentKey        = null
        currentUa         = null
        currentCookie     = null
        currentReferer    = null
        currentDrmScheme  = null
        currentHeaders.clear()
        insideExtInf      = false
    }
}

