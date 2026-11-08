package com.skymusic

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
        }

        val btnOverlay = Button(this).apply {
            text = "1. 开启【悬浮窗】权限"
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

        val btnAccessibility = Button(this).apply {
            text = "2. 开启【无障碍】服务"
            setOnClickListener {
                if (!isAccessibilityServiceEnabled(this@MainActivity, SkyAccessibilityService::class.java)) {
                    Toast.makeText(this@MainActivity, "请在列表中开启 SkyMusic 服务", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    Toast.makeText(this@MainActivity, "无障碍服务已开启", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnStart = Button(this).apply {
            text = "3. 启动悬浮窗 (进入光遇)"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this@MainActivity)) {
                    startService(Intent(this@MainActivity, FloatingWindowService::class.java))
                    Toast.makeText(this@MainActivity, "悬浮窗已启动！", Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true) // 退回后台，准备进入游戏
                } else {
                    Toast.makeText(this@MainActivity, "请先开启悬浮窗权限！", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "关闭悬浮窗"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingWindowService::class.java))
                Toast.makeText(this@MainActivity, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(btnOverlay)
        layout.addView(btnAccessibility)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)
    }

    // 检查无障碍服务是否开启的工具方法
    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (enabledService in enabledServices) {
            val enabledServiceInfo = enabledService.resolveInfo.serviceInfo
            if (enabledServiceInfo.packageName == context.packageName && enabledServiceInfo.name == service.name) {
                return true
            }
        }
        return false
    }
}
