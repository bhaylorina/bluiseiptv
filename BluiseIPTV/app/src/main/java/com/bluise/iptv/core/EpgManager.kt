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
    private const val TAG = "EpgManagerDebug" 
    
    val epgData = HashMap<String, ArrayList<EpgProgram>>()
    val channelNames = HashMap<String, String>()
    var isEpgLoaded = false
    
    var lastEpgLoadTime = 0L 
    private const val CACHE_EXPIRY_MS = 12 * 60 * 60 * 1000L 
    
    var onEpgLoaded: (() -> Unit)? = null

    fun fetchEpgInBackground(epgUrl: String = "https://epgshare01.online/epgshare01/epg_ripper_ALL_SOURCES1.xml.gz") {
        val now = System.currentTimeMillis()
        if (isEpgLoaded && (now - lastEpgLoadTime < CACHE_EXPIRY_MS)) {
            Log.d(TAG, "Cache valid, download skipped.")
            return 
        }

        thread {
            try {
                Log.d(TAG, "STEP 1: Downloading EPG from $epgUrl")
                
                val request = Request.Builder().url(epgUrl).build()
                val response = PlayerEngine.okHttpClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download Failed. HTTP Code: ${response.code}")
                    return@thread
                }

                Log.d(TAG, "STEP 2: Download Success! Extracting file...")
                var inputStream = response.body?.byteStream() ?: return@thread
                
                val contentType = response.header("Content-Type", "") ?: ""
                val isGzip = epgUrl.endsWith(".gz") || response.request.url.toString().endsWith(".gz") || contentType.contains("gzip")
                
                if (isGzip) {
                    inputStream = GZIPInputStream(inputStream)
                }
                
                if (isEpgLoaded) {
                    epgData.clear()
                    channelNames.clear()
                }
                
                Log.d(TAG, "STEP 3: Parsing XML Data (Yeh 2-3 sec lega)...")
                parseXmltv(inputStream)
                
                isEpgLoaded = true
                lastEpgLoadTime = System.currentTimeMillis()
                
                Log.d(TAG, "STEP 4: Done! Total Channels: ${channelNames.size}, Programs: ${epgData.size}")
                onEpgLoaded?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "EPG Fetch Error: ${e.message}")
            }
        }
    }

    private fun parseTime(timeStr: String?): Long {
        if (timeStr.isNullOrEmpty()) return 0L
        return try {
            val cleanStr = if (timeStr.length >= 14) timeStr.substring(0, 14) else timeStr
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH)
            format.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
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

    fun getCurrentProgram(tvgId: String?, channelName: String): EpgProgram? {
        val now = System.currentTimeMillis()

        // 1. Pehle match karne ki koshish karega ID se
        var matchedPrograms: ArrayList<EpgProgram>? = null
        if (!tvgId.isNullOrEmpty() && epgData.containsKey(tvgId)) {
            matchedPrograms = epgData[tvgId]
        }

        // 2. Agar ID nahi mili, toh Naam se match karega
        if (matchedPrograms == null) {
            val cleanSearchName = channelName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.getDefault()).replace("hd", "").replace("in", "")
            for ((id, programs) in epgData) {
                val realName = channelNames[id] ?: ""
                val cleanId = id.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.getDefault()).replace("in", "")
                val cleanRealName = realName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.getDefault()).replace("hd", "")

                if (cleanId == cleanSearchName || cleanId.contains(cleanSearchName) || cleanSearchName.contains(cleanId) ||
                    cleanRealName == cleanSearchName || cleanRealName.contains(cleanSearchName) || cleanSearchName.contains(cleanRealName)
                ) {
                    matchedPrograms = programs
                    break
                }
            }
        }

        // 3. Agar Channel mil gaya, toh usme se best show nikalenge!
        if (matchedPrograms != null && matchedPrograms.isNotEmpty()) {
            
            // A. Pehle exact time match karo (Real Live TV)
            var activeProgram = matchedPrograms.firstOrNull { now in it.startMs..it.endMs }
            
            // B. Agar Time thoda aage-peeche hai (EPG outdated hai), toh Next aane wala show utha lo
            if (activeProgram == null) {
                activeProgram = matchedPrograms.firstOrNull { it.endMs > now }
            }
            
            // C. Agar EPG bohot hi purana hai, toh list ka aakhiri show utha kar dikha do
            if (activeProgram == null) {
                activeProgram = matchedPrograms.lastOrNull()
            }
            
            Log.d(TAG, "✅ Success! Showing EPG for '$channelName' -> ${activeProgram?.title}")
            return activeProgram
        }

        Log.d(TAG, "❌ Completely Failed to match name: '$channelName'")
        return null
    }
}
