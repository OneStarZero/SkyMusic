package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class MainActivity : AppCompatActivity() {
    private var previewMediaPlayer: MediaPlayer? = null
    private var webViewInstance: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 🌟 原生音频引擎
    private var soundPool: SoundPool? = null
    private val soundMap = mutableMapOf<Int, Int>()
    private val handler = Handler(Looper.getMainLooper())

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
        
        initSoundPool()

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
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = cb
                    return try {
                        val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "audio/*"
                        }
                        intent.type = "audio/*"
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (e: Exception) {
                        filePathCallback = null
                        false
                    }
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

    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        
        soundPool = SoundPool.Builder()
            .setMaxStreams(50) // 最多同时播放 50 个音
            .setAudioAttributes(audioAttributes)
            .build()

        try {
            // 反射读取 res/raw 下 piano0~piano14
            val fields = R.raw::class.java.fields
            for (field in fields) {
                val resName = field.name
                if (resName.startsWith("piano", ignoreCase = true)) {
                    val indexStr = resName.replace("piano", "", ignoreCase = true)
                    val index = indexStr.toIntOrNull()
                    if (index != null && index in 0..14) {
                        val resId = field.getInt(null)
                        val soundId = soundPool?.load(this, resId, 1)
                        if (soundId != null) {
                            soundMap[index] = soundId
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

        @JavascriptInterface
        fun playSheetPreview(notesJsonStr: String) {
            runOnUiThread {
                try {
                    soundPool?.autoPause()
                    handler.removeCallbacksAndMessages(null)

                    val jsonArray = org.json.JSONArray(notesJsonStr)
                    for (i in 0 until jsonArray.length()) {
                        val noteObj = jsonArray.getJSONObject(i)
                        val timeMs = noteObj.getLong("time")
                        val keyIndex = noteObj.getInt("keyIndex")

                        handler.postDelayed({
                            val soundId = soundMap[keyIndex]
                            if (soundId != null) {
                                soundPool?.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
                            }
                        }, timeMs)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        @JavascriptInterface
        fun stopSheetPreview() {
            runOnUiThread {
                soundPool?.autoPause()
                handler.removeCallbacksAndMessages(null)
            }
        }

        // 🌟 新增：原 MIDI 曲目试听（用 SoundPool 弹奏）
        @JavascriptInterface
        fun playRawMidiPreview(notesJsonStr: String) {
            runOnUiThread {
                try {
                    soundPool?.autoPause()
                    handler.removeCallbacksAndMessages(null)

                    val jsonArray = org.json.JSONArray(notesJsonStr)
                    for (i in 0 until jsonArray.length()) {
                        val noteObj = jsonArray.getJSONObject(i)
                        val timeMs = noteObj.getLong("time")
                        val keyIndex = noteObj.getInt("keyIndex")

                        handler.postDelayed({
                            val soundId = soundMap[keyIndex]
                            if (soundId != null) {
                                soundPool?.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
                            }
                        }, timeMs)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        @JavascriptInterface
        fun stopRawMidiPreview() {
            runOnUiThread {
                soundPool?.autoPause()
                handler.removeCallbacksAndMessages(null)
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
        soundPool?.release()
        soundPool = null
        handler.removeCallbacksAndMessages(null)
    }
}