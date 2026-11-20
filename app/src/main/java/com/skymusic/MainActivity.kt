package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0f172a")) }

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

        val btnSettings = TextView(this).apply {
            text = "⚙️ 设置"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(30, 20, 30, 20)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setOnClickListener { showSettingsDialog() }
        }
        val settingsParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 60
            rightMargin = 20
        }
        rootLayout.addView(btnSettings, settingsParams)
        setContentView(rootLayout)
    }

    private fun showSettingsDialog() {
        val options = arrayOf("开启悬浮窗权限", "开启无障碍服务", "手动启动悬浮球", "刷新随机背景")
        AlertDialog.Builder(this)
            .setTitle("SkyMusic 设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    2 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                            Toast.makeText(this, "请先开启悬浮窗权限！", Toast.LENGTH_SHORT).show()
                        } else {
                            val intent = Intent(this, FloatingWindowService::class.java)
                            startService(intent)
                            Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        // 🌟 通过 JS 桥接通知 WebView 刷新背景颜色
                        webViewInstance?.evaluateJavascript("if (window.initRandomBackground) window.initRandomBackground();", null)
                        Toast.makeText(this, "✨ 主题已刷新！", Toast.LENGTH_SHORT).show()
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
            val fileName = "$name-$author.txt".replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val targetFile = File(folder, fileName)
            targetFile.writeText(fullJson, Charsets.UTF_8)
            Toast.makeText(this, "✅ 已保存至:\n${targetFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}