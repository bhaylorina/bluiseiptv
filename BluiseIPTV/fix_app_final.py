import os

code = r"""package com.bluise.iptv

import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    var flipper: ViewFlipper? = null
    var playerView: PlayerView? = null
    var playlistListView: ListView? = null
    var channelListView: ListView? = null
    var playerContainer: FrameLayout? = null
    
    // Controls
    var btnFullscreen: Button? = null
    var btnScale: Button? = null
    var btnQuality: Button? = null
    var btnBack: Button? = null
    
    var searchBar: EditText? = null
    var playlistAdapter: ArrayAdapter<String>? = null
    var channelAdapter: ChannelAdapter? = null
    
    var player: ExoPlayer? = null
    val playlists = ArrayList<String>()
    
    // Lists
    val channels = ArrayList<Channel>() 
    val allChannels = ArrayList<Channel>()
    val favoriteNames = HashSet<String>()
    
    var isFullscreen = false
    var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    val handler = Handler(Looper.getMainLooper())
    val hideControlsRunnable = Runnable { hideControls() }
    
    // Image Cache
    val imageCache = LruCache<String, Bitmap>(50 * 1024 * 1024) 
    val executor = Executors.newFixedThreadPool(4)

    data class Channel(
        val name: String, 
        val url: String,
        val logoUrl: String? = null,
        val userAgent: String? = null,
        val cookie: String? = null,
        val licenseUrl: String? = null,
        var isFavorite: Boolean = false
    )

    class LocalClearKeyCallback(private val jsonResponse: ByteArray) : MediaDrmCallback {
        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray { return ByteArray(0) }
        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray { return jsonResponse }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        try {
            setContentView(R.layout.activity_main)
            supportActionBar?.hide()

            // FIX: Explicitly tell Kotlin this is a Set of Strings
            val prefs = getSharedPreferences("iptv_favs", Context.MODE_PRIVATE)
            val savedSet = prefs.getStringSet("names", HashSet<String>())
            if (savedSet != null) {
                favoriteNames.addAll(savedSet)
            }

            flipper = findViewById(R.id.viewFlipper)
            playerView = findViewById(R.id.playerView)
            playlistListView = findViewById(R.id.playlistListView)
            channelListView = findViewById(R.id.channelListView)
            playerContainer = findViewById(R.id.playerContainer)
            
            btnFullscreen = findViewById(R.id.btnFullscreen)
            btnScale = findViewById(R.id.btnScale)
            btnQuality = findViewById(R.id.btnQuality)
            btnBack = findViewById(R.id.btnBackToPlaylists)
            searchBar = findViewById(R.id.searchBar)
            val btnAdd = findViewById<Button>(R.id.btnAddPlaylist)

            loadPlaylists()
            
            playlistAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, playlists) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    val fullString = getItem(position) ?: ""
                    view.text = fullString.split("|").firstOrNull() ?: "Unknown"
                    view.setTextColor(Color.WHITE)
                    view.textSize = 18f
                    return view
                }
            }
            playlistListView?.adapter = playlistAdapter
            
            btnAdd?.setOnClickListener { showAddPlaylistDialog() }

            playlistListView?.setOnItemClickListener { _, _, position, _ ->
                val url = playlists[position].split("|").last()
                loadChannels(url)
            }
            
            playlistListView?.setOnItemLongClickListener { _, _, position, _ ->
                showPlaylistOptions(position)
                true
            }

            searchBar?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterChannels(s.toString())
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFullscreen) {
                        toggleFullscreen()
                    } else if (flipper?.displayedChild == 1) {
                        releasePlayer()
                        flipper?.displayedChild = 0
                        supportActionBar?.hide()
                        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
                        searchBar?.setText("")
                    } else {
                        finish()
                    }
                }
            })

            btnBack?.setOnClickListener {
                if (isFullscreen) toggleFullscreen() else {
                    releasePlayer()
                    flipper?.displayedChild = 0
                    searchBar?.setText("")
                }
            }

            btnFullscreen?.setOnClickListener { showControls(); toggleFullscreen() }
            btnScale?.setOnClickListener { showControls(); toggleScale() }
            btnQuality?.setOnClickListener { showControls(); showQualityDialog() }
            playerView?.setOnClickListener { if (btnFullscreen?.visibility == View.VISIBLE) hideControls() else showControls() }

            channelListView?.setOnItemClickListener { _, _, position, _ ->
                playChannel(channels[position])
            }
            
        } catch (e: Throwable) {
            Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    inner class ChannelAdapter(context: Context, private val items: ArrayList<Channel>) : ArrayAdapter<Channel>(context, 0, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_channel, parent, false)
            val channel = items[position]
            
            val tvName = view.findViewById<TextView>(R.id.tvChannelName)
            val imgLogo = view.findViewById<ImageView>(R.id.imgLogo)
            val imgFav = view.findViewById<ImageView>(R.id.imgFav)
            
            tvName.text = channel.name
            
            if (channel.isFavorite) {
                imgFav.setImageResource(android.R.drawable.star_big_on)
                tvName.setTextColor(Color.YELLOW)
            } else {
                imgFav.setImageResource(android.R.drawable.star_off)
                tvName.setTextColor(Color.WHITE)
            }
            
            imgFav.setOnClickListener { toggleFavorite(channel) }

            imgLogo.setImageResource(android.R.drawable.ic_menu_gallery)
            if (channel.logoUrl != null) {
                val cached = imageCache.get(channel.logoUrl)
                if (cached != null) {
                    imgLogo.setImageBitmap(cached)
                } else {
                    imgLogo.tag = channel.logoUrl
                    executor.execute {
                        try {
                            val bmp = BitmapFactory.decodeStream(URL(channel.logoUrl).openStream())
                            if (bmp != null) {
                                imageCache.put(channel.logoUrl, bmp)
                                runOnUiThread { 
                                    if (imgLogo.tag == channel.logoUrl) imgLogo.setImageBitmap(bmp) 
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
            return view
        }
    }

    fun toggleFavorite(channel: Channel) {
        channel.isFavorite = !channel.isFavorite
        if (channel.isFavorite) favoriteNames.add(channel.name) else favoriteNames.remove(channel.name)
        getSharedPreferences("iptv_favs", Context.MODE_PRIVATE).edit().putStringSet("names", favoriteNames).apply()
        sortAndRefresh()
    }
    
    fun sortAndRefresh() { filterChannels(searchBar?.text.toString()) }

    fun filterChannels(query: String) {
        val lower = query.lowercase()
        val sorted = ArrayList(allChannels)
        sorted.sortWith(Comparator { c1, c2 ->
            when {
                c1.isFavorite && !c2.isFavorite -> -1
                !c1.isFavorite && c2.isFavorite -> 1
                else -> 0
            }
        })
        channels.clear()
        if (lower.isEmpty()) channels.addAll(sorted)
        else {
            for (ch in sorted) {
                if (ch.name.lowercase().contains(lower)) channels.add(ch)
            }
        }
        channelAdapter?.notifyDataSetChanged()
    }

    fun playChannel(channel: Channel) {
        try {
            releasePlayer()
            val headers = HashMap<String, String>()
            if (channel.userAgent != null) headers["User-Agent"] = channel.userAgent
            if (channel.cookie != null) headers["Cookie"] = channel.cookie
            if (!headers.containsKey("User-Agent")) headers["User-Agent"] = "BluiseIPTV"

            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"]!!)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)

            var drmSessionManager: DefaultDrmSessionManager? = null
            if (channel.licenseUrl != null && channel.licenseUrl.contains(":")) {
               try {
                   val parts = channel.licenseUrl.split(":")
                   val kidB64 = Base64.encodeToString(hexToBytes(parts[0].trim()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                   val keyB64 = Base64.encodeToString(hexToBytes(parts[1].trim()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                   val json = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"" + keyB64 + "\",\"kid\":\"" + kidB64 + "\"}],\"type\":\"temporary\"}"
                   drmSessionManager = DefaultDrmSessionManager.Builder()
                       .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                       .setMultiSession(true)
                       .build(LocalClearKeyCallback(json.toByteArray()))
               } catch (e: Exception) {}
            }

            val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
            if (drmSessionManager != null) mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }

            val mediaItemBuilder = MediaItem.Builder().setUri(channel.url)
            if (channel.url.contains(".ts")) { } 
            else if (!channel.url.contains(".mpd") && !channel.url.contains(".MPD")) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            
            val playerBuilder = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory)
            val trackSelector = DefaultTrackSelector(this)
            trackSelector.setParameters(trackSelector.buildUponParameters().setForceHighestSupportedBitrate(true))
            playerBuilder.setTrackSelector(trackSelector)
            
            player = playerBuilder.build()
            playerView?.player = player
            player?.setMediaItem(mediaItemBuilder.build())
            player?.prepare()
            player?.play()
            showControls()
        } catch (e: Exception) { Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_SHORT).show() }
    }

    fun loadChannels(m3uUrl: String) {
        Toast.makeText(this, "Loading...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val content = URL(m3uUrl).readText()
                val lines = content.split("\n")
                val newChannels = ArrayList<Channel>()
                var currentName = "Unknown"
                var currentLogo: String? = null
                var currentUserAgent: String? = null
                var currentCookie: String? = null
                var currentLicense: String? = null
                
                for (line in lines) {
                    val trim = line.trim()
                    if (trim.startsWith("#EXTINF")) { 
                        if (trim.contains("tvg-logo=\"")) currentLogo = trim.substringAfter("tvg-logo=\"").substringBefore("\"")
                        if (trim.contains(",")) currentName = trim.substringAfterLast(",").trim() 
                    }
                    else if (trim.contains("http-user-agent=")) currentUserAgent = trim.substringAfter("http-user-agent=").trim()
                    else if (trim.contains("\"cookie\":\"")) currentCookie = trim.substringAfter("\"cookie\":\"").substringBefore("\"")
                    else if (trim.contains("license_key=")) { var key = trim.substringAfter("license_key="); if (key.contains("|")) key = key.substringBefore("|"); currentLicense = key }
                    else if (!trim.startsWith("#") && trim.isNotEmpty()) { 
                        val isFav = favoriteNames.contains(currentName)
                        newChannels.add(Channel(currentName, trim, currentLogo, currentUserAgent, currentCookie, currentLicense, isFavorite=isFav))
                        currentLogo = null
                        currentName = "Unknown"
                    }
                }
                runOnUiThread {
                    allChannels.clear(); allChannels.addAll(newChannels)
                    sortAndRefresh()
                    channelAdapter = ChannelAdapter(this@MainActivity, channels)
                    channelListView?.adapter = channelAdapter
                    flipper?.displayedChild = 1 
                }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Error loading", Toast.LENGTH_SHORT).show() } }
        }
    }

    fun showControls() {
        btnFullscreen?.visibility = View.VISIBLE; btnScale?.visibility = View.VISIBLE; btnQuality?.visibility = View.VISIBLE; btnBack?.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable); handler.postDelayed(hideControlsRunnable, 3000)
    }
    
    fun hideControls() { 
        btnFullscreen?.visibility = View.GONE; btnScale?.visibility = View.GONE; btnQuality?.visibility = View.GONE; btnBack?.visibility = View.GONE 
    }
    
    fun toggleScale() {
        showControls()
        resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView?.resizeMode = resizeMode
    }
    
    fun toggleFullscreen() {
        val params = playerContainer?.layoutParams ?: return
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            params.height = (250 * resources.displayMetrics.density).toInt()
            channelListView?.visibility = View.VISIBLE
            searchBar?.visibility = View.VISIBLE 
            btnFullscreen?.text = "⛶"
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            supportActionBar?.show() 
            isFullscreen = false
        } else {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            channelListView?.visibility = View.GONE
            searchBar?.visibility = View.GONE 
            btnFullscreen?.text = "X"
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            supportActionBar?.hide() 
            isFullscreen = true
        }
        playerContainer?.layoutParams = params
    }
    
    fun showQualityDialog() { if (player != null) TrackSelectionDialogBuilder(this, "Quality", player!!, C.TRACK_TYPE_VIDEO).build().show() }
    
    fun releasePlayer() {
        if (player != null) { player?.release(); player = null }
        handler.removeCallbacks(hideControlsRunnable)
    }

    fun hexToBytes(s: String): ByteArray { val len = s.length; val data = ByteArray(len / 2); var i = 0; while (i < len) { data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte(); i += 2 }; return data }
    fun savePlaylists() { getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().putStringSet("list", playlists.toMutableSet()).apply() }
    fun loadPlaylists() { val s = getSharedPreferences("iptv", Context.MODE_PRIVATE).getStringSet("list", null); if (s != null) { playlists.clear(); playlists.addAll(s) } }
    fun showRenameDialog(p: Int) { val input = EditText(this); input.setText(playlists[p].split("|")[0]); AlertDialog.Builder(this).setView(input).setPositiveButton("Save"){_,_-> playlists[p] = input.text.toString()+"|"+playlists[p].split("|")[1]; savePlaylists(); playlistAdapter?.notifyDataSetChanged()}.show() }
    fun showAddPlaylistDialog() { val l=LinearLayout(this);l.orientation=1;val n=EditText(this);n.hint="Name";val u=EditText(this);u.hint="URL";l.addView(n);l.addView(u);AlertDialog.Builder(this).setView(l).setPositiveButton("Add"){_,_->if(n.text.isNotEmpty()&&u.text.isNotEmpty()){playlists.add(n.text.toString()+"|"+u.text.toString());savePlaylists();playlistAdapter?.notifyDataSetChanged()}}.show() }
    fun showPlaylistOptions(p: Int) { AlertDialog.Builder(this).setItems(arrayOf("Rename","Delete")){_,w->if(w==0)showRenameDialog(p)else{playlists.removeAt(p);savePlaylists();playlistAdapter?.notifyDataSetChanged()}}.show() }
    override fun onStop() { super.onStop(); releasePlayer() }
}
"""

with open("src/main/java/com/bluise/iptv/MainActivity.kt", "w") as f:
    f.write(code)

print("✅ FINAL FIX APPLIED: Type Error Resolved!")
