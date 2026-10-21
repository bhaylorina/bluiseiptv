package com.bluise.iptv.core

data class Channel(
    val name: String,
    val url: String,
    val tvgId: String? = null, // 🔥 NAYA ADD KIYA (EPG MATCH KARNE KE LIYE)
    val logoUrl: String? = null,
    val group: String = "Others",
    val userAgent: String? = null,
    val cookie: String? = null,
    val referer: String? = null,
    val drmLicenseUrl: String? = null,
    val drmKeyId: String? = null,
    val drmKey: String? = null,
    val drmScheme: String? = null,
    var isFavorite: Boolean = false,
    val customHeaders: HashMap<String, String>? = null
) {
    // Niche ka getHeadersMap() function same rahega...
    fun getHeadersMap(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()

        customHeaders?.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                headers[key.trim()] = value.trim()
            }
        }

        if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent.trim()
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie.trim()
        if (!referer.isNullOrBlank()) headers["Referer"] = referer.trim()

        return headers
    }
}
