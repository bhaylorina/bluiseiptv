package com.bluise.iptv.core

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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

    // Fast dictionary: cleaned channel name -> EPG channel id
    private val searchIndex = HashMap<String, String>()

    // cleaned name -> resolved channel id (null = no match). Avoids repeated partial-match loops.
    private val resolveCache = HashMap<String, String?>()

    // Background thread likhta hai, UI thread padhta hai -> dono is lock se guard hain
    private val lock = Any()

    @Volatile var isEpgLoaded = false
    @Volatile private var isLoading = false
    var lastEpgLoadTime = 0L
    private const val CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L

    // Jo show 2 ghante pehle khatam ho chuka hai usko memory mein rakhne ka faayda nahi
    private const val KEEP_PAST_MS = 2 * 60 * 60 * 1000L

    // 48 ghante se aage ke shows ki zaroorat nahi (current + next hi dikhte hain) -> kam objects, kam memory/GC
    private const val KEEP_FUTURE_MS = 48 * 60 * 60 * 1000L

    // Loading ke dauran itne ms mein ek baar UI ko refresh bolenge
    private const val PROGRESS_NOTIFY_MS = 5000L

    // Parsed EPG ka compact binary cache (cacheDir me). Isse har launch pe 28MB XML parse nahi karna padta.
    private const val CACHE_FILE = "epg_cache_v1.bin"
    private const val CACHE_MAGIC = 0x45504731 // "EPG1"
    private const val MAX_COUNT = 5_000_000

    private const val DEFAULT_EPG_URL =
        "https://dl.dropboxusercontent.com/scl/fi/s986xqczdsn77t1umeqdu/epg.xml.gz?rlkey=z7tlltik2tu75jjb35lh1vci5&st=fm6xgo12"

    var onEpgLoaded: (() -> Unit)? = null

    fun fetchEpgInBackground(context: Context, epgUrl: String = DEFAULT_EPG_URL) {
        val now = System.currentTimeMillis()
        if (isEpgLoaded && (now - lastEpgLoadTime < CACHE_EXPIRY_MS)) return
        if (isLoading) return // pehle se load chal raha hai, dobara thread mat banao
        isLoading = true

        val cacheFile = File(context.applicationContext.cacheDir, CACHE_FILE)

        thread(name = "EpgLoader") {
            // Background priority: UI/video ko CPU milega, phone kam garam
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val t0 = System.currentTimeMillis()
            try {
                // 1. Disk cache: turant EPG dikhao. 12 ghante se purana nahi hai toh network/parse skip.
                if (!isEpgLoaded && loadFromDisk(cacheFile)) {
                    Log.d(TAG, "EPG disk cache loaded in ${System.currentTimeMillis() - t0} ms")
                    if (System.currentTimeMillis() - lastEpgLoadTime < CACHE_EXPIRY_MS) return@thread
                }

                // 2. Cache nahi hai ya purana hai -> download + parse
                val request = Request.Builder().url(epgUrl).build()

                PlayerEngine.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "EPG HTTP error: ${response.code}")
                        return@use
                    }
                    val body = response.body ?: return@use

                    val contentType = response.header("Content-Type", "") ?: ""
                    val isGzip = epgUrl.contains(".gz") ||
                            response.request.url.toString().contains(".gz") ||
                            contentType.contains("gzip")

                    // 64KB buffer: default 512 bytes slow TV boxes pe bahut dheema padta hai
                    val stream: InputStream = if (isGzip) {
                        GZIPInputStream(body.byteStream(), 64 * 1024)
                    } else {
                        BufferedInputStream(body.byteStream(), 64 * 1024)
                    }

                    val firstLoad = !isEpgLoaded

                    // Pehli baar: seedha live maps mein bharo (UI ko progressively dikhega)
                    // Refresh: alag maps mein bharo, end mein swap (beech mein EPG blank nahi hoga)
                    val targetEpg = if (firstLoad) epgData else HashMap<String, ArrayList<EpgProgram>>()
                    val targetIndex = if (firstLoad) searchIndex else HashMap<String, String>()

                    if (firstLoad) {
                        synchronized(lock) {
                            epgData.clear()
                            searchIndex.clear()
                            resolveCache.clear()
                        }
                    }

                    val kept = parseXmltv(stream, targetEpg, targetIndex, firstLoad)

                    synchronized(lock) {
                        if (!firstLoad) {
                            epgData.clear()
                            epgData.putAll(targetEpg)
                            searchIndex.clear()
                            searchIndex.putAll(targetIndex)
                        }
                        resolveCache.clear()
                    }

                    isEpgLoaded = true
                    lastEpgLoadTime = System.currentTimeMillis()
                    Log.d(TAG, "EPG loaded: $kept programmes in ${lastEpgLoadTime - t0} ms (firstLoad=$firstLoad)")
                    onEpgLoaded?.invoke()

                    // Next launch ke liye disk pe save (parse ke baad maps me koi nahi likhta, isliye lock ki zaroorat nahi)
                    saveToDisk(cacheFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "EPG Error: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    // true = cache se data load ho gaya
    private fun loadFromDisk(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
                if (input.readInt() != CACHE_MAGIC) throw IOException("bad magic")
                val savedAt = input.readLong()
                val minEnd = System.currentTimeMillis() - KEEP_PAST_MS

                val idxCount = input.readInt()
                if (idxCount < 0 || idxCount > MAX_COUNT) throw IOException("bad index count")
                val idx = HashMap<String, String>(idxCount * 2)
                repeat(idxCount) {
                    val key = input.readUTF()
                    val id = input.readUTF()
                    idx[key] = id
                }

                val chCount = input.readInt()
                if (chCount < 0 || chCount > MAX_COUNT) throw IOException("bad channel count")
                val epg = HashMap<String, ArrayList<EpgProgram>>(chCount * 2)
                repeat(chCount) {
                    val id = input.readUTF()
                    val n = input.readInt()
                    if (n < 0 || n > MAX_COUNT) throw IOException("bad programme count")
                    val list = ArrayList<EpgProgram>(n)
                    repeat(n) {
                        val title = input.readUTF()
                        val s = input.readLong()
                        val e = input.readLong()
                        if (e >= minEnd) list.add(EpgProgram(title, s, e)) // purane shows chhod do
                    }
                    if (list.isNotEmpty()) epg[id] = list
                }

                synchronized(lock) {
                    epgData.clear()
                    epgData.putAll(epg)
                    searchIndex.clear()
                    searchIndex.putAll(idx)
                    resolveCache.clear()
                }
                lastEpgLoadTime = savedAt
                isEpgLoaded = true
            }
            onEpgLoaded?.invoke()
            true
        } catch (e: Exception) {
            Log.e(TAG, "EPG cache read failed: ${e.message}")
            file.delete()
            false
        }
    }

    private fun saveToDisk(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeLong(lastEpgLoadTime)

                out.writeInt(searchIndex.size)
                for ((key, id) in searchIndex) {
                    out.writeUTF(key)
                    out.writeUTF(id)
                }

                out.writeInt(epgData.size)
                for ((id, list) in epgData) {
                    out.writeUTF(id)
                    out.writeInt(list.size)
                    for (p in list) {
                        out.writeUTF(p.title.take(300))
                        out.writeLong(p.startMs)
                        out.writeLong(p.endMs)
                    }
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "EPG cache write failed: ${e.message}")
            tmp.delete()
        }
    }

    private fun cleanName(name: String): String {
        // Regex ke bina (har getView pe allocation bachti hai). Sirf a-z, 0-9 rakhta hai, lowercase me.
        val sb = StringBuilder(name.length)
        for (c in name) {
            if ((c in 'a'..'z') || (c in '0'..'9')) sb.append(c)
            else if (c in 'A'..'Z') sb.append(c + 32)
        }
        return sb.toString()
    }

    // ---- FAST TIME PARSER (SimpleDateFormat ke bina) ----
    // Format: "20261023183000 +0530". Offset na ho toh IST maana jayega (pehle jaisa hi).
    private fun parseTime(s: String?): Long {
        if (s == null || s.length < 14) return 0L
        return try {
            val year = num(s, 0, 4)
            val month = num(s, 4, 2)
            val day = num(s, 6, 2)
            val hour = num(s, 8, 2)
            val minute = num(s, 10, 2)
            val second = num(s, 12, 2)

            var offsetMin = 330 // IST default
            var i = 14
            while (i < s.length && s[i] == ' ') i++
            if (i + 5 <= s.length && (s[i] == '+' || s[i] == '-')) {
                val sign = if (s[i] == '-') -1 else 1
                offsetMin = sign * (num(s, i + 1, 2) * 60 + num(s, i + 3, 2))
            }

            val days = daysFromCivil(year, month, day)
            ((((days * 24 + hour) * 60 + minute) * 60 + second) - offsetMin * 60L) * 1000L
        } catch (e: Exception) {
            0L
        }
    }

    private fun num(s: String, start: Int, len: Int): Int {
        var r = 0
        for (i in start until start + len) {
            val d = s[i] - '0'
            if (d < 0 || d > 9) throw NumberFormatException()
            r = r * 10 + d
        }
        return r
    }

    // Days since 1970-01-01 (civil date -> epoch days)
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    // Returns number of programmes kept
    private fun parseXmltv(
        inputStream: InputStream,
        targetEpg: HashMap<String, ArrayList<EpgProgram>>,
        targetIndex: HashMap<String, String>,
        notifyProgress: Boolean
    ): Int {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        val minEnd = System.currentTimeMillis() - KEEP_PAST_MS
        val maxStart = System.currentTimeMillis() + KEEP_FUTURE_MS
        var lastNotify = System.currentTimeMillis()
        var kept = 0

        var eventType = parser.eventType
        var currentChannelId = ""
        var currentTitle = ""
        var startMs = 0L
        var endMs = 0L
        var insideProgramme = false
        var insideChannel = false
        var skipProgramme = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (tagName.equals("channel", ignoreCase = true)) {
                        insideChannel = true
                        currentChannelId = parser.getAttributeValue(null, "id") ?: ""

                        if (currentChannelId.isNotEmpty()) {
                            val key = cleanName(currentChannelId)
                            if (key.isNotEmpty()) {
                                synchronized(lock) { targetIndex[key] = currentChannelId }
                            }
                        }
                    } else if (insideChannel && tagName.equals("display-name", ignoreCase = true)) {
                        val dName = parser.nextText()
                        if (currentChannelId.isNotEmpty() && dName.isNotEmpty()) {
                            val key = cleanName(dName)
                            if (key.isNotEmpty()) {
                                synchronized(lock) { targetIndex[key] = currentChannelId }
                            }
                        }
                    } else if (tagName.equals("programme", ignoreCase = true)) {
                        insideProgramme = true
                        currentChannelId = parser.getAttributeValue(null, "channel") ?: ""
                        startMs = parseTime(parser.getAttributeValue(null, "start"))
                        endMs = parseTime(parser.getAttributeValue(null, "stop"))
                        currentTitle = ""
                        // Purana (khatam ho chuka) show -> title padhne ki bhi zaroorat nahi
                        skipProgramme = (endMs != 0L && endMs < minEnd) || startMs > maxStart
                    } else if (insideProgramme && !skipProgramme && tagName.equals("title", ignoreCase = true)) {
                        currentTitle = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName.equals("channel", ignoreCase = true)) {
                        insideChannel = false
                    } else if (tagName.equals("programme", ignoreCase = true)) {
                        if (!skipProgramme && currentChannelId.isNotEmpty() && currentTitle.isNotEmpty() && startMs > 0L) {
                            synchronized(lock) {
                                targetEpg.getOrPut(currentChannelId) { ArrayList() }
                                    .add(EpgProgram(currentTitle, startMs, endMs))
                            }
                            kept++

                            // Poora parse hone ka wait nahi: har kuch second mein UI refresh
                            if (notifyProgress) {
                                val t = System.currentTimeMillis()
                                if (t - lastNotify >= PROGRESS_NOTIFY_MS) {
                                    lastNotify = t
                                    onEpgLoaded?.invoke()
                                }
                            }
                        }
                        insideProgramme = false
                    }
                }
            }
            eventType = parser.next()
        }
        return kept
    }

    // Lock ke andar hi call karna hai (getCurrent... functions already lock lete hain)
    private fun resolveChannelId(tvgId: String?, channelName: String): String? {
        // 1. Direct TVG-ID
        if (!tvgId.isNullOrEmpty() && epgData.containsKey(tvgId)) return tvgId

        // 2. Name se fast dictionary
        val clean = cleanName(channelName)
        if (clean.isEmpty()) return null
        if (resolveCache.containsKey(clean)) return resolveCache[clean]

        var id = searchIndex[clean]

                // Backup: Smart partial match
        if (id == null) {
            var possibleId: String? = null
            for ((key, value) in searchIndex) {
                if (key.contains(clean) || clean.contains(key)) {
                    possibleId = value
                    
                    // Agar hum SD channel dhundh rahe hain (jiske naam mein hd nahi hai), 
                    // par match mein galti se HD aa gaya, toh loop mat roko, aage sahi SD ko dhundho!
                    if (!clean.endsWith("hd") && key.endsWith("hd")) {
                        continue 
                    }
                    // Exact sahi match mil gaya, ab ruko
                    break 
                }
            }
            id = possibleId
        }
        // Loading ke dauran "no match" cache mat karo, baad mein mil sakta hai
        if (id != null || isEpgLoaded) resolveCache[clean] = id
        return id
    }

    // Sirf current live show. Nahi mila toh NULL.
    fun getCurrentProgram(tvgId: String?, channelName: String): EpgProgram? {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            val id = resolveChannelId(tvgId, channelName)
            if (id == null) null else epgData[id]?.firstOrNull { now in it.startMs..it.endMs }
        }
    }

    // Current aur Next show dono
    fun getCurrentAndNextProgram(tvgId: String?, channelName: String): Pair<EpgProgram?, EpgProgram?> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            val programs = resolveChannelId(tvgId, channelName)?.let { epgData[it] }
            if (programs == null) {
                Pair(null, null)
            } else {
                val idx = programs.indexOfFirst { now in it.startMs..it.endMs }
                if (idx != -1) {
                    Pair(programs[idx], programs.getOrNull(idx + 1))
                } else {
                    Pair(null, programs.firstOrNull { it.startMs > now })
                }
            }
        }
    }
}
