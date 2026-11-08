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
            runOnUiThread {
                handlePayload(jsonString)
            }
        }
    }

    // 核心逻辑：解析 -> 检查重名 -> 保存 -> 发送
    private fun handlePayload(payloadStr: String) {
        try {
            val payload = JSONObject(payloadStr)
            val name = payload.getString("name")
            val author = payload.getString("author")
            val transcriber = payload.getString("transcriber")
            val notesArray = payload.getJSONArray("notes")

            val notesList = mutableListOf<Pair<Long, Int>>()
            for (i in 0 until notesArray.length()) {
                val obj = notesArray.getJSONObject(i)
                notesList.add(Pair(obj.getLong("time"), obj.getString("key").replace("1Key", "").toIntOrNull() ?: 0))
            }

            // 构造目标路径
            val folder = File("/storage/emulated/0/SkyMusic-OneStar/AppSheets/")
            if (!folder.exists()) folder.mkdirs()

            // 规范命名格式：歌曲名-原作者.txt
            val fileName = "$name-$author.txt".replace("/", "_")
            val targetFile = File(folder, fileName)

            val saveAction = {
                // 构造标准 Sky Studio 格式数据
                val finalJson = JSONObject()
                finalJson.put("name", name)
                finalJson.put("author", author)
                // 【核心修复】：这里写入用户输入的改编者！
                finalJson.put("transcribedBy", transcriber) 
                finalJson.put("isComposed", false)
                finalJson.put("bpm", 700)
                finalJson.put("bitsPerPage", 16)
                finalJson.put("pitchLevel", 0)
                finalJson.put("isEncrypted", false)
                finalJson.put("songNotes", notesArray)

                val wrapperArray = JSONArray()
                wrapperArray.put(finalJson)

                targetFile.writeText(wrapperArray.toString(), Charsets.UTF_8)
                Toast.makeText(this@MainActivity, "已保存至本地曲库", Toast.LENGTH_SHORT).show()
            }

            if (targetFile.exists()) {
                // 重名询问覆盖
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("文件已存在")
                    .setMessage("本地已存在《$name - $author》的谱面，是否替换？")
                    .setCancelable(false)
                    .setPositiveButton("替换") { _, _ ->
                        saveAction()
                        sendToPlayService(notesList, name, author)
                    }
                    .setNegativeButton("取消") { _, _ ->
                        sendToPlayService(notesList, name, author) // 不保存，只播放
                    }
                    .show()
            } else {
                // 正常询问保存
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("保存谱面")
                    .setMessage("是否将当前谱面保存到本地曲库？")
                    .setCancelable(false)
                    .setPositiveButton("保存") { _, _ ->
                        saveAction()
                        sendToPlayService(notesList, name, author)
                    }
                    .setNegativeButton("取消") { _, _ ->
                        sendToPlayService(notesList, name, author) // 不保存，只播放
                    }
                    .show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "数据解析错误: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 独立的发送到播放服务逻辑
    private fun sendToPlayService(notesList: List<Pair<Long, Int>>, name: String, author: String) {
        // 【核心修复】：未开无障碍时，只提示不阻断保存
        if (SkyAccessibilityService.instance == null) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("谱面已处理！但需要开启无障碍服务才能自动演奏，是否前往设置？")
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
        startService(intent)
        
        Toast.makeText(this, "已发送至悬浮窗", Toast.LENGTH_SHORT).show()
    }
}
