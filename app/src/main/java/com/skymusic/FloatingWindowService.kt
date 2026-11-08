package com.skymusic

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: LinearLayout
    private lateinit var panelContainer: LinearLayout
    private var isPlaying = false
    private var songData = listOf<Pair<Long, Int>>()
    private val keyPositions = mutableListOf<Pair<Float, Float>>()

    // 天遇谱面路径
    private val skyStudioPath = "/storage/emulated/0/Android/data/com.Maple.SkyStudio/files/Sheet/"

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
            updatePlayButtonText("🎵 开始演奏", true)
        }

        if (!this::floatView.isInitialized) {
            createFloatingWindow()
        }
        return START_STICKY
    }

    private fun createFloatingWindow() {
        floatView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1A1A2E"))
            setPadding(30, 30, 30, 30)
        }

        // 标题栏（可拖动）
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
        val btnClose = TextView(this).apply {
            text = "✖"
            setTextColor(Color.RED)
            textSize = 18f
            setPadding(20, 0, 0, 0)
            setOnClickListener { stopSelf() }
        }
        titleBar.addView(titleText)
        titleBar.addView(btnClose)
        floatView.addView(titleBar)

        // 分割线
        floatView.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { setMargins(0, 20, 0, 20) }
        })

        // 滚动列表容器
        val scrollView = ScrollView(this)
        panelContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(panelContainer)
        floatView.addView(scrollView, LinearLayout.LayoutParams(600, 800)) // 固定宽高

        // 初始显示主菜单
        showMainMenu()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 200

        // 拖动逻辑
        titleBar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        return true
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

        try { windowManager.addView(floatView, params) } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败，请检查权限", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    // 主页菜单
    private fun showMainMenu() {
        panelContainer.removeAllViews()
        
        val btnLibrary = Button(this).apply {
            text = "📂 读取天遇本地曲库"
            setOnClickListener { loadLocalScores() }
        }
        panelContainer.addView(btnLibrary)

        val btnCalibrate = Button(this).apply {
            text = "🎯 校准 15 键位"
            setOnClickListener { startCalibration() }
        }
        panelContainer.addView(btnCalibrate)

        if (songData.isNotEmpty()) {
            val btnPlay = Button(this).apply {
                text = "🎵 开始演奏 (已接收网页谱面)"
                setBackgroundColor(Color.parseColor("#10b981"))
                setOnClickListener { startPlayback() }
            }
            panelContainer.addView(btnPlay)
        }
    }

    // 直接扫描目录读取
    private fun loadLocalScores() {
        panelContainer.removeAllViews()
        
        val btnBack = Button(this).apply {
            text = "⬅ 返回主菜单"
            setOnClickListener { showMainMenu() }
        }
        panelContainer.addView(btnBack)

        val folder = File(skyStudioPath)
        if (!folder.exists() || !folder.isDirectory) {
            val tv = TextView(this).apply {
                text = "未找到天遇目录：\n$skyStudioPath\n\n(部分安卓11+系统可能拒绝访问，请通过网页发送)"
                setTextColor(Color.RED)
                textSize = 12f
                setPadding(0, 20, 0, 20)
            }
            panelContainer.addView(tv)
            return
        }

        val files = folder.listFiles { f -> f.extension.equals("txt", true) || f.extension.equals("json", true) }
        if (files.isNullOrEmpty()) {
            val tv = TextView(this).apply { text = "目录内无谱面文件"; setTextColor(Color.GRAY); setPadding(0, 20, 0, 20) }
            panelContainer.addView(tv)
            return
        }

        for (file in files) {
            val btn = Button(this).apply {
                text = "🎵 ${file.nameWithoutExtension}"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.parseColor("#38BDF8"))
                setOnClickListener { directPlaySkyStudioFile(file) }
            }
            panelContainer.addView(btn)
        }
    }

    // 直接解析天遇的 txt（无需 AI，原生转换）
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

            if (notes.isEmpty()) {
                Toast.makeText(this, "谱面为空", Toast.LENGTH_SHORT).show()
                return
            }
            songData = notes
            startPlayback()

        } catch (e: Exception) {
            Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private var playBtnRef: Button? = null
    private fun updatePlayButtonText(text: String, show: Boolean) {
        if (this::floatView.isInitialized) {
            showMainMenu() // 重置为主界面来刷新状态
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
                text = "${i + 1}"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#80FFFFFF"))
                gravity = Gravity.CENTER
                setPadding(20, 20, 20, 20)
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
                            initialX = params.x; initialY = params.y
                            initialTouchX = event.rawX; initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
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
            text = "✅ 保存键位"
            setBackgroundColor(Color.GREEN)
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
        btnParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        btnParams.y = 150
        windowManager.addView(saveBtn, btnParams)
    }

    private fun startPlayback() {
        if (isPlaying) return
        if (songData.isEmpty()) { Toast.makeText(this, "没有可播放的谱面", Toast.LENGTH_SHORT).show(); return }
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) { Toast.makeText(this, "请先校准键位！", Toast.LENGTH_SHORT).show(); return }
        
        isPlaying = true
        Toast.makeText(this, "开始演奏！", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true) // 尽可能把悬浮窗留在前台
        
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
