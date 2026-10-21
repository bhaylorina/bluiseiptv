package com.bluise.iptv.core

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

data class EpgProgram(
    val title: String,
    val startMs: Long,
    val endMs: Long
)

object EpgManager {
    private const val TAG = "EpgManager"
    
    val epgData = HashMap<String, ArrayList<EpgProgram>>()
    var isEpgLoaded = false
    var onEpgLoaded: (() -> Unit)? = null

    fun fetchEpgInBackground(epgUrl: String = "https://iptv-org.github.io/epg/guides/in/tvguide.in.epg.xml") {
        if (isEpgLoaded) return // Sirf ek baar load hoga

        thread {
            try {
                val connection = URL(epgUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                val inputStream = connection.inputStream
                parseXmltv(inputStream)
                isEpgLoaded = true
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

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("programme", ignoreCase = true)) {
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
                        if (tagName.equals("programme", ignoreCase = true)) {
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
        } catch (e: Exception) { }
    }

    // 🔥 OTT Navigator style Smart Matching!
    fun getCurrentProgram(tvgId: String?, channelName: String): EpgProgram? {
        val now = System.currentTimeMillis()

        // 1. Agar tvg-id hai toh direct match karo
        if (!tvgId.isNullOrEmpty() && epgData.containsKey(tvgId)) {
            return epgData[tvgId]?.firstOrNull { now in it.startMs..it.endMs }
        }

        // 2. Agar tvg-id nahi hai, toh channel ke naam se match karo!
        val cleanName = channelName.replace(" ", "").replace("HD", "").lowercase(Locale.getDefault())
        
        for ((key, programs) in epgData) {
            // EPG ki IDs ko clean karke compare karo (e.g. SonyMax.in -> sonymax)
            val cleanKey = key.replace(" ", "").replace(".in", "").lowercase(Locale.getDefault())
            
            if (cleanKey == cleanName || cleanKey.contains(cleanName) || cleanName.contains(cleanKey)) {
                val activeProgram = programs.firstOrNull { now in it.startMs..it.endMs }
                if (activeProgram != null) {
                    return activeProgram
                }
            }
        }
        return null
    }
}
