package com.bluise.iptv.core

import org.json.JSONObject
import java.io.InputStream
import java.util.regex.Pattern

class IptvParser {

    fun parseM3U(input: InputStream): List<Channel> {
        val lines = input.bufferedReader().readLines()
        val channels = ArrayList<Channel>()
        
        var currentTitle: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentLicenseKey: String? = null
        var currentKeyId: String? = null
        var currentKey: String? = null
        var currentUa: String? = null
        var currentCookie: String? = null
        var currentReferer: String? = null
        var currentDrmScheme: String? = null
        
        //  NEW: Dynamic Headers store karne ke liye Map
        var currentHeaders = HashMap<String, String>()
        
        for (line in lines) {
            val trim = line.trim()
            if (trim.isEmpty()) continue

            if (trim.startsWith("#EXTINF:")) {
                currentTitle = trim.substringAfterLast(",").trim()
                
                val matcher = Pattern.compile("([a-zA-Z0-9_.-]+)=?(\"[^\"]*\"|[^\\s\"]+)").matcher(trim)
                while (matcher.find()) {
                    val keyName = matcher.group(1)?.lowercase() ?: ""
                    var value = matcher.group(2) ?: ""

                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length - 1)
                    }

                    when (keyName) {
                        "tvg-logo", "logo" -> currentLogo = value.trim()
                        "group-title" -> currentGroup = value.trim()
                        "keyid", "kid" -> currentKeyId = value
                        "key", "license_key" -> currentKey = value
                    }
                }
            }
            else if (trim.startsWith("#EXTGRP:")) {
                currentGroup = trim.substringAfter(":").trim()
            }

            else if (trim.startsWith("#EXTHTTP:")) {
                try {
                    val jsonStr = trim.removePrefix("#EXTHTTP:").trim()
                    val json = JSONObject(jsonStr)
                    if (json.has("cookie")) currentCookie = json.optString("cookie")
                    if (json.has("user-agent")) currentUa = json.optString("user-agent")
                    if (json.has("User-Agent")) currentUa = json.optString("User-Agent")
                    if (json.has("referer")) currentReferer = json.optString("referer")
                } catch (e: Exception) { }
            }

            else if (trim.startsWith("#KODIPROP:")) {
                val prop = trim.removePrefix("#KODIPROP:").trim()

                if (prop.startsWith("inputstream.adaptive.license_type=")) {
                    currentDrmScheme = prop.substringAfter("=").trim()
                }
                else if (prop.startsWith("inputstream.adaptive.license_key=")) {
                    val valKey = prop.removePrefix("inputstream.adaptive.license_key=").trim()
                    if (valKey.contains("keyid=") && valKey.contains("key=")) {
                        val matcherKid = Pattern.compile("keyid=([a-fA-F0-9]+)").matcher(valKey)
                        if (matcherKid.find()) currentKeyId = matcherKid.group(1)
                        val matcherKey = Pattern.compile("key=([a-fA-F0-9]+)").matcher(valKey)
                        if (matcherKey.find()) currentKey = matcherKey.group(1)
                    }
                    else if (valKey.contains(":") && !valKey.startsWith("http")) {
                        val parts = valKey.split(":")
                        if (parts.size >= 2) {
                            currentKeyId = parts[0].trim()
                            currentKey = parts[1].trim()
                        }
                    }
                    else if (valKey.startsWith("http")) {
                        currentLicenseKey = valKey
                    }
                }
                else if (prop.startsWith("http-user-agent=")) {
                    currentUa = prop.removePrefix("http-user-agent=").trim()
                }
            }

            else if (trim.startsWith("#EXTVLCOPT:")) {
                if (trim.contains("http-user-agent=")) {
                    currentUa = trim.substringAfter("=").trim()
                }
                if (trim.contains("http-referrer=")) {
                    currentReferer = trim.substringAfter("=").trim()
                }
                if (trim.contains("http-cookie=")) {
                    currentCookie = trim.substringAfter("=").trim()
                }
            }

            else if (!trim.startsWith("#")) {
                var finalUrl = trim

                if (trim.contains("|")) {
                    val parts = trim.split("|")
                    finalUrl = parts[0].trim()
                    
                    if (parts.size > 1) {
                        val params = parts[1].split("&")
                        for (p in params) {
                            //  NEW: Har Header ko dynamically split karo
                            val keyVal = p.split("=", limit = 2)
                            if (keyVal.size == 2) {
                                val headerKey = keyVal[0].trim()
                                val headerValue = keyVal[1].trim()

                                // Purane known headers
                                when {
                                    headerKey.equals("User-Agent", true) -> currentUa = headerValue
                                    headerKey.equals("Referer", true) -> currentReferer = headerValue
                                    headerKey.equals("Cookie", true) -> currentCookie = headerValue
                                }
                                
                                //  ALL HEADERS (Origin, Auth Token, etc.) Map me save karo
                                currentHeaders[headerKey] = headerValue
                            }
                        }
                    }
                }

                val channel = Channel(
                    name = currentTitle ?: "Unknown Channel",
                    url = finalUrl,
                    logoUrl = currentLogo,
                    group = if (currentGroup.isNullOrEmpty()) "Others" else currentGroup!!,
                    userAgent = currentUa,
                    cookie = currentCookie,
                    referer = currentReferer,
                    drmLicenseUrl = currentLicenseKey,
                    drmKeyId = currentKeyId,
                    drmKey = currentKey,
                    drmScheme = currentDrmScheme,
                    
                    //  Pass captured dynamic headers to Channel
                    customHeaders = if (currentHeaders.isEmpty()) null else HashMap(currentHeaders)
                )

                channels.add(channel)

                // Reset all variables
                currentTitle = null; currentLogo = null; currentGroup = null
                currentLicenseKey = null; currentKeyId = null; currentKey = null
                currentUa = null; currentCookie = null; currentReferer = null
                currentDrmScheme = null 
                currentHeaders.clear() // Map clear for next channel
            }
        }

        return channels
    }
}
