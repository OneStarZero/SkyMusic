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
    
    // 核心数据
    private var isPlaying = false
    private var currentIndex = 0
    private var songData = listOf<Pair<Long, Int>>()
    private val keyPositions = mutableListOf<Pair<Float, Float>>()
    private var currentSpeed = 1.0f

    private var currentSongName = ""
    private var currentAuthor = ""
    private var currentDuration = "0:00"
    private var currentNotesCount = 0

    // 状态管理
    private var isMiniMode = true 
    private var isPlayerMode = false 
    private lateinit var tvStatus: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var tvSpeed: TextView
    private lateinit var contentLayout: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var playRunnable: Runnable? = null
    private val skyStudioPath = "/storage/emulated/0/Android/data/com.Maple.SkyStudio/files/Sheet/"
    private val myAppPath = "/storage/emulated/0/Android/data/com.skymusic/files/Sheets/"
    
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var playerParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadKeyPositions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isNewData = intent?.getBooleanExtra("is_new_data", false) ?: false
        if (isNewData) {
            intent?.getSerializableExtra("song_data")?.let {
                @Suppress("UNCHECKED_CAST")
                songData = it as ArrayList<Pair<Long, Int>>
                stopPlayback()
                currentIndex = 0
                
                currentSongName = intent.getStringExtra("song_name") ?: "App 发送谱面"
                currentAuthor = intent.getStringExtra("song_author") ?: "未知作者"
                currentNotesCount = songData.size
                currentDuration = calculateDuration(songData)
                
                if (!this::floatView.isInitialized) createFloatingWindow()
                if (!isMiniMode && !isPlayerMode) switchPage(1) 
            }
        }

        if (!this::floatView.isInitialized) createFloatingWindow()
        return START_STICKY
    }

    private fun createFloatingWindow() {
        floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val dm = resources.displayMetrics

        // 1. 控制台窗口参数
        panelParams = WindowManager.LayoutParams(
            (dm.widthPixels * 0.8).toInt(), (dm.heightPixels * 0.6).toInt(), 
            layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }

        // 2. 悬浮球参数
        ballParams = WindowManager.LayoutParams(
            80, 80, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }

        // 3. 播放器参数
        playerParams = WindowManager.LayoutParams(
            (dm.widthPixels * 0.8).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, 
            layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.START; x = 50; y = 150 }

        try {
            windowManager.addView(floatView, ballParams)
            showMiniBall()
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    // ================= 玻璃质感工具 =================
    private fun createGlassBackground(radius: Float, alpha: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(alpha, 15, 23, 42)) // 深空蓝，半透明
            setStroke(2, Color.argb(60, 255, 255, 255)) // 极细的白色高光边框
        }
    }

    // ================= 模式 1：小星星悬浮球 =================
    private fun showMiniBall() {
        isMiniMode = true; isPlayerMode = false
        floatView.removeAllViews()
        val ball = TextView(this).apply {
            text = "✦"; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FDE047"))
            setShadowLayer(10f, 0f, 0f, Color.parseColor("#EAB308")) 
        }
        val ballWrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#800F172A"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        ballWrapper.addView(ball)
        floatView.addView(ballWrapper)
        setupDrag(ballWrapper, ballParams)
        ballWrapper.setOnClickListener { showFullPanel(0) }
        windowManager.updateViewLayout(floatView, ballParams)
    }

    // ================= 模式 2：主控制台 =================
    private fun showFullPanel(initialPage: Int = 0) {
        isMiniMode = false; isPlayerMode = false
        floatView.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(30, 30, 30, 30)
            background = createGlassBackground(40f, 240)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        
        val titleBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleText = TextView(this).apply {
            text = "SkyMusic"; setTextColor(Color.WHITE); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnMinimize = TextView(this).apply {
            text = "➖"; setTextColor(Color.parseColor("#38BDF8")); textSize = 22f; setPadding(20, 0, 0, 0)
            setOnClickListener { showMiniBall() }
        }
        val resizeHandle = TextView(this).apply {
            text = "◢"; setTextColor(Color.parseColor("#38BDF8")); textSize = 20f; setPadding(20, 0, 0, 0)
        }

        titleBar.addView(titleText); titleBar.addView(btnMinimize); titleBar.addView(resizeHandle); 
        panel.addView(titleBar)

        val navLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 20) }
        val btnNavLocal = Button(this).apply { text = "本地谱面"; setOnClickListener { switchPage(0) } }
        val btnNavApp = Button(this).apply { text = "App发送"; setOnClickListener { switchPage(1) } }
        val btnNavCalibrate = Button(this).apply { 
            text = "🎯 校准15键"; setBackgroundColor(Color.parseColor("#EF4444")); setTextColor(Color.WHITE)
            setOnClickListener { if(checkAccessibility()) { showMiniBall(); startCalibration() } } 
        }
        navLayout.addView(btnNavCalibrate, LinearLayout.LayoutParams(0, -2, 1.2f))
        navLayout.addView(btnNavLocal, LinearLayout.LayoutParams(0, -2, 1f))
        navLayout.addView(btnNavApp, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(navLayout)

        contentLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(contentLayout, LinearLayout.LayoutParams(-1, 0, 1f))

        floatView.addView(panel)
        windowManager.updateViewLayout(floatView, panelParams)

        setupDrag(titleBar, panelParams)
        setupResize(resizeHandle, panelParams)

        switchPage(initialPage)
    }

    private fun switchPage(page: Int) {
        if (!this::contentLayout.isInitialized) return 
        contentLayout.removeAllViews()
        val scrollView = ScrollView(this)
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(listLayout); contentLayout.addView(scrollView)

        if (page == 0) {
            val filesList = mutableListOf<File>()
            val skyFolder = File(skyStudioPath)
            if (skyFolder.exists()) filesList.addAll(skyFolder.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList())
            val myFolder = File(myAppPath)
            if (myFolder.exists()) filesList.addAll(myFolder.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList())

            if (filesList.isEmpty()) {
                listLayout.addView(TextView(this).apply { text = "无本地谱面"; setTextColor(Color.GRAY) })
                return
            }
            for (file in filesList) {
                addSongToList(listLayout, file.nameWithoutExtension, "点击加载", "0:00", 0) { directLoadLocalFile(file) }
            }
        } else {
            if (songData.isEmpty()) {
                listLayout.addView(TextView(this).apply { text = "暂无 App 发送的谱面"; setTextColor(Color.GRAY) })
            } else {
                addSongToList(listLayout, currentSongName, currentAuthor, currentDuration, currentNotesCount) { preparePlay() }
            }
        }
    }

    private fun addSongToList(container: LinearLayout, title: String, author: String, duration: String, notes: Int, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlassBackground(20f, 120)
            setPadding(25, 20, 25, 20); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 15) }
            setOnClickListener { onClick() }
        }
        card.addView(TextView(this).apply { text = "$title - $author"; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) })
        if (notes > 0) {
            card.addView(TextView(this).apply { text = "时长: $duration | 音符: $notes"; setTextColor(Color.parseColor("#94A3B8")); textSize = 13f; setPadding(0, 5, 0, 0) })
        }
        container.addView(card)
    }

    // ================= 模式 3：悬浮播放器 =================
    private fun showPlayerPanel() {
        isMiniMode = false; isPlayerMode = true
        floatView.removeAllViews()

        val playerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlassBackground(35f, 250)
            setPadding(30, 25, 30, 25)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val titleTv = TextView(this).apply {
            text = "《$currentSongName》 - $currentAuthor"
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val resizeHandle = TextView(this).apply {
            text = "◢"; setTextColor(Color.parseColor("#38BDF8")); textSize = 18f; setPadding(20, 0, 0, 0)
        }
        titleRow.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(resizeHandle)

        tvStatus = TextView(this).apply { setTextColor(Color.parseColor("#38BDF8")); textSize = 12f; setPadding(0, 5, 0, 5) }
        
        playerLayout.addView(titleRow)
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
            text = "▶"; textSize = 18f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#10B981")); cornerRadius = 20f }
            setOnClickListener { togglePlayback() }
        }
        btnRow.addView(btnPlayPause, LinearLayout.LayoutParams(0, 120, 1.2f))

        val speedControl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#33000000")); cornerRadius = 20f }
            setPadding(10, 0, 10, 0)
        }
        val btnSpeedDown = Button(this).apply {
            text = "-"; textSize = 18f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setOnClickListener { changeSpeed(-0.1f) }
        }
        tvSpeed = TextView(this).apply {
            text = "1.0x"; setTextColor(Color.parseColor("#38BDF8")); textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val btnSpeedUp = Button(this).apply {
            text = "+"; textSize = 18f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setOnClickListener { changeSpeed(0.1f) }
        }
        speedControl.addView(btnSpeedDown, LinearLayout.LayoutParams(80, 120))
        speedControl.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))
        speedControl.addView(btnSpeedUp, LinearLayout.LayoutParams(80, 120))
        
        val speedParams = LinearLayout.LayoutParams(0, 120, 1.8f)
        speedParams.setMargins(20, 0, 20, 0)
        btnRow.addView(speedControl, speedParams)

        val btnClose = Button(this).apply {
            text = "✖"; textSize = 16f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#EF4444")); cornerRadius = 20f }
            setOnClickListener { stopPlayback(); showFullPanel(0) }
        }
        btnRow.addView(btnClose, LinearLayout.LayoutParams(0, 120, 0.8f))

        playerLayout.addView(btnRow)
        floatView.addView(playerLayout)
        
        windowManager.updateViewLayout(floatView, playerParams)
        
        setupDrag(titleRow, playerParams)
        setupResize(resizeHandle, playerParams)

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

    // ================= 逻辑控制 =================
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

    // 【核心改动】：选中后只展示播放器界面，不自动播放！
    private fun preparePlay() {
        if (!checkAccessibility()) return
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            Toast.makeText(this, "请先校准键位！", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 停止之前的播放状态
        stopPlayback()
        currentIndex = 0 
        
        handler.postDelayed({
            showPlayerPanel() // 弹出小窗
            updatePlayerUI()  // 更新为“已暂停”状态
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

    // ================= 拖拽与缩放 =================
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
                        if (abs(event.rawX - initialTouchX) > 15 || abs(event.rawY - initialTouchY) > 15) isDragging = true
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

    private fun setupResize(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialWidth = 0; private var initialHeight = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = params.width; initialHeight = params.height
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        
                        // 限制最小 400x300，防止拉得太小
                        params.width = maxOf(400, initialWidth + deltaX)
                        params.height = maxOf(300, initialHeight + deltaY)
                        
                        windowManager.updateViewLayout(floatView, params)
                        return true
                    }
                }
                return false
            }
        })
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
            
            val timeMatches = timeRegex.findAll(content).map { it.groupValues[1].toLong() }.toList()
            val keyMatches = keyRegex.findAll(content).map { it.groupValues[1].toInt() }.toList()
            var authorMatch = authorRegex.find(content)?.groupValues?.getOrNull(1) ?: "未知作者"
            if (authorMatch == "Unknown") authorMatch = "未知作者"

            if (timeMatches.isEmpty() || keyMatches.isEmpty()) return

            val notes = mutableListOf<Pair<Long, Int>>()
            for (i in timeMatches.indices) { if (i < keyMatches.size) notes.add(Pair(timeMatches[i], keyMatches[i])) }
            
            songData = notes.sortedBy { it.first }
            currentIndex = 0
            currentSongName = file.nameWithoutExtension
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
