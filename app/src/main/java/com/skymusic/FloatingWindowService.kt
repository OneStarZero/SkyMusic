package com.skymusic

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
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

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: FrameLayout
    
    // 播放器核心数据
    private var isPlaying = false
    private var currentIndex = 0
    private var songData = listOf<Pair<Long, Int>>()
    private val keyPositions = mutableListOf<Pair<Float, Float>>()
    private var currentSpeed = 1.0f

    private var currentSongName = ""
    private var currentAuthor = ""
    private var currentDuration = "0:00"
    private var currentNotesCount = 0

    // UI 状态管理
    private var isMiniMode = true 
    private var isPlayerMode = false 
    private lateinit var tvStatus: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var btnSpeed: Button
    private lateinit var contentLayout: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var playRunnable: Runnable? = null
    private val skyStudioPath = "/storage/emulated/0/Android/data/com.Maple.SkyStudio/files/Sheet/"
    
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams

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
            
            currentSongName = "App 发送谱面"
            currentAuthor = "网页端"
            currentNotesCount = songData.size
            currentDuration = calculateDuration(songData)
            
            if (!this::floatView.isInitialized) createFloatingWindow()
            // 如果当前在主控制台，自动切到 App 页
            if (!isMiniMode && !isPlayerMode) {
                switchPage(1) 
            } else {
                Toast.makeText(this, "已收到 App 发送谱面", Toast.LENGTH_SHORT).show()
            }
        }

        if (!this::floatView.isInitialized) {
            createFloatingWindow()
        }
        return START_STICKY
    }

    private fun createFloatingWindow() {
        floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        
        // 自适应屏幕大小：宽 85%，高 60%
        val dm = resources.displayMetrics
        val maxW = (dm.widthPixels * 0.85).toInt()
        val maxH = (dm.heightPixels * 0.6).toInt()

        panelParams = WindowManager.LayoutParams(
            maxW, maxH, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

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

    // ================= 模式 1：小星星悬浮球 =================
    private fun showMiniBall() {
        isMiniMode = true; isPlayerMode = false
        floatView.removeAllViews()

        val ball = TextView(this).apply {
            text = "✦"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FDE047"))
            setShadowLayer(10f, 0f, 0f, Color.parseColor("#EAB308")) 
        }

        val ballWrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#AA0F172A"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
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
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F00F172A")) 
            setPadding(30, 30, 30, 30)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val titleBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleText = TextView(this).apply {
            text = "SkyMusic 控制台"
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnMinimize = TextView(this).apply {
            text = "➖"
            setTextColor(Color.parseColor("#38BDF8")); textSize = 20f; setPadding(20, 0, 0, 0)
            setOnClickListener { showMiniBall() }
        }
        titleBar.addView(titleText)
        titleBar.addView(btnMinimize)
        panel.addView(titleBar)

        val navLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 20) }
        val btnNavLocal = Button(this).apply { text = "本地谱面"; setOnClickListener { switchPage(0) } }
        val btnNavApp = Button(this).apply { text = "App发送"; setOnClickListener { switchPage(1) } }
        val btnNavCalibrate = Button(this).apply { 
            text = "🎯 校准15键"
            setBackgroundColor(Color.parseColor("#EF4444")); setTextColor(Color.WHITE)
            setOnClickListener { 
                if(checkAccessibility()) { showMiniBall(); startCalibration() } 
            } 
        }
        
        navLayout.addView(btnNavCalibrate, LinearLayout.LayoutParams(0, -2, 1.2f))
        navLayout.addView(btnNavLocal, LinearLayout.LayoutParams(0, -2, 1f))
        navLayout.addView(btnNavApp, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(navLayout)

        contentLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(contentLayout, LinearLayout.LayoutParams(-1, 0, 1f))

        floatView.addView(panel)
        
        panelParams.x = 0; panelParams.y = 0
        windowManager.updateViewLayout(floatView, panelParams)

        switchPage(initialPage)
    }

    private fun switchPage(page: Int) {
        // 防止空指针，确保引用已初始化
        if (!this::contentLayout.isInitialized) return 
        
        contentLayout.removeAllViews()
        
        val scrollView = ScrollView(this)
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(listLayout)
        contentLayout.addView(scrollView)

        if (page == 0) {
            val folder = File(skyStudioPath)
            if (!folder.exists() || !folder.isDirectory) {
                listLayout.addView(TextView(this).apply { text = "未找到 Sky Studio 目录"; setTextColor(Color.RED); setPadding(0, 20, 0, 20) })
                return
            }
            val files = folder.listFiles { f -> f.extension.equals("txt", true) || f.extension.equals("json", true) }
            if (files.isNullOrEmpty()) {
                listLayout.addView(TextView(this).apply { text = "无本地谱面"; setTextColor(Color.GRAY) })
                return
            }
            for (file in files) {
                addSongToList(listLayout, file.nameWithoutExtension, "点击加载", "0:00", 0) {
                    directLoadSkyStudioFile(file)
                }
            }
        } else {
            if (songData.isEmpty()) {
                listLayout.addView(TextView(this).apply { text = "暂无 App 发送的谱面"; setTextColor(Color.GRAY); setPadding(0,20,0,20) })
            } else {
                addSongToList(listLayout, currentSongName, currentAuthor, currentDuration, currentNotesCount) {
                    preparePlay()
                }
            }
        }
    }

    private fun addSongToList(container: LinearLayout, title: String, author: String, duration: String, notes: Int, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 15) }
            setOnClickListener { onClick() }
        }
        val titleTv = TextView(this).apply {
            text = "$title - $author"
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD)
        }
        card.addView(titleTv)
        
        if (notes > 0) {
            val infoTv = TextView(this).apply {
                text = "时长: $duration  |  音符数: $notes"
                setTextColor(Color.parseColor("#94A3B8")); textSize = 12f
                setPadding(0, 5, 0, 0)
            }
            card.addView(infoTv)
        }
        container.addView(card)
    }

    // ================= 模式 3：左下角迷你播放器 =================
    private fun showPlayerPanel() {
        isMiniMode = false; isPlayerMode = true
        floatView.removeAllViews()

        val playerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0F172A"))
            setPadding(25, 20, 25, 20)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 30f)
                }
            }
            clipToOutline = true
        }

        val titleTv = TextView(this).apply {
            text = "《$currentSongName》 - $currentAuthor"
            setTextColor(Color.WHITE); textSize = 14f
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tvStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8")); textSize = 12f; setPadding(0, 5, 0, 5)
        }
        playerLayout.addView(titleTv)
        playerLayout.addView(tvStatus)

        seekBar = SeekBar(this).apply {
            max = 100
            visibility = View.INVISIBLE // 默认隐藏
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    // 【安全防护】：防止只有1个音符时除0崩溃
                    if (fromUser && songData.size > 1) {
                        currentIndex = (progress / 100f * (songData.size - 1)).toInt()
                        updatePlayerUI()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (isPlaying) { stopPlayback(); togglePlayback() }
                }
            })
        }
        playerLayout.addView(seekBar)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        
        btnPlayPause = Button(this).apply {
            text = "▶ 播放"
            setBackgroundColor(Color.parseColor("#10B981")); setTextColor(Color.WHITE)
            setOnClickListener { togglePlayback() }
        }
        btnSpeed = Button(this).apply {
            text = "1.0x"
            setBackgroundColor(Color.parseColor("#334155")); setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener { changeSpeed() }
        }
        val btnClose = Button(this).apply {
            text = "✖"
            setBackgroundColor(Color.parseColor("#EF4444")); setTextColor(Color.WHITE)
            setOnClickListener { 
                stopPlayback()
                showFullPanel(0) 
            }
        }

        btnRow.addView(btnPlayPause, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow.addView(btnSpeed, LinearLayout.LayoutParams(0, -2, 0.6f))
        btnRow.addView(btnClose, LinearLayout.LayoutParams(0, -2, 0.4f))
        playerLayout.addView(btnRow)

        floatView.addView(playerLayout)
        
        // 放置在左下角
        panelParams.width = (resources.displayMetrics.widthPixels * 0.75).toInt()
        panelParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        panelParams.gravity = Gravity.BOTTOM or Gravity.START
        panelParams.x = 30
        panelParams.y = 200
        windowManager.updateViewLayout(floatView, panelParams)
        
        setupDrag(titleTv, panelParams)
        updatePlayerUI()
    }

    private fun updatePlayerUI() {
        if (!isPlayerMode || !this::floatView.isInitialized) return
        
        val progressPercent = if (songData.size <= 1) 0 else (currentIndex.toFloat() / (songData.size - 1) * 100).toInt()
        seekBar.progress = progressPercent
        
        if (isPlaying) {
            seekBar.visibility = View.VISIBLE
            tvStatus.text = "播放中: ${currentIndex + 1} / ${songData.size}"
        } else {
            seekBar.visibility = View.INVISIBLE
            tvStatus.text = "已暂停"
        }
        
        btnPlayPause.text = if (isPlaying) "⏸ 暂停" else "▶ 播放"
        btnPlayPause.setBackgroundColor(if (isPlaying) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
    }

    // ================= 播放逻辑 =================
    
    private fun checkAccessibility(): Boolean {
        if (SkyAccessibilityService.instance == null) {
            Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_LONG).show()
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
        
        handler.postDelayed({
            showPlayerPanel()
            if (!isPlaying) togglePlayback() 
        }, 50)
    }

    private fun togglePlayback() {
        if (songData.isEmpty()) return
        if (!checkAccessibility()) return

        if (isPlaying) {
            stopPlayback()
        } else {
            if (currentIndex >= songData.size) currentIndex = 0
            startPlayback()
        }
        updatePlayerUI()
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
        if (isPlaying) { stopPlayback(); startPlayback() }
    }

    private fun startPlayback() {
        if (songData.isEmpty() || currentIndex >= songData.size) return
        isPlaying = true
        
        playRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying || currentIndex >= songData.size) {
                    isPlaying = false
                    updatePlayerUI()
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
                    updatePlayerUI()
                }
                updatePlayerUI()
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
            private var isDragging = false
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY
                        isDragging = false
                        return false // 返回 false 保证按钮的点击事件不被拦截
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(event.rawX - initialTouchX)
                        val dy = Math.abs(event.rawY - initialTouchY)
                        if (dx > 10 || dy > 10) isDragging = true // 只有移动超过一定距离才算拖拽
                        
                        if (isDragging) {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(floatView, params)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) return true // 拖拽结束，拦截点击事件
                    }
                }
                return false
            }
        })
    }

    // 【修复】绝对精准的时长计算，防止除0和格式错误
    private fun calculateDuration(notes: List<Pair<Long, Int>>): String {
        if (notes.isEmpty()) return "0:00"
        val totalMs = notes.last().first
        val totalSec = totalMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }

    // 【修复】强力正则提取，不怕乱码
    private fun directLoadSkyStudioFile(file: File) {
        try {
            var content = ""
            try {
                content = file.readText(Charsets.UTF_8)
                if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
            } catch (e: Exception) {
                content = file.readText(Charsets.UTF_16LE)
            }

            val timeRegex = Regex("\"time\"\\s*:\\s*(\\d+)")
            val keyRegex = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"")
            val authorRegex = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"")
            
            val timeMatches = timeRegex.findAll(content).map { it.groupValues[1].toLong() }.toList()
            val keyMatches = keyRegex.findAll(content).map { it.groupValues[1].toInt() }.toList()
            val authorMatch = authorRegex.find(content)?.groupValues?.getOrNull(1) ?: "未知作者"

            if (timeMatches.isEmpty() || keyMatches.isEmpty()) {
                Toast.makeText(this, "读取失败：无有效音符", Toast.LENGTH_SHORT).show()
                return
            }

            val notes = mutableListOf<Pair<Long, Int>>()
            for (i in timeMatches.indices) {
                if (i < keyMatches.size) notes.add(Pair(timeMatches[i], keyMatches[i]))
            }
            
            songData = notes.sortedBy { it.first }
            currentIndex = 0
            currentSongName = file.nameWithoutExtension
            currentAuthor = authorMatch
            currentNotesCount = songData.size
            currentDuration = calculateDuration(songData)

            preparePlay()
            
        } catch (e: Exception) {
            Toast.makeText(this, "读取异常", Toast.LENGTH_SHORT).show()
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
                        MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY; return true }
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
