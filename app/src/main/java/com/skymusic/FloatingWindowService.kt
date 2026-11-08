package com.skymusic

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.Locale
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

    private var currentPage = 0 // 0: 本地, 1: App发送

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadKeyPositions()
        File(appSheetsPath).let { if (!it.exists()) it.mkdirs() }
        File(localSheetsPath).let { if (!it.exists()) it.mkdirs() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isNewData = intent?.getBooleanExtra("is_new_data", false) ?: false
        if (isNewData) {
            intent?.getSerializableExtra("song_data")?.let {
                // 【修复】：补上了缺失的右括号
                @Suppress("UNCHECKED_CAST")
                songData = it as ArrayList<Pair<Long, Int>>
                
                stopPlayback()
                currentIndex = 0
                
                currentSongName = intent.getStringExtra("song_name") ?: "App 发送谱面"
                currentAuthor = intent.getStringExtra("song_author") ?: "未知作者"
                currentNotesCount = songData.size
                currentDuration = calculateDuration(songData)
                
                if (!this::floatView.isInitialized) createFloatingWindow()
                if (!isMiniMode && !isPlayerMode) {
                    currentPage = 1
                    showFullPanel(1) 
                }
            }
        }

        if (!this::floatView.isInitialized) createFloatingWindow()
        return START_STICKY
    }

    private fun createFloatingWindow() {
        floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val dm = resources.displayMetrics

        panelParams = WindowManager.LayoutParams(
            (dm.widthPixels * 0.7).toInt(), (dm.heightPixels * 0.55).toInt(), 
            layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        ballParams = WindowManager.LayoutParams(
            80, 80, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }

        playerParams = WindowManager.LayoutParams(
            (dm.widthPixels * 0.85).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, 
            layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.START; x = 50; y = 100 }

        try {
            windowManager.addView(floatView, ballParams)
            showMiniBall()
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun showMiniBall() {
        isMiniMode = true; isPlayerMode = false
        floatView.removeAllViews()
        val ball = TextView(this).apply {
            text = "✦"; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FDE047"))
            setShadowLayer(10f, 0f, 0f, Color.parseColor("#EAB308")) 
        }
        val ballWrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#AA0F172A"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        ballWrapper.addView(ball)
        floatView.addView(ballWrapper)
        setupDrag(ballWrapper, ballParams)
        ballWrapper.setOnClickListener { showFullPanel(currentPage) }
        windowManager.updateViewLayout(floatView, ballParams)
    }

    private fun showFullPanel(initialPage: Int = 0) {
        isMiniMode = false; isPlayerMode = false
        floatView.removeAllViews()
        
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6101418"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 20f) }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 20, 20, 20)
        }
        titleText = TextView(this).apply {
            text = if (initialPage == 0) "本地曲库" else "App 发送"
            setTextColor(Color.WHITE); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnRefresh = TextView(this).apply {
            text = "↻"; setTextColor(Color.WHITE); textSize = 22f; setPadding(20, 0, 20, 0)
            setOnClickListener { showFullPanel(currentPage) } 
        }
        val btnClose = TextView(this).apply {
            text = "✖"; setTextColor(Color.WHITE); textSize = 18f; setPadding(10, 0, 0, 0)
            setOnClickListener { stopPlayback(); showMiniBall() }
        }
        topBar.addView(titleText); topBar.addView(btnRefresh); topBar.addView(btnClose)
        rootLayout.addView(topBar)

        rootLayout.addView(View(this).apply { setBackgroundColor(Color.parseColor("#334155")); layoutParams = LinearLayout.LayoutParams(-1, 1) })

        val scrollView = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10) }
        scrollView.addView(listContainer)
        rootLayout.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1F26"))
            setPadding(0, 15, 0, 15)
        }
        val btnNavLocal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setOnClickListener { currentPage = 0; showFullPanel(0) }
        }
        btnNavLocal.addView(TextView(this).apply { text = "📂"; textSize = 20f; gravity = Gravity.CENTER })
        btnNavLocal.addView(TextView(this).apply { text = "本地曲库"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER })

        val btnNavApp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setOnClickListener { currentPage = 1; showFullPanel(1) }
        }
        btnNavApp.addView(TextView(this).apply { text = "☁️"; textSize = 20f; gravity = Gravity.CENTER })
        btnNavApp.addView(TextView(this).apply { text = "App 发送"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER })

        val btnNavSet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setOnClickListener { 
                if(checkAccessibility()) { showMiniBall(); startCalibration() } 
            }
        }
        btnNavSet.addView(TextView(this).apply { text = "🎯"; textSize = 20f; gravity = Gravity.CENTER })
        btnNavSet.addView(TextView(this).apply { text = "校准键位"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER })

        bottomBar.addView(btnNavLocal, LinearLayout.LayoutParams(0, -2, 1f))
        bottomBar.addView(btnNavApp, LinearLayout.LayoutParams(0, -2, 1f))
        bottomBar.addView(btnNavSet, LinearLayout.LayoutParams(0, -2, 1f))
        rootLayout.addView(bottomBar)

        floatView.addView(rootLayout)
        windowManager.updateViewLayout(floatView, panelParams)

        setupDrag(topBar, panelParams)
        switchPage(initialPage)
    }

    private fun switchPage(page: Int) {
        if (!this::listContainer.isInitialized) return 
        listContainer.removeAllViews()

        if (page == 0) {
            val filesList = mutableListOf<File>()
            val localFolder = File(localSheetsPath)
            if (localFolder.exists()) filesList.addAll(localFolder.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList())
            
            val appFolder = File(appSheetsPath)
            if (appFolder.exists()) filesList.addAll(appFolder.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList())

            if (filesList.isEmpty()) {
                listContainer.addView(TextView(this).apply { 
                    text = "未找到乐谱。\n请将 txt 文件放入：\n/storage/emulated/0/SkyMusic-OneStar/LocalSheets/"
                    setTextColor(Color.GRAY); textSize = 13f; setPadding(20, 20, 20, 20) 
                })
                return
            }
            for (file in filesList) {
                val info = readLocalFileInfo(file)
                addSongToList(file.nameWithoutExtension, info.first, info.second, info.third) { directLoadLocalFile(file) }
            }
        } else {
            if (songData.isEmpty()) {
                listContainer.addView(TextView(this).apply { text = "暂无 App 发送的谱面"; setTextColor(Color.GRAY); textSize = 13f; setPadding(20, 20, 20, 20) })
            } else {
                addSongToList(currentSongName, currentAuthor, currentDuration, currentNotesCount) { preparePlay() }
            }
        }
    }

    private fun addSongToList(title: String, author: String, duration: String, notes: Int, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 15, 10, 15)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { onClick() }
        }
        textLayout.addView(TextView(this).apply { 
            text = title
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD) 
        })
        textLayout.addView(TextView(this).apply { 
            text = "音符: $notes | $duration | $author"
            setTextColor(Color.parseColor("#7B7B7B")); textSize = 13f; setPadding(0, 4, 0, 0) 
        })
        card.addView(textLayout)

        val btnDelete = TextView(this).apply {
            text = "🗑️"; textSize = 20f; setPadding(15, 0, 15, 0)
            setOnClickListener { Toast.makeText(this@FloatingWindowService, "删除功能请在文件管理器中操作", Toast.LENGTH_SHORT).show() }
        }
        card.addView(btnDelete)

        val btnPlay = TextView(this).apply {
            text = "▶"; textSize = 22f; setTextColor(Color.WHITE); setPadding(15, 0, 15, 0)
            setOnClickListener { onClick() }
        }
        card.addView(btnPlay)

        listContainer.addView(card)
    }

    private fun showPlayerPanel() {
        isMiniMode = false; isPlayerMode = true
        floatView.removeAllViews()

        val playerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0F172A"))
            setPadding(20, 15, 20, 15)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 20f) }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val titleTv = TextView(this).apply {
            text = "《$currentSongName》 - $currentAuthor"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tvStatus = TextView(this).apply { setTextColor(Color.parseColor("#38BDF8")); textSize = 11f; setPadding(0, 3, 0, 3) }
        playerLayout.addView(titleTv)
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

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        
        btnPlayPause = Button(this).apply {
            text = "▶"; textSize = 16f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#10B981")); cornerRadius = 10f }
            setOnClickListener { togglePlayback() }
        }
        btnRow.addView(btnPlayPause, LinearLayout.LayoutParams(0, 90, 1f))

        val speedControl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#33000000")); cornerRadius = 10f }
            setPadding(5, 0, 5, 0)
        }
        val btnSpeedDown = Button(this).apply { text = "-"; textSize = 14f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }; setOnClickListener { changeSpeed(-0.1f) } }
        tvSpeed = TextView(this).apply { text = "1.0x"; setTextColor(Color.parseColor("#38BDF8")); textSize = 12f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD) }
        val btnSpeedUp = Button(this).apply { text = "+"; textSize = 14f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }; setOnClickListener { changeSpeed(0.1f) } }
        
        speedControl.addView(btnSpeedDown, LinearLayout.LayoutParams(60, 90))
        speedControl.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))
        speedControl.addView(btnSpeedUp, LinearLayout.LayoutParams(60, 90))
        
        val speedParams = LinearLayout.LayoutParams(0, 90, 1.8f)
        speedParams.setMargins(10, 0, 10, 0)
        btnRow.addView(speedControl, speedParams)

        val btnClose = Button(this).apply {
            text = "✖"; textSize = 14f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#EF4444")); cornerRadius = 10f }
            setOnClickListener { stopPlayback(); showFullPanel(currentPage) }
        }
        btnRow.addView(btnClose, LinearLayout.LayoutParams(0, 90, 0.6f))

        playerLayout.addView(btnRow)
        floatView.addView(playerLayout)
        
        windowManager.updateViewLayout(floatView, playerParams)
        setupDrag(titleTv, playerParams)
        updatePlayerUI()
    }

    private fun updatePlayerUI() {
        if (!isPlayerMode || !this::floatView.isInitialized) return
        val progressPercent = if (songData.size <= 1) 0 else (currentIndex.toFloat() / (songData.size - 1) * 100).toInt()
        seekBar.progress = progressPercent
        
        if (isPlaying) {
            tvStatus.text = "播放中: ${currentIndex + 1} / ${songData.size}"
            btnPlayPause.text = "⏸"
            (btnPlayPause.background as GradientDrawable).setColor(Color.parseColor("#EF4444"))
        } else {
            tvStatus.text = "已暂停"
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
        tvSpeed.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
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
                updatePlayerUI()
            }
        }
        handler.post(playRunnable!!)
    }

    private fun stopPlayback() {
        isPlaying = false
        if (playRunnable != null) { handler.removeCallbacks(playRunnable!!); playRunnable = null }
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            private var isDragging = false
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY; isDragging = false; return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - initialTouchX) > 10 || abs(event.rawY - initialTouchY) > 10) isDragging = true
                        if (isDragging) {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(floatView, params)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> { if (isDragging) return true }
                }
                return false
            }
        })
    }

    private fun readLocalFileInfo(file: File): Triple<String, String, Int> {
        try {
            var content = file.readText(Charsets.UTF_8)
            if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
            
            val timeRegex = Regex("\"time\"\\s*:\\s*(\\d+)")
            val authorRegex = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"")
            val transcriberRegex = Regex("\"transcribedBy\"\\s*:\\s*\"([^\"]+)\"")
            
            val timeMatches = timeRegex.findAll(content).map { it.groupValues[1].toLong() }.toList()
            var displayAuthor = authorRegex.find(content)?.groupValues?.getOrNull(1) ?: "未知作者"
            
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

            return Triple(displayAuthor, duration, notesCount)
        } catch (e: Exception) {
            return Triple("未知作者", "0:00", 0)
        }
    }

    private fun calculateDuration(notes: List<Pair<Long, Int>>): String {
        if (notes.isEmpty()) return "0:00"
        val totalSec = notes.last().first / 1000
        return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun directLoadLocalFile(file: File) {
        try {
            var content = file.readText(Charsets.UTF_8)
            if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
            
            val timeRegex = Regex("\"time\"\\s*:\\s*(\\d+)")
            val keyRegex = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"")
            val authorRegex = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"")
            val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            
            val timeMatches = timeRegex.findAll(content).map { it.groupValues[1].toLong() }.toList()
            val keyMatches = keyRegex.findAll(content).map { it.groupValues[1].toInt() }.toList()
            var authorMatch = authorRegex.find(content)?.groupValues?.getOrNull(1) ?: "未知作者"
            var nameMatch = nameRegex.find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension
            if (authorMatch == "Unknown") authorMatch = "未知作者"

            if (timeMatches.isEmpty() || keyMatches.isEmpty()) return

            val notes = mutableListOf<Pair<Long, Int>>()
            for (i in timeMatches.indices) { if (i < keyMatches.size) notes.add(Pair(timeMatches[i], keyMatches[i])) }
            
            songData = notes.sortedBy { it.first }
            currentIndex = 0
            currentSongName = nameMatch
            currentAuthor = authorMatch
            currentNotesCount = songData.size
            currentDuration = calculateDuration(songData)

            preparePlay()
        } catch (e: Exception) { Toast.makeText(this, "读取异常", Toast.LENGTH_SHORT).show() }
    }

    private fun startCalibration() {
        val dm = resources.displayMetrics
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            for (i in 0 until 15) { keyPositions.add(Pair(dm.widthPixels * ((i % 5) + 1) / 6f, dm.heightPixels * ((i / 5) + 6) / 10f)) }
        }
        val calibrateViews = mutableListOf<TextView>()
        for (i in 0 until 15) {
            val dot = TextView(this).apply { text = "${i + 1}"; setTextColor(Color.BLACK); setBackgroundColor(Color.parseColor("#80FFFFFF")); gravity = Gravity.CENTER; setPadding(20, 20, 20, 20) }
            val params = WindowManager.LayoutParams(120, 120, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.START
            params.x = keyPositions[i].first.toInt() - 60; params.y = keyPositions[i].second.toInt() - 60

            dot.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0; private var initialY = 0; private var initialTouchX = 0f; private var initialTouchY = 0f
                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY; return true }
                        MotionEvent.ACTION_MOVE -> { params.x = initialX + (event.rawX - initialTouchX).toInt(); params.y = initialY + (event.rawY - initialTouchY).toInt(); windowManager.updateViewLayout(dot, params); keyPositions[i] = Pair((params.x + 60).toFloat(), (params.y + 60).toFloat()); return true }
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
