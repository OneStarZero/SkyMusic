package com.skymusic

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Outline
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import java.io.File

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: FrameLayout
    
    // 播放器核心数据
    private var isPlaying = false
    private var currentIndex = 0
    private var songData = listOf<Pair<Long, Int>>()
    private val keyPositions = mutableListOf<Pair<Float, Float>>()
    private var currentSpeed = 1.0f // 播放倍速

    // 状态变量
    private var currentSongName = "" // 当前曲目名称
    private var isFromApp = false // 是否来自 App 网页发送

    // 播放控制 UI 组件
    private lateinit var tvStatus: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var btnSpeed: Button

    private val handler = Handler(Looper.getMainLooper())
    private var playRunnable: Runnable? = null

    // Sky Studio 默认路径
    private val skyStudioPath = "/storage/emulated/0/Android/data/com.Maple.SkyStudio/files/Sheet/"
    
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    private var isMiniMode = true

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadKeyPositions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getSerializableExtra("song_data")?.let {
            @Suppress("UNCHECKED_CAST")
            songData = it as ArrayList<Pair<Long, Int>>
            stopPlayback()
            currentIndex = 0
            
            // 【需求实现】：来自 App 端发送的谱面名称标记
            currentSongName = "来自 App 端"
            isFromApp = true
            
            Toast.makeText(this, "已载入：$currentSongName", Toast.LENGTH_SHORT).show()
            
            if (!isMiniMode && this::floatView.isInitialized) showFullPanel()
        }

        if (!this::floatView.isInitialized) {
            createFloatingWindow()
        }
        return START_STICKY
    }

    private fun createFloatingWindow() {
        floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        
        panelParams = WindowManager.LayoutParams(
            950, 1300, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }

        // 悬浮球尺寸
        ballParams = WindowManager.LayoutParams(
            90, 90, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }

        try {
            windowManager.addView(floatView, ballParams)
            showMiniBall()
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败，请检查权限", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun showFullPanel() {
        isMiniMode = false
        floatView.removeAllViews()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1A1A2E"))
            setPadding(40, 40, 40, 40)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // 1. 标题栏
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(this).apply {
            text = "☰ SkyMusic 演奏台"
            setTextColor(Color.WHITE); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnMinimize = TextView(this).apply {
            text = "➖"
            setTextColor(Color.parseColor("#38BDF8")); textSize = 22f
            setPadding(20, 0, 0, 0)
            setOnClickListener { showMiniBall() }
        }
        titleBar.addView(titleText)
        titleBar.addView(btnMinimize)
        panel.addView(titleBar)

        // 分割线
        panel.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { setMargins(0, 20, 0, 20) }
        })

        // 2. 滚动内容区
        val scrollView = ScrollView(this)
        val contentLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(contentLayout)
        panel.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        // 3. 底部播放控制台
        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 0)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        
        tvStatus = TextView(this).apply {
            text = "等待选择谱面..."
            setTextColor(Color.parseColor("#94A3B8")); textSize = 12f
            setPadding(10, 10, 10, 10)
        }
        controlBar.addView(tvStatus)

        seekBar = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && songData.isNotEmpty()) {
                        currentIndex = (progress / 100f * (songData.size - 1)).toInt()
                        updateUI()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (isPlaying) { stopPlayback(); togglePlayback() }
                }
            })
        }
        controlBar.addView(seekBar)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnPlayPause = Button(this).apply {
            text = "▶ 播放"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setOnClickListener { togglePlayback() }
        }
        btnSpeed = Button(this).apply {
            text = "1.0x"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener { changeSpeed() }
        }
        btnRow.addView(btnPlayPause, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow.addView(btnSpeed, LinearLayout.LayoutParams(0, -2, 1f))
        controlBar.addView(btnRow)

        panel.addView(controlBar)

        val resizeHandle = TextView(this).apply {
            text = "◢"
            setTextColor(Color.parseColor("#38BDF8")); textSize = 24f
            gravity = Gravity.BOTTOM or Gravity.END
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { gravity = Gravity.END }
        }
        panel.addView(resizeHandle)

        floatView.addView(panel)

        setupDrag(titleBar, panelParams)
        setupResize(resizeHandle, panelParams)
        
        windowManager.updateViewLayout(floatView, panelParams)
        showMainMenu(contentLayout)
        updateUI()
    }

    private fun showMiniBall() {
        isMiniMode = true
        floatView.removeAllViews()

        val ball = TextView(this).apply {
            text = "✦"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FDE047"))
            setShadowLayer(10f, 0f, 0f, Color.parseColor("#EAB308")) 
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val ballWrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#AA0F172A"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        ballWrapper.addView(ball)

        floatView.addView(ballWrapper)
        setupDrag(ballWrapper, ballParams)
        
        ballWrapper.setOnClickListener {
            if (isMiniMode) showFullPanel()
        }

        windowManager.updateViewLayout(floatView, ballParams)
    }

    private fun updateUI() {
        if (!this::floatView.isInitialized || isMiniMode) return
        val progressPercent = if (songData.isEmpty()) 0 else (currentIndex.toFloat() / (songData.size - 1) * 100).toInt()
        seekBar.progress = progressPercent
        
        // 格式化显示文字：状态 | 歌名 | 进度 | 速度
        val statusText = if (songData.isEmpty()) "等待选择谱面" else "进度: ${currentIndex + 1} / ${songData.size}"
        val displayName = if (isFromApp) "$currentSongName" else "《$currentSongName》"
        
        tvStatus.text = "状态: ${if (isPlaying) "播放中" else "已暂停"} | $displayName $statusText | 速度: ${currentSpeed}x"
        btnPlayPause.text = if (isPlaying) "⏸ 暂停" else "▶ 播放"
        btnPlayPause.setBackgroundColor(if (isPlaying) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
    }

    private fun togglePlayback() {
        if (songData.isEmpty()) {
            Toast.makeText(this, "没有可播放的谱面", Toast.LENGTH_SHORT).show()
            return
        }
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            Toast.makeText(this, "请先校准键位！", Toast.LENGTH_SHORT).show()
            return
        }

        if (isPlaying) {
            stopPlayback()
        } else {
            if (currentIndex >= songData.size) currentIndex = 0
            startPlayback()
        }
        updateUI()
    }

    private fun changeSpeed() {
        when (currentSpeed) {
            0.5f -> currentSpeed = 1.0f
            1.0f -> currentSpeed = 1.5f
            1.5f -> currentSpeed = 2.0f
            2.0f -> currentSpeed = 0.5f
            else -> currentSpeed = 1.0f
        }
        btnSpeed.text = "${currentSpeed}x"
        if (isPlaying) {
            stopPlayback()
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (songData.isEmpty() || currentIndex >= songData.size) return
        isPlaying = true
        
        playRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying || currentIndex >= songData.size) {
                    isPlaying = false
                    updateUI()
                    return
                }
                
                val (timeMs, keyIndex) = songData[currentIndex]
                val point = keyPositions.getOrNull(keyIndex) ?: Pair(0f, 0f)
                
                if (point.first != -1f) {
                    SkyAccessibilityService.instance?.clickPoints(listOf(point))
                }
                
                currentIndex++
                
                if (currentIndex < songData.size) {
                    val nextInterval = songData[currentIndex].first - timeMs
                    val adjustedInterval = (nextInterval / currentSpeed).toLong()
                    handler.postDelayed(this, maxOf(adjustedInterval, 1L))
                } else {
                    isPlaying = false
                    updateUI()
                    Toast.makeText(this@FloatingWindowService, "演奏完成", Toast.LENGTH_SHORT).show()
                }
                updateUI()
            }
        }
        handler.post(playRunnable!!)
    }

    private fun stopPlayback() {
        isPlaying = false
        if (playRunnable != null) {
            handler.removeCallbacks(playRunnable!!)
            playRunnable = null
        }
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatView, params)
                        return true
                    }
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
                        params.width = maxOf(600, minOf(initialWidth + deltaX, 1200))
                        params.height = maxOf(500, minOf(initialHeight + deltaY, 1600))
                        windowManager.updateViewLayout(floatView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun showMainMenu(container: LinearLayout) {
        container.removeAllViews()
        
        val btnLibrary = Button(this).apply {
            text = "📂 读取 Sky Studio 本地曲库"
            setOnClickListener { loadLocalScores(container) }
        }
        container.addView(btnLibrary)

        val btnCalibrate = Button(this).apply {
            text = "🎯 校准 15 键位"
            setOnClickListener { showMiniBall(); startCalibration() }
        }
        container.addView(btnCalibrate)

        if (songData.isNotEmpty()) {
            val btnPlay = Button(this).apply {
                text = "🎵 演奏当前谱面 (${if (isFromApp) "来自 App" else currentSongName})"
                setBackgroundColor(Color.parseColor("#0EA5E9"))
                setOnClickListener { 
                    stopPlayback()
                    currentIndex = 0
                    togglePlayback() 
                }
            }
            container.addView(btnPlay)
        }
    }

    private fun loadLocalScores(container: LinearLayout) {
        container.removeAllViews()
        
        val btnBack = Button(this).apply {
            text = "⬅ 返回主菜单"
            setOnClickListener { showMainMenu(container) }
        }
        container.addView(btnBack)

        val folder = File(skyStudioPath)
        if (!folder.exists() || !folder.isDirectory) {
            val tv = TextView(this).apply {
                text = "未找到 Sky Studio 曲库目录\n$skyStudioPath"
                setTextColor(Color.RED); textSize = 12f; setPadding(0, 20, 0, 20)
            }
            container.addView(tv)
            return
        }

        val files = folder.listFiles { f -> f.extension.equals("txt", true) || f.extension.equals("json", true) }
        if (files.isNullOrEmpty()) {
            container.addView(TextView(this).apply { text = "目录为空"; setTextColor(Color.GRAY) })
            return
        }

        for (file in files) {
            val btn = Button(this).apply {
                text = "🎵 ${file.nameWithoutExtension}"
                textSize = 13f; setBackgroundColor(Color.parseColor("#1E293B")); setTextColor(Color.parseColor("#38BDF8"))
                setOnClickListener { 
                    directLoadSkyStudioFile(file)
                    showMiniBall()
                }
            }
            container.addView(btn)
        }
    }

    // 终极防乱码读取法：无视编码，强行提取
    private fun directLoadSkyStudioFile(file: File) {
        try {
            var content = ""
            try {
                content = file.readText(Charsets.UTF_8)
                if (content.contains("\u0000") || content.contains("\uFFFD")) {
                    content = file.readText(Charsets.UTF_16LE)
                }
            } catch (e: Exception) {
                content = file.readText(Charsets.UTF_16LE)
            }

            val timeRegex = Regex("\"time\"\\s*:\\s*(\\d+)")
            val keyRegex = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"")
            
            val timeMatches = timeRegex.findAll(content).map { it.groupValues[1].toLong() }.toList()
            val keyMatches = keyRegex.findAll(content).map { it.groupValues[1].toInt() }.toList()

            if (timeMatches.isEmpty() || keyMatches.isEmpty() || timeMatches.size != keyMatches.size) {
                Toast.makeText(this, "提取失败：未找到有效音符数据", Toast.LENGTH_SHORT).show()
                return
            }

            val notes = mutableListOf<Pair<Long, Int>>()
            for (i in timeMatches.indices) {
                notes.add(Pair(timeMatches[i], keyMatches[i]))
            }

            val sortedNotes = notes.sortedBy { it.first }

            songData = sortedNotes
            currentSongName = file.nameWithoutExtension // 记录本地曲名
            isFromApp = false // 标记为本地曲库
            currentIndex = 0
            updateUI()
            
            Toast.makeText(this, "已载入《$currentSongName》，共 ${songData.size} 个音符，请点击播放", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "读取异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCalibration() {
        val dm = resources.displayMetrics
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            for (i in 0 until 15) {
                keyPositions.add(Pair(dm.widthPixels * ((i % 5) + 1) / 6f, dm.heightPixels * ((i / 5) + 6) / 10f))
            }
        }

        val calibrateViews = mutableListOf<TextView>()
        for (i in 0 until 15) {
            val dot = TextView(this).apply {
                text = "${i + 1}"; setTextColor(Color.BLACK); setBackgroundColor(Color.parseColor("#80FFFFFF")); gravity = Gravity.CENTER; setPadding(20, 20, 20, 20)
            }

            val params = WindowManager.LayoutParams(
                120, 120,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = keyPositions[i].first.toInt() - 60
            params.y = keyPositions[i].second.toInt() - 60

            dot.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0; private var initialY = 0
                private var initialTouchX = 0f; private var initialTouchY = 0f
                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY; return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt(); params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(dot, params)
                            keyPositions[i] = Pair((params.x + 60).toFloat(), (params.y + 60).toFloat())
                            return true
                        }
                    }
                    return false
                }
            })
            windowManager.addView(dot, params)
            calibrateViews.add(dot)
        }

        val saveBtn = Button(this).apply {
            text = "✅ 保存键位"; setBackgroundColor(Color.GREEN)
            setOnClickListener {
                saveKeyPositions()
                calibrateViews.forEach { windowManager.removeView(it) }
                windowManager.removeView(this)
                Toast.makeText(this@FloatingWindowService, "键位已保存！", Toast.LENGTH_SHORT).show()
            }
        }

        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        btnParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; btnParams.y = 150
        windowManager.addView(saveBtn, btnParams)
    }

    private fun loadKeyPositions() {
        keyPositions.clear()
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        for (i in 0 until 15) {
            keyPositions.add(Pair(prefs.getFloat("key_${i}_x", -1f), prefs.getFloat("key_${i}_y", -1f)))
        }
    }

    private fun saveKeyPositions() {
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        val editor = prefs.edit()
        for (i in 0 until 15) {
            editor.putFloat("key_${i}_x", keyPositions[i].first)
            editor.putFloat("key_${i}_y", keyPositions[i].second)
        }
        editor.apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        if (this::floatView.isInitialized) {
            try { windowManager.removeView(floatView) } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
