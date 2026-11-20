package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private var webViewInstance: WebView? = null

    private val midiPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val midiPath = result.data?.getStringExtra("midiPath")
            val fileName = result.data?.getStringExtra("fileName")
            if (midiPath != null && fileName != null) {
                val safeName = fileName.replace("'", "\\'")
                webViewInstance?.evaluateJavascript("window.onMidiPathReceived('$midiPath', '$safeName');", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                )
            }
        }

        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B1120")) }

        webViewInstance = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        rootLayout.addView(webViewInstance, FrameLayout.LayoutParams(-1, -1))

        // 🌟 优化：把设置按钮做成半透明玻璃质感，去掉突兀的黑底
        val btnSettings = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#550B1120")) // 半透明深蓝
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { showSettingsDialog() }
        }
        
        rootLayout.addView(btnSettings, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 60
            rightMargin = 20
        })
        setContentView(rootLayout)
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            "🌌 刷新随机背景 (更换星空主题)",
            "🚀 启动悬浮球",
            "🔑 开启悬浮窗权限",
            "♿ 开启无障碍服务",
            "ℹ️ 关于 SkyMusic 0.0.7"
        )
        
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("SkyMusic 设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // 🌟 调用 JS 刷新背景
                        webViewInstance?.evaluateJavascript("if (window.refreshRandomBackground) window.refreshRandomBackground();", null)
                        Toast.makeText(this, "✨ 正在生成新的星空...", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                            Toast.makeText(this, "请先开启悬浮窗权限！", Toast.LENGTH_SHORT).show()
                        } else {
                            startService(Intent(this, FloatingWindowService::class.java))
                            Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    3 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    4 -> {
                        AlertDialog.Builder(this)
                            .setTitle("关于")
                            .setMessage("SkyMusic 0.0.7\n\n光遇 MIDI 智能转谱工具\n\n愿你在这片星空下奏响最美的旋律。")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            }.show()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startPlay(jsonString: String) = runOnUiThread { handleSendApp(jsonString) }

        @JavascriptInterface
        fun saveLocal(jsonString: String) = runOnUiThread { handleSaveLocal(jsonString) }

        @JavascriptInterface
        fun openMidiPicker() {
            runOnUiThread { midiPickerLauncher.launch(Intent(this@MainActivity, MidiPickerActivity::class.java)) }
        }

        @JavascriptInterface
        fun saveTheme(jsonString: String) = runOnUiThread {
            try {
                val o = JSONObject(jsonString)
                getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE).edit()
                    .putFloat("theme_hue", o.optDouble("hue", 220.0).toFloat())
                    .putString("theme_accent", o.optString("accent", "#7DD3FC"))
                    .putString("theme_bg_top", o.optString("bgTop", "#0B1120"))
                    .putString("theme_bg_bottom", o.optString("bgBottom", "#1E1B4B"))
                    .apply()
            } catch (_: Exception) {}
        }
    }

    private fun handleSendApp(payloadStr: String) {
        try {
            val payload = JSONObject(payloadStr)
            val notesArray = payload.optJSONArray("notes")
            val notesList = mutableListOf<Pair<Long, Int>>()
            if (notesArray != null) {
                for (i in 0 until notesArray.length()) {
                    val obj = notesArray.getJSONObject(i)
                    notesList.add(Pair(obj.getLong("time"), obj.getString("key").replace("1Key", "").toIntOrNull() ?: 0))
                }
            }
            val intent = Intent(this, FloatingWindowService::class.java)
            intent.putExtra("is_new_data", true)
            intent.putExtra("song_data", ArrayList(notesList))
            intent.putExtra("song_name", payload.optString("name", "未命名"))
            intent.putExtra("song_author", payload.optString("author", "未知作者"))
            intent.putExtra("song_model", payload.optString("model", ""))
            intent.putExtra("song_bpm", payload.optString("bpm", "0"))
            startService(intent)
            Toast.makeText(this, "🚀 已发送至悬浮窗", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSaveLocal(payloadStr: String) {
    try {
        val payload = JSONObject(payloadStr)
        val name = payload.optString("name", "未命名")
        val author = payload.optString("author", "未知作者")
        val fullJson = payload.optString("fullJson", payloadStr)
        val folder = File("/storage/emulated/0/SkyMusic-OneStar/LocalSheets/")
        if (!folder.exists()) folder.mkdirs()
        
        // 🌟 将模型名从 name 中提取出来，或者从 payload 提取
        val baseName = if (name.contains(" || ")) name.substringBefore(" || ").trim() else name
        val modelName = if (name.contains(" || ")) name.substringAfter(" || ").trim() else payload.optString("model", "")
        
        // 🌟 严格按照 歌曲名-作者 || 模型名.txt 命名
        val fileName = if (modelName.isNotBlank()) {
            "$baseName-$author || $modelName.txt"
        } else {
            "$baseName-$author.txt"
        }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        
        File(folder, fileName).writeText(fullJson, Charsets.UTF_8)
        Toast.makeText(this, "✅ 已保存", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(this, "❌ 保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
}