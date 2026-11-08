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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // 根布局使用 FrameLayout，方便悬浮右上角设置按钮
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0f172a"))
        }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true

            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(webView: WebView?, cb: android.webkit.ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
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

        // 右上角设置悬浮按钮
        val btnSettings = TextView(this).apply {
            text = "⚙️"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#88000000"))
            setOnClickListener { showSettingsDialog() }
        }
        val settingsParams = FrameLayout.LayoutParams(150, 150).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 80
            rightMargin = 30
        }
        rootLayout.addView(btnSettings, settingsParams)
        setContentView(rootLayout)

        // 自动请求权限并启动悬浮窗
        checkAndStartService()
    }

    private fun checkAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请允许悬浮窗权限以使用自动演奏", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                // 启动悬浮窗服务
                startService(Intent(this, FloatingWindowService::class.java))
            }
        } else {
            startService(Intent(this, FloatingWindowService::class.java))
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf("1. 开启悬浮窗权限", "2. 开启无障碍服务", "3. 手动启动悬浮窗")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    2 -> checkAndStartService()
                }
            }.show()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startPlay(jsonString: String) {
            runOnUiThread {
                if (SkyAccessibilityService.instance == null) {
                    Toast.makeText(this@MainActivity, "请先在设置中开启无障碍服务！", Toast.LENGTH_LONG).show()
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
                    intent.putExtra("song_data", ArrayList(notesList))
                    startService(intent)
                    Toast.makeText(this@MainActivity, "已发送！请切回游戏演奏", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "解析错误", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
