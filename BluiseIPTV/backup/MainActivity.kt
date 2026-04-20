package com.bluise.iptv

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bluise.iptv.core.Channel
import com.bluise.iptv.core.IptvParser
import com.bluise.iptv.core.PlayerEngine
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future

class MainActivity : AppCompatActivity() {

    var flipper: ViewFlipper? = null
    var playerView: PlayerView? = null
    var playlistListView: ListView? = null
    var channelListView: ListView? = null
    var playerContainer: FrameLayout? = null
    var controlsContainer: RelativeLayout? = null
    
    // UI Buttons
    var btnFullscreen: Button? = null
    var btnScale: Button? = null
    var btnQuality: Button? = null
    var btnBack: Button? = null
    var btnPlay: Button? = null
    var btnRew: Button? = null
    var btnFwd: Button? = null
    
    // Settings Button Global Variable
    var btnSettings: Button? = null

    // Group Filter Buttons
    var btnShowAll: Button? = null
    var btnShowGroups: Button? = null
    
    var searchBar: EditText? = null
    
    // Channel Name Overlay
    var channelNameOverlay: TextView? = null
    var debugText: TextView? = null
    var playlistAdapter: ArrayAdapter<String>? = null
    var channelAdapter: ChannelAdapter? = null 

    var player: ExoPlayer? = null
    var lastPlayedIndex = -1
    val playlists = ArrayList<String>()     
    val displayPlaylists = ArrayList<String>() 
    val channels = ArrayList<Channel>() 
    
    // Master List
    val masterChannels = ArrayList<Channel>() 
    val allChannels = ArrayList<Channel>()
    
    // Map to track channel source
    val channelSourceMap = HashMap<String, String>()

    // Group Logic Variables
    val groupList = ArrayList<String>() // Folder Names
    var isGroupView = false // True = Showing Folders, False = Showing Channels
    var currentGroupFilter: String? = null // Current opened folder name

    // 6 Category HashSets
    private val categoryMaps = mutableMapOf<String, LinkedHashSet<String>>(
        "Movie" to LinkedHashSet(),
        "Entertain" to LinkedHashSet(),
        "Info" to LinkedHashSet(),
        "News" to LinkedHashSet(),
        "Gujarati" to LinkedHashSet(),
        "Music" to LinkedHashSet()
    )
    private var currentCategory: String? = null

    var isFullscreen = false
    var isManualTrackOverride = false
    var lastErrorTime = 0L //  FLAG: Toast ko baar-baar aane se rokne ke liye
    var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    var isShowingFavorites = false 

    var isProcessing = false

    private lateinit var audioManager: AudioManager
    private var gestureDetector: GestureDetector? = null
    private var infoText: TextView? = null
    
    val handler = Handler(Looper.getMainLooper())
    
    // Search Debounce Variable (CPU Saver)
    private var searchRunnable: Runnable? = null
    
    // Controls Hiding
    val hideControlsRunnable = Runnable { 
        controlsContainer?.visibility = View.GONE 
        debugText?.visibility = View.GONE 
    }
    
    val hideInfoRunnable = Runnable { infoText?.visibility = View.GONE }
    val hideChannelNameRunnable = Runnable { channelNameOverlay?.visibility = View.GONE }

    val executor = Executors.newFixedThreadPool(4)
    private var currentLoadTask: Future<*>? = null

    //  File Picker Variables
    var dialogNameInput: EditText? = null
    var dialogUrlInput: EditText? = null

    //  The File Picker Engine (Android Storage Access Framework)
    private val filePickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 1. Auto-fill the URL box with internal Android URI
            dialogUrlInput?.setText(uri.toString())
            
            // 2. Auto-extract File Name to fill the Name box
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        var fileName = cursor.getString(nameIndex)
                        if (fileName.endsWith(".m3u", true)) fileName = fileName.substring(0, fileName.length - 4)
                        if (fileName.endsWith(".m3u8", true)) fileName = fileName.substring(0, fileName.length - 5)
                        dialogNameInput?.setText(fileName)
                    }
                }
            }
            
            // 3. Give app permanent permission to read this file even after restart
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        //  UPDATE: App start hote hi naye variable name (isProxyEnabled) mein state load karo
        val prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
        PlayerEngine.isProxyEnabled = prefs.getBoolean("vps_proxy_enabled", true)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        TvManager.checkMode(this)
        TvManager.applyOrientation(this)

        if (TvManager.isTvMode) {
            val layoutParams = window.attributes
            layoutParams.screenBrightness = 1.0f
            window.attributes = layoutParams
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            setContentView(R.layout.activity_main)
            supportActionBar?.hide()
            
            val prefs = getSharedPreferences("iptv_categories", Context.MODE_PRIVATE)
            categoryMaps.keys.forEach { cat ->
                val orderedJson = prefs.getString(cat + "_ordered", null)
                if (orderedJson != null) {
                    try {
                        val jsonArray = org.json.JSONArray(orderedJson)
                        for (i in 0 until jsonArray.length()) {
                            categoryMaps[cat]?.add(jsonArray.getString(i))
                        }
                    } catch (e: Exception) { }
                } 
                else {
                    val savedSet = prefs.getStringSet(cat, null)
                    if (savedSet != null) categoryMaps[cat]?.addAll(savedSet)
                }
            }
            
            setupUI()
            updatePlayerMargins(false)
            loadPlaylists() 
            
            forceReloadAllPlaylists()
            
            setupGestures()
        } catch (e: Throwable) { }
    }
    
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isFullscreen && event.action == KeyEvent.ACTION_DOWN) {
            val wasHidden = controlsContainer?.visibility != View.VISIBLE
            showControls()
            
            // 🔥 TV REMOTE MASTER FIX: Agar UI hidden tha, toh pehla button sirf UI dikhane ke liye consume karo
            if (wasHidden && TvManager.isTvMode) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        return true // Pehla click consume ho gaya, video galti se pause/forward nahi hogi
                    }
                }
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    playNextChannel()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    playPreviousChannel()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun forceReloadAllPlaylists() {
        if (playlists.isEmpty()) return
        
        isProcessing = true 
        val latch = CountDownLatch(playlists.size)
        val tempAllChannels = ArrayList<Channel>()
        
        channelSourceMap.clear()

        for (playlist in playlists) {
            executor.execute {
                try {
                    val parts = playlist.split("|")
                    val playlistName = parts[0]
                    val urlStr = parts.last()
                    
                    val parsed = try { 
                        if (urlStr.startsWith("content://")) {
                            //  LOCAL FILE READING MODE (No Internet Needed)
                            val uri = android.net.Uri.parse(urlStr)
                            val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Cannot open file")
                            IptvParser().parseM3U(inputStream)
                        } else {
                            //  INTERNET URL MODE (ROUTED THROUGH VPS)
                            val request = okhttp3.Request.Builder().url(urlStr).build()
                            val response = PlayerEngine.okHttpClient.newCall(request).execute()
                            val inputStream = response.body?.byteStream() ?: throw Exception("Empty body")
                            IptvParser().parseM3U(inputStream)
                        }
                    } catch (e: Exception) { 
                        ArrayList<Channel>() 
                    }
                    synchronized(tempAllChannels) {
                        tempAllChannels.addAll(parsed)
                        parsed.forEach { ch ->
                            if (ch.url.isNotEmpty()) {
                                channelSourceMap[ch.url] = playlistName
                            }
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }

        Thread {
            try {
                latch.await(60, TimeUnit.SECONDS) 
                runOnUiThread {
                    masterChannels.clear()
                    masterChannels.addAll(tempAllChannels)
                    allChannels.clear()
                    allChannels.addAll(tempAllChannels)
                    isProcessing = false 
                    
                    if (TvManager.isTvMode && playlists.isNotEmpty()) {
                        playlistListView?.post { playlistListView?.requestFocus() }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { isProcessing = false }
            }
        }.start()
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                val screenWidth = resources.displayMetrics.widthPixels
                val sensitivity = 700f 
                
                if (e1.x < screenWidth / 2) {
                    val lp = window.attributes
                    val change = distanceY / sensitivity
                    val newBright = (lp.screenBrightness + change).coerceIn(0.01f, 1.0f)
                    lp.screenBrightness = newBright
                    window.attributes = lp
                    showInfo(" ${(newBright * 100).toInt()}%")
                } else {
                    if (Math.abs(distanceY) > 10) {
                         val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                         val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                         val change = if (distanceY > 0) 1 else -1
                         val newVol = (currentVol + change).coerceIn(0, maxVol)
                         audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                         val percent = (newVol.toFloat() / maxVol.toFloat() * 100).toInt()
                         showInfo(" $percent%")
                    }
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsContainer?.visibility == View.VISIBLE) controlsContainer?.visibility = View.GONE
                else showControls()
                return true
            }
        })
        playerContainer?.setOnTouchListener { _, event -> gestureDetector?.onTouchEvent(event); true }
    }

    private fun showInfo(text: String) {
        infoText?.text = text
        infoText?.visibility = View.VISIBLE
        infoText?.bringToFront()
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, 1500)
    }
    
    private fun showChannelNameOverlay(name: String) {
        channelNameOverlay?.text = name
        channelNameOverlay?.visibility = View.VISIBLE
        channelNameOverlay?.bringToFront()
        handler.removeCallbacks(hideChannelNameRunnable)
        handler.postDelayed(hideChannelNameRunnable, 1000) 
    }

    override fun onUserLeaveHint() {
        if (player != null && player?.isPlaying == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                    enterPictureInPictureMode(params)
                } catch (e: Exception) {}
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPiP: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPiP, newConfig)
        if (isInPiP) {
            controlsContainer?.visibility = View.GONE
            infoText?.visibility = View.GONE
            channelNameOverlay?.visibility = View.GONE
            searchBar?.visibility = View.GONE
            channelListView?.visibility = View.GONE
            playlistListView?.visibility = View.GONE
            findViewById<View>(R.id.groupFilterBar)?.visibility = View.GONE 
            playerContainer?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
            updatePlayerMargins(true) 
            supportActionBar?.hide()
            btnSettings?.visibility = View.GONE
        } else {
            if (!isFullscreen) {
                playerContainer?.layoutParams?.height = (250 * resources.displayMetrics.density).toInt()
                channelListView?.visibility = View.VISIBLE
                searchBar?.visibility = View.VISIBLE
                findViewById<View>(R.id.groupFilterBar)?.visibility = View.VISIBLE 
                playlistListView?.visibility = View.VISIBLE
                updatePlayerMargins(false)
                showSystemUI()
                
                if (flipper?.displayedChild == 0) {
                    btnSettings?.visibility = View.VISIBLE
                }
            }
            showControls()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.getInsetsController(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemUI() {
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) hideSystemUI()
    }

    fun setupUI() {
        flipper = findViewById(R.id.viewFlipper)
        playerView = findViewById(R.id.playerView)
        playlistListView = findViewById(R.id.playlistListView)
        channelListView = findViewById(R.id.channelListView)
        playerContainer = findViewById(R.id.playerContainer)
        controlsContainer = findViewById(R.id.controlsContainer)
        
        btnShowAll = findViewById(R.id.btnShowAll)
        btnShowGroups = findViewById(R.id.btnShowGroups)
        
        if (TvManager.isTvMode) {
            playerContainer?.visibility = View.GONE
            playlistListView?.isFocusable = true
            channelListView?.isFocusable = true
            playlistListView?.itemsCanFocus = true
            channelListView?.itemsCanFocus = true
            btnShowAll?.isFocusable = true
            btnShowGroups?.isFocusable = true
        }
        
        infoText = TextView(this)
        infoText?.setTextColor(Color.YELLOW)
        infoText?.textSize = 36f
        infoText?.typeface = Typeface.DEFAULT_BOLD
        infoText?.setShadowLayer(10f, 0f, 0f, Color.BLACK)
        infoText?.gravity = Gravity.CENTER
        infoText?.visibility = View.GONE
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.CENTER
        playerContainer?.addView(infoText, params)
        
        channelNameOverlay = TextView(this)
        channelNameOverlay?.setTextColor(Color.WHITE)
        channelNameOverlay?.textSize = 18f
        channelNameOverlay?.setTypeface(null, Typeface.BOLD)
        channelNameOverlay?.setPadding(30, 15, 30, 15)
        channelNameOverlay?.setBackgroundColor(Color.parseColor("#80000000")) 
        channelNameOverlay?.gravity = Gravity.CENTER
        channelNameOverlay?.visibility = View.GONE
        val nameParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        nameParams.gravity = Gravity.CENTER
        playerContainer?.addView(channelNameOverlay, nameParams)
        
        btnSettings = Button(this)
        btnSettings?.text = "\u2699"
        btnSettings?.textSize = 25f
        btnSettings?.setTextColor(Color.WHITE)
        btnSettings?.setBackgroundColor(Color.TRANSPARENT)
        val btnParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        btnParams.gravity = Gravity.TOP or Gravity.END
        btnParams.setMargins(0, 92, 30, 0) 
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(btnSettings, btnParams)
        
        btnSettings?.setOnClickListener { 
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        
        if (TvManager.isTvMode) {
            btnSettings?.isFocusable = true
            btnSettings?.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) v.setBackgroundColor(Color.RED)
                else v.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnScale = findViewById(R.id.btnScale)
        btnQuality = findViewById(R.id.btnQuality)
        btnBack = findViewById(R.id.btnBackToPlaylists)
        btnPlay = findViewById(R.id.btnPlay)
        btnRew = findViewById(R.id.btnRew)
        btnFwd = findViewById(R.id.btnFwd)
        
        searchBar = findViewById(R.id.searchBar)
        val btnAdd = findViewById<Button>(R.id.btnAddPlaylist)

        // Tab Buttons
        val t1 = findViewById<Button>(R.id.tabMovie)
        val t2 = findViewById<Button>(R.id.tabEnt)
        val t3 = findViewById<Button>(R.id.tabInfo)
        val t4 = findViewById<Button>(R.id.tabNews)
        val t5 = findViewById<Button>(R.id.tabGuj)
        val t6 = findViewById<Button>(R.id.tabMusic)
        t1.textSize = 12f; t2.textSize = 12f; t3.textSize = 12f
        t4.textSize = 12f; t5.textSize = 12f; t6.textSize = 12f

        t1.setOnClickListener { openCategory("Movie") }
        t2.setOnClickListener { openCategory("Entertain") }
        t3.setOnClickListener { openCategory("Info") }
        t4.setOnClickListener { openCategory("News") }
        t5.setOnClickListener { openCategory("Gujarati") }
        t6.setOnClickListener { openCategory("Music") }
        
        TvManager.applyFocusEffect(t1, t2, t3, t4, t5, t6, btnPlay!!, btnRew!!, btnFwd!!, btnFullscreen!!, btnScale!!, btnQuality!!, btnBack!!)
        
        TvManager.applyFocusEffect(btnShowAll!!, btnShowGroups!!)

        // 🔥 THE ULTIMATE TV FOCUS FIX 🔥
        // Explicitly linking buttons so Android TV doesn't get confused when pressing Left/Right
        if (TvManager.isTvMode) {
            btnScale?.nextFocusRightId = R.id.btnRew
            btnRew?.nextFocusLeftId = R.id.btnScale
            btnRew?.nextFocusRightId = R.id.btnPlay
            btnPlay?.nextFocusLeftId = R.id.btnRew
            btnPlay?.nextFocusRightId = R.id.btnFwd
            btnFwd?.nextFocusLeftId = R.id.btnPlay
            btnFwd?.nextFocusRightId = R.id.btnQuality
            btnQuality?.nextFocusLeftId = R.id.btnFwd
            btnQuality?.nextFocusRightId = R.id.btnFullscreen
            btnFullscreen?.nextFocusLeftId = R.id.btnQuality
        }

        playerView?.useController = false 
        playerView?.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_NEVER)
        playerView?.keepScreenOn = true

        playlistAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayPlaylists) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val item = getItem(position) ?: ""
                view.text = item.split("|").firstOrNull() ?: "Unknown"
                view.setTextColor(Color.WHITE)
                
                if (TvManager.isTvMode) {
                    view.textSize = 22f
                    view.setPadding(40, 40, 40, 40)
                    view.isFocusable = true
                    view.isFocusableInTouchMode = false
                    view.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) view.setBackgroundColor(Color.parseColor("#888888"))
                        else view.setBackgroundColor(Color.TRANSPARENT)
                    }
                    view.setOnClickListener {
                        val url = item.split("|").last()
                        loadChannels(url)
                    }
                    view.setOnLongClickListener {
                        val realItem = displayPlaylists[position]
                        val realIndex = playlists.indexOf(realItem)
                        if (realIndex != -1) showPlaylistOptions(realIndex)
                        true
                    }
                } else {
                    view.textSize = 22f
                    view.setPadding(30, 30, 30, 30)
                }
                return view
            }
        }
        playlistListView?.adapter = playlistAdapter
        
        btnAdd?.setOnClickListener { showAddPlaylistDialog() }
        
        playlistListView?.setOnItemClickListener { _, _, position, _ ->
            if (!TvManager.isTvMode) {
                val item = displayPlaylists[position]
                val url = item.split("|").last()
                loadChannels(url)
            }
        }
        
        playlistListView?.setOnItemLongClickListener { _, _, position, _ ->
            val realItem = displayPlaylists[position]
            val realIndex = playlists.indexOf(realItem)
            if (realIndex != -1) showPlaylistOptions(realIndex) 
            true
        }
        
        searchBar?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { 
                if (isGroupView && s.toString().isNotEmpty()) {
                    showAllChannels()
                }
                
                // CPU Saver Debounce
                searchRunnable?.let { handler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    filterChannels(s.toString())
                }
                handler.postDelayed(searchRunnable!!, 300)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    toggleFullscreen()
                } else if (flipper?.displayedChild == 1) { 
                    if (currentGroupFilter != null) {
                        showGroupFolders()
                    } else if (isGroupView) {
                        showAllChannels()
                    } else {
                        releasePlayer()
                        flipper?.displayedChild = 0
                        supportActionBar?.hide()
                        showSystemUI()
                        searchBar?.setText("")
                        isShowingFavorites = false
                        currentCategory = null
                        isProcessing = false 
                        
                        btnSettings?.visibility = View.VISIBLE
                        
                        if (TvManager.isTvMode) {
                            playerContainer?.visibility = View.GONE
                            playlistListView?.post { 
                                if (playlistListView?.childCount ?: 0 > 0) {
                                    playlistListView?.getChildAt(0)?.requestFocus()
                                } else {
                                    playlistListView?.requestFocus()
                                }
                            }
                        }
                    }
                } else {
                    finish()
                }
            }
        })
        
        btnBack?.setOnClickListener { 
             onBackPressedDispatcher.onBackPressed()
        }
        
        btnFullscreen?.setOnClickListener { toggleFullscreen(); showControls() }
        btnQuality?.setOnClickListener { showTrackDialog(); showControls() }
        
        btnPlay?.setOnClickListener {
            if (player != null) {
                if (player!!.isPlaying) { player!!.pause(); btnPlay?.text = "▶" } else { player!!.play(); btnPlay?.text = "||" }
            }
            showControls()
        }
        
        btnRew?.setOnClickListener { playPreviousChannel(); showControls() }
        btnFwd?.setOnClickListener { playNextChannel(); showControls() }

        btnScale?.setOnClickListener { 
            showControls()
            if (playerView != null) {
                resizeMode = when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                playerView?.resizeMode = resizeMode
            }
        }
        
        channelListView?.setOnItemClickListener { _, _, position, _ -> 
             if (isGroupView) {
                 loadGroupChannels(groupList[position])
             } else {
                 if (!TvManager.isTvMode) playChannel(channels[position]) 
             }
        }

        channelListView?.setOnItemLongClickListener { _, _, position, _ ->
            if (!isGroupView) {
                if (currentCategory != null) {
                    showCategoryOptions(channels[position])
                } else {
                    showAddToCategoryDialog(channels[position])
                }
                true
            } else {
                false
            }
        }
        
        btnShowAll?.setOnClickListener { showAllChannels() }
        btnShowGroups?.setOnClickListener { showGroupFolders() }
    }

    fun showAllChannels() {
        isGroupView = false
        currentGroupFilter = null
        
        btnShowAll?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#AA00FF")) 
        btnShowAll?.setTextColor(Color.WHITE)
        btnShowGroups?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333")) 
        btnShowGroups?.setTextColor(Color.parseColor("#AAAAAA"))
        
        channels.clear()
        channels.addAll(allChannels)
        
        channelAdapter = ChannelAdapter(this, channels)
        channelListView?.adapter = channelAdapter
    }

    fun showGroupFolders() {
        isGroupView = true
        currentGroupFilter = null
        
        btnShowGroups?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#AA00FF"))
        btnShowGroups?.setTextColor(Color.WHITE)
        btnShowAll?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
        btnShowAll?.setTextColor(Color.parseColor("#AAAAAA"))
        
        groupList.clear()
        val groups = allChannels.map { it.group }.distinct().sorted()
        groupList.addAll(groups)
        
        val groupAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, groupList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                
                view.text = "📁  " + getItem(position) 
                view.setTextColor(Color.WHITE)
                view.textSize = 18f
                view.setPadding(30, 30, 30, 30)
                
                if (TvManager.isTvMode) {
                    view.isFocusable = true
                    view.setBackgroundColor(Color.TRANSPARENT)
                    view.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) view.setBackgroundColor(Color.parseColor("#888888"))
                        else view.setBackgroundColor(Color.TRANSPARENT)
                    }
                    view.setOnClickListener { loadGroupChannels(groupList[position]) }
                }
                return view
            }
        }
        channelListView?.adapter = groupAdapter
    }

    fun loadGroupChannels(groupName: String) {
        isGroupView = false 
        currentGroupFilter = groupName 
        
        channels.clear()
        val filtered = allChannels.filter { it.group == groupName }
        channels.addAll(filtered)
        
        channelAdapter = ChannelAdapter(this, channels)
        channelListView?.adapter = channelAdapter
        
        channelListView?.setSelection(0)
    }

    private fun openCategory(catName: String) {
        if (isProcessing) {
            Toast.makeText(this, "Loading channels...", Toast.LENGTH_SHORT).show()
            return
        }
        isProcessing = true
        currentCategory = catName
        isShowingFavorites = true
        
        if (TvManager.isTvMode) {
             playerContainer?.visibility = View.GONE
        }
        
        btnSettings?.visibility = View.GONE
        findViewById<View>(R.id.groupFilterBar)?.visibility = View.GONE
        
        val catSet = categoryMaps[catName] ?: LinkedHashSet()
        val catList = ArrayList<Channel>()
        
        catSet.forEach { entry ->
            if (entry.contains("|")) {
                val parts = entry.split("|")
                val name = parts[0]
                val source = parts[1]
                
                val found = masterChannels.find { 
                    it.name == name && channelSourceMap[it.url] == source 
                }
                if (found != null) catList.add(found)
                
            } else {
                val found = masterChannels.find { it.name == entry }
                if (found != null) catList.add(found)
            }
        }

        channels.clear()
        channels.addAll(catList)
        channelAdapter = ChannelAdapter(this, channels)
        channelListView?.adapter = channelAdapter
        flipper?.displayedChild = 1
        isProcessing = false
        
        if (TvManager.isTvMode) {
            channelListView?.post { 
                channelListView?.setSelection(0)
                if (channelListView?.childCount ?: 0 > 0) {
                    channelListView?.getChildAt(0)?.requestFocus()
                } else {
                    channelListView?.requestFocus()
                }
            }
        }
    }

    private fun showAddToCategoryDialog(channel: Channel) {
        val cats = categoryMaps.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add to Category")
            .setItems(cats) { _, which ->
                val selectedCat = cats[which]
                
                val source = channelSourceMap[channel.url] ?: "Unknown"
                val uniqueId = "${channel.name}|$source"
                
                categoryMaps[selectedCat]?.add(uniqueId)
                saveCategories()
                Toast.makeText(this, "Added to $selectedCat", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showCategoryOptions(channel: Channel) {
        val options = arrayOf("Move to Top", "Move to Bottom", "Remove")
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(options) { _, which ->
                val catName = currentCategory ?: return@setItems
                val set = categoryMaps[catName] ?: return@setItems
                
                val source = channelSourceMap[channel.url] ?: "Unknown"
                val uniqueId = "${channel.name}|$source"
                
                val targetId = if (set.contains(uniqueId)) uniqueId else channel.name

                when (which) {
                    0 -> { 
                        set.remove(targetId)
                        val newList = LinkedHashSet<String>()
                        newList.add(targetId)
                        newList.addAll(set)
                        categoryMaps[catName] = newList
                    }
                    1 -> { 
                        set.remove(targetId)
                        set.add(targetId)
                    }
                    2 -> { 
                        set.remove(targetId)
                    }
                }
                saveCategories()
                openCategory(catName) 
            }.show()
    }

    private fun saveCategories() {
        val prefs = getSharedPreferences("iptv_categories", Context.MODE_PRIVATE).edit()
        categoryMaps.forEach { (cat, set) ->
            val jsonArray = org.json.JSONArray()
            for (item in set) {
                jsonArray.put(item)
            }
            prefs.putString(cat + "_ordered", jsonArray.toString())
        }
        prefs.apply()
    }

    fun showTrackDialog() {
        if (player == null) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tracks, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        val tabVideo = dialogView.findViewById<TextView>(R.id.tabVideo)
        val tabAudio = dialogView.findViewById<TextView>(R.id.tabAudio)
        val listView = dialogView.findViewById<ListView>(R.id.trackListView)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)
        
        if (TvManager.isTvMode) {
            listView.isFocusable = true
            listView.itemsCanFocus = true
            tabVideo.isFocusable = true
            tabAudio.isFocusable = true
            
            val focusListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundColor(Color.parseColor("#888888")) 
                } else {
                    val tv = v as TextView
                    if (tv.currentTextColor == Color.RED) {
                        v.setBackgroundColor(Color.parseColor("#333333"))
                    } else {
                        v.setBackgroundColor(Color.parseColor("#252525"))
                    }
                }
            }
            tabVideo.onFocusChangeListener = focusListener
            tabAudio.onFocusChangeListener = focusListener
        }
        
        loadTracks(C.TRACK_TYPE_VIDEO, listView, dialog)
        
        tabVideo.setOnClickListener {
            tabVideo.setTextColor(Color.RED); tabVideo.setBackgroundColor(Color.parseColor("#333333"))
            tabAudio.setTextColor(Color.GRAY); tabAudio.setBackgroundColor(Color.parseColor("#252525"))
            loadTracks(C.TRACK_TYPE_VIDEO, listView, dialog)
        }
        tabAudio.setOnClickListener {
            tabAudio.setTextColor(Color.RED); tabAudio.setBackgroundColor(Color.parseColor("#333333"))
            tabVideo.setTextColor(Color.GRAY); tabVideo.setBackgroundColor(Color.parseColor("#252525"))
            loadTracks(C.TRACK_TYPE_AUDIO, listView, dialog)
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    fun loadTracks(trackType: Int, listView: ListView, dialog: AlertDialog) {
        val tracks = player?.currentTracks ?: return
        val groupList = ArrayList<String>()
        val groupIndexMap = ArrayList<Int>() 
        val trackIndexMap = ArrayList<Int>()
        var selectedPosition = -1
        var currentIndex = 0
        if (trackType == C.TRACK_TYPE_VIDEO) {
            groupList.add("Auto")
            groupIndexMap.add(-1); trackIndexMap.add(-1)
            var isAuto = true
            val parameters = player?.trackSelectionParameters
            if (parameters != null) {
                for (i in 0 until tracks.groups.size) {
                    val group = tracks.groups[i]
                    if (group.type == trackType) {
                        if (parameters.overrides.containsKey(group.mediaTrackGroup)) { isAuto = false; break }
                    }
                }
            }
            if (isAuto) selectedPosition = 0
            currentIndex++
        }
        for (i in 0 until tracks.groups.size) {
            val group = tracks.groups[i]
            if (group.type == trackType) {
                for (j in 0 until group.length) {
                    val format = group.getTrackFormat(j)
                    if (group.isTrackSupported(j)) {
                        if (trackType == C.TRACK_TYPE_VIDEO) {
                            val bitrate = if(format.bitrate > 0) format.bitrate / 1000 else 0
                            groupList.add("${format.width}x${format.height}, ${bitrate}kbps")
                        } else {
                            val lang = format.language ?: "Unknown"
                            val label = format.label ?: ""
                            groupList.add("${lang.uppercase()} $label")
                        }
                        groupIndexMap.add(i); trackIndexMap.add(j)
                        if (selectedPosition == -1 && group.isTrackSelected(j)) selectedPosition = currentIndex
                        currentIndex++
                    }
                }
            }
        }
        val adapter = object : ArrayAdapter<String>(this, R.layout.item_track, R.id.tvTrackName, groupList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val rb = view.findViewById<RadioButton>(R.id.rbTrack)
                rb.isChecked = (position == selectedPosition)
                
                if (TvManager.isTvMode) {
                    view.isFocusable = true
                    view.isFocusableInTouchMode = false
                    rb.isFocusable = false
                    rb.isClickable = false
                    view.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) view.setBackgroundColor(Color.parseColor("#555555"))
                        else view.setBackgroundColor(Color.TRANSPARENT)
                    }
                    view.setOnClickListener {
                         try {
                            if (player != null) {
                                val groupIndex = groupIndexMap[position]
                                val builder = player!!.trackSelectionParameters.buildUpon()
                                if (groupIndex == -1) {
                                    if (trackType == C.TRACK_TYPE_VIDEO) isManualTrackOverride = false // 🔥 AUTO RESET
                                    builder.clearOverridesOfType(trackType)
                                    if (trackType == C.TRACK_TYPE_VIDEO) builder.setForceHighestSupportedBitrate(false)
                                } else {
                                    if (trackType == C.TRACK_TYPE_VIDEO) isManualTrackOverride = true // 🔥 MANUAL LOCK ON
                                    val trackIndex = trackIndexMap[position]
                                    val group = tracks.groups[groupIndex].mediaTrackGroup
                                    builder.clearOverridesOfType(trackType)
                                    val override = TrackSelectionOverride(group, trackIndex)
                                    builder.setOverrideForType(override)
                                    if (trackType == C.TRACK_TYPE_VIDEO) builder.setForceHighestSupportedBitrate(false)
                                }
                                player!!.trackSelectionParameters = builder.build()
                            }
                        } catch (e: Exception) {}
                        dialog.dismiss()
                    }
                }
                return view
            }
        }
        listView.adapter = adapter
        
        if (!TvManager.isTvMode) {
            listView.setOnItemClickListener { _, _, position, _ ->
                 try {
                    if (player != null) {
                        val groupIndex = groupIndexMap[position]
                        val builder = player!!.trackSelectionParameters.buildUpon()
                        if (groupIndex == -1) {
                            if (trackType == C.TRACK_TYPE_VIDEO) isManualTrackOverride = false // 🔥 AUTO RESET
                            builder.clearOverridesOfType(trackType)
                            if (trackType == C.TRACK_TYPE_VIDEO) builder.setForceHighestSupportedBitrate(false)
                        } else {
                            if (trackType == C.TRACK_TYPE_VIDEO) isManualTrackOverride = true // 🔥 MANUAL LOCK ON
                            val trackIndex = trackIndexMap[position]
                            val group = tracks.groups[groupIndex].mediaTrackGroup
                            builder.clearOverridesOfType(trackType)
                            val override = TrackSelectionOverride(group, trackIndex)
                            builder.setOverrideForType(override)
                            if (trackType == C.TRACK_TYPE_VIDEO) builder.setForceHighestSupportedBitrate(false)
                        }
                        player!!.trackSelectionParameters = builder.build()
                    }
                } catch (e: Exception) {}
                dialog.dismiss()
            }
        }
    }

    fun updatePlayerMargins(isFull: Boolean) {
        val container = findViewById<FrameLayout>(R.id.playerContainer)
        if (container != null) {
            val params = container.layoutParams as LinearLayout.LayoutParams
            val density = resources.displayMetrics.density
            val topMargin = if (isFull) 0 else (45 * density).toInt()
            params.setMargins(0, topMargin, 0, 0)
            container.layoutParams = params
        }
    }

    fun showControls() {
        controlsContainer?.visibility = View.VISIBLE
        debugText?.visibility = View.VISIBLE
        
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
        
        if (TvManager.isTvMode) {
             if (isFullscreen) {
                 // 🔥 BUG FIX: Fullscreen me list hide hoti hai, toh void me focus mat bhejo! Sirf buttons pe rakho.
                 val current = currentFocus
                 if (current != btnPlay && current != btnRew && current != btnFwd && 
                     current != btnScale && current != btnQuality && current != btnFullscreen) {
                     btnPlay?.requestFocus()
                 }
             } else {
                 // Normal mode me purana rule chalne do
                 if (currentFocus == null) {
                     if (flipper?.displayedChild == 1) {
                         channelListView?.requestFocus()
                     } else if (flipper?.displayedChild == 0) {
                         playlistListView?.requestFocus()
                     } else {
                         btnPlay?.requestFocus()
                     }
                 }
             }
        }
    }

    fun playChannel(channel: Channel) {
        if (channel.url.isEmpty()) return
        
        isManualTrackOverride = false //  Naya channel aate hi Flag reset taaki default setting laagu ho
        lastErrorTime = 0L //  Naye channel ke liye error timer reset

        try {
            lastPlayedIndex = channels.indexOf(channel)

            if (debugText == null && playerContainer != null) {
                debugText = TextView(this)
                debugText?.setTextColor(Color.parseColor("#90EE90"))
                debugText?.textSize = 14f
                debugText?.setTypeface(null, Typeface.BOLD)
                debugText?.setShadowLayer(5f, 0f, 0f, Color.BLACK)
                debugText?.visibility = View.GONE 
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, 
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                params.setMargins(20, 20, 0, 0)
                playerContainer?.addView(debugText, params)
            }

            if (player == null) {
                player = PlayerEngine.createPlayer(this)
                playerView?.player = player
                playerView?.controllerAutoShow = false 
                
                player?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { 
                        btnPlay?.text = if (isPlaying) "||" else "" 
                    }

                    //  BUG FIX: Grey box hide karo aur sirf 1 baar actual error Toast dikhao
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        
                        // 1. ExoPlayer ke default UI update hone ke THEEK BAAD isey hide karo
                        playerView?.post {
                            playerView?.findViewById<TextView>(androidx.media3.ui.R.id.exo_error_message)?.visibility = View.GONE
                        }
                        
                        val currentTime = System.currentTimeMillis()
                        
                        // 2. Agar pichle 10 second mein error nahi dikhaya, tabhi naya Toast dikhao (3-baar ka spam rokne ke liye)
                        if (currentTime - lastErrorTime > 10000) {
                            lastErrorTime = currentTime
                            
                            // 3. Proper Error Detail nikalna
                            val errorDetail = error.cause?.message ?: error.errorCodeName
                            
                            // 4. Apna chota aur clean Toast dikhana (Sirf 2 second ke liye)
                            Toast.makeText(this@MainActivity, "Error: $errorDetail", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onEvents(eventPlayer: Player, events: Player.Events) {
                        if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED) || events.contains(Player.EVENT_TRACKS_CHANGED)) {
                            val format = (eventPlayer as? ExoPlayer)?.videoFormat
                            if (format != null) {
                                var br = if (format.bitrate > 0) format.bitrate / 1000 else 0
                                
                                if (br == 0) {
                                    var found = false
                                    if (format.id != null) {
                                        for (group in eventPlayer.currentTracks.groups) {
                                            if (group.type == C.TRACK_TYPE_VIDEO) {
                                                for (i in 0 until group.length) {
                                                    val tFormat = group.getTrackFormat(i)
                                                    if (tFormat.id == format.id && tFormat.bitrate > 0) {
                                                        br = tFormat.bitrate / 1000
                                                        found = true; break
                                                    }
                                                }
                                            }
                                            if (found) break
                                        }
                                    }
                                    if (!found) {
                                        for (group in eventPlayer.currentTracks.groups) {
                                            if (group.type == C.TRACK_TYPE_VIDEO && group.isSelected) {
                                                for (i in 0 until group.length) {
                                                    if (group.isTrackSelected(i)) {
                                                        val tFormat = group.getTrackFormat(i)
                                                        if (tFormat.width == format.width && tFormat.height == format.height && tFormat.bitrate > 0) {
                                                            br = tFormat.bitrate / 1000
                                                            found = true; break
                                                        }
                                                    }
                                                }
                                            }
                                            if (found) break
                                        }
                                    }
                                }
                                
                                val info = "${format.width}x${format.height} | ${br}kbps"
                                if (info.isNotEmpty() && debugText?.text != info) {
                                    runOnUiThread { 
                                        debugText?.text = info 
                                        debugText?.visibility = View.VISIBLE
                                        
                                        // Sirf ye do line ka jadoo hai jo pehle miss ho gaya tha!
                                        handler.removeCallbacks(hideControlsRunnable)
                                        handler.postDelayed(hideControlsRunnable, 3000)
                                    }
                                }
                            }
                        }
                    }
                    
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        
                        if (isManualTrackOverride) return //  CHUP CHAP BAITH JAO! User ne manual lock lagaya hai.

                        val prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
                        val videoMode = prefs.getInt("video_mode", 0) 

                        if (videoMode == 1 || videoMode == 2) { 
                            var targetBitrate = if (videoMode == 1) Int.MAX_VALUE else -1
                            var targetGroupIndex = -1
                            var targetTrackIndex = -1
                            var hasVideo = false
                            
                            for (i in 0 until tracks.groups.size) {
                                val group = tracks.groups[i]
                                if (group.type == C.TRACK_TYPE_VIDEO) {
                                    hasVideo = true
                                    for (j in 0 until group.length) {
                                        if (group.isTrackSupported(j)) {
                                            val format = group.getTrackFormat(j)
                                            val bit = format.bitrate
                                            if (bit > 0) {
                                                if (videoMode == 1 && bit < targetBitrate) {
                                                    targetBitrate = bit
                                                    targetGroupIndex = i
                                                    targetTrackIndex = j
                                                } else if (videoMode == 2 && bit > targetBitrate) {
                                                    targetBitrate = bit
                                                    targetGroupIndex = i
                                                    targetTrackIndex = j
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (hasVideo && targetGroupIndex != -1 && targetTrackIndex != -1) {
                                val targetGroup = tracks.groups[targetGroupIndex].mediaTrackGroup
                                val currentParams = player!!.trackSelectionParameters
                                
                                val overrideConfig = currentParams.overrides[targetGroup]
                                val isAlreadyLocked = overrideConfig != null && overrideConfig.trackIndices.contains(targetTrackIndex)
                                
                                if (!isAlreadyLocked) {
                                    val builder = currentParams.buildUpon()
                                    builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO) 
                                    val override = TrackSelectionOverride(targetGroup, targetTrackIndex)
                                    builder.setOverrideForType(override)
                                    player!!.trackSelectionParameters = builder.build()
                                }
                            }
                        }
                    }
                })
            } //  YAHAN WOH MISSING BRACKET LAGA DIYA HAI JO PLAYER KO CLOSE KAREGA

            PlayerEngine.playChannel(this, player!!, channel)
            
            // Initial Builder Setup
            val prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
            val videoMode = prefs.getInt("video_mode", 0) 
            
            val builder = player!!.trackSelectionParameters.buildUpon()
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO) // Clear purane locks
            builder.setPreferredAudioLanguages("hi", "en")
            
            if (videoMode == 2) {
                // Force High (Mode 2)
                builder.setForceHighestSupportedBitrate(true)
            } else {
                // Auto (Mode 0) or Force Low (Mode 1 - Actual lock handled inside onTracksChanged)
                builder.setForceHighestSupportedBitrate(false)
            }

            player!!.trackSelectionParameters = builder.build()

            controlsContainer?.visibility = View.GONE 
            
            showChannelNameOverlay(channel.name)
            playerContainer?.visibility = View.VISIBLE
            
            if (TvManager.isTvMode && !isFullscreen) {
                toggleFullscreen()
            }
            
        } catch (e: Exception) { 
            e.printStackTrace()
        }
    }

    fun loadChannels(m3uUrl: String) {
        // 🚀 THE MAGIC INTENT LAUNCHER (App Interceptor)
        if (m3uUrl.startsWith("app://")) {
            val packageName = m3uUrl.substring(6) // "app://" ko hata kar sirf package name nikalna
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                startActivity(launchIntent) // App open kar do!
            } else {
                Toast.makeText(this, "App not found or cannot be opened!", Toast.LENGTH_SHORT).show()
            }
            return // Yahan se wapas bhej do, aage M3U parser mein mat jane do!
        }

        if (currentLoadTask != null && !currentLoadTask!!.isDone) {
            currentLoadTask?.cancel(true)
        }

        if (TvManager.isTvMode) {
             channelAdapter = null
             channelListView?.adapter = null
        }

        currentLoadTask = executor.submit {
            try {
                if (Thread.currentThread().isInterrupted) return@submit

                val newChannels = try {
                    if (m3uUrl.startsWith("content://")) {
                        //  LOCAL FILE READING MODE
                        val uri = android.net.Uri.parse(m3uUrl)
                        val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Cannot open file")
                        IptvParser().parseM3U(inputStream)
                    } else {
                        //  INTERNET URL MODE (ROUTED THROUGH VPS)
                        val request = okhttp3.Request.Builder()
                            .url(m3uUrl)
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                        
                        if (Thread.currentThread().isInterrupted) return@submit
                        
                        val response = PlayerEngine.okHttpClient.newCall(request).execute()
                        val inputStream = response.body?.byteStream() ?: throw Exception("Empty body")
                        IptvParser().parseM3U(inputStream)
                    }
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) return@submit
                    e.printStackTrace()
                    ArrayList<Channel>() 
                }

                if (Thread.currentThread().isInterrupted) return@submit

                runOnUiThread {
                    if (Thread.currentThread().isInterrupted) return@runOnUiThread

                    if (TvManager.isTvMode) {
                        playerContainer?.visibility = View.GONE
                        playerContainer?.layoutParams?.height = 0
                    }
                    
                    btnSettings?.visibility = View.GONE
                    
                    findViewById<View>(R.id.groupFilterBar)?.visibility = View.VISIBLE

                    if (newChannels.isNotEmpty()) {
                        allChannels.clear()
                        allChannels.addAll(newChannels)
                        
                        showAllChannels()
                        
                        flipper?.displayedChild = 1 
                        
                        if (TvManager.isTvMode) {
                            channelListView?.post {
                                if (channelListView?.childCount ?: 0 > 0) {
                                    channelListView?.getChildAt(0)?.requestFocus()
                                } else {
                                    channelListView?.requestFocus()
                                }
                            }
                        }
                    } else {
                         Toast.makeText(this@MainActivity, "Playlist Empty or Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun filterChannels(query: String) {
        val lower = query.lowercase()
        channels.clear()
        if (lower.isEmpty()) channels.addAll(allChannels) 
        else { for (ch in allChannels) { if (ch.name.lowercase().contains(lower)) channels.add(ch) } }
        
        if (channelListView?.adapter != channelAdapter) {
            channelListView?.adapter = channelAdapter
        }
        channelAdapter?.notifyDataSetChanged()
    }
    
    fun releasePlayer() { 
        player?.release()
        player = null
        handler.removeCallbacks(hideControlsRunnable) 
    }
    
    private fun playNextChannel() {
        if (channels.isEmpty()) return
        val currentUrl = player?.currentMediaItem?.localConfiguration?.uri.toString()
        val currentIndex = channels.indexOfFirst { it.url == currentUrl }
        
        if (currentIndex != -1 && currentIndex < channels.size - 1) {
            playChannel(channels[currentIndex + 1])
            channelListView?.smoothScrollToPosition(currentIndex + 1)
        } else if (currentIndex == channels.size - 1) {
            playChannel(channels[0]) 
            channelListView?.smoothScrollToPosition(0)
        }
    }

    private fun playPreviousChannel() {
        if (channels.isEmpty()) return
        val currentUrl = player?.currentMediaItem?.localConfiguration?.uri.toString()
        val currentIndex = channels.indexOfFirst { it.url == currentUrl }
        
        if (currentIndex > 0) {
            playChannel(channels[currentIndex - 1])
            channelListView?.smoothScrollToPosition(currentIndex - 1)
        } else if (currentIndex == 0) {
            playChannel(channels[channels.size - 1]) 
            channelListView?.smoothScrollToPosition(channels.size - 1)
        }
    }

    fun savePlaylists() { 
        val prefs = getSharedPreferences("iptv", Context.MODE_PRIVATE).edit()
        
        val jsonArray = org.json.JSONArray()
        for (item in playlists) {
            jsonArray.put(item)
        }
        prefs.putString("list_ordered", jsonArray.toString())
        prefs.apply()
        
        updateDisplayList() 
    }

    fun updateDisplayList() { 
        displayPlaylists.clear()
        displayPlaylists.addAll(playlists)
        playlistAdapter?.notifyDataSetChanged() 
    }

    fun showRenameDialog(p: Int) { val input = EditText(this); input.setText(playlists[p].split("|")[0]); AlertDialog.Builder(this).setView(input).setPositiveButton("Save"){_,_-> playlists[p] = input.text.toString()+"|"+playlists[p].split("|")[1]; savePlaylists(); }.show() }
    fun showAddPlaylistDialog() { 
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        dialogNameInput = EditText(this)
        dialogNameInput?.hint = "Playlist Name"

        dialogUrlInput = EditText(this)
        dialogUrlInput?.hint = "URL or Local File Path"

        val btnBrowse = Button(this)
        btnBrowse.text = "📁 BROWSE LOCAL FILE (.m3u)"
        btnBrowse.setBackgroundColor(Color.parseColor("#444444"))
        btnBrowse.setTextColor(Color.WHITE)
        val btnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnParams.setMargins(0, 20, 0, 20)
        btnBrowse.layoutParams = btnParams

        // Open File Manager when clicked
        btnBrowse.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*")) // Allow all files to easily find .m3u
        }

        layout.addView(dialogNameInput)
        layout.addView(dialogUrlInput)
        layout.addView(btnBrowse)

        AlertDialog.Builder(this)
            .setTitle("Add Playlist")
            .setView(layout)
            .setPositiveButton("Add") { _, _ -> 
                val n = dialogNameInput?.text.toString().trim()
                val u = dialogUrlInput?.text.toString().trim()
                if(n.isNotEmpty() && u.isNotEmpty()){
                    playlists.add("$n|$u")
                    savePlaylists()
                }
            }
            .setNegativeButton("Cancel", null)
            // 🚀 NAYA CODE: Ye bottom-left par set ho jayega
            .setNeutralButton("ADD APP") { _, _ ->
                openAppPicker() // Ye function hum apna Intent Logic chalane ke liye use karenge
            }
            .show() 
    }
    fun openAppPicker() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        // Saari install apps nikalna jo open ho sakti hain
        val installedApps = pm.queryIntentActivities(mainIntent, 0)
        
        // Apps ko A to Z sort karna
        installedApps.sortBy { it.loadLabel(pm).toString().lowercase() }

        // Sirf naam ki list banana UI ke liye
        val appNames = installedApps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select App to Add")
            .setItems(appNames) { _, which ->
                val selectedApp = installedApps[which]
                val appName = selectedApp.loadLabel(pm).toString()
                val packageName = selectedApp.activityInfo.packageName

                // Custom URL scheme "app://" ka use karke save karenge
                // Taaki baad mein player ko pata chale ki ye m3u nahi, app hai
                val appUrl = "app://$packageName"
                
                playlists.add("$appName|$appUrl")
                savePlaylists()
                
                Toast.makeText(this, "$appName added successfully!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
            
    fun showPlaylistOptions(p: Int) {
        val options = arrayOf("Rename", "Edit URL", "Delete")
        
        AlertDialog.Builder(this)
            .setTitle("Choose Option")
            .setItems(options) { _, w ->
                when (w) {
                    0 -> showRenameDialog(p)      
                    1 -> showEditUrlDialog(p)     
                    2 -> {                        
                        playlists.removeAt(p)
                        savePlaylists()
                    }
                }
            }
            .show()
    }

    fun showEditUrlDialog(p: Int) {
        val item = playlists[p]
        val parts = item.split("|")
        val currentName = parts[0]
        val currentUrl = if (parts.size > 1) parts[1] else ""

        val input = EditText(this)
        input.setText(currentUrl)
        input.hint = "Enter New URL"

        AlertDialog.Builder(this)
            .setTitle("Edit URL for: $currentName")
            .setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    playlists[p] = "$currentName|$newUrl"
                    savePlaylists()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun loadPlaylists() { 
        val prefs = getSharedPreferences("iptv", Context.MODE_PRIVATE)
        playlists.clear()
        
        val orderedJson = prefs.getString("list_ordered", null)
        if (orderedJson != null) {
            try {
                val jsonArray = org.json.JSONArray(orderedJson)
                for (i in 0 until jsonArray.length()) {
                    playlists.add(jsonArray.getString(i))
                }
            } catch (e: Exception) { }
        } 
        else {
            val s = prefs.getStringSet("list", null)
            if (s != null) playlists.addAll(s)
        }
        updateDisplayList() 
    }


    override fun onStop() { 
        super.onStop()
        releasePlayer() 
    }

    fun toggleFullscreen() {
        val params = playerContainer?.layoutParams ?: return
        
        if (isFullscreen) { 
            params.height = (250 * resources.displayMetrics.density).toInt()
            channelListView?.visibility = View.VISIBLE
            searchBar?.visibility = View.VISIBLE
            findViewById<View>(R.id.groupFilterBar)?.visibility = View.VISIBLE
            playlistListView?.visibility = View.VISIBLE
            
            showSystemUI()
            updatePlayerMargins(false)
            supportActionBar?.show()
            isFullscreen = false 

            if (TvManager.isTvMode) {
                 releasePlayer()
                 playerContainer?.visibility = View.GONE
                 
                 if (flipper?.displayedChild == 1 && lastPlayedIndex != -1) {
                     channelListView?.post {
                         channelListView?.setSelection(lastPlayedIndex)
                         channelListView?.requestFocus()
                     }
                 }
            }

        } else { 
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            channelListView?.visibility = View.GONE
            searchBar?.visibility = View.GONE
            findViewById<View>(R.id.groupFilterBar)?.visibility = View.GONE
            playlistListView?.visibility = View.GONE
            
            hideSystemUI()
            updatePlayerMargins(true)
            supportActionBar?.hide()
            isFullscreen = true 
        }
        playerContainer?.layoutParams = params
        
        TvManager.handleFullscreenToggle(this, isFullscreen)

        btnFullscreen?.post {
            if (controlsContainer?.visibility == View.VISIBLE) {
                btnFullscreen?.visibility = View.VISIBLE
                btnFullscreen?.alpha = 1.0f 
                // 🔥 FIX: bringToFront() removed to save TV Focus from breaking
            }
            
            if (isFullscreen) {
                btnFullscreen?.text = "X"
            } else {
                btnFullscreen?.text = "[ ]"
            }
            btnFullscreen?.requestLayout()
        }
    }

    inner class ChannelAdapter(context: Context, private val items: ArrayList<Channel>) : ArrayAdapter<Channel>(context, 0, items) { 
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View { 
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_channel, parent, false)
            val channel = items[position]
            val tvName = view.findViewById<TextView>(R.id.tvChannelName)
            val imgLogo = view.findViewById<ImageView>(R.id.imgLogo)
            val imgFav = view.findViewById<ImageView>(R.id.imgFav)
            imgFav.visibility = View.GONE
            
            tvName.text = channel.name
            tvName.setTextColor(Color.WHITE)
            
            if (!channel.logoUrl.isNullOrEmpty()) {
                Glide.with(context)
                    .load(channel.logoUrl)
                    .apply(RequestOptions()
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery) 
                        .diskCacheStrategy(DiskCacheStrategy.ALL)) 
                    .into(imgLogo)
            } else {
                imgLogo.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            
            if (TvManager.isTvMode) {
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                
                if (view is ViewGroup) {
                    view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
                tvName.isFocusable = false
                tvName.isClickable = false
                imgLogo.isFocusable = false
                imgLogo.isClickable = false
                
                view.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) view.setBackgroundColor(Color.parseColor("#888888"))
                    else view.setBackgroundColor(Color.TRANSPARENT)
                }

                view.setOnClickListener {
                     playChannel(channels[position])
                }
                
                view.setOnLongClickListener {
                    if (currentCategory != null) {
                        showCategoryOptions(channels[position])
                    } else {
                        showAddToCategoryDialog(channels[position])
                    }
                    true
                }
            }
            
            return view 
        } 
    }
}