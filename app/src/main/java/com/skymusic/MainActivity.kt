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
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var webView: WebView

    // 处理网页端选择文件
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
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                )
            }
        }

        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0f172a")) }

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                
                // 极限性能优化
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            
            // 强制硬件加速
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(webView: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = cb
                    try {
                        fileChooserLauncher.launch(params?.createIntent())
                    } catch (e: Exception) {
                        filePathCallback = null
                        return false
                    }
                    return true
                }
            }
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        rootLayout.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootLayout)
    }

    // 网页交互接口
    inner class WebAppInterface {
        @JavascriptInterface
        fun startPlay(jsonString: String) {
            runOnUiThread { handlePayload(jsonString, false) }
        }

        @JavascriptInterface
        fun saveLocal(jsonString: String) {
            runOnUiThread { handlePayload(jsonString, true) }
        }
    }

    // 处理保存与播放
    private fun handlePayload(payloadStr: String, isSaveAction: Boolean) {
        try {
            val payload = JSONObject(payloadStr)
            val name = payload.optString("name", "未命名")
            val author = payload.optString("author", "未知作者")
            
            val notesArray = payload.optJSONArray("notes")
            val notesList = mutableListOf<Pair<Long, Int>>()
            if (notesArray != null) {
                for (i in 0 until notesArray.length()) {
                    val obj = notesArray.getJSONObject(i)
                    notesList.add(Pair(obj.getLong("time"), obj.getString("key").replace("1Key", "").toIntOrNull() ?: 0))
                }
            }

            if (isSaveAction) {
                // 保存逻辑：存入 LocalSheets
                val fullJson = payload.optString("fullJson", payloadStr)
                val targetFolder = "LocalSheets" 
                val folder = File("/storage/emulated/0/SkyMusic-OneStar/$targetFolder/")
                if (!folder.exists()) folder.mkdirs()

                val fileName = "$name-$author.txt".replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val targetFile = File(folder, fileName)
                targetFile.writeText(fullJson, Charsets.UTF_8)
                
                Toast.makeText(this@MainActivity, "✅ 已保存至 LocalSheets", Toast.LENGTH_SHORT).show()
            } else {
                // 播放逻辑
                sendToPlayService(notesList, name, author)
                Toast.makeText(this@MainActivity, "🚀 已发送至悬浮窗", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 检查无障碍并拉起悬浮窗
    private fun sendToPlayService(notesList: List<Pair<Long, Int>>, name: String, author: String) {
        if (SkyAccessibilityService.instance == null) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("需要开启无障碍服务才能自动演奏，是否前往设置？")
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
