package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
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
                val safeName = JSONObject.quote(fileName)
                val safePath = JSONObject.quote(midiPath)
                webViewInstance?.evaluateJavascript("if (window.onMidiPathReceived) window.onMidiPathReceived($safePath,$safeName);", null)
            }
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density

        requestLegacyStorageIfNeeded()

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                }
                cacheMode = WebSettings.LOAD_NO_CACHE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pushBridgeStateToWeb()
                }
            }
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        rootLayout.addView(webViewInstance, FrameLayout.LayoutParams(-1, -1))

        // 顶部状态与快捷操作胶囊栏（支持横向滚动防遮挡/溢出，视觉与 HTML 深空毛玻璃一致）
        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }

        val barContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#990B1120"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
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
                    setColor(Color.parseColor("#551E293B"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1).coerceAtLeast(1), safeColor(strokeHex, "#33FFFFFF"))
                }
                setOnClickListener { view ->
                    view.animate()
                        .scaleX(0.92f).scaleY(0.92f).setDuration(80)
                        .withEndAction {
                            view.animate().scaleX(1f).scaleY(1f)
                                .setInterpolator(OvershootInterpolator(1.6f)).setDuration(140).start()
                        }.start()
                    onClick()
                }
            }
        }

        btnOverlay = createActionBtn("开启悬浮窗") {
            openOverlayPermissionSettings()
        }
        btnAccessibility = createActionBtn("无障碍权限") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        btnRefreshBg = createActionBtn("刷新背景") {
            webViewInstance?.evaluateJavascript("if (window.refreshRandomBackground) window.refreshRandomBackground();", null)
            Toast.makeText(this, "✨ 正在刷新星空背景", Toast.LENGTH_SHORT).show()
        }
        btnFloatBall = createActionBtn("开启悬浮球", accent) {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                openOverlayPermissionSettings()
            } else {
                startFloatingServiceSafe()
                Toast.makeText(this, "🚀 悬浮球已启动", Toast.LENGTH_SHORT).show()
                pushBridgeStateToWeb()
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

        barScroll.addView(barContainer)
        rootLayout.addView(barScroll, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(20)
            rightMargin = dp(10)
            leftMargin = dp(10)
        })

        setContentView(rootLayout)
        refreshPermissionIndicators()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        density = resources.displayMetrics.density
        pushBridgeStateToWeb()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionIndicators()
        pushBridgeStateToWeb()
    }

    override fun onDestroy() {
        webViewInstance?.apply {
            stopLoading()
            removeJavascriptInterface("AndroidBridge")
            destroy()
        }
        webViewInstance = null
        super.onDestroy()
    }

    private fun requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val perms = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            val missing = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                storagePermissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun startFloatingServiceSafe(extraIntent: Intent? = null) {
        val intent = extraIntent ?: Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun refreshPermissionIndicators() {
        val hasOverlay = hasOverlayPermission()
        val hasA11y = SkyAccessibilityService.instance != null

        btnOverlay?.text = if (hasOverlay) "悬浮窗✓" else "开启悬浮窗"
        btnOverlay?.setTextColor(if (hasOverlay) Color.parseColor("#86EFAC") else Color.parseColor("#F8FAFC"))

        btnAccessibility?.text = if (hasA11y) "无障碍✓" else "无障碍权限"
        btnAccessibility?.setTextColor(if (hasA11y) Color.parseColor("#86EFAC") else Color.parseColor("#F8FAFC"))
    }

    private fun pushBridgeStateToWeb() {
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        val stateJson = JSONObject().apply {
            put("hasOverlay", hasOverlayPermission())
            put("hasAccessibility", SkyAccessibilityService.instance != null)
            put("themeHue", prefs.getFloat("theme_hue", 220f))
            put("accent", prefs.getString("theme_accent", "#7DD3FC") ?: "#7DD3FC")
            put("bgTop", prefs.getString("theme_bg_top", "#0B1120") ?: "#0B1120")
            put("bgBottom", prefs.getString("theme_bg_bottom", "#1E1B4B") ?: "#1E1B4B")
            put("panelAlpha", prefs.getFloat("panel_alpha", 0.85f))
            put("sdkInt", Build.VERSION.SDK_INT)
        }
        webViewInstance?.post {
            webViewInstance?.evaluateJavascript(
                "if (window.onNativeStateSync) window.onNativeStateSync(${JSONObject.quote(stateJson.toString())});",
                null
            )
        }
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
                pushBridgeStateToWeb()
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun requestStateSync() = runOnUiThread {
            refreshPermissionIndicators()
            pushBridgeStateToWeb()
        }

        @JavascriptInterface
        fun openOverlaySettings() = runOnUiThread { openOverlayPermissionSettings() }

        @JavascriptInterface
        fun openAccessibilitySettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /**
         * 生成谱面图片并保存到 /storage/emulated/0/SkyMusic-OneStar/<乐谱名>/
         * 同步返回 JSON 字符串：{"ok":true,"dir":"...","files":[...]} 或 {"ok":false,"error":"..."}
         * 注意：@JavascriptInterface 方法在 WebView 的 JavaBridge 线程执行，
         * 这里不能碰 UI，也不能 runOnUiThread（否则拿不到返回值）。
         */
        @JavascriptInterface
        fun saveSheetImage(payloadJson: String): String {
            return try {
                val baseDir = File("/storage/emulated/0/SkyMusic-OneStar/")
                if (!baseDir.exists()) baseDir.mkdirs()
                SheetImageExporter.export(baseDir, payloadJson).toString()
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", e.message ?: "未知错误").toString()
            }
        }
    }

    private fun handleSendApp(payloadStr: String) {
        try {
            val payload = JSONObject(payloadStr)
            val notesArray = payload.optJSONArray("notes")
            val notesList = ArrayList<Pair<Long, Int>>()
            if (notesArray != null) {
                for (i in 0 until notesArray.length()) {
                    val obj = notesArray.getJSONObject(i)
                    notesList.add(Pair(obj.optLong("time", 0L), obj.optString("key", "").replace("1Key", "").toIntOrNull() ?: 0))
                }
            }
            val intent = Intent(this, FloatingWindowService::class.java).apply {
                putExtra("is_new_data", true)
                putExtra("song_data", notesList)
                putExtra("song_name", payload.optString("name", "未命名"))
                putExtra("song_author", payload.optString("author", "未知作者"))
                putExtra("song_model", payload.optString("model", ""))
                putExtra("song_bpm", payload.optString("bpm", "0"))
            }
            startFloatingServiceSafe(intent)
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
                "$baseName-$author ||$modelName.txt"
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
