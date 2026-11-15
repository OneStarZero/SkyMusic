package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
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
import java.io.InputStream

class MainActivity : AppCompatActivity() {
    private var previewMediaPlayer: MediaPlayer? = null
    private var webViewInstance: WebView? = null

    private val midiFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        
        try {
            var fileName = "未知文件.mid"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                }
            }

            // 🌟 修复：严格拦截，如果不是 mid 或 midi 结尾，绝不进行后续操作，防止 MP3 卡死
            if (!fileName.lowercase().endsWith(".mid") && !fileName.lowercase().endsWith(".midi")) {
                runOnUiThread { Toast.makeText(this, "请选择标准 MIDI 文件 (.mid / .midi)", Toast.LENGTH_SHORT).show() }
                return@registerForActivityResult
            }

            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()

            if (bytes != null) {
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                runOnUiThread {
                    val jsCode = "window.onFileSelectedFromAndroid('data:application/octet-stream;base64,$base64Data', '${fileName.replace("'", "\\'")}');"
                    webViewInstance?.evaluateJavascript(jsCode, null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show() }
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
            
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(webView: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    // 直接调起原生选择器
                    midiFileLauncher.launch("*/*")
                    return true
                }
            }
            
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
                            val intent = Intent(this, FloatingWindowService::class.java)
                            startService(intent)
                            Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
                        }
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
        fun playMidiPreview(base64Data: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val tempFile = File(cacheDir, "temp_preview.mid")
                    tempFile.writeBytes(bytes)
                    
                    previewMediaPlayer?.release()
                    previewMediaPlayer = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        prepare()
                        start()
                        setOnCompletionListener { it.release(); previewMediaPlayer = null }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun stopMidiPreview() {
            runOnUiThread {
                try {
                    previewMediaPlayer?.let {
                        if (it.isPlaying) it.stop()
                        it.release()
                    }
                } catch (e: Exception) {} finally { previewMediaPlayer = null }
            }
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
        previewMediaPlayer?.release()
        previewMediaPlayer = null
    }
}