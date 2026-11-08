package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    
    private lateinit var sheetListLayout: LinearLayout
    private lateinit var btnRefresh: Button
    private lateinit var btnSelectFolder: Button
    private lateinit var tvStatus: TextView
    
    // 保存用户授权的文件夹 URI (系统会永久记住)
    private var currentFolderUri: Uri? = null
    private val PREFS_NAME = "SkyMusicPrefs"
    private val KEY_FOLDER_URI = "sky_sheet_folder_uri"

    // 安卓原生文件夹选择器
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            // 永久保存读取权限（非常重要，否则重启 App 就失效了）
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            currentFolderUri = it
            
            // 保存到本地
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_FOLDER_URI, it.toString()).apply()
            updateStatusText()
            loadLocalScores()
            Toast.makeText(this, "曲库授权成功！", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 动态申请存储权限 (针对老版本)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        val scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
            setBackgroundColor(android.graphics.Color.parseColor("#0f172a"))
        }
        scrollView.addView(root)

        val title = TextView(this).apply {
            text = "SkyMusic 控制台"
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 30)
        }
        root.addView(title)

        // 状态提示
        tvStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 40)
        }
        root.addView(tvStatus)

        // 按钮：悬浮窗权限
        val btnOverlay = Button(this).apply {
            text = "1. 开启悬浮窗权限"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } else {
                        Toast.makeText(this@MainActivity, "已开启", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        root.addView(btnOverlay)

        // 按钮：无障碍服务
        val btnAccessibility = Button(this).apply {
            text = "2. 开启无障碍服务"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(btnAccessibility)

        // 按钮：校准
        val btnCalibrate = Button(this).apply {
            text = "🎯 校准 15 键位坐标"
            setOnClickListener {
                startService(Intent(this@MainActivity, FloatingWindowService::class.java).apply { action = "ACTION_CALIBRATE" })
                Toast.makeText(this@MainActivity, "请切回桌面进行校准", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            }
        }
        root.addView(btnCalibrate)

        val divider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            setPadding(0, 40, 0, 40)
        }
        root.addView(divider)

        // 按钮：授权曲库文件夹
        btnSelectFolder = Button(this).apply {
            text = "📁 授权天遇曲库目录"
            setBackgroundColor(android.graphics.Color.parseColor("#6366f1")) // 紫色高亮
            setOnClickListener {
                // 引导用户直接选择天遇的目录
                folderPickerLauncher.launch(null)
            }
        }
        root.addView(btnSelectFolder)

        // 按钮：刷新
        btnRefresh = Button(this).apply {
            text = "🔄 刷新本地曲库"
            setOnClickListener { loadLocalScores() }
        }
        root.addView(btnRefresh)

        sheetListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 30, 0, 50)
        }
        root.addView(sheetListLayout)

        setContentView(scrollView)

        // 读取上次保存的授权
        val savedUri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_FOLDER_URI, null)
        if (savedUri != null) {
            currentFolderUri = Uri.parse(savedUri)
        }

        updateStatusText()
        loadLocalScores()
    }

    private fun updateStatusText() {
        if (currentFolderUri == null) {
            tvStatus.text = "状态：未授权曲库目录，请点击下方紫色按钮进行授权。\n(注：请在弹出的界面中选择 天遇的 Sheet 文件夹)"
        } else {
            tvStatus.text = "状态：已授权曲库目录"
            btnSelectFolder.text = "📁 重新授权曲库目录"
            btnSelectFolder.setBackgroundColor(android.graphics.Color.parseColor("#334155"))
        }
    }

    // 扫描并加载本地曲目
    private fun loadLocalScores() {
        sheetListLayout.removeAllViews()
        
        if (currentFolderUri == null) {
            val tv = TextView(this).apply {
                text = "请先授权目录！"
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
            }
            sheetListLayout.addView(tv)
            return
        }

        try {
            // 使用 DocumentFile 或 ContentResolver 查询文件
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                currentFolderUri, 
                android.provider.DocumentsContract.getTreeDocumentId(currentFolderUri)
            )
            
            val cursor = contentResolver.query(childrenUri, null, null, null, null)
            var hasFiles = false

            cursor?.use {
                while (it.moveToNext()) {
                    val displayName = it.getString(it.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    val docUriStr = it.getString(it.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    
                    // 筛选 .txt 或 .json 文件
                    if (displayName.endsWith(".txt", true) || displayName.endsWith(".json", true)) {
                        hasFiles = true
                        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(currentFolderUri, docUriStr)
                        
                        val scoreBtn = Button(this).apply {
                            text = "🎵 ${displayName.substringBeforeLast(".")}"
                            textSize = 16f
                            setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
                            setTextColor(android.graphics.Color.parseColor("#38BDF8"))
                            
                            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            params.setMargins(0, 0, 0, 20)
                            layoutParams = params

                            setOnClickListener { parseAndPlayUri(fileUri, displayName) }
                        }
                        sheetListLayout.addView(scoreBtn)
                    }
                }
            }

            if (!hasFiles) {
                val tv = TextView(this).apply {
                    text = "目录内没有找到任何 .txt 或 .json 谱面。"
                    setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                }
                sheetListLayout.addView(tv)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取目录失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 读取选中的文件并播放 (使用 URI 流读取)
    private fun parseAndPlayUri(fileUri: Uri, fileName: String) {
        try {
            val inputStream = contentResolver.openInputStream(fileUri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()
            reader.close()

            val jsonArray = if (jsonString.trim().startsWith("[")) {
                val rootArray = JSONArray(jsonString)
                if (rootArray.getJSONObject(0).has("songNotes")) {
                    rootArray.getJSONObject(0).getJSONArray("songNotes")
                } else {
                    rootArray
                }
            } else {
                JSONArray(jsonString)
            }

            val notesList = mutableListOf<Pair<Long, Int>>()
            for (i in 0 until jsonArray.length()) {
                val noteObj = jsonArray.getJSONObject(i)
                if (noteObj.has("time") && noteObj.has("key")) {
                    val time = noteObj.getLong("time")
                    val keyStr = noteObj.getString("key")
                    val keyIndex = keyStr.replace("1Key", "").toIntOrNull() ?: 0
                    notesList.add(Pair(time, keyIndex))
                }
            }

            if (notesList.isEmpty()) {
                Toast.makeText(this, "谱面无音符数据！", Toast.LENGTH_SHORT).show()
                return
            }

            // 发送数据给悬浮窗服务
            val intent = Intent(this, FloatingWindowService::class.java)
            intent.putExtra("song_data", ArrayList(notesList))
            startService(intent)
            
            Toast.makeText(this, "成功加载《${fileName.substringBeforeLast(".")}》，请切回游戏", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)

        } catch (e: Exception) {
            Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
