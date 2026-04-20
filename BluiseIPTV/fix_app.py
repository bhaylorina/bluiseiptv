import os

code = r"""package com.bluise.iptv

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.LruCache
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
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
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import java.net.URL
import java.util.UUID
import java.util.regex.Pattern
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    var flipper: ViewFlipper? = null
    var playerView: PlayerView? = null
    var playlistListView: ListView? = null
    var channelListView: ListView? = null
    var playerContainer: FrameLayout? = null
    
    // UI Buttons
    var btnFullscreen: Button? = null
    var btnScale: Button? = null
    var btnQuality: Button? = null
    var btnBack: Button? = null
    var btnPip: Button? = null
    
    var searchBar: EditText? = null
    var playlistAdapter: ArrayAdapter<String>? = null
    var channelAdapter: ChannelAdapter? = null
    
    var player: ExoPlayer? = null
    val playlists = ArrayList<String>()     
    val displayPlaylists = ArrayList<String>() 
    val channels = ArrayList<Channel>() 
    val allChannels = ArrayList<Channel>()
    
    val favoriteNames = HashSet<String>()
    val favLinksMap = HashMap<String, Channel>()
    
    var isFullscreen = false
    var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    val handler = Handler(Looper.getMainLooper())
    val hideControlsRunnable = Runnable { hideControls() }
    val imageCache = LruCache<String, Bitmap>(50 * 1024 * 1024) 
    val executor = Executors.newFixedThreadPool(4)
    var isShowingFavorites = false

    data class Channel(val name: String, val url: String, val logoUrl: String? = null, val userAgent: String? = null, val cookie: String? = null, val licenseUrl: String? = null, var isFavorite: Boolean = false)

    class LocalClearKeyCallback(private val jsonResponse: ByteArray) : MediaDrmCallback {
        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray { return ByteArray(0) }
        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray { return jsonResponse }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            setContentView(R.layout.activity_main)
            supportActionBar?.hide()
            val prefs = getSharedPreferences("iptv_favs", Context.MODE_PRIVATE)
            val savedSet = prefs.getStringSet("names", HashSet<String>())
            if (savedSet != null) favoriteNames.addAll(savedSet)

            setupUI()
            loadPlaylists()
            refreshFavoritesInBackground()
        } catch (e: Throwable) { }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player != null && player?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerView?.controllerAutoShow = false
            playerView?.hideController()
            hideControls()
            channelListView?.visibility = View.GONE
            searchBar?.visibility = View.GONE
            playerContainer?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            playerView?.controllerAutoShow = false 
            if (!isFullscreen) {
                playerContainer?.layoutParams?.height = (250 * resources.displayMetrics.density).toInt()
                channelListView?.visibility = View.VISIBLE
                searchBar?.visibility = View.VISIBLE
                showSystemUI()
            } else {
                hideSystemUI()
            }
        }
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) { }
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun showSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
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
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnScale = findViewById(R.id.btnScale)
        btnQuality = findViewById(R.id.btnQuality)
        btnBack = findViewById(R.id.btnBackToPlaylists)
        btnPip = findViewById(R.id.btnPip)
        searchBar = findViewById(R.id.searchBar)
        val btnAdd = findViewById<Button>(R.id.btnAddPlaylist)

        playerView?.controllerAutoShow = false 
        playerView?.controllerShowTimeoutMs = 3000
        playerView?.keepScreenOn = true

        playlistAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayPlaylists) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val item = getItem(position) ?: ""
                if (item == "★ Favorites") { view.text = item; view.setTextColor(Color.YELLOW); view.textSize = 20f } 
                else { view.text = item.split("|").firstOrNull() ?: "Unknown"; view.setTextColor(Color.WHITE); view.textSize = 18f }
                view.setPadding(30, 30, 30, 30)
                return view
            }
        }
        playlistListView?.adapter = playlistAdapter
        
        btnAdd?.setOnClickListener { showAddPlaylistDialog() }
        btnPip?.setOnClickListener { enterPipMode() }

        playlistListView?.setOnItemClickListener { _, _, position, _ ->
            val item = displayPlaylists[position]
            if (item == "★ Favorites") loadFavoritesInstantly() else { val url = item.split("|").last(); loadChannels(url) }
        }
        playlistListView?.setOnItemLongClickListener { _, _, position, _ ->
            if (displayPlaylists[position] != "★ Favorites") { val realItem = displayPlaylists[position]; val realIndex = playlists.indexOf(realItem); if (realIndex != -1) showPlaylistOptions(realIndex) }
            true
        }
        searchBar?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterChannels(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) toggleFullscreen() else if (flipper?.displayedChild == 1) { releasePlayer(); flipper?.displayedChild = 0; supportActionBar?.hide(); showSystemUI(); searchBar?.setText(""); isShowingFavorites = false } else finish()
            }
        })
        btnBack?.setOnClickListener { if (isFullscreen) toggleFullscreen() else { releasePlayer(); flipper?.displayedChild = 0; searchBar?.setText(""); isShowingFavorites = false } }
        btnFullscreen?.setOnClickListener { showControls(); toggleFullscreen() }
        
        btnScale?.setOnClickListener { 
            showControls()
            resizeMode = when (resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            playerView?.resizeMode = resizeMode
        }
        
        btnQuality?.setOnClickListener { showControls(); showQualityDialog() }
        playerView?.setOnClickListener { if (btnFullscreen?.visibility == View.VISIBLE) hideControls() else showControls() }
        channelListView?.setOnItemClickListener { _, _, position, _ -> playChannel(channels[position]) }
    }

    fun refreshFavoritesInBackground() {
        if (favoriteNames.isEmpty() || playlists.isEmpty()) return
        thread {
            try {
                for (playlist in playlists) {
                    val url = playlist.split("|").last(); val content = URL(url).readText(); val lines = content.split("\n")
                    var currentName = "Unknown"
                    for (line in lines) {
                        val trim = line.trim()
                        if (trim.startsWith("#EXTINF")) { 
                            if (trim.contains(",")) currentName = trim.substringAfterLast(",").trim() 
                        }
                        else if (!trim.startsWith("#") && trim.isNotEmpty()) { 
                            if (favoriteNames.contains(currentName)) {
                                val ch = Channel(currentName, trim, null, null, null, null, isFavorite = true)
                                favLinksMap[currentName] = ch
                            }
                            currentName = "Unknown"
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun loadFavoritesInstantly() {
        isShowingFavorites = true
        val favList = ArrayList<Channel>()
        for (name in favoriteNames) {
            if (favLinksMap.containsKey(name)) favList.add(favLinksMap[name]!!)
            else favList.add(Channel(name, "", null, null, null, null, isFavorite = true))
        }
        favList.sortBy { it.name }
        allChannels.clear(); allChannels.addAll(favList)
        channels.clear(); channels.addAll(favList)
        channelAdapter = ChannelAdapter(this@MainActivity, channels)
        channelListView?.adapter = channelAdapter
        flipper?.displayedChild = 1 
    }

    inner class ChannelAdapter(context: Context, private val items: ArrayList<Channel>) : ArrayAdapter<Channel>(context, 0, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_channel, parent, false)
            val channel = items[position]
            val tvName = view.findViewById<TextView>(R.id.tvChannelName)
            val imgLogo = view.findViewById<ImageView>(R.id.imgLogo)
            val imgFav = view.findViewById<ImageView>(R.id.imgFav)
            
            tvName.text = channel.name
            
            if (channel.isFavorite) { imgFav.setImageResource(android.R.drawable.star_big_on); tvName.setTextColor(Color.YELLOW) } 
            else { imgFav.setImageResource(android.R.drawable.star_off); tvName.setTextColor(Color.WHITE) }
            imgFav.setOnClickListener { toggleFavorite(channel) }
            imgLogo.setImageResource(android.R.drawable.ic_menu_gallery)
            if (channel.logoUrl != null) {
                val cached = imageCache.get(channel.logoUrl)
                if (cached != null) imgLogo.setImageBitmap(cached)
                else {
                    imgLogo.tag = channel.logoUrl
                    executor.execute {
                        try {
                            val bmp = BitmapFactory.decodeStream(URL(channel.logoUrl).openStream())
                            if (bmp != null) { imageCache.put(channel.logoUrl, bmp); runOnUiThread { if (imgLogo.tag == channel.logoUrl) imgLogo.setImageBitmap(bmp) } }
                        } catch (e: Exception) {}
                    }
                }
            }
            return view
        }
    }

    fun toggleFavorite(channel: Channel) {
        channel.isFavorite = !channel.isFavorite
        if (channel.isFavorite) { favoriteNames.add(channel.name); favLinksMap[channel.name] = channel } 
        else { favoriteNames.remove(channel.name); favLinksMap.remove(channel.name) }
        getSharedPreferences("iptv_favs", Context.MODE_PRIVATE).edit().putStringSet("names", favoriteNames).apply()
        if (!isShowingFavorites) filterChannels(searchBar?.text.toString()) 
        else { if(!channel.isFavorite) { allChannels.remove(channel); channels.remove(channel); channelAdapter?.notifyDataSetChanged() } }
    }

    fun playChannel(channel: Channel) {
        if (channel.url.isEmpty()) return
        try {
            releasePlayer()
            val userAgent = if (channel.userAgent != null && channel.userAgent.isNotEmpty()) channel.userAgent else "BluiseIPTV"
            val headers = HashMap<String, String>()
            headers["User-Agent"] = userAgent
            if (channel.cookie != null) headers["Cookie"] = channel.cookie
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
            var drmSessionManager: DefaultDrmSessionManager? = null
            
            if (channel.licenseUrl != null) {
                val lic = channel.licenseUrl
                if (lic.contains("keyid=") && lic.contains("key=")) {
                     val p = Pattern.compile("([a-fA-F0-9]{32})")
                     val m = p.matcher(lic)
                     val keys = ArrayList<String>()
                     while(m.find()) { keys.add(m.group(1)!!) }
                     if (keys.size >= 2) {
                         val kid = keys[0]; val k = keys[1]
                         val kidB64 = Base64.encodeToString(hexToBytes(kid), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                         val kB64 = Base64.encodeToString(hexToBytes(k), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                         val json = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"$kB64\",\"kid\":\"$kidB64\"}],\"type\":\"temporary\"}"
                         drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true)
                            .build(LocalClearKeyCallback(json.toByteArray()))
                     }
                }
                else if (lic.contains(":") && !lic.startsWith("http")) {
                   try {
                       val parts = lic.split(":")
                       val kidB64 = Base64.encodeToString(hexToBytes(parts[0].trim()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                       val keyB64 = Base64.encodeToString(hexToBytes(parts[1].trim()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                       val json = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"$keyB64\",\"kid\":\"$kidB64\"}],\"type\":\"temporary\"}"
                       drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .setMultiSession(true)
                            .build(LocalClearKeyCallback(json.toByteArray()))
                   } catch (e: Exception) {}
                }
                else if (lic.startsWith("http")) {
                    val drmCallback = HttpMediaDrmCallback(lic, httpFactory)
                    drmCallback.setKeyRequestProperty("User-Agent", userAgent)
                    if (channel.cookie != null) drmCallback.setKeyRequestProperty("Cookie", channel.cookie)
                    drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true)
                        .build(drmCallback)
                } 
            }
            val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
            if (drmSessionManager != null) mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
            val mediaItemBuilder = MediaItem.Builder().setUri(channel.url)
            if (channel.url.contains("extension=ts") || channel.url.endsWith(".ts")) mediaItemBuilder.setMimeType("video/mp2t") 
            else if (!channel.url.contains(".mpd") && !channel.url.contains(".MPD")) mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            val playerBuilder = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory)
            val trackSelector = DefaultTrackSelector(this)
            trackSelector.setParameters(trackSelector.buildUponParameters().setForceHighestSupportedBitrate(true))
            playerBuilder.setTrackSelector(trackSelector)
            player = playerBuilder.build()
            playerView?.player = player
            playerView?.controllerAutoShow = false 
            player?.setMediaItem(mediaItemBuilder.build())
            player?.prepare()
            player?.play()
        } catch (e: Exception) { }
    }

    fun loadChannels(m3uUrl: String) {
        thread {
            try {
                val content = URL(m3uUrl).readText(); val lines = content.split("\n"); val newChannels = ArrayList<Channel>()
                var currentName = "Unknown"; var currentLogo: String? = null
                var currentUa: String? = null; var currentCookie: String? = null; var currentLic: String? = null
                for (line in lines) {
                    val trim = line.trim()
                    if (trim.isEmpty()) continue
                    if (trim.startsWith("#EXTINF")) { 
                        if (trim.contains("tvg-logo=\"")) currentLogo = trim.substringAfter("tvg-logo=\"").substringBefore("\"")
                        if (trim.contains(",")) currentName = trim.substringAfterLast(",").trim() 
                    }
                    else if (trim.contains("keyid=")) { currentLic = trim }
                    else if (trim.contains("license_key")) {
                        var key = trim.substringAfter("=")
                        if (key.contains("http")) key = key.substring(key.indexOf("http"))
                        currentLic = key.trim()
                    }
                    else if (trim.contains(":") && !trim.startsWith("http") && !trim.startsWith("#")) {
                        if (trim.length > 20 && trim.length < 100) currentLic = trim
                    }
                    else if (trim.contains("http-user-agent=")) currentUa = trim.substringAfter("http-user-agent=").trim()
                    else if (trim.contains("\"cookie\":\"")) currentCookie = trim.substringAfter("\"cookie\":\"").substringBefore("\"")
                    else if (!trim.startsWith("#")) { 
                        val isFav = favoriteNames.contains(currentName)
                        newChannels.add(Channel(currentName, trim, currentLogo, currentUa, currentCookie, currentLic, isFavorite=isFav))
                        currentLogo = null; currentName = "Unknown"; currentLic = null; currentUa = null; currentCookie = null
                    }
                }
                runOnUiThread { allChannels.clear(); allChannels.addAll(newChannels); filterChannels(searchBar?.text.toString()); channelAdapter = ChannelAdapter(this@MainActivity, channels); channelListView?.adapter = channelAdapter; flipper?.displayedChild = 1 }
            } catch (e: Exception) { }
        }
    }

    fun filterChannels(query: String) {
        val lower = query.lowercase(); val sorted = ArrayList(allChannels)
        if (!isShowingFavorites) sorted.sortWith(Comparator { c1, c2 -> when { c1.isFavorite && !c2.isFavorite -> -1; !c1.isFavorite && c2.isFavorite -> 1; else -> 0 } })
        channels.clear()
        if (lower.isEmpty()) channels.addAll(sorted) else { for (ch in sorted) { if (ch.name.lowercase().contains(lower)) channels.add(ch) } }
        channelAdapter?.notifyDataSetChanged()
    }

    fun showControls() { btnFullscreen?.visibility = View.VISIBLE; btnScale?.visibility = View.VISIBLE; btnQuality?.visibility = View.VISIBLE; btnBack?.visibility = View.VISIBLE; btnPip?.visibility = View.VISIBLE; handler.removeCallbacks(hideControlsRunnable); handler.postDelayed(hideControlsRunnable, 3000) }
    fun hideControls() { btnFullscreen?.visibility = View.GONE; btnScale?.visibility = View.GONE; btnQuality?.visibility = View.GONE; btnBack?.visibility = View.GONE; btnPip?.visibility = View.GONE }
    fun toggleScale() { showControls(); resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT; playerView?.resizeMode = resizeMode }
    
    fun toggleFullscreen() {
        val params = playerContainer?.layoutParams ?: return
        if (isFullscreen) { 
            params.height = (250 * resources.displayMetrics.density).toInt()
            channelListView?.visibility = View.VISIBLE
            searchBar?.visibility = View.VISIBLE
            btnFullscreen?.text = "⛶"
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            showSystemUI()
            supportActionBar?.show()
            isFullscreen = false 
        } else { 
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            channelListView?.visibility = View.GONE
            searchBar?.visibility = View.GONE
            btnFullscreen?.text = "X"
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemUI()
            supportActionBar?.hide()
            isFullscreen = true 
        }
        playerContainer?.layoutParams = params
    }
    
    fun showQualityDialog() { if (player != null) TrackSelectionDialogBuilder(this, "Quality", player!!, C.TRACK_TYPE_VIDEO).build().show() }
    fun releasePlayer() { if (player != null) { player?.release(); player = null }; handler.removeCallbacks(hideControlsRunnable) }
    fun hexToBytes(s: String): ByteArray { val len = s.length; val data = ByteArray(len / 2); var i = 0; while (i < len) { data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte(); i += 2 }; return data }
    fun savePlaylists() { getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().putStringSet("list", playlists.toMutableSet()).apply(); updateDisplayList() }
    fun loadPlaylists() { val s = getSharedPreferences("iptv", Context.MODE_PRIVATE).getStringSet("list", null); playlists.clear(); if (s != null) playlists.addAll(s); updateDisplayList() }
    fun updateDisplayList() { displayPlaylists.clear(); displayPlaylists.add("★ Favorites"); displayPlaylists.addAll(playlists); playlistAdapter?.notifyDataSetChanged() }
    
    fun showRenameDialog(p: Int) { 
        val input = EditText(this); input.setText(playlists[p].split("|")[0])
        val builder = AlertDialog.Builder(this)
        builder.setView(input)
        builder.setPositiveButton("Save", DialogInterface.OnClickListener { _, _ -> playlists[p] = input.text.toString()+"|"+playlists[p].split("|")[1]; savePlaylists() })
        builder.show() 
    }
    
    fun showAddPlaylistDialog() { 
        val l=LinearLayout(this);l.orientation=1;val n=EditText(this);n.hint="Name";val u=EditText(this);u.hint="URL";l.addView(n);l.addView(u)
        val builder = AlertDialog.Builder(this)
        builder.setView(l)
        builder.setPositiveButton("Add", DialogInterface.OnClickListener { _, _ -> if(n.text.isNotEmpty()&&u.text.isNotEmpty()){playlists.add(n.text.toString()+"|"+u.text.toString());savePlaylists()} })
        builder.show() 
    }
    
    fun showPlaylistOptions(p: Int) { 
        val builder = AlertDialog.Builder(this)
        builder.setItems(arrayOf("Rename","Delete"), DialogInterface.OnClickListener { _, w -> if(w==0)showRenameDialog(p)else{playlists.removeAt(p);savePlaylists()} })
        builder.show() 
    }
    
    override fun onStop() { super.onStop(); releasePlayer() }
}
"""

path = "/data/data/com.termux/files/home/BluiseIPTV/src/main/java/com/bluise/iptv/MainActivity.kt"
with open(path, "w") as f:
    f.write(code)

print("FILE CREATED SUCCESSFULLY!")
