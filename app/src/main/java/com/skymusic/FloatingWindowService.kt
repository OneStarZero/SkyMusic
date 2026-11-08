package com.skymusic

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: FrameLayout
    
    private var isPlaying = false
    private var songData = listOf<Pair<Long, Int>>()
    private val keyPositions = mutableListOf<Pair<Float, Float>>()

    private val skyStudioPath = "/storage/emulated/0/Android/data/com.Maple.SkyStudio/files/Sheet/"
    
    // 记录面板和悬浮球的 WindowManager 参数，用于切换
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    
    private var isMiniMode = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadKeyPositions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_CALIBRATE") {
            startCalibration()
            return START_STICKY
        }

        intent?.getSerializableExtra("song_data")?.let {
            @Suppress("UNCHECKED_CAST")
            songData = it as ArrayList<Pair<Long, Int>>
            isPlaying = false
            if (!this::floatView.isInitialized) createFloatingWindow()
            refreshPanelContent()
        }

        if (!this::floatView.isInitialized) {
            createFloatingWindow()
        }
        return START_STICKY
    }

    private fun createFloatingWindow() {
        // 根布局，用于容纳完整面板和悬浮球
        floatView = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        
        panelParams = WindowManager.LayoutParams(
            800, 1000, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200
        }

        ballParams = WindowManager.LayoutParams(
            120, 120, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200
        }

        try {
            windowManager.addView(floatView, panelParams)
            showFullPanel()
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败，请检查权限", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    // 渲染完整面板
    private fun showFullPanel() {
        isMiniMode = false
        floatView.removeAllViews()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1A1A2E"))
            setPadding(30, 30, 30, 30)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // 1. 标题栏（含最小化按钮）
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(this).apply {
            text = "☰ SkyMusic"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnMinimize = TextView(this).apply {
            text = "✖"
            setTextColor(Color.RED)
            textSize = 20f
            setPadding(20, 0, 0, 0)
            setOnClickListener { showMiniBall() }
        }
        titleBar.addView(titleText)
        titleBar.addView(btnMinimize)
        panel.addView(titleBar)

        // 2. 分割线
        panel.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { setMargins(0, 20, 0, 20) }
        })

        // 3. 滚动内容区
        val scrollView = ScrollView(this)
        val contentLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(contentLayout)
        panel.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f)) // 占据剩余空间

        // 4. 底部缩放角标
        val resizeHandle = TextView(this).apply {
            text = "◢"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 24f
            gravity = Gravity.BOTTOM or Gravity.END
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { gravity = Gravity.END }
        }
        panel.addView(resizeHandle)

        floatView.addView(panel)

        // 绑定拖拽和缩放事件
        setupDrag(titleBar, panelParams)
        setupResize(resizeHandle, panelParams)

        // 加载内容
        showMainMenu(contentLayout)

        // 更新窗口尺寸
        windowManager.updateViewLayout(floatView, panelParams)
    }

    // 渲染悬浮球
    private fun showMiniBall() {
        isMiniMode = true
        floatView.removeAllViews()

        val ball = TextView(this).apply {
            text = "🎵"
            textSize = 28f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#38BDF8"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            
            // 为了美观加个圆角背景（简易版）
            setTextColor(Color.WHITE)
        }

        floatView.addView(ball)
        setupDrag(ball, ballParams)
        
        ball.setOnClickListener {
            if (isMiniMode) showFullPanel()
        }

        // 更新窗口尺寸
        windowManager.updateViewLayout(floatView, ballParams)
    }

    // 面板拖动逻辑
    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        return false // 返回 false 是为了能让点击事件生效
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

    // 角标拖拽缩放逻辑
    private fun setupResize(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialWidth = 0; private var initialHeight = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = params.width
                        initialHeight = params.height
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        
                        // 限制最小和最大尺寸
                        val newWidth = maxOf(500, minOf(initialWidth + deltaX, 1200))
                        val newHeight = maxOf(400, minOf(initialHeight + deltaY, 1600))
                        
                        params.width = newWidth
                        params.height = newHeight
                        windowManager.updateViewLayout(floatView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    // 刷新面板（外部调用）
    private fun refreshPanelContent() {
        if (!isMiniMode && this::floatView.isInitialized) {
            showFullPanel()
        }
    }

    private fun showMainMenu(container: LinearLayout) {
        container.removeAllViews()
        
        val btnLibrary = Button(this).apply {
            text = "📂 读取天遇本地曲库"
            setOnClickListener { loadLocalScores(container) }
        }
        container.addView(btnLibrary)

        val btnCalibrate = Button(this).apply {
            text = "🎯 校准 15 键位"
            setOnClickListener { startCalibration() }
        }
        container.addView(btnCalibrate)

        if (songData.isNotEmpty()) {
            val btnPlay = Button(this).apply {
                text = "🎵 开始演奏 (网页发送)"
                setBackgroundColor(Color.parseColor("#10b981"))
                setOnClickListener { startPlayback() }
            }
            container.addView(btnPlay)
        }
    }

    private fun loadLocalScores(container: LinearLayout) {
        container.removeAllViews()
        
        val btnBack = Button(this).apply {
            text = "⬅ 返回"
            setOnClickListener { showMainMenu(container) }
        }
        container.addView(btnBack)

        val folder = File(skyStudioPath)
        if (!folder.exists() || !folder.isDirectory) {
            val tv = TextView(this).apply {
                text = "未找到目录：\n$skyStudioPath\n(部分系统限制访问)"
                setTextColor(Color.RED); textSize = 12f; setPadding(0, 20, 0, 20)
            }
            container.addView(tv)
            return
        }

        val files = folder.listFiles { f -> f.extension.equals("txt", true) || f.extension.equals("json", true) }
        if (files.isNullOrEmpty()) {
            container.addView(TextView(this).apply { text = "无谱面"; setTextColor(Color.GRAY) })
            return
        }

        for (file in files) {
            val btn = Button(this).apply {
                text = "🎵 ${file.nameWithoutExtension}"
                textSize = 12f; setBackgroundColor(Color.parseColor("#1E293B")); setTextColor(Color.parseColor("#38BDF8"))
                setOnClickListener { directPlaySkyStudioFile(file) }
            }
            container.addView(btn)
        }
    }

    private fun directPlaySkyStudioFile(file: File) {
        try {
            val content = file.readText()
            val jsonArray = if (content.trim().startsWith("[")) {
                val root = JSONArray(content)
                if (root.getJSONObject(0).has("songNotes")) root.getJSONObject(0).getJSONArray("songNotes") else root
            } else {
                JSONArray(content)
            }

            val notes = mutableListOf<Pair<Long, Int>>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                notes.add(Pair(obj.getLong("time"), obj.getString("key").replace("1Key", "").toIntOrNull() ?: 0))
            }

            if (notes.isEmpty()) { Toast.makeText(this, "谱面为空", Toast.LENGTH_SHORT).show(); return }
            songData = notes
            startPlayback()
        } catch (e: Exception) {
            Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCalibration() {
        Toast.makeText(this, "拖动点对齐琴键，点下方保存", Toast.LENGTH_LONG).show()
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

    private fun startPlayback() {
        if (isPlaying) return
        if (songData.isEmpty()) { Toast.makeText(this, "没有可播放的谱面", Toast.LENGTH_SHORT).show(); return }
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) { Toast.makeText(this, "请先校准键位！", Toast.LENGTH_SHORT).show(); return }
        
        isPlaying = true
        Toast.makeText(this, "开始演奏！", Toast.LENGTH_SHORT).show()
        
        val handler = Handler(Looper.getMainLooper())
        var index = 0

        val runnable = object : Runnable {
            override fun run() {
                if (!isPlaying || index >= songData.size) { isPlaying = false; return }
                val (timeMs, keyIndex) = songData[index]
                val point = keyPositions.getOrNull(keyIndex) ?: Pair(0f, 0f)
                
                if (point.first != -1f) SkyAccessibilityService.instance?.clickPoints(listOf(point))
                
                index++
                if (index < songData.size) handler.postDelayed(this, maxOf(songData[index].first - timeMs, 1))
            }
        }
        handler.post(runnable)
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
        isPlaying = false
        if (this::floatView.isInitialized) {
            try { windowManager.removeView(floatView) } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
