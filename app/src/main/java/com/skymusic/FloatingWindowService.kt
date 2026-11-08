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
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: LinearLayout
    private var isPlaying = false
    
    // 这是你需要从网页 txt 里读取并解析的数据
    // 结构: (时间ms, 按键索引 0-14)
    private val songData = listOf(
        Pair(0L, 0), Pair(200L, 1), Pair(400L, 2), Pair(600L, 3)
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (this::floatView.isInitialized) return START_STICKY

        floatView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC1A1A2E"))
            setPadding(40, 40, 40, 40)
        }

        val titleBtn = Button(this).apply {
            text = "☰ 拖动"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
        }

        val btnPlay = Button(this).apply {
            text = "▶ 播放测试"
            setOnClickListener { startPlayback() }
        }

        val btnClose = Button(this).apply {
            text = "✖ 关闭"
            setOnClickListener { stopSelf() }
        }

        floatView.addView(titleBtn)
        floatView.addView(btnPlay)
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
        params.x = 200
        params.y = 200

        titleBtn.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
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

        try {
            windowManager.addView(floatView, params)
        } catch (e: Exception) {
            stopSelf()
        }

        return START_STICKY
    }

    private fun startPlayback() {
        if (isPlaying) return
        if (SkyAccessibilityService.instance == null) {
            Toast.makeText(this, "请先开启无障碍服务！", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaying = true
        Toast.makeText(this, "开始演奏", Toast.LENGTH_SHORT).show()
        
        val handler = Handler(Looper.getMainLooper())
        var index = 0

        val runnable = object : Runnable {
            override fun run() {
                if (!isPlaying || index >= songData.size) {
                    isPlaying = false
                    return
                }
                
                val (timeMs, keyIndex) = songData[index]
                
                // 这里需要用户校准后的坐标
                // 为了测试，我们先写死在屏幕中心或者固定位置
                val dm = resources.displayMetrics
                val x = (dm.widthPixels / 15f) * (keyIndex + 1) 
                val y = dm.heightPixels * 0.8f // 假设琴键在屏幕下方 80% 处
                
                // 触发无障碍点击
                SkyAccessibilityService.instance?.clickPoints(listOf(Pair(x, y)))
                
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
        if (this::floatView.isInitialized) {
            windowManager.removeView(floatView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
