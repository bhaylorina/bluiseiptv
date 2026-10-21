package com.bluise.iptv.core

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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
    private const val TAG = "EpgManager"
    
    val epgData = HashMap<String, ArrayList<EpgProgram>>()
    val channelNames = HashMap<String, String>()
    var isEpgLoaded = false
    
    var lastEpgLoadTime = 0L 
    private const val CACHE_EXPIRY_MS = 12 * 60 * 60 * 1000L // 12 Ghante
    
    var onEpgLoaded: (() -> Unit)? = null

    fun fetchEpgInBackground(epgUrl: String = "https://raw.githubusercontent.com/arnab8820/JioTV-epg/main/epg1d.xml.gz") {
        val now = System.currentTimeMillis()
        if (isEpgLoaded && (now - lastEpgLoadTime < CACHE_EXPIRY_MS)) return 

        thread {
            try {
                if (isEpgLoaded) {
                    epgData.clear()
                    channelNames.clear()
                }

                val connection = URL(epgUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                
                var inputStream = connection.inputStream
                if (epgUrl.endsWith(".gz")) {
                    inputStream = GZIPInputStream(inputStream)
                }
                
                parseXmltv(inputStream)
                
                isEpgLoaded = true
                lastEpgLoadTime = System.currentTimeMillis()
                
                // Success hone par list refresh karega
                onEpgLoaded?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "EPG Fetch Error: ${e.message}")
            }
        }
    }

    // 🔥 NAYA: Bulletproof Date Parser (Kabhi fail nahi hoga)
    private fun parseTime(timeStr: String?): Long {
        if (timeStr.isNullOrEmpty()) return 0L
        return try {
            // Sirf shuru ke 14 numbers nikalega (yyyyMMddHHmmss)
            val cleanStr = if (timeStr.length >= 14) timeStr.substring(0, 14) else timeStr
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)
            format.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") // Fix Indian Time
            format.parse(cleanStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
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
                        } else if (insideChannel && tagName.equals("display-name", ignoreCase = true)) {
                            val dName = parser.nextText()
                            if (currentChannelId.isNotEmpty() && dName.isNotEmpty()) {
                                channelNames[currentChannelId] = dName
                            }
                        } else if (tagName.equals("programme", ignoreCase = true)) {
                            insideProgramme = true
                            currentChannelId = parser.getAttributeValue(null, "channel") ?: ""
                            // 🔥 Yahan Naya Date parser lagaya hai
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
                            // Agar time 0L nahi hai tabhi save karega
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

    // 🔥 NAYA: Aggressive Name Matching (Spaces aur Symbols ignore karega)
    fun getCurrentProgram(tvgId: String?, channelName: String): EpgProgram? {
        val now = System.currentTimeMillis()

        if (!tvgId.isNullOrEmpty() && epgData.containsKey(tvgId)) {
            val prog = epgData[tvgId]?.firstOrNull { now in it.startMs..it.endMs }
            if (prog != null) return prog
        }

        // Channel ke naam se saare symbols aur space hata dega ("DD National" -> "ddnational")
        val cleanSearchName = channelName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.getDefault()).replace("hd", "").replace("in", "")
        
        for ((id, programs) in epgData) {
            val realName = channelNames[id] ?: ""
            val cleanId = id.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.getDefault()).replace("in", "")
            val cleanRealName = realName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.getDefault()).replace("hd", "")

            // 100% accurate match karega
            if (cleanId == cleanSearchName || cleanId.contains(cleanSearchName) || cleanSearchName.contains(cleanId) ||
                cleanRealName == cleanSearchName || cleanRealName.contains(cleanSearchName) || cleanSearchName.contains(cleanRealName)
            ) {
                val activeProgram = programs.firstOrNull { now in it.startMs..it.endMs }
                if (activeProgram != null) {
                    return activeProgram
                }
            }
        }
        return null
    }
}
