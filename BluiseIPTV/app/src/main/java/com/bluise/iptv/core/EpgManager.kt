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
    val channelNames = HashMap<String, String>() // 🔥 NAYA: XML se asli naam nikalne ke liye
    var isEpgLoaded = false
    
    var lastEpgLoadTime = 0L 
    private const val CACHE_EXPIRY_MS = 12 * 60 * 60 * 1000L // 12 Ghante 
    
    var onEpgLoaded: (() -> Unit)? = null

    // 🔥 NAYA: JioTV aur Indian Channels ka fastest daily updating server
    fun fetchEpgInBackground(epgUrl: String = "https://raw.githubusercontent.com/arnab8820/JioTV-epg/main/epg1d.xml.gz") {
        val now = System.currentTimeMillis()
        if (isEpgLoaded && (now - lastEpgLoadTime < CACHE_EXPIRY_MS)) {
            return 
        }

        thread {
            try {
                if (isEpgLoaded) {
                    epgData.clear()
                    channelNames.clear()
                }

                val connection = URL(epgUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                
                // 🔥 NAYA: .gz (ZIP) file support add kiya
                var inputStream = connection.inputStream
                if (epgUrl.endsWith(".gz")) {
                    inputStream = GZIPInputStream(inputStream)
                }
                
                parseXmltv(inputStream)
                
                isEpgLoaded = true
                lastEpgLoadTime = System.currentTimeMillis()
                
                onEpgLoaded?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "EPG Fetch Error: ${e.message}")
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
            val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ENGLISH)
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
                            // 🔥 NAYA: Channel ID ke sath uska asli naam bhi save karega
                            val dName = parser.nextText()
                            if (currentChannelId.isNotEmpty() && dName.isNotEmpty()) {
                                channelNames[currentChannelId] = dName
                            }
                        } else if (tagName.equals("programme", ignoreCase = true)) {
                            insideProgramme = true
                            currentChannelId = parser.getAttributeValue(null, "channel") ?: ""
                            val startStr = parser.getAttributeValue(null, "start")
                            val stopStr = parser.getAttributeValue(null, "stop")
                            try {
                                startMs = dateFormat.parse(startStr)?.time ?: 0L
                                endMs = dateFormat.parse(stopStr)?.time ?: 0L
                            } catch (e: Exception) { }
                            currentTitle = ""
                        } else if (insideProgramme && tagName.equals("title", ignoreCase = true)) {
                            currentTitle = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("channel", ignoreCase = true)) {
                            insideChannel = false
                        } else if (tagName.equals("programme", ignoreCase = true)) {
                            if (currentChannelId.isNotEmpty() && currentTitle.isNotEmpty()) {
                                val program = EpgProgram(currentTitle, startMs, endMs)
                                if (!epgData.containsKey(currentChannelId)) {
                                    epgData[currentChannelId] = ArrayList()
                                }
                                epgData[currentChannelId]?.add(program)
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

    // 🔥 Advance Smart Name Matching (OTT Navigator jaisa)
    fun getCurrentProgram(tvgId: String?, channelName: String): EpgProgram? {
        val now = System.currentTimeMillis()

        if (!tvgId.isNullOrEmpty() && epgData.containsKey(tvgId)) {
            return epgData[tvgId]?.firstOrNull { now in it.startMs..it.endMs }
        }

        val cleanSearchName = channelName.replace(" ", "").replace("HD", "").replace("IN", "").lowercase(Locale.getDefault())
        
        for ((id, programs) in epgData) {
            val cleanId = id.replace(" ", "").replace(".in", "").lowercase(Locale.getDefault())
            val realName = channelNames[id] ?: ""
            val cleanRealName = realName.replace(" ", "").replace("HD", "").replace("IN", "").lowercase(Locale.getDefault())

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
