package com.skymusic

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var isPlaying = false
    private var songData = listOf<Pair<Long, Int>>()
    
    // 存储15个键的坐标
    private val keyPositions = mutableListOf<Pair<Float, Float>>()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        loadKeyPositions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getSerializableExtra("song_data")?.let {
            @Suppress("UNCHECKED_CAST")
            songData = it as ArrayList<Pair<Long, Int>>
            isPlaying = false 
        }

        if (!this::floatView.isInitialized) {
            createFloatingWindow()
        }
        return START_STICKY
    }

    private fun loadKeyPositions() {
        keyPositions.clear()
        for (i in 0 until 15) {
            val x = prefs.getFloat("key_${i}_x", -1f)
            val y = prefs.getFloat("key_${i}_y", -1f)
            keyPositions.add(Pair(x, y))
        }
    }

    private fun saveKeyPositions() {
        val editor = prefs.edit()
        for (i in 0 until 15) {
            editor.putFloat("key_${i}_x", keyPositions[i].first)
            editor.putFloat("key_${i}_y", keyPositions[i].second)
        }
        editor.apply()
    }

    private fun createFloatingWindow() {
        floatView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC1A1A2E"))
            setPadding(30, 30, 30, 30)
        }

        val titleText = TextView(this).apply {
            text = "☰ 按住拖动面板"
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }

        val btnCalibrate = Button(this).apply {
            text = "🎯 拖拽校准键位"
            setOnClickListener { startCalibration() }
        }

        val btnStartPlay = Button(this).apply {
            text = "🎵 开始演奏"
            setOnClickListener {
                if (songData.isNotEmpty()) {
                    if (keyPositions[0].first == -1f) {
                        Toast.makeText(this@FloatingWindowService, "请先校准键位！", Toast.LENGTH_SHORT).show()
                    } else {
                        startPlayback()
                    }
                } else {
                    Toast.makeText(this@FloatingWindowService, "请先在网页里发送数据", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnClose = Button(this).apply {
            text = "✖ 关闭悬浮窗"
            setOnClickListener { stopSelf() }
        }

        floatView.addView(titleText)
        floatView.addView(btnCalibrate)
        floatView.addView(btnStartPlay)
        floatView.addView(btnClose)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        // 主面板拖动逻辑
        titleText.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

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

        try { windowManager.addView(floatView, params) } catch (e: Exception) {}
    }

    // 核心：进入校准模式
    private fun startCalibration() {
        Toast.makeText(this, "拖动小圆点到光遇琴键上", Toast.LENGTH_LONG).show()
        
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        // 初始化坐标（如果之前没保存过，就在屏幕中下方排成 3x5 的阵列）
        if (keyPositions[0].first == -1f) {
            for (i in 0 until 15) {
                val row = i / 5
                val col = i % 5
                keyPositions[i] = Pair(
                    w * (col + 1) / 6f,
                    h * (row + 6) / 10f
                )
            }
        }

        // 创建 15 个可拖拽的小圆点
        val calibrateViews = mutableListOf<TextView>()
        for (i in 0 until 15) {
            val dot = TextView(this).apply {
                text = "${i+1}"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#80FFFFFF")) // 半透明白色
                gravity = Gravity.CENTER
                setPadding(15, 15, 15, 15)
            }

            val params = WindowManager.LayoutParams(
                100, 100,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = keyPositions[i].first.toInt() - 50 // 居中修正
            params.y = keyPositions[i].second.toInt() - 50

            dot.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

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
                            
                            // 实时记录当前坐标（注意加上半径修正）
                            keyPositions[i] = Pair((params.x + 50).toFloat(), (params.y + 50).toFloat())
                            return true
                        }
                    }
                    return false
                }
            })
            
            windowManager.addView(dot, params)
            calibrateViews.add(dot)
        }

        // 临时弹出一个“保存”按钮
        val saveBtn = Button(this).apply {
            text = "✅ 保存键位"
            setBackgroundColor(Color.GREEN)
            setOnClickListener {
                // 保存到本地
                saveKeyPositions()
                // 移除所有校准点
                calibrateViews.forEach { windowManager.removeView(it) }
                windowManager.removeView(this)
                Toast.makeText(this@FloatingWindowService, "键位已保存！", Toast.LENGTH_SHORT).show()
            }
        }

        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        btnParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        btnParams.y = 100
        windowManager.addView(saveBtn, btnParams)
    }

    private fun startPlayback() {
        if (isPlaying) return
        isPlaying = true
        Toast.makeText(this, "开始演奏！", Toast.LENGTH_SHORT).show()
        
        val handler = Handler(Looper.getMainLooper())
        var index = 0

        val runnable = object : Runnable {
            override fun run() {
                if (!isPlaying || index >= songData.size) {
                    isPlaying = false; return
                }
                
                val (timeMs, keyIndex) = songData[index]
                
                // 去本地缓存里取出用户校准的精确坐标
                val point = keyPositions.getOrNull(keyIndex) ?: Pair(0f, 0f)
                
                if (point.first != -1f) {
                    SkyAccessibilityService.instance?.clickPoints(listOf(point))
                }
                
                index++
                
                if (index < songData.size) {
                    val nextDelay = songData[index].first - timeMs
                    handler.postDelayed(this, maxOf(nextDelay, 1))
                }
            }
        }
        handler.post(runnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false
        if (this::floatView.isInitialized) windowManager.removeView(floatView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
