package com.skymusic

import android.app.Service
import android.content.Context
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
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: FrameLayout
    
    private var isPlaying = false
    private var currentIndex = 0
    private var songData = listOf<Pair<Long, Int>>()
    private val keyPositions = mutableListOf<Pair<Float, Float>>()
    private var currentSpeed = 1.0f

    private var currentSongName = ""
    private var currentAuthor = ""
    private var currentDuration = "0:00"
    private var currentNotesCount = 0
    private var currentTune = "未知调"
    private var currentBpm = "0" 

    private var isMiniMode = true 
    private var isPlayerMode = false 
    
    private lateinit var listContainer: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var tvSpeed: TextView

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

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        loadKeyPositions()
        File(appSheetsPath).let { if (!it.exists()) it.mkdirs() }
        File(localSheetsPath).let { if (!it.exists()) it.mkdirs() }
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
                    currentTune = intent.getStringExtra("song_tune") ?: "未知调"
                    currentBpm = intent.getStringExtra("song_bpm") ?: "0"
                    
                    if (!this::floatView.isInitialized) createFloatingWindow()
                    if (!isMiniMode && !isPlayerMode) {
                        currentPage = 1
                        showFullPanel(1) 
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (!this::floatView.isInitialized) createFloatingWindow()
        return START_STICKY
    }

    private fun createFloatingWindow() {
        try {
            floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            val dm = resources.displayMetrics

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val panelHeightRatio = if (isLandscape) 0.75f else 0.45f

            panelParams = WindowManager.LayoutParams(
                (dm.widthPixels * 0.85).toInt(), (dm.heightPixels * panelHeightRatio).toInt(), 
                layoutType, baseFlags, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            ballParams = WindowManager.LayoutParams(
                60, 60, layoutType, baseFlags, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }

            playerParams = WindowManager.LayoutParams(
                (dm.widthPixels * 0.85).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, 
                layoutType, baseFlags, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.START; x = 50; y = 100 }

            windowManager.addView(floatView, ballParams)
            showMiniBall()
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = 8 * density

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (!isDragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = initialX + deltaX.toInt()
                            params.y = initialY + deltaY.toInt()
                            windowManager.updateViewLayout(floatView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && event.pointerCount == 1) {
                        v.performClick() 
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun showMiniBall() {
        if (!this::floatView.isInitialized) return
        isMiniMode = true; isPlayerMode = false
        floatView.removeAllViews()
        
        val ball = TextView(this).apply {
            text = "✦"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FDE047"))
            setShadowLayer(8f, 0f, 0f, Color.parseColor("#EAB308")) 
        }
        val ballWrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#55FFFFFF")) 
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        ballWrapper.addView(ball)
        floatView.addView(ballWrapper)
        
        enableDrag(ballWrapper, ballParams)
        ballWrapper.setOnClickListener { showFullPanel(currentPage) }
        windowManager.updateViewLayout(floatView, ballParams)
    }

    private fun showFullPanel(initialPage: Int = 0) {
        if (!this::floatView.isInitialized) return
        isMiniMode = false; isPlayerMode = false
        floatView.removeAllViews()
        
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val panelHeightRatio = if (isLandscape) 0.75f else 0.45f
        panelParams.height = (resources.displayMetrics.heightPixels * panelHeightRatio).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6101418"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 15f) }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(15, 15, 15, 15)
            setBackgroundColor(Color.parseColor("#2D3748")) 
        }
        
        titleText = TextView(this).apply {
            text = if (initialPage == 0) "本地曲库 (按住此处拖动)" else "App 发送 (按住此处拖动)"
            setTextColor(Color.WHITE); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        
        val btnRefresh = TextView(this).apply {
            text = "↻ 刷新"; setTextColor(Color.parseColor("#38BDF8")); textSize = 14f; setPadding(15, 0, 15, 0)
            setOnClickListener { 
                if (currentPage == 0) {
                    cachedSongList = null
                    showFullPanel(0)
                } else {
                    showFullPanel(1)
                }
            } 
        }
        
        val btnClose = TextView(this).apply {
            text = "✖"; setTextColor(Color.WHITE); textSize = 16f; setPadding(10, 0, 0, 0)
            setOnClickListener { stopPlayback(); showMiniBall() }
        }
        topBar.addView(titleText); topBar.addView(btnRefresh); topBar.addView(btnClose)
        rootLayout.addView(topBar)

        enableDrag(topBar, panelParams)

        rootLayout.addView(View(this).apply { setBackgroundColor(Color.parseColor("#334155")); layoutParams = LinearLayout.LayoutParams(-1, 1) })

        val scrollView = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10) }
        scrollView.addView(listContainer)
        rootLayout.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBarHeight = (45 * density).toInt()
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1F26"))
            layoutParams = LinearLayout.LayoutParams(-1, bottomBarHeight)
        }
        
        fun createNavBtn(icon: String, label: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setOnClickListener { onClick() }
                addView(TextView(this@FloatingWindowService).apply { text = icon; textSize = 16f; gravity = Gravity.CENTER })
                addView(TextView(this@FloatingWindowService).apply { text = label; setTextColor(Color.WHITE); textSize = 10f; gravity = Gravity.CENTER })
            }
        }

        val btnNavLocal = createNavBtn("📂", "本地") { currentPage = 0; showFullPanel(0) }
        val btnNavApp = createNavBtn("☁️", "发送") { currentPage = 1; showFullPanel(1) }
        val btnNavSet = createNavBtn("🎯", "校准") { if(checkAccessibility()) { showMiniBall(); startCalibration() } }

        bottomBar.addView(btnNavLocal, LinearLayout.LayoutParams(0, -1, 1f))
        bottomBar.addView(btnNavApp, LinearLayout.LayoutParams(0, -1, 1f))
        bottomBar.addView(btnNavSet, LinearLayout.LayoutParams(0, -1, 1f))
        rootLayout.addView(bottomBar)

        floatView.addView(rootLayout)
        windowManager.updateViewLayout(floatView, panelParams)

        switchPage(initialPage)
    }

    private fun switchPage(page: Int) {
        if (!this::listContainer.isInitialized) return 
        listContainer.removeAllViews()

        if (page == 0) {
            if (cachedSongList != null) {
                renderLocalList(cachedSongList!!)
                return
            }

            val loadingTv = TextView(this).apply {
                text = "正在扫描并解析本地曲库..." 
                setTextColor(Color.GRAY); textSize = 13f; setPadding(20, 20, 20, 20)
            }
            listContainer.addView(loadingTv)

            refreshSessionId++
            val currentSession = refreshSessionId

            thread {
                try {
                    val filesList = mutableListOf<File>()
                    val localFolder = File(localSheetsPath)
                    if (localFolder.exists()) filesList.addAll(localFolder.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList())
                    val appFolder = File(appSheetsPath)
                    if (appFolder.exists()) filesList.addAll(appFolder.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList())

                    val songInfoList = filesList.map { file ->
                        val info = readLocalFileInfo(file)
                        Triple(file, info, "")
                    }
                    
                    cachedSongList = songInfoList

                    handler.post {
                        if (refreshSessionId == currentSession && this::listContainer.isInitialized && !isMiniMode && !isPlayerMode) {
                            listContainer.removeAllViews()
                            renderLocalList(songInfoList)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            if (songData.isEmpty()) {
                listContainer.addView(TextView(this).apply { text = "暂无 App 发送的谱面"; setTextColor(Color.GRAY); textSize = 12f; setPadding(10, 10, 10, 10) })
            } else {
                addSongToList(currentSongName, currentAuthor, currentDuration, currentNotesCount, currentTune, currentBpm) { preparePlay() }
            }
        }
    }

    private fun renderLocalList(songInfoList: List<Triple<File, List<String>, String>>) {
        if (!this::listContainer.isInitialized) return
        if (songInfoList.isEmpty()) {
            listContainer.addView(TextView(this).apply { 
                text = "未找到乐谱。\n请将 txt 文件放入：\nSkyMusic-OneStar/LocalSheets/"
                setTextColor(Color.GRAY); textSize = 12f; setPadding(10, 10, 10, 10) 
            })
            return
        }
        for ((file, info, _) in songInfoList) {
            addSongToList(info[0], info[1], info[2], info[3].toInt(), info[4], info[5]) { directLoadLocalFile(file) }
        }
    }

    private fun addSongToList(songName: String, author: String, duration: String, notes: Int, tune: String, bpm: String, onClick: () -> Unit) {
        if (!this::listContainer.isInitialized) return

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val padTopBottom = if (isLandscape) (6 * density).toInt() else (12 * density).toInt()
        val titleSize = if (isLandscape) 14f else 16f
        val subSize = if (isLandscape) 10f else 11f
        val btnSize = if (isLandscape) 15f else 18f

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), padTopBottom, (12 * density).toInt(), padTopBottom)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { onClick() }
        }
        
        textLayout.addView(TextView(this).apply { 
            text = songName
            setTextColor(Color.WHITE); textSize = titleSize; setTypeface(null, android.graphics.Typeface.BOLD); 
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END 
        })
        
        // 核心修复：直接使用系统原生的 GridLayout 自动排版，它能自适应横竖屏的宽度
        val gridLayout = GridLayout(this).apply {
            columnCount = 3 // 默认排 3 列，自动折行
            setPadding(0, if (isLandscape) (2 * density).toInt() else (4 * density).toInt(), 0, 0)
        }
        
        val speedStr = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        val items = listOf(
            "作者: $author", "音符: $notes", "BPM: $bpm", "时长: $duration", "调性: $tune", "倍速: $speedStr"
        )

        for (item in items) {
            val tv = TextView(this).apply {
                text = "$item  "
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = subSize
                layoutParams = GridLayout.LayoutParams().apply {
                    width = GridLayout.LayoutParams.WRAP_CONTENT
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                }
            }
            gridLayout.addView(tv)
        }

        textLayout.addView(gridLayout)
        card.addView(textLayout)

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, -1)
        }
        
        val btnDelete = TextView(this).apply { 
            text = "🗑️"; textSize = btnSize; setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            setOnClickListener { Toast.makeText(this@FloatingWindowService, "请在文件管理器中删除", Toast.LENGTH_SHORT).show() } 
        }
        btnLayout.addView(btnDelete)
        
        val btnPlay = TextView(this).apply { 
            text = "▶"; textSize = (btnSize + 2f); setTextColor(Color.parseColor("#10B981")); setPadding((10 * density).toInt(), 0, (5 * density).toInt(), 0)
            setOnClickListener { onClick() } 
        }
        btnLayout.addView(btnPlay)
        
        card.addView(btnLayout)
        listContainer.addView(card)
    }

    private fun showPlayerPanel() {
        if (!this::floatView.isInitialized) return
        isMiniMode = false; isPlayerMode = true
        floatView.removeAllViews()

        val playerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0F172A"))
            setPadding(15, 10, 15, 10)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 15f) }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val topBar = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL 
            setPadding(5, 5, 5, 5)
            setBackgroundColor(Color.parseColor("#2D3748")) 
        }
        val titleTv = TextView(this).apply {
            text = "《$currentSongName》 (按住拖动)"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        topBar.addView(titleTv)
        playerLayout.addView(topBar)

        enableDrag(topBar, playerParams)

        tvStatus = TextView(this).apply { setTextColor(Color.parseColor("#38BDF8")); textSize = 10f; setPadding(0, 5, 0, 2) }
        playerLayout.addView(tvStatus)

        seekBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && songData.size > 1) { currentIndex = (progress / 100f * (songData.size - 1)).toInt(); updatePlayerUI() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { if (isPlaying) { stopPlayback(); togglePlayback() } }
            })
        }
        playerLayout.addView(seekBar)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, (35 * density).toInt()) }
        
        btnPlayPause = Button(this).apply {
            text = "▶"; textSize = 13f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#10B981")); cornerRadius = 8f }
            setOnClickListener { togglePlayback() }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        btnRow.addView(btnPlayPause)

        val speedControl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#33000000")); cornerRadius = 8f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1.6f).apply { setMargins(8, 0, 8, 0) }
        }
        val btnSpeedDown = Button(this).apply { text = "-"; textSize = 12f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }; setOnClickListener { changeSpeed(-0.1f) } }
        tvSpeed = TextView(this).apply { text = "1.0x"; setTextColor(Color.parseColor("#38BDF8")); textSize = 11f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD) }
        val btnSpeedUp = Button(this).apply { text = "+"; textSize = 12f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }; setOnClickListener { changeSpeed(0.1f) } }
        
        speedControl.addView(btnSpeedDown, LinearLayout.LayoutParams((30 * density).toInt(), -1))
        speedControl.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))
        speedControl.addView(btnSpeedUp, LinearLayout.LayoutParams((30 * density).toInt(), -1))
        btnRow.addView(speedControl)

        val btnClose = Button(this).apply {
            text = "✖"; textSize = 12f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#EF4444")); cornerRadius = 8f }
            setOnClickListener { stopPlayback(); showFullPanel(currentPage) }
            layoutParams = LinearLayout.LayoutParams(0, -1, 0.6f)
        }
        btnRow.addView(btnClose)
        playerLayout.addView(btnRow)

        floatView.addView(playerLayout)
        windowManager.updateViewLayout(floatView, playerParams)
        
        updatePlayerUI()
    }

    private fun updatePlayerUI() {
        if (!isPlayerMode || !this::floatView.isInitialized) return
        val progressPercent = if (songData.size <= 1) 0 else (currentIndex.toFloat() / (songData.size - 1) * 100).toInt()
        seekBar.progress = progressPercent
        
        val speedStr = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        if (isPlaying) {
            tvStatus.text = "播放: ${currentIndex + 1} / ${songData.size}  |  $speedStr"
            btnPlayPause.text = "⏸"
            (btnPlayPause.background as GradientDrawable).setColor(Color.parseColor("#EF4444"))
        } else {
            tvStatus.text = "已暂停  |  $speedStr"
            btnPlayPause.text = "▶"
            (btnPlayPause.background as GradientDrawable).setColor(Color.parseColor("#10B981"))
        }
    }

    private fun checkAccessibility(): Boolean {
        if (SkyAccessibilityService.instance == null) {
            Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return false
        }
        return true
    }

    private fun preparePlay() {
        if (!checkAccessibility()) return
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            Toast.makeText(this, "请先校准键位！", Toast.LENGTH_SHORT).show()
            return
        }
        
        stopPlayback()
        currentIndex = 0 
        
        handler.postDelayed({
            showPlayerPanel() 
            updatePlayerUI()  
        }, 50)
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
        currentSpeed += delta
        currentSpeed = currentSpeed.coerceIn(0.5f, 5.0f)
        currentSpeed = Math.round(currentSpeed * 10f) / 10f
        if (this::tvSpeed.isInitialized) {
            tvSpeed.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        }
        
        handler.postDelayed({
            cachedSongList?.let {
                if (!isMiniMode && !isPlayerMode && currentPage == 0 && this::listContainer.isInitialized) {
                    listContainer.removeAllViews()
                    renderLocalList(it)
                }
            }
        }, 100)
        
        if (isPlaying) { stopPlayback(); startPlayback() }
    }

    private fun startPlayback() {
        if (songData.isEmpty() || currentIndex >= songData.size) return
        isPlaying = true
        playRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying || currentIndex >= songData.size) { isPlaying = false; updatePlayerUI(); return }
                val (timeMs, keyIndex) = songData[currentIndex]
                val point = keyPositions.getOrNull(keyIndex) ?: Pair(0f, 0f)
                if (point.first != -1f) SkyAccessibilityService.instance?.clickPoints(listOf(point))
                currentIndex++
                if (currentIndex < songData.size) {
                    val nextInterval = songData[currentIndex].first - timeMs
                    handler.postDelayed(this, maxOf((nextInterval / currentSpeed).toLong(), 1L))
                } else { isPlaying = false }
                
                val progressPercent = if (songData.size <= 1) 0 else (currentIndex.toFloat() / (songData.size - 1) * 100).toInt()
                if (this@FloatingWindowService::seekBar.isInitialized) {
                    seekBar.progress = progressPercent
                }
                
                val speedStr = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
                if (this@FloatingWindowService::tvStatus.isInitialized) {
                    tvStatus.text = "播放: ${currentIndex + 1} / ${songData.size}  |  $speedStr"
                }
            }
        }
        handler.post(playRunnable!!)
    }

    private fun stopPlayback() {
        isPlaying = false
        if (playRunnable != null) { handler.removeCallbacks(playRunnable!!); playRunnable = null }
    }

    private fun readLocalFileInfo(file: File): List<String> {
        try {
            var content = file.readText(Charsets.UTF_8)
            if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
            
            val timeRegex = Regex("\"time\"\\s*:\\s*(\\d+)")
            val authorRegex = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"")
            val transcriberRegex = Regex("\"transcribedBy\"\\s*:\\s*\"([^\"]+)\"")
            val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            val tuneRegex = Regex("\"tune\"\\s*:\\s*\"([^\"]+)\"") 
            val bpmRegex = Regex("\"bpm\"\\s*:\\s*(\\d+)")
            
            val timeMatches = timeRegex.findAll(content).map { it.groupValues[1].toLong() }.toList()
            
            var songName = nameRegex.find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringBefore("-")
            var displayAuthor = authorRegex.find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
            var tune = tuneRegex.find(content)?.groupValues?.getOrNull(1) ?: "未知调"
            var bpm = bpmRegex.find(content)?.groupValues?.getOrNull(1) ?: "0"
            
            val transcriber = transcriberRegex.find(content)?.groupValues?.getOrNull(1)
            if (!transcriber.isNullOrEmpty() && transcriber != "SkyMusic") {
                displayAuthor = "$displayAuthor / $transcriber"
            }
            if (displayAuthor == "Unknown") displayAuthor = "未知作者"

            val notesCount = timeMatches.size
            val duration = if (notesCount > 0) {
                val totalSec = timeMatches.maxOrNull()!! / 1000
                String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
            } else "0:00"

            return listOf(songName, displayAuthor, duration, notesCount.toString(), tune, bpm)
        } catch (e: Exception) {
            return listOf(file.nameWithoutExtension, "未知作者", "0:00", "0", "未知调", "0")
        }
    }

    private fun calculateDuration(notes: List<Pair<Long, Int>>): String {
        if (notes.isEmpty()) return "0:00"
        val totalSec = notes.last().first / 1000
        return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun directLoadLocalFile(file: File) {
        thread {
            try {
                var content = file.readText(Charsets.UTF_8)
                if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
                val timeRegex = Regex("\"time\"\\s*:\\s*(\\d+)")
                val keyRegex = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"")
                val authorRegex = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"")
                val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
                val tuneRegex = Regex("\"tune\"\\s*:\\s*\"([^\"]+)\"")
                val bpmRegex = Regex("\"bpm\"\\s*:\\s*(\\d+)")
                
                val timeMatches = timeRegex.findAll(content).map { it.groupValues[1].toLong() }.toList()
                val keyMatches = keyRegex.findAll(content).map { it.groupValues[1].toInt() }.toList()
                
                var nameMatch = nameRegex.find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringBefore("-")
                var authorMatch = authorRegex.find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
                var tuneMatch = tuneRegex.find(content)?.groupValues?.getOrNull(1) ?: "未知调"
                var bpmMatch = bpmRegex.find(content)?.groupValues?.getOrNull(1) ?: "0"
                if (authorMatch == "Unknown") authorMatch = "未知作者"
                
                if (timeMatches.isEmpty() || keyMatches.isEmpty()) {
                    handler.post { Toast.makeText(this, "读取失败: 空乐谱", Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                
                val notes = mutableListOf<Pair<Long, Int>>()
                for (i in timeMatches.indices) { if (i < keyMatches.size) notes.add(Pair(timeMatches[i], keyMatches[i])) }
                
                val sortedNotes = notes.sortedBy { it.first }
                
                handler.post {
                    songData = sortedNotes
                    currentIndex = 0
                    currentSongName = nameMatch
                    currentAuthor = authorMatch
                    currentTune = tuneMatch
                    currentBpm = bpmMatch
                    currentNotesCount = songData.size
                    currentDuration = calculateDuration(songData)
                    preparePlay()
                }
            } catch (e: Exception) {
                handler.post { Toast.makeText(this, "读取异常", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun startCalibration() {
        val dm = resources.displayMetrics
        val dotSize = (60 * density).toInt()
        val halfSize = dotSize / 2
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            for (i in 0 until 15) { keyPositions.add(Pair(dm.widthPixels * ((i % 5) + 1) / 6f, dm.heightPixels * ((i / 5) + 6) / 10f)) }
        }
        val calibrateViews = mutableListOf<TextView>()
        for (i in 0 until 15) {
            val dot = TextView(this).apply { text = "${i + 1}"; setTextColor(Color.BLACK); setBackgroundColor(Color.parseColor("#80FFFFFF")); gravity = Gravity.CENTER }
            val params = WindowManager.LayoutParams(dotSize, dotSize, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.START
            params.x = keyPositions[i].first.toInt() - halfSize; params.y = keyPositions[i].second.toInt() - halfSize

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
        val saveBtn = Button(this).apply {
            text = "✅ 保存键位"; setBackgroundColor(Color.GREEN)
            setOnClickListener {
                saveKeyPositions(); calibrateViews.forEach { windowManager.removeView(it) }; windowManager.removeView(this)
                Toast.makeText(this@FloatingWindowService, "键位已保存！", Toast.LENGTH_SHORT).show()
            }
        }
        val btnParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        btnParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; btnParams.y = 150
        windowManager.addView(saveBtn, btnParams)
    }

    private fun loadKeyPositions() {
        keyPositions.clear()
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        for (i in 0 until 15) { keyPositions.add(Pair(prefs.getFloat("key_${i}_x", -1f), prefs.getFloat("key_${i}_y", -1f))) }
    }

    private fun saveKeyPositions() {
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        val editor = prefs.edit()
        for (i in 0 until 15) { editor.putFloat("key_${i}_x", keyPositions[i].first); editor.putFloat("key_${i}_y", keyPositions[i].second) }
        editor.apply()
    }

    override fun onDestroy() {
        super.onDestroy(); stopPlayback()
        if (this::floatView.isInitialized) { try { windowManager.removeView(floatView) } catch (e: Exception) {} }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
