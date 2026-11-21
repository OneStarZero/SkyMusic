package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private var webViewInstance: WebView? = null
    private var density = 1f

    private val localSheetsDirPath = "/storage/emulated/0/SkyMusic-OneStar/LocalSheets"

    private val midiPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val midiPath = result.data?.getStringExtra("midiPath")
            val fileName = result.data?.getStringExtra("fileName")
            if (midiPath != null && fileName != null) {
                val safeName = fileName.replace("'", "\\'")
                webViewInstance?.evaluateJavascript("window.onMidiPathReceived('$midiPath', '$safeName');", null)
            }
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val readOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val writeOk = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (!readOk || !writeOk) {
                storagePermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                )
            }
        }

        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        val bgTop = prefs.getString("theme_bg_top", "#0B1120") ?: "#0B1120"
        val accent = prefs.getString("theme_accent", "#38BDF8") ?: "#38BDF8"

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(safeColor(bgTop, "#0B1120"))
        }

        webViewInstance = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
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
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        rootLayout.addView(webViewInstance, FrameLayout.LayoutParams(-1, -1))

        // 现代化简洁顶部快捷栏
        val quickBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#880F172A"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
            }
        }

        fun makeActionChip(
            label: String,
            textColorHex: String = "#F8FAFC",
            highlightBorderHex: String? = null,
            onClick: () -> Unit
        ): TextView = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(safeColor(textColorHex, "#F8FAFC"))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#331E293B"))
                cornerRadius = dp(12).toFloat()
                if (highlightBorderHex != null) {
                    setStroke(dp(1).coerceAtLeast(1), safeColor(highlightBorderHex, accent))
                }
            }
            setOnClickListener { onClick() }
        }

        val btnBall = makeActionChip("🚀 悬浮球", "#F8FAFC", accent) {
            launchFloatingWindow()
        }
        val btnMidi = makeActionChip("🎹 MIDI", "#E2E8F0") {
            midiPickerLauncher.launch(Intent(this, MidiPickerActivity::class.java))
        }
        val btnSheets = makeActionChip("📂 曲库", "#E2E8F0") {
            showSheetsInfoDialog()
        }
        val btnSettings = makeActionChip("⚙", "#F8FAFC") {
            showSettingsDialog()
        }

        quickBar.addView(btnBall)
        quickBar.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        quickBar.addView(btnMidi)
        quickBar.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        quickBar.addView(btnSheets)
        quickBar.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        quickBar.addView(btnSettings)

        rootLayout.addView(quickBar, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(24)
            rightMargin = dp(12)
        })

        setContentView(rootLayout)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun safeColor(hex: String, fallback: String): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.parseColor(fallback)
        }
    }

    private fun canDrawOverlayCompat(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun isAccessibilityEnabledCompat(): Boolean {
        return SkyAccessibilityService.instance != null
    }

    private fun launchFloatingWindow() {
        if (!canDrawOverlayCompat()) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startService(Intent(this, FloatingWindowService::class.java))
        Toast.makeText(this, "✨ 悬浮球已启动", Toast.LENGTH_SHORT).show()
    }

    private fun showSheetsInfoDialog() {
        val folder = File(localSheetsDirPath)
        if (!folder.exists()) folder.mkdirs()
        val count = folder.listFiles { f -> f.extension.equals("txt", true) }?.size ?: 0
        val msg = "本地曲库目录：\n$localSheetsDirPath\n\n当前本地谱面数量：$count 份"

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("本地曲库")
            .setMessage(msg)
            .setPositiveButton("复制路径") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SkyMusicSheetsPath", localSheetsDirPath))
                Toast.makeText(this, "已复制曲库路径", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("刷新网页") { _, _ ->
                webViewInstance?.reload()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showSettingsDialog() {
        val overlayStatus = if (canDrawOverlayCompat()) "已开启" else "未开启"
        val a11yStatus = if (isAccessibilityEnabledCompat()) "已开启" else "未开启"

        val options = arrayOf(
            "🌌 刷新随机背景 (更换星空主题)",
            "🚀 启动悬浮球",
            "🎹 选择 MIDI 文件",
            "🔑 悬浮窗权限（$overlayStatus）",
            "♿ 无障碍服务（$a11yStatus）",
            "ℹ️ 关于 SkyMusic 0.0.8"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("SkyMusic 设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        webViewInstance?.evaluateJavascript("if (window.refreshRandomBackground) window.refreshRandomBackground();", null)
                        Toast.makeText(this, "✨ 正在生成新的星空...", Toast.LENGTH_SHORT).show()
                    }
                    1 -> launchFloatingWindow()
                    2 -> midiPickerLauncher.launch(Intent(this, MidiPickerActivity::class.java))
                    3 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    4 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    5 -> {
                        AlertDialog.Builder(this)
                            .setTitle("关于")
                            .setMessage("SkyMusic 0.0.8\n\n光遇 MIDI 智能转谱工具\n\n愿你在这片星空下奏响最美的旋律。")
                            .setPositiveButton("确定", null)
                            .show()
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
        fun openMidiPicker() {
            runOnUiThread { midiPickerLauncher.launch(Intent(this@MainActivity, MidiPickerActivity::class.java)) }
        }

        @JavascriptInterface
        fun saveTheme(jsonString: String) = runOnUiThread {
            try {
                val o = JSONObject(jsonString)
                getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE).edit()
                    .putFloat("theme_hue", o.optDouble("hue", 220.0).toFloat())
                    .putString("theme_accent", o.optString("accent", "#7DD3FC"))
                    .putString("theme_bg_top", o.optString("bgTop", "#0B1120"))
                    .putString("theme_bg_bottom", o.optString("bgBottom", "#1E1B4B"))
                    .apply()
            } catch (_: Exception) {}
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
            intent.putExtra("song_bpm", payload.optString("bpm", "0"))
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
            val folder = File(localSheetsDirPath)
            if (!folder.exists()) folder.mkdirs()

            val baseName = if (name.contains(" || ")) name.substringBefore(" || ").trim() else name
            val modelName = if (name.contains(" || ")) name.substringAfter(" || ").trim() else payload.optString("model", "")

            val fileName = if (modelName.isNotBlank()) {
                "$baseName-$author || $modelName.txt"
            } else {
                "$baseName-$author.txt"
            }.replace(Regex("[\\\\/:*?\"<>|]"), "_")

            File(folder, fileName).writeText(fullJson, Charsets.UTF_8)
            Toast.makeText(this, "✅ 已保存", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
