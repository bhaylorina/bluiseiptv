package com.bluise.iptv.core

data class Channel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    
    //  Group Name (Default "Others")
    val group: String = "Others", 

    val userAgent: String? = null,
    val cookie: String? = null,
    val referer: String? = null,
    val drmLicenseUrl: String? = null,
    val drmKeyId: String? = null,
    val drmKey: String? = null,
    val drmScheme: String? = null, 
    var isFavorite: Boolean = false,
    
    //  NEW: Naye extra headers (Origin, Token, etc.) store karne ke liye
    val customHeaders: Map<String, String>? = null 
) {
    fun getHeadersMap(): Map<String, String> {
        val headers = HashMap<String, String>()
        if (!userAgent.isNullOrEmpty()) headers["User-Agent"] = userAgent
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie
        if (!referer.isNullOrEmpty()) headers["Referer"] = referer
        
        //  NEW: Agar Origin ya koi aur naya header hai, toh usko bhi List me jod do
        customHeaders?.forEach { (key, value) ->
            if (!headers.containsKey(key)) {
                headers[key] = value
            }
        }
        
        return headers
    }
}
