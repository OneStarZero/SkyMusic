package com.skymusic

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: LinearLayout

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (this::floatView.isInitialized) return START_STICKY

        // 主悬浮窗布局
        floatView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC1A1A2E")) // 深色半透明背景
            setPadding(40, 40, 40, 40)
        }

        // 拖动标题
        val titleBtn = Button(this).apply {
            text = "☰ 按住拖动"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
        }

        val btnPlay = Button(this).apply {
            text = "▶ 播放测试"
            setOnClickListener {
                // 测试触发一次无障碍点击 (屏幕中心)
                val dm = resources.displayMetrics
                SkyAccessibilityService.instance?.clickPoints(listOf(Pair(dm.widthPixels / 2f, dm.heightPixels / 2f)))
                Toast.makeText(this@FloatingWindowService, "已触发点击中心", Toast.LENGTH_SHORT).show()
            }
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

        // 实现拖拽逻辑
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
            Toast.makeText(this, "悬浮窗创建失败，请检查权限", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::floatView.isInitialized) {
            windowManager.removeView(floatView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
