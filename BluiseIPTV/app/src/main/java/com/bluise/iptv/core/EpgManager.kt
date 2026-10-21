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

// EPG ka ek program (Show) save karne ka format
data class EpgProgram(
    val title: String,
    val startMs: Long,
    val endMs: Long
)

object EpgManager {
    private const val TAG = "EpgManager"
    
    // Yahan hum tvg-id ke hisaab se saare programs save karenge
    val epgData = HashMap<String, ArrayList<EpgProgram>>()
    var isEpgLoaded = false

    // Jab EPG download ho jayega, toh UI ko refresh karne ke liye listener
    var onEpgLoaded: (() -> Unit)? = null

    // Background thread mein EPG download karega taaki TV hang na ho
    fun fetchEpgInBackground(epgUrl: String = "https://iptv-org.github.io/epg/guides/in/tvguide.in.epg.xml") {
        if (isEpgLoaded) return

        thread {
            try {
                Log.d(TAG, "Starting EPG Download: $epgUrl")
                val connection = URL(epgUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                
                val inputStream = connection.inputStream
                parseXmltv(inputStream)
                
                isEpgLoaded = true
                Log.d(TAG, "EPG Loading Complete! Channels found: ${epgData.size}")
                
                // Download hone ke baad MainActivity ko update bhejega
                onEpgLoaded?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "EPG Fetch Error: ${e.message}")
            }
        }
    }

    // Fast XML Parser jo RAM bacha kar EPG read karega
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
            
            // XMLTV mein time is format mein hota hai: 20261021203000 +0530
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
        } catch (e: Exception) {
            Log.e(TAG, "XML Parse Error: ${e.message}")
        }
    }

    // Jo time abhi chal raha hai, uska program return karega
    fun getCurrentProgram(tvgId: String?): EpgProgram? {
        if (tvgId.isNullOrEmpty() || !epgData.containsKey(tvgId)) return null
        
        val now = System.currentTimeMillis()
        val programs = epgData[tvgId] ?: return null
        
        for (program in programs) {
            if (now in program.startMs..program.endMs) {
                return program
            }
        }
        return null
    }
}
