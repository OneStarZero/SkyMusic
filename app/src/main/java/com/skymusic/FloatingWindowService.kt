package com.skymusic

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
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
        floatView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(20, 20, 20, 20)
        }

        val btnPlay = Button(this).apply {
            text = "测试点击 (3秒后)"
            setOnClickListener { testClick() }
        }
        floatView.addView(btnPlay)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 200

        windowManager.addView(floatView, params)
        return START_STICKY
    }

    private fun testClick() {
        Toast.makeText(this, "3秒后点击屏幕中心", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({
            val dm = resources.displayMetrics
            val centerX = dm.widthPixels / 2f
            val centerY = dm.heightPixels / 2f
            SkyAccessibilityService.instance?.clickPoints(listOf(Pair(centerX, centerY)))
        }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatView.isInitialized) windowManager.removeView(floatView)
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
