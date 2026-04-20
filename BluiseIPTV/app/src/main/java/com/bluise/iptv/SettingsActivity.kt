package com.bluise.iptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bluise.iptv.core.BackupHelper 
import com.bluise.iptv.core.PlayerEngine 

class SettingsActivity : AppCompatActivity() {

    // 1️⃣ BACKUP LAUNCHER (Save File)
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                BackupHelper.backupData(this, uri)
            }
        }
    }

    // 2️⃣ RESTORE LAUNCHER (Open File)
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val success = BackupHelper.restoreData(this, uri)
                if (success) {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 50, 50, 50)
        layout.setBackgroundColor(Color.parseColor("#1A1A1A"))
        layout.gravity = Gravity.CENTER_HORIZONTAL
        
        val scrollView = ScrollView(this)
        scrollView.isFillViewport = true
        scrollView.addView(layout)

        // ==========================================
        // 📺 SECTION: VIDEO QUALITY
        // ==========================================
        val title = TextView(this)
        title.text = "Video Quality Settings"
        title.textSize = 24f
        title.setTextColor(Color.YELLOW)
        title.gravity = Gravity.CENTER
        layout.addView(title)

        val radioGroup = RadioGroup(this)
        radioGroup.orientation = RadioGroup.VERTICAL
        radioGroup.setPadding(0, 50, 0, 50)

        val rbAuto = RadioButton(this)
        rbAuto.text = "Auto Quality (Adaptive)"
        rbAuto.textSize = 20f
        rbAuto.setTextColor(Color.WHITE)
        rbAuto.setPadding(20, 20, 20, 20)

        val rbLow = RadioButton(this)
        rbLow.text = "Data Saver / Buffer Fix (Force Low)"
        rbLow.textSize = 20f
        rbLow.setTextColor(Color.WHITE)
        rbLow.setPadding(20, 20, 20, 20)
        
        val rbHigh = RadioButton(this)
        rbHigh.text = "High Priority (Force Best)"
        rbHigh.textSize = 20f
        rbHigh.setTextColor(Color.WHITE)
        rbHigh.setPadding(20, 20, 20, 20)

        radioGroup.addView(rbAuto)
        radioGroup.addView(rbLow)
        radioGroup.addView(rbHigh)
        layout.addView(radioGroup)

        val btnSave = Button(this)
        btnSave.text = "Save Quality Settings"
        btnSave.setBackgroundColor(Color.DKGRAY)
        btnSave.setTextColor(Color.WHITE)
        layout.addView(btnSave)

        // ==========================================
        // 🔥 NETWORK SETTINGS (Special Edition Only)
        // ==========================================
        var proxySwitch: Switch? = null // TV Focus ke liye bahar declare kiya hai

        if (PlayerEngine.IS_SPECIAL_EDITION) {
            val proxySpacer = View(this)
            proxySpacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80)
            layout.addView(proxySpacer)

            val proxyTitle = TextView(this)
            proxyTitle.text = "Network Settings"
            proxyTitle.textSize = 24f
            proxyTitle.setTextColor(Color.parseColor("#00FF00"))
            proxyTitle.gravity = Gravity.CENTER
            layout.addView(proxyTitle)

            proxySwitch = Switch(this)
            proxySwitch.text = "Enable Smart Proxy (IPv4 Anti-Throttle)"
            proxySwitch.textSize = 18f
            proxySwitch.setTextColor(Color.WHITE)
            val switchParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            switchParams.setMargins(0, 40, 0, 40)
            proxySwitch.layoutParams = switchParams
            layout.addView(proxySwitch)

            // Load saved state
            proxySwitch.isChecked = prefs.getBoolean("vps_proxy_enabled", true)

            // Switch + Restart Logic
            proxySwitch.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("vps_proxy_enabled", isChecked).apply()
                
                Toast.makeText(this, "Setting Applied! Restarting...", Toast.LENGTH_LONG).show()

                proxySwitch.postDelayed({
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    Runtime.getRuntime().exit(0) 
                }, 1200)
            }
        }

        // ==========================================
        // 💾 SECTION: BACKUP & RESTORE
        // ==========================================
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80)
        layout.addView(spacer)

        val backupTitle = TextView(this)
        backupTitle.text = "Backup & Restore"
        backupTitle.textSize = 24f
        backupTitle.setTextColor(Color.CYAN)
        backupTitle.gravity = Gravity.CENTER
        layout.addView(backupTitle)

        val btnBackup = Button(this)
        btnBackup.text = "Backup Settings & Playlist"
        btnBackup.setBackgroundColor(Color.parseColor("#2C3E50"))
        btnBackup.setTextColor(Color.WHITE)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 30, 0, 0)
        btnBackup.layoutParams = params
        layout.addView(btnBackup)

        val btnRestore = Button(this)
        btnRestore.text = "Restore Settings"
        btnRestore.setBackgroundColor(Color.parseColor("#2C3E50"))
        btnRestore.setTextColor(Color.WHITE)
        val params2 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params2.setMargins(0, 20, 0, 0)
        btnRestore.layoutParams = params2
        layout.addView(btnRestore)

        setContentView(scrollView)

        // ==========================================
        // UI LOGIC & LISTENERS
        // ==========================================
        val videoMode = prefs.getInt("video_mode", 0) 
        when (videoMode) {
            1 -> rbLow.isChecked = true
            2 -> rbHigh.isChecked = true
            else -> rbAuto.isChecked = true
        }

        // TV Focus Logic (Dynamic array depending on Master Lock)
        if (TvManager.isTvMode) {
            val focusableViews = mutableListOf<View>(rbAuto, rbLow, rbHigh, btnSave, btnBackup, btnRestore)
            
            // Agar switch bana hai, toh list mein daal do
            proxySwitch?.let { focusableViews.add(4, it) }
            
            val focusListener = View.OnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(if (hasFocus) Color.parseColor("#444444") else 
                                     if (v == btnBackup || v == btnRestore) Color.parseColor("#2C3E50") 
                                     else Color.TRANSPARENT)
                
                if (v == btnSave && !hasFocus) v.setBackgroundColor(Color.DKGRAY)
            }

            focusableViews.forEach { 
                it.isFocusable = true
                it.onFocusChangeListener = focusListener
            }
            
            when (videoMode) {
                1 -> rbLow.requestFocus()
                2 -> rbHigh.requestFocus()
                else -> rbAuto.requestFocus()
            }
        }

        // 🚀 SMART SAVE: Ab ye check karega ki setting badli hai ya nahi
        fun saveSettings(isExplicitSave: Boolean) {
            val currentMode = prefs.getInt("video_mode", 0)
            val modeToSave = when {
                rbLow.isChecked -> 1
                rbHigh.isChecked -> 2
                else -> 0
            }

            // Agar user ne setting actually change ki hai
            if (currentMode != modeToSave) {
                prefs.edit().putInt("video_mode", modeToSave).apply()
                if (isExplicitSave) {
                    Toast.makeText(this, "Video Settings Saved!", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Agar bina change kiye "Save" button daba diya
                if (isExplicitSave) {
                    Toast.makeText(this, "No changes made.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        btnSave.setOnClickListener { saveSettings(true) }

        // 👇 YE PURANI LINES HAIN (Backup/Restore) JO MAINE MISS KAR DI THI 👇
        btnBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "bluise_backup_${System.currentTimeMillis()}.json")
            }
            backupLauncher.launch(intent)
        }

        btnRestore.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            restoreLauncher.launch(intent)
        }
        // 👆 ----------------------------------------------------------- 👆

        // 🚀 THE FIX: Back dabane par SILENTLY save karega, koi Ghost Toast nahi! (false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { 
                saveSettings(false) 
                finish()
            }
        })
    }
}