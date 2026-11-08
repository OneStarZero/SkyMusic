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
    
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    private var isMiniMode = true // 默认启动为小球模式

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadKeyPositions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getSerializableExtra("song_data")?.let {
            @Suppress("UNCHECKED_CAST")
            songData = it as ArrayList<Pair<Long, Int>>
            isPlaying = false
            Toast.makeText(this, "已接收网页谱面", Toast.LENGTH_SHORT).show()
            if (!isMiniMode && this::floatView.isInitialized) showFullPanel() // 刷新一下面板
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
            800, 1000, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200
        }

        ballParams = WindowManager.LayoutParams(
            140, 140, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200
        }

        try {
            windowManager.addView(floatView, ballParams)
            showMiniBall() // 默认渲染成悬浮球
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败，请检查权限", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    // 展开完整面板
    private fun showFullPanel() {
        isMiniMode = false
        floatView.removeAllViews()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1A1A2E"))
            setPadding(30, 30, 30, 30)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(this).apply {
            text = "☰ SkyMusic"
            setTextColor(Color.WHITE); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnMinimize = TextView(this).apply {
            text = "➖" // 改成减号表示收起
            setTextColor(Color.parseColor("#38BDF8")); textSize = 24f
            setPadding(20, 0, 0, 0)
            setOnClickListener { showMiniBall() }
        }
        titleBar.addView(titleText)
        titleBar.addView(btnMinimize)
        panel.addView(titleBar)

        panel.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { setMargins(0, 20, 0, 20) }
        })

        val scrollView = ScrollView(this)
        val contentLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(contentLayout)
        panel.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

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
        
        // 先清理原来的参数，再应用新的
        windowManager.updateViewLayout(floatView, panelParams)
        showMainMenu(contentLayout)
    }

    // 缩成悬浮球
    private fun showMiniBall() {
        isMiniMode = true
        floatView.removeAllViews()

        val ball = TextView(this).apply {
            text = "🎵"
            textSize = 32f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#38BDF8"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setTextColor(Color.WHITE)
        }

        floatView.addView(ball)
        setupDrag(ball, ballParams)
        
        // 只有点击动作才会展开
        ball.setOnClickListener {
            if (isMiniMode) showFullPanel()
        }

        windowManager.updateViewLayout(floatView, ballParams)
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
                        params.width = maxOf(500, minOf(initialWidth + deltaX, 1200))
                        params.height = maxOf(400, minOf(initialHeight + deltaY, 1600))
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
                text = "🎵 演奏网页生成的谱面"
                setBackgroundColor(Color.parseColor("#10b981"))
                setOnClickListener { startPlayback() }
            }
            container.addView(btnPlay)
        }
    }

    // 直接读取天遇原生文本，不做任何转化，即点即弹
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
                text = "未找到目录：\n$skyStudioPath"
                setTextColor(Color.RED); textSize = 12f
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
                textSize = 12f; setBackgroundColor(Color.parseColor("#1E293B")); setTextColor(Color.parseColor("#38BDF8"))
                setOnClickListener { 
                    // 点击直接解析并演奏
                    directPlaySkyStudioFile(file)
                    showMiniBall() // 演奏时自动收回小球，不挡视野
                }
            }
            container.addView(btn)
        }
    }

    // 不经过任何复杂处理，直接提取天遇格式的 JSON 数组
    private fun directPlaySkyStudioFile(file: File) {
        try {
            val content = file.readText()
            // 找到 "songNotes" 的数组，直接转成音符
            val jsonArray = if (content.trim().startsWith("[")) {
                val root = JSONArray(content)
                if (root.getJSONObject(0).has("songNotes")) root.getJSONObject(0).getJSONArray("songNotes") else root
            } else {
                JSONArray(content)
            }

            val notes = mutableListOf<Pair<Long, Int>>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                // 天遇的 "1Key0" -> 0
                notes.add(Pair(obj.getLong("time"), obj.getString("key").replace("1Key", "").toIntOrNull() ?: 0))
            }

            if (notes.isEmpty()) { Toast.makeText(this, "谱面为空", Toast.LENGTH_SHORT).show(); return }
            songData = notes
            startPlayback()
        } catch (e: Exception) {
            Toast.makeText(this, "演奏失败: 文件格式异常", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCalibration() {
        showMiniBall() // 校准前先把面板收起来，免得挡住
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
