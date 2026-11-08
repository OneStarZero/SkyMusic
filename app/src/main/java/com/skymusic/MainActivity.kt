package com.skymusic

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    
    // 用于接收网页发起的文件选择请求
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK && result.data != null) {
            val dataString = result.data?.dataString
            if (dataString != null) {
                results = arrayOf(Uri.parse(dataString))
            }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0f172a")) // 修复白屏，底色调黑
        }

        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(android.graphics.Color.parseColor("#1e293b"))
        }

        val btnOverlay = Button(this).apply {
            text = "1. 悬浮窗权限"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@MainActivity, "已开启", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "2. 无障碍服务"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "请开启 SkyMusic 服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        controlBar.addView(btnOverlay)
        controlBar.addView(btnAccessibility)
        rootLayout.addView(controlBar)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = WebViewClient()
            
            // 核心修复：接管网页的文件上传请求
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    
                    val intent = fileChooserParams?.createIntent()
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: Exception) {
                        this@MainActivity.filePathCallback = null
                        return false
                    }
                    return true
                }
            }
            
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }
        rootLayout.addView(webView)

        setContentView(rootLayout)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startPlay(jsonString: String) {
            runOnUiThread {
                if (SkyAccessibilityService.instance == null) {
                    Toast.makeText(this@MainActivity, "请先开启无障碍服务！", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "请先开启悬浮窗权限！", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                try {
                    val jsonArray = JSONArray(jsonString)
                    val notesList = mutableListOf<Pair<Long, Int>>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val noteObj = jsonArray.getJSONObject(i)
                        val time = noteObj.getLong("time")
                        val keyStr = noteObj.getString("key")
                        val keyIndex = keyStr.replace("1Key", "").toIntOrNull() ?: 0
                        notesList.add(Pair(time, keyIndex))
                    }

                    val intent = Intent(this@MainActivity, FloatingWindowService::class.java)
                    intent.putExtra("song_data", ArrayList(notesList))
                    startService(intent)
                    
                    Toast.makeText(this@MainActivity, "数据已发送！请去光遇里校准并播放", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
