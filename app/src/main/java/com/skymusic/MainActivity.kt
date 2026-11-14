package com.skymusic

import android.annotation.SuppressLint
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 纯代码创建全屏 WebView，不需要任何 layout 文件
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

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")
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

                if (notesArray == null || notesArray.length() == 0) return

                saveFileToDirectory(targetFolder, name, author, fullJsonContent)

                val notesList = ArrayList<Pair<Long, Int>>()
                for (i in 0 until notesArray.length()) {
                    val note = notesArray.getJSONObject(i)
                    val time = note.getLong("time")
                    val keyStr = note.getString("key").replace("1Key", "")
                    notesList.add(Pair(time, keyStr.toIntOrNull() ?: 0))
                }

                val intent = Intent(this@MainActivity, FloatingWindowService::class.java).apply {
                    putExtra("is_new_data", true)
                    putExtra("song_data", notesList)
                    putExtra("song_name", name)
                    putExtra("song_author", author)
                    putExtra("song_bpm", json.optString("bpm", "0"))
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

            } catch (e: Exception) {
                e.printStackTrace()
                showToast("处理失败: ${e.message}")
            }
        }

        @JavascriptInterface
        fun saveLocal(jsonStr: String) {
            try {
                val json = JSONObject(jsonStr)
                saveFileToDirectory(json.optString("targetFolder", "LocalSheets"), json.optString("name", "未命名"), json.optString("author", "未知作者"), json.optString("fullJson", jsonStr))
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
                val file = File(targetDir, "$safeName-$safeAuthor.txt")
                file.writeText(content, Charsets.UTF_8)
                showToast("已保存: ${file.name}")
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("保存失败: ${e.message}")
            }
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
