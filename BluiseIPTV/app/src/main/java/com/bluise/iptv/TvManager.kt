package com.bluise.iptv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.animation.DecelerateInterpolator

object TvManager {

    // Global flag to check if running on TV
    var isTvMode = false

    /**
     * Step 1: Detect Device Type (TV or Mobile)
     * Call this in onCreate() before setContentView()
     */
    fun checkMode(context: Context) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        isTvMode = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Step 2: Set Orientation based on Device
     * TV -> Always Landscape
     * Mobile -> Starts in Portrait
     */
    fun applyOrientation(activity: Activity) {
        if (isTvMode) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    
    /**
     * Step 3: TV Focus Animation (Zoom Effect)
     * Makes buttons bigger when selected with Remote
     */
    fun applyFocusEffect(vararg views: View) {
        if (!isTvMode) return // Do nothing for Mobile

        for (view in views) {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            
            view.setOnFocusChangeListener { v, hasFocus ->
                // Zoom In/Out Animation
                val scale = if (hasFocus) 1.1f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
                
                // Highlight Color (Dark Gray)
                if (hasFocus) v.setBackgroundColor(Color.parseColor("#444444")) 
                else v.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }
    
    /**
     * Step 4: Handle Fullscreen Toggle Logic
     * TV -> Stays Landscape
     * Mobile -> Switches between Portrait and Landscape
     */
    fun handleFullscreenToggle(activity: Activity, isFullscreen: Boolean) {
        if (isTvMode) {
            // TV is always Landscape, do not change orientation
            return 
        } else {
            // Mobile switches orientation
            if (isFullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
}