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
import org.json.JSONArray

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
        
        // 申请存储权限
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

        // 右上角原生设置按钮
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

        checkPermissions()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请点击右上角设置，授予悬浮窗权限", Toast.LENGTH_LONG).show()
        }
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
            runOnUiThread {
                if (SkyAccessibilityService.instance == null) {
                    Toast.makeText(this@MainActivity, "请先在设置中开启无障碍！", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try {
                    val jsonArray = JSONArray(jsonString)
                    val notesList = mutableListOf<Pair<Long, Int>>()
                    for (i in 0 until jsonArray.length()) {
                        val noteObj = jsonArray.getJSONObject(i)
                        notesList.add(Pair(noteObj.getLong("time"), noteObj.getString("key").replace("1Key", "").toIntOrNull() ?: 0))
                    }

                    val intent = Intent(this@MainActivity, FloatingWindowService::class.java)
                    intent.putExtra("is_new_data", true)
                    intent.putExtra("song_data", ArrayList(notesList))
                    intent.putExtra("song_name", "App 发送谱面")
                    intent.putExtra("song_author", "网页端")
                    startService(intent)
                    
                    Toast.makeText(this@MainActivity, "已发送至悬浮窗！", Toast.LENGTH_SHORT).show()

                    // 强制弹出保存询问
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("保存谱面")
                        .setMessage("是否将当前生成的谱面保存到本地曲库？")
                        .setCancelable(false)
                        .setPositiveButton("保存") { _, _ ->
                            val success = saveSongToLocal(jsonString)
                            if (success) Toast.makeText(this@MainActivity, "已保存至 AppSheets 目录！", Toast.LENGTH_LONG).show()
                            else Toast.makeText(this@MainActivity, "保存失败", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()

                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "解析错误: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 保存到公开的 AppSheets 目录
    private fun saveSongToLocal(jsonString: String): Boolean {
        return try {
            val folder = java.io.File("/storage/emulated/0/SkyMusic-OneStar/AppSheets/")
            if (!folder.exists()) folder.mkdirs()

            val fileName = "SkyMusic_${System.currentTimeMillis()}.txt"
            val file = java.io.File(folder, fileName)

            val content = "[{\"name\":\"App生成_${System.currentTimeMillis()}\",\"author\":\"SkyMusic\",\"isComposed\":false,\"bpm\":700,\"bitsPerPage\":16,\"pitchLevel\":0,\"isEncrypted\":false,\"songNotes\":$jsonString}]"
            file.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
