package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private var webViewInstance: WebView? = null
    private var density = 1f

    private var btnOverlay: TextView? = null
    private var btnAccessibility: TextView? = null
    private var btnRefreshBg: TextView? = null
    private var btnFloatBall: TextView? = null

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
        val accent = prefs.getString("theme_accent", "#7DD3FC") ?: "#7DD3FC"

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

        // 与星空 WebView 融合的 4 按钮顶部栏
        val barContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#880B1120"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#38FFFFFF"))
            }
        }

        fun createActionBtn(label: String, strokeHex: String = "#33FFFFFF", onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(Color.parseColor("#F8FAFC"))
                gravity = Gravity.CENTER
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#441E293B"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1).coerceAtLeast(1), safeColor(strokeHex, "#33FFFFFF"))
                }
                setOnClickListener { onClick() }
            }
        }

        btnOverlay = createActionBtn("开启悬浮窗") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnAccessibility = createActionBtn("无障碍权限") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnRefreshBg = createActionBtn("刷新背景") {
            webViewInstance?.evaluateJavascript("if (window.refreshRandomBackground) window.refreshRandomBackground();", null)
            Toast.makeText(this, "✨ 正在刷新星空背景", Toast.LENGTH_SHORT).show()
        }
        btnFloatBall = createActionBtn("开启悬浮球", accent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startService(Intent(this, FloatingWindowService::class.java))
                Toast.makeText(this, "🚀 悬浮球已启动", Toast.LENGTH_SHORT).show()
            }
        }

        val gap = dp(5)
        barContainer.addView(btnOverlay)
        barContainer.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(gap, 1) })
        barContainer.addView(btnAccessibility)
        barContainer.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(gap, 1) })
        barContainer.addView(btnRefreshBg)
        barContainer.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(gap, 1) })
        barContainer.addView(btnFloatBall)

        rootLayout.addView(barContainer, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(26)
            rightMargin = dp(10)
            leftMargin = dp(10)
        })

        setContentView(rootLayout)
        refreshPermissionIndicators()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionIndicators()
    }

    private fun refreshPermissionIndicators() {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasA11y = SkyAccessibilityService.instance != null

        btnOverlay?.text = if (hasOverlay) "悬浮窗✓" else "开启悬浮窗"
        btnOverlay?.setTextColor(if (hasOverlay) Color.parseColor("#86EFAC") else Color.parseColor("#F8FAFC"))

        btnAccessibility?.text = if (hasA11y) "无障碍✓" else "无障碍权限"
        btnAccessibility?.setTextColor(if (hasA11y) Color.parseColor("#86EFAC") else Color.parseColor("#F8FAFC"))
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun safeColor(hex: String, fallback: String): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.parseColor(fallback)
        }
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
            val folder = File("/storage/emulated/0/SkyMusic-OneStar/LocalSheets/")
            if (!folder.exists()) folder.mkdirs()

            val baseName = if (name.contains(" || ")) name.substringBefore(" || ").trim() else name
            val modelName = if (name.contains(" || ")) name.substringAfter(" || ").trim() else payload.optString("model", "")

            val fileName = if (modelName.isNotBlank()) {
                "$baseName-$author \vert{}\vert{}$modelName.txt"
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
