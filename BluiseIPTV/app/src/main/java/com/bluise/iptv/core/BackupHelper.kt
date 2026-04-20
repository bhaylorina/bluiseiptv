package com.bluise.iptv.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object BackupHelper {

    // 🔥 TARGETED BACKUP: Sirf aapki 3 specific files save karega
    fun backupData(context: Context, uri: Uri) {
        try {
            val masterJson = JSONObject()

            // 1. Save PLAYLISTS (File name: "iptv")
            val playlistPrefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
            if (playlistPrefs.all.isNotEmpty()) {
                masterJson.put("iptv", prefsToJson(playlistPrefs))
            }

            // 2. Save CATEGORIES/TABS (File name: "iptv_categories")
            val catPrefs = context.getSharedPreferences("iptv_categories", Context.MODE_PRIVATE)
            if (catPrefs.all.isNotEmpty()) {
                masterJson.put("iptv_categories", prefsToJson(catPrefs))
            }

            // 3. Save SETTINGS (File name: "iptv_settings")
            val settingsPrefs = context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
            if (settingsPrefs.all.isNotEmpty()) {
                masterJson.put("iptv_settings", prefsToJson(settingsPrefs))
            }

            // Write to File
            val outputStream = context.contentResolver.openOutputStream(uri)
            val writer = OutputStreamWriter(outputStream)
            writer.write(masterJson.toString())
            writer.close()
            outputStream?.close()

            Toast.makeText(context, "Backup Successful! Saved Playlists & Tabs.", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Backup Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ♻️ TARGETED RESTORE
    fun restoreData(context: Context, uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val stringBuilder = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                stringBuilder.append(line)
                line = reader.readLine()
            }
            reader.close()
            inputStream?.close()

            val masterJson = JSONObject(stringBuilder.toString())

            // 1. Restore PLAYLISTS
            if (masterJson.has("iptv")) {
                val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
                jsonToPrefs(prefs, masterJson.getJSONObject("iptv"))
            }

            // 2. Restore CATEGORIES/TABS
            if (masterJson.has("iptv_categories")) {
                val prefs = context.getSharedPreferences("iptv_categories", Context.MODE_PRIVATE)
                jsonToPrefs(prefs, masterJson.getJSONObject("iptv_categories"))
            }

            // 3. Restore SETTINGS
            if (masterJson.has("iptv_settings")) {
                val prefs = context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
                jsonToPrefs(prefs, masterJson.getJSONObject("iptv_settings"))
            }

            Toast.makeText(context, "Restore Complete! Restarting...", Toast.LENGTH_LONG).show()
            true

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Invalid Backup File!", Toast.LENGTH_LONG).show()
            false
        }
    }

    // --- HELPER FUNCTIONS ---

    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val jsonObject = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is Boolean -> jsonObject.put(key, value)
                is Float -> jsonObject.put(key, value.toDouble())
                is Int -> jsonObject.put(key, value)
                is Long -> jsonObject.put(key, value)
                is String -> jsonObject.put(key, value)
                is Set<*> -> {
                    val jsonArray = JSONArray()
                    for (item in value) jsonArray.put(item.toString())
                    jsonObject.put(key, jsonArray)
                }
            }
        }
        return jsonObject
    }

    private fun jsonToPrefs(prefs: SharedPreferences, jsonObject: JSONObject) {
        val editor = prefs.edit()
        editor.clear() // Purana data saaf karke naya daalo
        
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)

            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is JSONArray -> {
                    val set = HashSet<String>()
                    for (i in 0 until value.length()) {
                        set.add(value.getString(i))
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }
}
