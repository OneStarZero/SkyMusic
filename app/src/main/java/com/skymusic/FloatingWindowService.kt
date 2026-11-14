package com.skymusic

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatView: FrameLayout? = null
    
    private var isPlaying = false
    private var currentIndex = 0
    private var songData = listOf<Pair<Long, Int>>()
    private val keyPositions = mutableListOf<Pair<Float, Float>>()
    private var currentSpeed = 1.0f

    private var currentSongName = ""
    private var currentAuthor = ""
    private var currentDuration = "0:00"
    private var currentNotesCount = 0
    private var currentBpm = "0" 

    private var isMiniMode = true 
    private var isPlayerMode = false 
    
    private var listContainer: LinearLayout? = null
    private var tvStatus: TextView? = null
    private var seekBar: SeekBar? = null
    private var btnPlayPause: Button? = null
    private var tvSpeed: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var playRunnable: Runnable? = null
    
    private val basePath = "/storage/emulated/0/SkyMusic-OneStar"
    private val appSheetsPath = "$basePath/AppSheets"
    private val localSheetsPath = "$basePath/LocalSheets"
    
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var playerParams: WindowManager.LayoutParams

    private var currentPage = 0 
    private var density = 1.0f

    private var cachedSongList: List<Triple<File, List<String>, String>>? = null
    private var refreshSessionId = 0
    
    private val playBgDrawable = GradientDrawable().apply { setColor(Color.parseColor("#10B981")); cornerRadius = 6f }
    private val pauseBgDrawable = GradientDrawable().apply { setColor(Color.parseColor("#EF4444")); cornerRadius = 6f }

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            density = resources.displayMetrics.density
            startForegroundService()
            loadKeyPositions()
            File(appSheetsPath).let { if (!it.exists()) it.mkdirs() }
            File(localSheetsPath).let { if (!it.exists()) it.mkdirs() }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "skymusic_fg"
            val channel = NotificationChannel(channelId, "SkyMusic 运行中", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = Notification.Builder(this, channelId)
                .setContentTitle("SkyMusic 运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true).build()
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isNewData = intent?.getBooleanExtra("is_new_data", false) ?: false
        if (isNewData) {
            intent?.getSerializableExtra("song_data")?.let {
                try {
                    @Suppress("UNCHECKED_CAST")
                    songData = it as ArrayList<Pair<Long, Int>>
                    stopPlayback()
                    currentIndex = 0
                    currentSongName = intent.getStringExtra("song_name") ?: "App 发送谱面"
                    currentAuthor = intent.getStringExtra("song_author") ?: "未知作者"
                    currentNotesCount = songData.size
                    currentDuration = calculateDuration(songData)
                    currentBpm = "0"
                    
                    handler.post {
                        if (floatView == null) createFloatingWindow()
                        if (!isMiniMode && !isPlayerMode) {
                            currentPage = 1
                            showFullPanel(1) 
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        if (floatView == null) createFloatingWindow()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (floatView == null) return

        val dm = resources.displayMetrics
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        panelParams.width = if (isLandscape) (dm.widthPixels * 0.38).toInt() else (dm.widthPixels * 0.55).toInt()
        panelParams.height = if (isLandscape) WindowManager.LayoutParams.WRAP_CONTENT else (dm.heightPixels * 0.45).toInt()
        
        playerParams.width = (dm.widthPixels * 0.45f).toInt()
        if (playerParams.y > dm.heightPixels - 200) playerParams.y = (dm.heightPixels * 0.7).toInt()

        try {
            when {
                isMiniMode -> windowManager.updateViewLayout(floatView, ballParams)
                isPlayerMode -> windowManager.updateViewLayout(floatView, playerParams)
                else -> {
                    floatView?.requestLayout()
                    windowManager.updateViewLayout(floatView, panelParams)
                    showFullPanel(currentPage) 
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun createFloatingWindow() {
        try {
            floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            val dm = resources.displayMetrics

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            panelParams = WindowManager.LayoutParams(
                if (isLandscape) (dm.widthPixels * 0.38).toInt() else (dm.widthPixels * 0.55).toInt(), 
                if (isLandscape) WindowManager.LayoutParams.WRAP_CONTENT else (dm.heightPixels * 0.45).toInt(), 
                layoutType, baseFlags, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = (dm.widthPixels * 0.075).toInt(); y = (dm.heightPixels * 0.2).toInt() }

            ballParams = WindowManager.LayoutParams(60, 60, layoutType, baseFlags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }

            playerParams = WindowManager.LayoutParams((dm.widthPixels * 0.45).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, layoutType, baseFlags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (dm.widthPixels - (dm.widthPixels * 0.45).toInt()) / 2; y = dm.heightPixels - 400 }

            windowManager.addView(floatView, ballParams)
            showMiniBall()
        } catch (e: Exception) { stopSelf() }
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams, isLocked: () -> Boolean = { false }) {
        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f; var isDragging = false
        val touchSlop = 8 * density
        view.setOnTouchListener { v, event ->
            if (isLocked()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY; isDragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) isDragging = true
                        if (isDragging) { params.x = initialX + dx.toInt(); params.y = initialY + dy.toInt(); windowManager.updateViewLayout(floatView, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!isDragging && event.pointerCount == 1) v.performClick(); isDragging = false; true }
                else -> false
            }
        }
    }

    private fun showMiniBall() {
        if (floatView == null) return
        isMiniMode = true; isPlayerMode = false
        try {
            floatView?.removeAllViews()
            val ball = TextView(this).apply { text = "✦"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#FDE047")); setShadowLayer(8f, 0f, 0f, Color.parseColor("#EAB308")) }
            val ballWrapper = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#55FFFFFF")); outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) } }; clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1) }
            ballWrapper.addView(ball); floatView?.addView(ballWrapper)
            enableDrag(ballWrapper, ballParams)
            ballWrapper.setOnClickListener { showFullPanel(currentPage) }
            windowManager.updateViewLayout(floatView, ballParams)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showFullPanel(initialPage: Int = 0) {
        if (floatView == null) return
        isMiniMode = false; isPlayerMode = false
        try {
            floatView?.removeAllViews()
            val dm = resources.displayMetrics
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            panelParams.width = if (isLandscape) (dm.widthPixels * 0.38).toInt() else (dm.widthPixels * 0.55).toInt()
            panelParams.height = if (isLandscape) WindowManager.LayoutParams.WRAP_CONTENT else (dm.heightPixels * 0.45).toInt()

            val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#E6101418")); outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 15f) } }; clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1) }
            val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(15, 15, 15, 15); setBackgroundColor(Color.parseColor("#2D3748")) }
            val titleText = TextView(this).apply { text = if (initialPage == 0) "本地曲库 (按住拖动)" else "App 发送 (按住拖动)"; setTextColor(Color.WHITE); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            val btnRefresh = TextView(this).apply { text = "↻ 刷新"; setTextColor(Color.parseColor("#38BDF8")); textSize = 14f; setPadding(15, 0, 15, 0); setOnClickListener { if (currentPage == 0) { cachedSongList = null; showFullPanel(0) } else { showFullPanel(1) } } }
            val btnClose = TextView(this).apply { text = "✖"; setTextColor(Color.WHITE); textSize = 16f; setPadding(10, 0, 0, 0); setOnClickListener { stopPlayback(); showMiniBall() } }
            topBar.addView(titleText); topBar.addView(btnRefresh); topBar.addView(btnClose)
            rootLayout.addView(topBar)
            enableDrag(topBar, panelParams)
            rootLayout.addView(View(this).apply { setBackgroundColor(Color.parseColor("#334155")); layoutParams = LinearLayout.LayoutParams(-1, 1) })

            val scrollView = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
            listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP; setPadding(10, 10, 10, 10) }
            scrollView.addView(listContainer)
            rootLayout.addView(scrollView)

            val bottomBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#1A1F26")); layoutParams = LinearLayout.LayoutParams(-1, (45 * density).toInt()) }
            fun createNavBtn(icon: String, label: String, onClick: () -> Unit): LinearLayout { return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { onClick() }; addView(TextView(this@FloatingWindowService).apply { text = icon; textSize = 16f; gravity = Gravity.CENTER }); addView(TextView(this@FloatingWindowService).apply { text = label; setTextColor(Color.WHITE); textSize = 10f; gravity = Gravity.CENTER }) } }

            bottomBar.addView(createNavBtn("📂", "本地") { currentPage = 0; showFullPanel(0) }, LinearLayout.LayoutParams(0, -1, 1f))
            bottomBar.addView(createNavBtn("☁️", "发送") { currentPage = 1; showFullPanel(1) }, LinearLayout.LayoutParams(0, -1, 1f))
            bottomBar.addView(createNavBtn("🎯", "校准") { if(checkAccessibility()) { showMiniBall(); startCalibration() } }, LinearLayout.LayoutParams(0, -1, 1f))
            rootLayout.addView(bottomBar)

            floatView?.addView(rootLayout)
            windowManager.updateViewLayout(floatView, panelParams)
            switchPage(initialPage)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun switchPage(page: Int) {
        val container = listContainer ?: return
        container.removeAllViews()

        if (page == 0) {
            if (cachedSongList != null) { renderLocalList(cachedSongList!!); return }
            val loadingTv = TextView(this).apply { text = "正在扫描并解析本地曲库..."; setTextColor(Color.GRAY); textSize = 13f; setPadding(20, 20, 20, 20) }
            container.addView(loadingTv)

            refreshSessionId++
            val currentSession = refreshSessionId
            thread {
                try {
                    val filesList = mutableListOf<File>()
                    File(localSheetsPath).let { if (it.exists()) filesList.addAll(it.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList()) }
                    val songInfoList = filesList.map { file -> Triple(file, readLocalFileInfo(file), "") }
                    cachedSongList = songInfoList
                    handler.post {
                        if (refreshSessionId == currentSession && listContainer != null && !isMiniMode && !isPlayerMode) {
                            listContainer?.removeAllViews()
                            renderLocalList(songInfoList)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } else {
            if (songData.isNotEmpty()) { addSongToList(currentSongName, currentAuthor, currentDuration, currentNotesCount, currentBpm, null) { preparePlay() } } 
            else { container.addView(TextView(this).apply { text = "暂无 App 发送的内存谱面"; setTextColor(Color.GRAY); textSize = 12f; setPadding(10, 10, 10, 10) }) }
        }
    }

    private fun renderLocalList(songInfoList: List<Triple<File, List<String>, String>>) {
        val container = listContainer ?: return
        if (songInfoList.isEmpty()) { container.addView(TextView(this).apply { text = "未找到本地乐谱。\n请将 txt 文件放入：\nSkyMusic-OneStar/LocalSheets/"; setTextColor(Color.GRAY); textSize = 12f; setPadding(10, 10, 10, 10) }); return }
        for ((file, info, _) in songInfoList) { addSongToList(info[0], info[1], info[2], info[3].toInt(), info[5], file) { directLoadLocalFile(file) } }
    }

    private fun addSongToList(songName: String, author: String, duration: String, notes: Int, bpm: String, file: File?, onClick: () -> Unit) {
        val container = listContainer ?: return
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val pad = if (isLandscape) (6 * density).toInt() else (12 * density).toInt()
        val titleSize = if (isLandscape) 14f else 16f
        val subSize = if (isLandscape) 10f else 11f
        val btnSize = if (isLandscape) 15f else 18f

        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((12 * density).toInt(), pad, (12 * density).toInt(), pad); layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val textLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { onClick() } }
        textLayout.addView(TextView(this).apply { text = songName; setTextColor(Color.WHITE); textSize = titleSize; setTypeface(null, android.graphics.Typeface.BOLD); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
        
        val subContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, if (isLandscape) (2 * density).toInt() else (4 * density).toInt(), 0, 0) }
        val speedStr = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        val items = listOf("作者: $author", "音符: $notes", "BPM: $bpm", "时长: $duration", "倍速: $speedStr")
        var currentLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var currentLineWidth = 0f
        val maxWidth = resources.displayMetrics.widthPixels * (if (isLandscape) 0.38f else 0.55f) - (100 * density)

        for (item in items) {
            val tv = TextView(this).apply { text = "$item  ·  "; setTextColor(Color.parseColor("#9CA3AF")); textSize = subSize }
            val width = tv.paint.measureText(tv.text.toString())
            if (currentLineWidth + width > maxWidth && currentLine.childCount > 0) { subContainer.addView(currentLine); currentLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; currentLineWidth = 0f }
            currentLine.addView(tv); currentLineWidth += width
        }
        if (currentLine.childCount > 0) subContainer.addView(currentLine)
        textLayout.addView(subContainer); card.addView(textLayout)

        val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, -1) }
        
        // 垃圾桶逻辑：实体文件弹窗，内存提示
        val btnDelete = TextView(this).apply { 
            text = "🗑️"; textSize = btnSize; setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            setOnClickListener { 
                if (file != null) {
                    val contextWrapper = ContextThemeWrapper(this@FloatingWindowService, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    AlertDialog.Builder(contextWrapper)
                        .setTitle("确认删除")
                        .setMessage("确定要删除该谱面吗？\n\n路径：\n${file.absolutePath}")
                        .setPositiveButton("删除") { _, _ ->
                            if (file.exists() && file.delete()) {
                                Toast.makeText(this@FloatingWindowService, "已删除", Toast.LENGTH_SHORT).show()
                                if (currentPage == 0) { cachedSongList = null; showFullPanel(0) }
                            } else { Toast.makeText(this@FloatingWindowService, "删除失败", Toast.LENGTH_SHORT).show() }
                        }.setNegativeButton("取消", null).create().also { 
                            it.window?.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
                            it.show() 
                        }
                } else {
                    Toast.makeText(this@FloatingWindowService, "该谱面在内存中，退出 App 自动清除", Toast.LENGTH_SHORT).show()
                }
            } 
        }
        btnLayout.addView(btnDelete)
        
        val btnPlay = TextView(this).apply { text = "▶"; textSize = (btnSize + 2f); setTextColor(Color.parseColor("#10B981")); setPadding((10 * density).toInt(), 0, (5 * density).toInt(), 0); setOnClickListener { onClick() } }
        btnLayout.addView(btnPlay)
        
        card.addView(btnLayout); container.addView(card)
    }

    private fun showPlayerPanel() {
        if (floatView == null) return
        isMiniMode = false; isPlayerMode = true
        try {
            floatView?.removeAllViews()
            playerParams.width = (resources.displayMetrics.widthPixels * 0.45).toInt()
            val playerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#EE0F172A")); setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 10f) } }; clipToOutline = true
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            playerLayout.addView(TextView(this).apply { text = "《$currentSongName》"; setTextColor(Color.WHITE); textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
            tvStatus = TextView(this).apply { setTextColor(Color.parseColor("#38BDF8")); textSize = 9f; setPadding(0, 2, 0, 2) }
            playerLayout.addView(tvStatus)
            seekBar = SeekBar(this).apply {
                max = 100; layoutParams = LinearLayout.LayoutParams(-1, (20 * density).toInt())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser && songData.size > 1) { currentIndex = (progress / 100f * (songData.size - 1)).toInt(); updatePlayerUI() } }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { if (isPlaying) { stopPlayback(); togglePlayback() } }
                })
            }
            playerLayout.addView(seekBar)

            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, (28 * density).toInt()) }
            btnPlayPause = Button(this).apply { text = "▶"; textSize = 10f; setTextColor(Color.WHITE); background = playBgDrawable; setOnClickListener { togglePlayback() }; layoutParams = LinearLayout.LayoutParams(0, -1, 0.8f) }
            btnRow.addView(btnPlayPause)

            val speedControl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = GradientDrawable().apply { setColor(Color.parseColor("#33000000")); cornerRadius = 4f }; layoutParams = LinearLayout.LayoutParams(0, -1, 1.4f).apply { setMargins(4, 0, 4, 0) } }
            speedControl.addView(Button(this).apply { text = "-"; textSize = 10f; setTextColor(Color.WHITE); background = null; setOnClickListener { changeSpeed(-0.1f) } }, LinearLayout.LayoutParams((20 * density).toInt(), -1))
            tvSpeed = TextView(this).apply { text = "1.0x"; setTextColor(Color.parseColor("#38BDF8")); textSize = 10f; gravity = Gravity.CENTER }
            speedControl.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))
            speedControl.addView(Button(this).apply { text = "+"; textSize = 10f; setTextColor(Color.WHITE); background = null; setOnClickListener { changeSpeed(0.1f) } }, LinearLayout.LayoutParams((20 * density).toInt(), -1))
            btnRow.addView(speedControl)

            btnRow.addView(Button(this).apply { text = "✖"; textSize = 10f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.parseColor("#EF4444")); cornerRadius = 4f }; setOnClickListener { stopPlayback(); showFullPanel(currentPage) }; layoutParams = LinearLayout.LayoutParams(0, -1, 0.5f) })
            playerLayout.addView(btnRow)
            enableDrag(playerLayout, playerParams) { isPlaying }
            floatView?.addView(playerLayout)
            windowManager.updateViewLayout(floatView, playerParams)
            updatePlayerUI()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun updatePlayerUI() {
        if (!isPlayerMode || floatView == null || seekBar == null || tvStatus == null || btnPlayPause == null) return
        seekBar?.progress = if (songData.size <= 1) 0 else (currentIndex.toFloat() / (songData.size - 1) * 100).toInt()
        val speedStr = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        if (isPlaying) {
            tvStatus?.text = "▶ ${currentIndex + 1}/${songData.size} | $speedStr"
            btnPlayPause?.text = "⏸"; btnPlayPause?.background = pauseBgDrawable
        } else {
            tvStatus?.text = "已暂停 | $speedStr"
            btnPlayPause?.text = "▶"; btnPlayPause?.background = playBgDrawable
        }
    }

    private fun checkAccessibility(): Boolean {
        if (SkyAccessibilityService.instance == null) {
            Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        return true
    }

    // 延迟检查无障碍
    private fun preparePlay() {
        if (!checkAccessibility()) return
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) { Toast.makeText(this, "请先校准键位！", Toast.LENGTH_SHORT).show(); return }
        stopPlayback()
        currentIndex = 0 
        handler.postDelayed({ showPlayerPanel(); updatePlayerUI() }, 50)
    }

    private fun togglePlayback() {
        if (songData.isEmpty()) return
        if (!checkAccessibility()) return
        if (isPlaying) stopPlayback() else {
            if (currentIndex >= songData.size) currentIndex = 0
            startPlayback()
        }
        updatePlayerUI()
    }

    private fun changeSpeed(delta: Float) {
        currentSpeed = (currentSpeed + delta).coerceIn(0.5f, 5.0f)
        currentSpeed = Math.round(currentSpeed * 10f) / 10f
        tvSpeed?.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        handler.postDelayed({ cachedSongList?.let { if (!isMiniMode && !isPlayerMode && currentPage == 0 && listContainer != null) { listContainer?.removeAllViews(); renderLocalList(it) } } }, 100)
        if (isPlaying) { stopPlayback(); startPlayback() }
    }

    private fun startPlayback() {
        if (songData.isEmpty() || currentIndex >= songData.size) return
        isPlaying = true
        playRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying || currentIndex >= songData.size) { resetPlayState(); return }
                try {
                    val (timeMs, keyIndex) = songData[currentIndex]
                    val point = keyPositions.getOrNull(keyIndex) ?: Pair(0f, 0f)
                    if (point.first != -1f) SkyAccessibilityService.instance?.clickPoints(listOf(point))
                    currentIndex++
                    updatePlayerUI() 
                    if (currentIndex < songData.size) {
                        val nextInterval = songData[currentIndex].first - timeMs
                        handler.postDelayed(this, maxOf((nextInterval / currentSpeed).toLong(), 1L))
                    } else { resetPlayState() }
                } catch (e: Exception) { resetPlayState() }
            }
        }
        handler.post(playRunnable!!)
    }

    private fun resetPlayState() {
        stopPlayback(); currentIndex = 0
        handler.post { if (isPlayerMode && seekBar != null) updatePlayerUI() }
    }

    private fun stopPlayback() {
        isPlaying = false
        if (playRunnable != null) { handler.removeCallbacks(playRunnable!!); playRunnable = null }
    }

    private fun readLocalFileInfo(file: File): List<String> {
        try {
            val content = if (file.length() > 50000) file.inputStream().bufferedReader().use { it.readText().take(10000) } else { var text = file.readText(Charsets.UTF_8); if (text.contains("\u0000")) text = file.readText(Charsets.UTF_16LE); text }
            var songName = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringBefore("-")
            var displayAuthor = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
            var bpm = Regex("\"bpm\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: "0"
            if (displayAuthor.isBlank()) displayAuthor = "未知作者"
            if (songName.isBlank()) songName = file.nameWithoutExtension
            val notesCount = Regex("\"time\"\\s*:\\s*\\d+").findAll(content).count()
            val duration = if (notesCount > 0) { val totalSec = (Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content).lastOrNull()?.groupValues?.getOrNull(1) ?: "0").toLong() / 1000; String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60) } else "0:00"
            return listOf(songName, displayAuthor, duration, notesCount.toString(), "", bpm)
        } catch (e: Exception) { return listOf(file.nameWithoutExtension, "未知作者", "0:00", "0", "", "0") }
    }

    private fun calculateDuration(notes: List<Pair<Long, Int>>): String {
        if (notes.isEmpty()) return "0:00"
        return String.format(Locale.getDefault(), "%d:%02d", notes.last().first / 1000 / 60, notes.last().first / 1000 % 60)
    }

    private fun directLoadLocalFile(file: File) {
        thread {
            try {
                var content = file.readText(Charsets.UTF_8)
                if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
                val timeMatches = Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content).map { it.groupValues[1].toLong() }.toList()
                val keyMatches = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"").findAll(content).map { it.groupValues[1].toInt() }.toList()
                if (timeMatches.isEmpty() || keyMatches.isEmpty()) { handler.post { Toast.makeText(this, "读取失败: 空乐谱", Toast.LENGTH_SHORT).show() }; return@thread }
                val notes = mutableListOf<Pair<Long, Int>>()
                for (i in timeMatches.indices) { if (i < keyMatches.size) notes.add(Pair(timeMatches[i], keyMatches[i])) }
                handler.post {
                    songData = notes.sortedBy { it.first }
                    currentIndex = 0
                    currentSongName = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringBefore("-")
                    currentAuthor = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
                    currentBpm = Regex("\"bpm\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: "0"
                    currentNotesCount = songData.size
                    currentDuration = calculateDuration(songData)
                    preparePlay()
                }
            } catch (e: Exception) { handler.post { Toast.makeText(this, "读取异常", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun startCalibration() {
        val dm = resources.displayMetrics
        val dotSize = (38 * density).toInt(); val halfSize = dotSize / 2
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) { for (i in 0 until 15) { keyPositions.add(Pair(dm.widthPixels * ((i % 5) + 1) / 6f, dm.heightPixels * ((i / 5) + 6) / 10f)) } }
        val calibrateViews = mutableListOf<TextView>()
        for (i in 0 until 15) {
            val dot = TextView(this).apply { text = "${i + 1}"; setTextColor(Color.parseColor("#38BDF8")); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#BB0F172A")); setStroke((2 * density).toInt(), Color.parseColor("#38BDF8")) } }
            val params = WindowManager.LayoutParams(dotSize, dotSize, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.START; params.x = keyPositions[i].first.toInt() - halfSize; params.y = keyPositions[i].second.toInt() - halfSize
            dot.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0; private var initialY = 0; private var initialTouchX = 0f; private var initialTouchY = 0f
                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY; return true }
                        MotionEvent.ACTION_MOVE -> { params.x = initialX + (event.rawX - initialTouchX).toInt(); params.y = initialY + (event.rawY - initialTouchY).toInt(); windowManager.updateViewLayout(dot, params); keyPositions[i] = Pair((params.x + halfSize).toFloat(), (params.y + halfSize).toFloat()); return true }
                    }
                    return false
                }
            })
            windowManager.addView(dot, params); calibrateViews.add(dot)
        }
        val saveBtn = Button(this).apply { text = "✅ 保存键位"; setTextColor(Color.WHITE); textSize = 14f; background = GradientDrawable().apply { setColor(Color.parseColor("#10B981")); cornerRadius = 8f }; setOnClickListener { saveKeyPositions(); calibrateViews.forEach { windowManager.removeView(it) }; windowManager.removeView(this); Toast.makeText(this@FloatingWindowService, "键位已保存！", Toast.LENGTH_SHORT).show() } }
        val btnParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        btnParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; btnParams.y = 150; windowManager.addView(saveBtn, btnParams)
    }

    private fun loadKeyPositions() { keyPositions.clear(); val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE); for (i in 0 until 15) { keyPositions.add(Pair(prefs.getFloat("key_${i}_x", -1f), prefs.getFloat("key_${i}_y", -1f))) } }
    private fun saveKeyPositions() { val editor = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE).edit(); for (i in 0 until 15) { editor.putFloat("key_${i}_x", keyPositions[i].first); editor.putFloat("key_${i}_y", keyPositions[i].second) }; editor.apply() }

    override fun onDestroy() {
        super.onDestroy()
        try { stopPlayback(); handler.removeCallbacksAndMessages(null); floatView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }; floatView = null } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}