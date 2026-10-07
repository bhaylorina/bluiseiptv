package com.bluise.iptv.core

data class Channel(
    val name: String,
    val url: String,
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
    fun getHeadersMap(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()

        // customHeaders pehle — dedicated fields baad mein override karenge
        customHeaders?.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                headers[key.trim()] = value.trim()
            }
        }

        // Dedicated fields highest priority
        if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent.trim()
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie.trim()
        if (!referer.isNullOrBlank()) headers["Referer"] = referer.trim()

        return headers
    }
}
