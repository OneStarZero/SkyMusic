package com.skymusic

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 纯代码创建全屏 WebView，不依赖任何 layout.xml
        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        
        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(webView)
        setContentView(rootLayout)

        // 2. 开启 WebView 核心权限（很多按钮点不动是因为没开这些）
        webView.settings.apply {
            javaScriptEnabled = true      // 必须开启 JS
            domStorageEnabled = true      // 开启本地存储
            allowFileAccess = true        // 允许读取本地文件
            allowContentAccess = true
            databaseEnabled = true
        }
        
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient() // 辅助处理 JS 弹窗等
        
        // 3. 加载本地 assets/index.html
        webView.loadUrl("file:///android_asset/index.html")
        
        // 4. 绑定 JS 桥接对象，名字必须叫 AndroidBridge
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
    }

    // ==========================================
    // 🔗 网页与原生通信的桥梁
    // ==========================================
    inner class AndroidBridge {

        // 接收网页发送的 JSON 字符串
        @JavascriptInterface
        fun startPlay(jsonStr: String) {
            runOnUiThread {
                try {
                    // 尝试解析
                    val json = JSONObject(jsonStr)
                    val name = json.optString("name", "未命名")
                    val author = json.optString("author", "未知作者")
                    val fullJsonContent = json.optString("fullJson", jsonStr)
                    val targetFolder = json.optString("targetFolder", "AppSheets")
                    val notesArray = json.optJSONArray("notes")

                    if (notesArray == null || notesArray.length() == 0) {
                        Toast.makeText(this@MainActivity, "没有接收到有效音符，格式错误", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    // 1. 静默保存到 AppSheets
                    saveFileToDirectory(targetFolder, name, author, fullJsonContent)

                    // 2. 组装播放数据
                    val notesList = ArrayList<Pair<Long, Int>>()
                    for (i in 0 until notesArray.length()) {
                        val note = notesArray.getJSONObject(i)
                        val time = note.getLong("time")
                        // 网页传过来的可能是 "1Key0" 也可能是 "1Key5"
                        val keyStr = note.getString("key").replace("1Key", "")
                        val keyIndex = keyStr.toIntOrNull() ?: 0
                        notesList.add(Pair(time, keyIndex))
                    }

                    // 3. 启动悬浮窗播放
                    val intent = Intent(this@MainActivity, FloatingWindowService::class.java).apply {
                        putExtra("is_new_data", true)
                        putExtra("song_data", notesList)
                        putExtra("song_name", name)
                        putExtra("song_author", author)
                        putExtra("song_bpm", json.optString("bpm", "0"))
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    
                    // 可选：发完数据后，可以隐藏网页
                    // moveTaskToBack(true) 

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 接收网页“保存本地”指令
        @JavascriptInterface
        fun saveLocal(jsonStr: String) {
            runOnUiThread {
                try {
                    val json = JSONObject(jsonStr)
                    val name = json.optString("name", "未命名")
                    val author = json.optString("author", "未知作者")
                    val fullJsonContent = json.optString("fullJson", jsonStr)
                    val targetFolder = json.optString("targetFolder", "LocalSheets")

                    saveFileToDirectory(targetFolder, name, author, fullJsonContent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 统一保存文件（覆盖模式）
        private fun saveFileToDirectory(folderName: String, name: String, author: String, content: String) {
            try {
                val targetDir = File("/storage/emulated/0/SkyMusic-OneStar/$folderName")
                if (!targetDir.exists()) targetDir.mkdirs()

                // 过滤非法字符
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                val safeAuthor = author.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                
                val fileName = "$safeName-$safeAuthor.txt"
                val file = File(targetDir, fileName)

                // 写入文本（如果已存在会自动覆盖）
                file.writeText(content, Charsets.UTF_8)

                Toast.makeText(this@MainActivity, "保存成功: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
