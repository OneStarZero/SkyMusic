package com.skymusic

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化你的布局
        setContentView(R.layout.activity_main)
        
        webView = findViewById(R.id.webView)
        // 配置你的 WebView 设置...
        
        // 绑定 JS 桥接
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
    }

    // 你的 AndroidBridge 内部类
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

                // 1. 静默覆盖保存到 AppSheets
                saveFileToDirectory(targetFolder, name, author, fullJsonContent)

                // 2. 组装播放数据
                val notesList = ArrayList<Pair<Long, Int>>()
                for (i in 0 until notesArray.length()) {
                    val note = notesArray.getJSONObject(i)
                    val time = note.getLong("time")
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

        // 统一的保存逻辑（自动处理同名覆盖）
        private fun saveFileToDirectory(folderName: String, name: String, author: String, content: String) {
            try {
                // 目录：/storage/emulated/0/SkyMusic-OneStar/AppSheets 或 LocalSheets
                val targetDir = File("/storage/emulated/0/SkyMusic-OneStar/$folderName")
                if (!targetDir.exists()) targetDir.mkdirs()

                // 清理文件名中的非法字符
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                val safeAuthor = author.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                
                // 规范：歌曲名-原作者.txt
                val fileName = "$safeName-$safeAuthor.txt"
                val file = File(targetDir, fileName)

                // 直接写入，如果文件已存在则自动覆盖（不会产生 (1) 这种副本）
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
