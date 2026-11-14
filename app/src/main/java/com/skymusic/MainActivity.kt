package com.skymusic

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 用纯代码创建一个全屏的 WebView，不需要 R.layout.activity_main
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

        // 2. 配置 WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        
        webView.webViewClient = WebViewClient()
        
        // 3. 加载 assets 里的网页
        webView.loadUrl("file:///android_asset/index.html")
        
        // 4. 绑定 JS 桥接
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun startPlay(jsonStr: String) {
            try {
                val json = JSONObject(jsonStr)
                val name = json.optString("name", "未命名")
                val author = json.optString("author", "未知作者")
                val fullJsonContent = json.optString("fullJson", jsonStr)
                val targetFolder = json.optString("targetFolder", "AppSheets")
                val notesArray = json.optJSONArray("notes")

                if (notesArray == null || notesArray.length() == 0) {
                    showToast("没有接收到有效音符")
                    return
                }

                // 静默覆盖保存到 AppSheets
                saveFileToDirectory(targetFolder, name, author, fullJsonContent)

                val notesList = ArrayList<Pair<Long, Int>>()
                for (i in 0 until notesArray.length()) {
                    val note = notesArray.getJSONObject(i)
                    val time = note.getLong("time")
                    val keyStr = note.getString("key").replace("1Key", "")
                    val keyIndex = keyStr.toIntOrNull() ?: 0
                    notesList.add(Pair(time, keyIndex))
                }

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
                
                runOnUiThread {
                    moveTaskToBack(true) // App 切后台，让悬浮窗露出来
                }

            } catch (e: Exception) {
                e.printStackTrace()
                showToast("处理失败: ${e.message}")
            }
        }

        @JavascriptInterface
        fun saveLocal(jsonStr: String) {
            try {
                val json = JSONObject(jsonStr)
                val name = json.optString("name", "未命名")
                val author = json.optString("author", "未知作者")
                val fullJsonContent = json.optString("fullJson", jsonStr)
                val targetFolder = json.optString("targetFolder", "LocalSheets")

                saveFileToDirectory(targetFolder, name, author, fullJsonContent)
                
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("保存失败: ${e.message}")
            }
        }

        private fun saveFileToDirectory(folderName: String, name: String, author: String, content: String) {
            try {
                val targetDir = File("/storage/emulated/0/SkyMusic-OneStar/$folderName")
                if (!targetDir.exists()) targetDir.mkdirs()

                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                val safeAuthor = author.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                
                val fileName = "$safeName-$safeAuthor.txt"
                val file = File(targetDir, fileName)

                file.writeText(content, Charsets.UTF_8)

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "已保存: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
