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
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK && result.data != null) {
            result.data?.dataString?.let { results = arrayOf(Uri.parse(it)) }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                )
            }
        }

        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0f172a")) }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(webView: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = cb
                    try { fileChooserLauncher.launch(params?.createIntent()) } catch (e: Exception) { this@MainActivity.filePathCallback = null; return false }
                    return true
                }
            }
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }
        rootLayout.addView(webView, FrameLayout.LayoutParams(-1, -1))

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
        val options = arrayOf("开启悬浮窗权限", "开启无障碍服务", "手动启动悬浮球")
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
                            startService(Intent(this, FloatingWindowService::class.java))
                            Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.show()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startPlay(jsonString: String) {
            runOnUiThread { handlePayload(jsonString, "AppSheets", false) }
        }

        @JavascriptInterface
        fun saveLocal(jsonString: String) {
            runOnUiThread { handlePayload(jsonString, "LocalSheets", true) }
        }
    }

    // 核心逻辑：根据 isSaveAction 区分是保存还是播放
    private fun handlePayload(payloadStr: String, defaultFolder: String, isSaveAction: Boolean) {
        try {
            val payload = JSONObject(payloadStr)
            val name = payload.optString("name", "未命名")
            val author = payload.optString("author", "未知作者")
            val transcriber = payload.optString("transcriber", "未知作者")
            
            val notesArray = payload.optJSONArray("notes")
            val notesList = mutableListOf<Pair<Long, Int>>()
            if (notesArray != null) {
                for (i in 0 until notesArray.length()) {
                    val obj = notesArray.getJSONObject(i)
                    notesList.add(Pair(obj.getLong("time"), obj.getString("key").replace("1Key", "").toIntOrNull() ?: 0))
                }
            }

            // 如果是保存动作
            if (isSaveAction) {
                val fullJson = payload.optString("fullJson", payloadStr)
                // 网页端传的是 AppSheets，但原生端强制判断：如果是保存，就存到 LocalSheets（或者你可以根据实际需求调整）
                // 这里为了适配你 "只有点击保存到本地才保存" 的需求，强制存在 LocalSheets
                val targetFolder = "LocalSheets" 
                
                val folder = File("/storage/emulated/0/SkyMusic-OneStar/$targetFolder/")
                if (!folder.exists()) folder.mkdirs()

                val fileName = "$name-$author.txt".replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val targetFile = File(folder, fileName)

                // 强制覆盖写入
                targetFile.writeText(fullJson, Charsets.UTF_8)
                
                Toast.makeText(this@MainActivity, "✅ 已保存至 $targetFolder", Toast.LENGTH_SHORT).show()
            } else {
                // 如果是播放动作，直接发送到悬浮窗，不写文件
                sendToPlayService(notesList, name, author)
                Toast.makeText(this@MainActivity, "🚀 已发送至悬浮窗", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "❌ 处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendToPlayService(notesList: List<Pair<Long, Int>>, name: String, author: String) {
        if (SkyAccessibilityService.instance == null) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("谱面已就绪！但需要开启无障碍服务才能自动演奏，是否前往设置？")
                .setPositiveButton("前往") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val intent = Intent(this, FloatingWindowService::class.java)
        intent.putExtra("is_new_data", true)
        intent.putExtra("song_data", ArrayList(notesList))
        intent.putExtra("song_name", name)
        intent.putExtra("song_author", author)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
