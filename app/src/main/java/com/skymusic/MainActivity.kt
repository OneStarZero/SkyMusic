package com.skymusic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 根布局
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶部控制条
        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
        }

        val btnOverlay = Button(this).apply {
            text = "开启悬浮窗权限"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@MainActivity, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val btnStart = Button(this).apply {
            text = "启动悬浮窗"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this@MainActivity)) {
                    startService(Intent(this@MainActivity, FloatingWindowService::class.java))
                    Toast.makeText(this@MainActivity, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "请先开启悬浮窗权限！", Toast.LENGTH_SHORT).show()
                }
            }
        }

        controlBar.addView(btnOverlay)
        controlBar.addView(btnStart)
        rootLayout.addView(controlBar)

        // WebView 加载本地的 HTML
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // 允许网页使用本地存储
            webViewClient = WebViewClient()
            // 加载 assets 文件夹里的 index.html
            loadUrl("file:///android_asset/index.html")
        }
        rootLayout.addView(webView)

        setContentView(rootLayout)
    }
}
