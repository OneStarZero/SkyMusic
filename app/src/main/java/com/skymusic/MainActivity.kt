package com.skymusic

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        val btnOverlay = Button(this).apply {
            text = "1. 开启悬浮窗权限"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        
        val btnStart = Button(this).apply {
            text = "2. 启动悬浮窗"
            setOnClickListener {
                startService(Intent(this@MainActivity, FloatingWindowService::class.java))
            }
        }
        
        layout.addView(btnOverlay)
        layout.addView(btnStart)
        setContentView(layout)
    }
}
