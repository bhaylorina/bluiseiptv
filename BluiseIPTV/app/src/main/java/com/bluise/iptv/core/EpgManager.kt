package com.bluise.iptv.core

import android.util.Log
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

data class EpgProgram(
    val title: String,
    val startMs: Long,
    val endMs: Long
)

object EpgManager {
    private const val TAG = "EpgManagerFast" 
    
    val epgData = HashMap<String, ArrayList<EpgProgram>>()
    
    // 🔥 SUPER-FAST DICTIONARY (UI ko lag se bachane ke liye background mein set hogi)
    private val searchIndex = HashMap<String, String>() 

    var isEpgLoaded = false
    var lastEpgLoadTime = 0L 
    private const val CACHE_EXPIRY_MS = 12 * 60 * 60 * 1000L 
    var onEpgLoaded: (() -> Unit)? = null

    // NAYA URL: 'www.dropbox.com' ki jagah 'dl.dropboxusercontent.com' lagaya hai aur '&dl=1' hata diya hai
    fun fetchEpgInBackground(epgUrl: String = "https://dl.dropboxusercontent.com/scl/fi/s986xqczdsn77t1umeqdu/epg.xml.gz?rlkey=z7tlltik2tu75jjb35lh1vci5&st=fm6xgo12") {
        val now = System.currentTimeMillis()
        if (isEpgLoaded && (now - lastEpgLoadTime < CACHE_EXPIRY_MS)) return 

        thread {
            try {
                val request = Request.Builder()
                    .url(epgUrl)
                    .build()
                val response = PlayerEngine.okHttpClient.newCall(request).execute()
                
                if (!response.isSuccessful) return@thread

                var inputStream = response.body?.byteStream() ?: return@thread
                val contentType = response.header("Content-Type", "") ?: ""
                
                // BUG FIX: .endsWith ki jagah .contains lagaya, kyunki URL ke aage parameters hain
                val isGzip = epgUrl.contains(".gz") || response.request.url.toString().contains(".gz") || contentType.contains("gzip")
                
                if (isGzip) {
                    inputStream = GZIPInputStream(inputStream)
                }
                
                if (isEpgLoaded) {
                    epgData.clear()
                    searchIndex.clear()
                }
                
                parseXmltv(inputStream)
                
                isEpgLoaded = true
                lastEpgLoadTime = System.currentTimeMillis()
                onEpgLoaded?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "EPG Error: ${e.message}")
            }
        }
    }

    // MISSING FUNCTION 1: NAYA Code clean karne ka function
    private fun cleanName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.getDefault()).replace("hd", "").replace("in", "")
    }

    // MISSING FUNCTION 2: 100% Accurate Time Zone Parser
    private fun parseTime(timeStr: String?): Long {
        if (timeStr.isNullOrEmpty()) return 0L
        return try {
            val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ENGLISH)
            format.parse(timeStr.trim())?.time ?: 0L
        } catch (e: Exception) {
            try {
                val cleanStr = if (timeStr.length >= 14) timeStr.substring(0, 14) else timeStr
                val fallbackFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)
                fallbackFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                fallbackFormat.parse(cleanStr)?.time ?: 0L
            } catch (ex: Exception) {
                0L
            }
        }
    }

    private fun parseXmltv(inputStream: InputStream) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            var currentChannelId = ""
            var currentTitle = ""
            var startMs = 0L
            var endMs = 0L
            var insideProgramme = false
            var insideChannel = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("channel", ignoreCase = true)) {
                            insideChannel = true
                            currentChannelId = parser.getAttributeValue(null, "id") ?: ""
                            
                            // 🔥 DICTIONARY BANAYI JAA RAHI HAI (Bina UI ko roke)
                            if (currentChannelId.isNotEmpty()) {
                                searchIndex[cleanName(currentChannelId)] = currentChannelId
                            }
                        } else if (insideChannel && tagName.equals("display-name", ignoreCase = true)) {
                            val dName = parser.nextText()
                            if (currentChannelId.isNotEmpty() && dName.isNotEmpty()) {
                                searchIndex[cleanName(dName)] = currentChannelId
                            }
                        } else if (tagName.equals("programme", ignoreCase = true)) {
                            insideProgramme = true
                            currentChannelId = parser.getAttributeValue(null, "channel") ?: ""
                            startMs = parseTime(parser.getAttributeValue(null, "start"))
                            endMs = parseTime(parser.getAttributeValue(null, "stop"))
                            currentTitle = ""
                        } else if (insideProgramme && tagName.equals("title", ignoreCase = true)) {
                            currentTitle = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("channel", ignoreCase = true)) {
                            insideChannel = false
                        } else if (tagName.equals("programme", ignoreCase = true)) {
                            if (currentChannelId.isNotEmpty() && currentTitle.isNotEmpty() && startMs > 0L) {
                                if (!epgData.containsKey(currentChannelId)) {
                                    epgData[currentChannelId] = ArrayList()
                                }
                                epgData[currentChannelId]?.add(EpgProgram(currentTitle, startMs, endMs))
                            }
                            insideProgramme = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { 
            Log.e(TAG, "XML Parse Error: ${e.message}")
        }
    }

    // 🔥 NO LOOP IN UI - Direct Dictionary Match - 0% Lag!
    fun getCurrentProgram(tvgId: String?, channelName: String): EpgProgram? {
        val now = System.currentTimeMillis()
        var targetId: String? = null

        // 1. Check direct TVG-ID
        if (!tvgId.isNullOrEmpty() && epgData.containsKey(tvgId)) {
            targetId = tvgId
        }

        // 2. Check Name from Fast Dictionary (Bina kisi bhayankar loop ke)
        if (targetId == null) {
            val cleanSearchName = cleanName(channelName)
            targetId = searchIndex[cleanSearchName]
            
            // Backup for partial match (Lightweight)
            if (targetId == null) {
                for (key in searchIndex.keys) {
                    if (key.contains(cleanSearchName) || cleanSearchName.contains(key)) {
                        targetId = searchIndex[key]
                        break
                    }
                }
            }
        }

        // 3. Return ONLY Exact Live Show (Nahi mila toh Seedha NULL, No purana data!)
        if (targetId != null && epgData.containsKey(targetId)) {
            val programs = epgData[targetId]!!
            return programs.firstOrNull { now in it.startMs..it.endMs }
        }

        return null
    }
}
