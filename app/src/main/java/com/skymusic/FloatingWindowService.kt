package com.skymusic

import android.app.AlertDialog
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatView: FrameLayout

    private var isPlaying = false
    private var currentIndex = 0
    private var songData = listOf<Pair<Long, Int>>()
    private val keyPositions = mutableListOf<Pair<Float, Float>>()
    private var currentSpeed = 1.0f

    private var currentSongName = ""
    private var currentAuthor = ""
    private var currentDuration = "0:00"
    private var currentNotesCount = 0
    private var currentBpm = "0"
    private var currentModel = ""

    private var isMiniMode = true
    private var isPlayerMode = false

    private lateinit var listContainer: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var tvSpeed: TextView
    private lateinit var scrollView: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private var playRunnable: Runnable? = null

    private val basePath = "/storage/emulated/0/SkyMusic-OneStar"
    private val appSheetsPath = "$basePath/AppSheets"
    private val localSheetsPath = "$basePath/LocalSheets"

    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var playerParams: WindowManager.LayoutParams

    private var currentPage = 0
    private var density = 1.0f
    private var cachedSongList: List<Triple<File, List<String>, String>>? = null
    private var refreshSessionId = 0

    private val playBgDrawable = GradientDrawable().apply { cornerRadius = 8f }
    private val pauseBgDrawable = GradientDrawable().apply { cornerRadius = 8f }

    private var calibrationViews = mutableListOf<TextView>()
    private var calibrationBtnContainer: LinearLayout? = null

    // 设置项
    private var keyMode = 15
    private var defaultSpeed = 1.0f
    private var clickToleranceMs = 25L
    private var ballSize = 60
    private var panelAlpha = 0.60f // 修改为 60%
    private var autoBackToList = true
    private var compactList = true
    private val songKeyModeMap = mutableMapOf<String, Int>()

    // 主题系统
    private var themeAccent = "#007AFF" // 改为蓝色
    private var themeBgPanel = "#EB0B1120"
    private var themeTopBar = "#1E293B"
    private var themeCard = "#1E293B"
    private var themeTextMain = "#F8FAFC"
    private var themeTextSoft = "#CBD5E1"
    private var themeHue = 220f
    private var syncWebTheme = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density

        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        keyMode = prefs.getInt("key_mode", 15)
        defaultSpeed = prefs.getFloat("default_speed", 1.0f)
        currentSpeed = defaultSpeed
        clickToleranceMs = prefs.getLong("click_tolerance", 25L)
        ballSize = prefs.getInt("ball_size", 60)
        panelAlpha = prefs.getFloat("panel_alpha", 0.60f) // 读取保存的值
        autoBackToList = prefs.getBoolean("auto_back_list", true)
        compactList = prefs.getBoolean("compact_list", true)
        syncWebTheme = prefs.getBoolean("sync_web_theme", false)
        themeHue = prefs.getFloat("theme_hue", 220f)
        themeAccent = prefs.getString("theme_accent", "#007AFF") ?: "#007AFF" // 优先读取保存蓝色值

        prefs.all.forEach { (k, v) ->
            if (k.startsWith("song_mode_") && v is Int) songKeyModeMap[k.removePrefix("song_mode_")] = v
        }

        applyHueTheme(themeHue)
        playBgDrawable.setColor(Color.parseColor("#34D399"))
        pauseBgDrawable.setColor(Color.parseColor("#F87171"))

        loadKeyPositions()
        File(appSheetsPath).mkdirs()
        File(localSheetsPath).mkdirs()
    }

    private fun applyHueTheme(hue: Float) {
        themeHue = hue
        themeAccent = hsvHex((hue + 40f) % 360f, 0.85f, 0.72f)
        themeTopBar = hsvHex(hue % 360f, 0.32f, 0.18f)
        themeCard = hsvHex(hue % 360f, 0.28f, 0.14f)
        val bg = Color.HSVToColor(floatArrayOf(hue % 360f, 0.40f, 0.08f))
        val a = (panelAlpha * 255).toInt().coerceIn(90, 255)
        themeBgPanel = String.format("#%02X%06X", a, 0xFFFFFF and bg)
    }

    private fun hsvHex(h: Float, s: Float, v: Float): String {
        return String.format("#%06X", 0xFFFFFF and Color.HSVToColor(floatArrayOf(h, s, v)))
    }

    private fun c(hex: String, fb: String = "#FFFFFF"): Int {
        return try { Color.parseColor(hex) } catch (_: Exception) { Color.parseColor(fb) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("is_new_data", false) == true) {
            intent.getSerializableExtra("song_data")?.let {
                try {
                    @Suppress("UNCHECKED_CAST")
                    songData = it as ArrayList<Pair<Long, Int>>
                    stopPlayback()
                    currentIndex = 0
                    currentSongName = intent.getStringExtra("song_name") ?: "App 发送谱面"
                    currentAuthor = intent.getStringExtra("song_author") ?: "未知作者"
                    currentModel = intent.getStringExtra("song_model") ?: ""
                    currentNotesCount = songData.size
                    currentDuration = calculateDuration(songData)
                    currentBpm = intent.getStringExtra("song_bpm") ?: "0"
                    if (!this::floatView.isInitialized) createFloatingWindow()
                    if (!isMiniMode && !isPlayerMode) { currentPage = 1; showFullPanel(1) }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        if (!this::floatView.isInitialized) createFloatingWindow()
        return START_STICKY
    }

    private fun createFloatingWindow() {
        try {
            floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            val dm = resources.displayMetrics
            val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            panelParams = WindowManager.LayoutParams((dm.widthPixels * if (land) 0.35f else 0.55f).toInt(), (dm.heightPixels * if (land) 0.50f else 0.45f).toInt(), type, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (dm.widthPixels * 0.075).toInt(); y = (dm.heightPixels * 0.2).toInt() }
            ballParams = WindowManager.LayoutParams(ballSize, ballSize, type, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 200 }
            val pw = (dm.widthPixels * if (land) 0.30f else 0.45f).toInt()
            playerParams = WindowManager.LayoutParams(pw, WindowManager.LayoutParams.WRAP_CONTENT, type, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (dm.widthPixels - pw) / 2; y = dm.heightPixels - 400 }
            
            windowManager.addView(floatView, ballParams)
            showMiniBall()
        } catch (_: Exception) {
            Toast.makeText(this, "悬浮窗启动失败", Toast.LENGTH_SHORT).show(); stopSelf()
        }
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams, locked: () -> Boolean = { false }) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var dragging = false
        val slop = 8 * density
        view.setOnTouchListener { v, e ->
            if (locked()) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == 1) {
                        val dx = e.rawX - tx; val dy = e.rawY - ty
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                        if (dragging) { params.x = ix + dx.toInt(); params.y = iy + dy.toInt(); windowManager.updateViewLayout(floatView, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging && e.pointerCount == 1) v.performClick(); dragging = false; true }
                else -> false
            }
        }
    }

    private fun showMiniBall() {
        if (!this::floatView.isInitialized) return
        isMiniMode = true; isPlayerMode = false; floatView.removeAllViews()
        ballParams.width = ballSize; ballParams.height = ballSize

        val ball = TextView(this).apply { text = "✦"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(c("#FDE047")); setShadowLayer(8f, 0f, 0f, c("#EAB308")) }
        val wrap = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#55FFFFFF"))
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) } }
            clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        wrap.addView(ball); floatView.addView(wrap)
        enableDrag(wrap, ballParams); wrap.setOnClickListener { showFullPanel(currentPage) }
        windowManager.updateViewLayout(floatView, ballParams)
    }

    private fun showFullPanel(page: Int = 0) {
        if (!this::floatView.isInitialized) return
        isMiniMode = false; isPlayerMode = false; floatView.removeAllViews()

        if (syncWebTheme) {
            val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
            themeHue = prefs.getFloat("theme_hue", themeHue)
            applyHueTheme(themeHue)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(c(themeBgPanel, "#EB0B1120"))
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 20f) } }
            clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14, 14, 14, 14); setBackgroundColor(c(themeTopBar, "#1E293B")) }
        titleText = TextView(this).apply { text = when (page) { 0 -> "本地曲库"; 1 -> "App 发送"; else -> "设置" }; setTextColor(c(themeTextMain)); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        
        val btnRefresh = TextView(this).apply { text = "↻"; setTextColor(c(themeAccent)); textSize = 18f; setPadding(12, 0, 12, 0); setOnClickListener { when (currentPage) { 0 -> { cachedSongList = null; showFullPanel(0) }; else -> showFullPanel(currentPage) } } }
        val btnClose = TextView(this).apply { text = "✕"; setTextColor(c(themeTextMain)); textSize = 18f; setPadding(8, 0, 4, 0); setOnClickListener { showMiniBall() } }
        topBar.addView(titleText); topBar.addView(btnRefresh); topBar.addView(btnClose)
        root.addView(topBar); enableDrag(topBar, panelParams)

        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#33FFFFFF")); layoutParams = LinearLayout.LayoutParams(-1, 1) })

        scrollView = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 8, 10, 8) }
        scrollView.addView(listContainer); root.addView(scrollView)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#CC0B1120")); layoutParams = LinearLayout.LayoutParams(-1, (46 * density).toInt()) }
        
        fun nav(icon: String, label: String, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { click() }
            addView(TextView(this@FloatingWindowService).apply { text = icon; textSize = 18f; setTextColor(c(themeTextMain)); gravity = Gravity.CENTER })
            addView(TextView(this@FloatingWindowService).apply { text = label; setTextColor(c(themeTextSoft)); textSize = 10f; gravity = Gravity.CENTER })
        }
        bottom.addView(nav("◱", "本地") { currentPage = 0; showFullPanel(0) }, LinearLayout.LayoutParams(0, -1, 1f))
        bottom.addView(nav("☁", "发送") { currentPage = 1; showFullPanel(1) }, LinearLayout.LayoutParams(0, -1, 1f))
        bottom.addView(nav("⚙", "设置") { currentPage = 2; showFullPanel(2) }, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(bottom)

        floatView.addView(root); windowManager.updateViewLayout(floatView, panelParams)
        
        switchPage(page)
        handler.postDelayed({ scrollView.scrollY = lastScrollY }, 50)
    }

    private var lastScrollY = 0

    private fun switchPage(page: Int) {
        if (!this::listContainer.isInitialized) return
        lastScrollY = 0
        listContainer.removeAllViews()
        when (page) {
            0 -> {
                if (cachedSongList != null) { renderLocalList(cachedSongList!!); return }
                listContainer.addView(TextView(this).apply { text = "扫描中..."; setTextColor(c(themeTextSoft)); textSize = 13f; setPadding(16, 16, 16, 16) })
                refreshSessionId++
                val sid = refreshSessionId
                thread {
                    try {
                        val folder = File(localSheetsPath)
                        val files = folder.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList()
                        val list = files.map { Triple(it, readLocalFileInfo(it), "") }
                        cachedSongList = list
                        handler.post { if (sid == refreshSessionId && this::listContainer.isInitialized && !isMiniMode && !isPlayerMode) { listContainer.removeAllViews(); renderLocalList(list) } }
                    } catch (_: Exception) {}
                }
            }
            1 -> {
                if (songData.isNotEmpty()) addSongToList(currentSongName, currentAuthor, currentDuration, currentNotesCount, currentBpm, currentModel, null) { preparePlay() }
                val files = File(appSheetsPath).listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList()
                if (files.isEmpty() && songData.isEmpty()) listContainer.addView(TextView(this).apply { text = "暂无发送的谱面"; setTextColor(c(themeTextSoft)); textSize = 12f; setPadding(12, 12, 12, 12) })
                else for (f in files) { val info = readLocalFileInfo(f); addSongToList(info[0], info[1], info[2], info[3].toInt(), info[5], info[6], f) { directLoadLocalFile(f) } }
            }
            2 -> showSettingsPage()
        }
    }

    private fun showSettingsPage() {
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        fun title(t: String) = TextView(this).apply { text = t; setTextColor(c(themeAccent)); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(6, 14, 6, 6) }
        fun item(t: String, s: String, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12); setBackgroundColor(c(themeCard)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 14f) } }
            clipToOutline = true; setOnClickListener { 
                onItemClicked(click) // 修改：统一调用 onItemClicked 保存 scroll y
                @Suppress("DEPRECATION") // 保留原有逻辑以确保 scroll y 被捕获
                view?.scrollTo(0, view.scrollHeight - (scrollView?.scrollY ?: 0) - view.height) // 简单的强制滚动，如果 scrollY 有效则保持位置，无效则尽量保持
            }
            addView(TextView(this@FloatingWindowService).apply { text = t; setTextColor(c(themeTextMain)); textSize = 13f })
            addView(TextView(this@FloatingWindowService).apply { text = s; setTextColor(c(themeTextSoft)); textSize = 11f; setPadding(0, 3, 0, 0) })
        }
        fun customView(v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12); setBackgroundColor(c(themeCard)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 14f) } }
            clipToOutline = true; addView(v)
        }

        listContainer.addView(title("🎹 键位"))
        listContainer.addView(item("15 键校准", "标准 5×3 网格") { if (checkAccessibility()) startCalibration(15) })
        listContainer.addView(item("9 键校准", "标准 3×3 网格") { if (checkAccessibility()) startCalibration(9) })
        listContainer.addView(item("默认键位", "当前 ${keyMode} 键") { keyMode = if (keyMode == 15) 9 else 15; prefs.edit().putInt("key_mode", keyMode).apply(); loadKeyPositions(); showFullPanel(2) })

        listContainer.addView(title("🎵 演奏"))
        listContainer.addView(item("默认倍速", String.format(Locale.getDefault(), "%.1fx", defaultSpeed)) { defaultSpeed += 0.1f; if (defaultSpeed > 3f) defaultSpeed = 0.5f; defaultSpeed = Math.round(defaultSpeed * 10f) / 10f; currentSpeed = defaultSpeed; prefs.edit().putFloat("default_speed", defaultSpeed).apply(); showFullPanel(2) })
        listContainer.addView(item("点击容差", "${clickToleranceMs}ms") { clickToleranceMs = when (clickToleranceMs) { 15L -> 25L; 25L -> 40L; else -> 15L }; prefs.edit().putLong("click_tolerance", clickToleranceMs).apply(); showFullPanel(2) })
        listContainer.addView(item("播完回列表", if (autoBackToList) "开" else "关") { autoBackToList = !autoBackToList; prefs.edit().putBoolean("auto_back_list", autoBackToList).apply(); showFullPanel(2) })

        listContainer.addView(title("🎨 主题"))
        // 移除了色相滑块

        listContainer.addView(item("强调色（快捷）", "当前 $themeAccent") {
            val presets = listOf("#007AFF", "#FDE047", "#34D399", "#C084FC", "#F472B6", "#FFFFFF")
            val i = presets.indexOf(themeAccent).let { if (it < 0) 0 else (it + 1) % presets.size }
            themeAccent = presets[i]; prefs.edit().putString("theme_accent", themeAccent).apply(); showFullPanel(2)
        })
        // 移除了同步 WebView 背景
        
        listContainer.addView(item("面板透明度", "${(panelAlpha * 100).toInt()}%" ) {
            panelAlpha = when { panelAlpha < 0.40f -> 0.40f; panelAlpha < 0.55f -> 0.55f; panelAlpha < 0.60f -> 0.60f; else -> 0.75f }
            prefs.edit().putFloat("panel_alpha", panelAlpha).apply(); applyHueTheme(themeHue); showFullPanel(2)
        })

        listContainer.addView(title("⚙️ 其他"))
        listContainer.addView(item("清除单曲键位覆盖", "全部恢复全局键位") {
            val ed = prefs.edit(); songKeyModeMap.keys.toList().forEach { ed.remove("song_mode_$it") }; ed.apply(); songKeyModeMap.clear(); Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
        })
        listContainer.addView(item("权限入口", "悬浮窗 / 无障碍") {
            val dialog = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)).setTitle("权限").setItems(arrayOf("悬浮窗", "无障碍")) { _, w -> startActivity(if (w == 0) Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")) else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.create()
            dialog.window?.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE); dialog.show()
        })
    }

    // 新增：确保点击设置时的滚动行为
    private fun onItemClicked(click: () -> Unit) {
        if (scrollView != null && listContainer != null) {
            val viewHeight = listContainer.height
            listContainer.scrollTo(0, viewHeight / 2)
        }
        click.invoke()
    }

    private fun renderLocalList(list: List<Triple<File, List<String>, String>>) {
        if (list.isEmpty()) { listContainer.addView(TextView(this).apply { text = "LocalSheets 为空"; setTextColor(c(themeTextSoft)); setPadding(12, 12, 12, 12) }); return }
        for ((file, info, _) in list) addSongToList(info[0], info[1], info[2], info[3].toInt(), info[5], info[6], file) { directLoadLocalFile(file) }
    }

    private fun addSongToList(songName: String, author: String, duration: String, notes: Int, bpm: String, model: String, file: File?, onClick: () -> Unit) {
        if (!this::listContainer.isInitialized) return
        val pathKey = file?.absolutePath ?: "__memory__"
        val songMode = songKeyModeMap[pathKey] ?: keyMode
        val pureName = if (songName.contains(" || ")) songName.substringBefore(" || ") else songName
        val modelName = when { model.isNotBlank() -> model; songName.contains(" || ") -> songName.substringAfter(" || "); else -> "未知模型" }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(c(themeCard)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 14f) } }
            clipToOutline = true
        }

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { lastScrollY = scrollView?.scrollY ?: 0; onClick() } }

        val title = TextView(this).apply {
            setTextColor(c(themeTextMain)); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true; isFocusable = true; isFocusableInTouchMode = true
            val full = "$pureName  ·  $modelName"
            val sp = SpannableString(full); val idx = full.lastIndexOf("·")
            if (idx >= 0) sp.setSpan(ForegroundColorSpan(c(themeAccent)), idx, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sp
        }
        textCol.addView(title)

        if (compactList) {
            textCol.addView(TextView(this).apply { text = "$author  ·  $duration  ·  ${songMode}键"; setTextColor(c(themeTextSoft)); textSize = 11f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END; setPadding(0, (3 * density).toInt(), 0, 0) })
        } else {
            textCol.addView(TextView(this).apply { text = "$author  ·  $modelName"; setTextColor(c(themeTextSoft)); textSize = 11f; setSingleLine(true); setPadding(0, (3 * density).toInt(), 0, 0) })
            textCol.addView(TextView(this).apply { text = "音符$notes  ·  BPM$bpm  ·  $duration  ·  ${songMode}键"; setTextColor(Color.parseColor("#94A3B8")); textSize = 10f; setSingleLine(true) })
        }
        card.addView(textCol)

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        
        fun iconBtn(t: String, color: Int? = null, click: () -> Unit) = TextView(this).apply { 
            text = t; textSize = 16f; setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0); 
            if (color != null) setTextColor(color) else setTextColor(c(themeTextSoft))
            setOnClickListener { lastScrollY = scrollView?.scrollY ?: 0; click() } 
        }
        
        btns.addView(iconBtn("ⓘ") { showSongDetailDialog(pureName, author, duration, notes, bpm, modelName, file, songMode) })
        btns.addView(iconBtn(if (songMode == 15) "15" else "9", c(themeAccent)) { val nm = if (songMode == 15) 9 else 15; songKeyModeMap[pathKey] = nm; getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE).edit().putInt("song_mode_$pathKey", nm).apply(); showFullPanel(currentPage) })
        btns.addView(iconBtn("✕") {
            if (file == null) { Toast.makeText(this, "内存谱面", Toast.LENGTH_SHORT).show(); return@iconBtn }
            val d = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)).setTitle("删除").setMessage(file.name).setPositiveButton("删除") { _, _ -> if (file.delete()) { if (currentPage == 0) { cachedSongList = null; showFullPanel(0) } else showFullPanel(1) } }.setNegativeButton("取消", null).create()
            d.window?.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE); d.show()
        })
        btns.addView(iconBtn("▷", c("#34D399")) { onClick() })
        
        card.addView(btns)
        listContainer.addView(card)
    }

    private fun showSongDetailDialog(name: String, author: String, duration: String, notes: Int, bpm: String, model: String, file: File?, mode: Int) {
        val msg = "歌名：$name\n作者：$author\n模型：$model\nBPM：$bpm\n音符：$notes\n时长：$duration\n键位：${mode}键\n文件：${file?.name ?: "mem"}"
        val d = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)).setTitle("谱面详情").setMessage(msg).setPositiveButton("确定", null).create()
        d.window?.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE); d.show()
    }

    private fun showPlayerPanel() {
        if (!this::floatView.isInitialized) return
        isMiniMode = false; isPlayerMode = true; floatView.removeAllViews()
        val dm = resources.displayMetrics
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        playerParams.width = (dm.widthPixels * if (land) 0.30f else 0.45f).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(c(themeBgPanel, "#EE0B1120"))
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 16f) } }
            clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val title = TextView(this).apply {
            text = "$currentSongName  ·  $currentModel"; setTextColor(c(themeTextMain)); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true; isFocusable = true; isFocusableInTouchMode = true
        }
        layout.addView(title)
        layout.addView(TextView(this).apply { text = "$currentAuthor  ·  $currentDuration"; setTextColor(c(themeTextSoft)); textSize = 9f; setPadding(0, 2, 0, 4) })
        tvStatus = TextView(this).apply { setTextColor(c(themeAccent)); textSize = 9f; setPadding(0, 0, 0, 4) }
        layout.addView(tvStatus)

        seekBar = SeekBar(this).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(-1, (20 * density).toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser && songData.size > 1) { currentIndex = (p / 100f * (songData.size - 1)).toInt(); updatePlayerUI() } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        layout.addView(seekBar)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, (30 * density).toInt()) }
        
        btnPlayPause = Button(this).apply { text = "▶"; textSize = 12f; setTextColor(Color.WHITE); background = playBgDrawable; setOnClickListener { togglePlayback() }; layoutParams = LinearLayout.LayoutParams(0, -1, 1f) }
        row.addView(btnPlayPause)

        val speed = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#33000000")); cornerRadius = 6f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1.5f).apply { leftMargin = 6; rightMargin = 6 }
        }
        speed.addView(Button(this).apply { text = "−"; textSize = 12f; setTextColor(Color.WHITE); background = null; setOnClickListener { changeSpeed(-0.1f) } }, LinearLayout.LayoutParams((24 * density).toInt(), -1))
        tvSpeed = TextView(this).apply { text = "1.0x"; setTextColor(c(themeAccent)); textSize = 10f; gravity = Gravity.CENTER }
        speed.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))
        speed.addView(Button(this).apply { text = "+"; textSize = 12f; setTextColor(Color.WHITE); background = null; setOnClickListener { changeSpeed(0.1f) } }, LinearLayout.LayoutParams((24 * density).toInt(), -1))
        row.addView(speed)
        
        row.addView(Button(this).apply { text = "✕"; textSize = 12f; setTextColor(Color.WHITE); background = pauseBgDrawable; setOnClickListener { stopPlayback(); showFullPanel(currentPage) }; layoutParams = LinearLayout.LayoutParams(0, -1, 0.6f) })
        layout.addView(row)

        enableDrag(layout, playerParams) { isPlaying }
        floatView.addView(layout); windowManager.updateViewLayout(floatView, playerParams); updatePlayerUI()
    }

    private fun updatePlayerUI() {
        if (!isPlayerMode || !this::floatView.isInitialized) return
        seekBar.progress = if (songData.size <= 1) 0 else (currentIndex.toFloat() / (songData.size - 1) * 100).toInt()
        val sp = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        if (isPlaying) { 
            tvStatus.text = "▶ $currentIndex/${songData.size}  |  $sp"; 
            btnPlayPause.text = "⏸"; btnPlayPause.background = pauseBgDrawable 
        } else { 
            tvStatus.text = "暂停  |  $sp"; 
            btnPlayPause.text = "▶"; btnPlayPause.background = playBgDrawable 
        }
    }

    private fun checkAccessibility(): Boolean {
        if (SkyAccessibilityService.instance == null) { Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_SHORT).show(); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return false }
        return true
    }

    private fun preparePlay() {
        if (!checkAccessibility()) return
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) { Toast.makeText(this, "请先校准键位", Toast.LENGTH_SHORT).show(); return }
        stopPlayback(); currentIndex = 0
        handler.postDelayed({ showPlayerPanel(); updatePlayerUI() }, 50)
    }

    private fun togglePlayback() {
        if (songData.isEmpty() || !checkAccessibility()) return
        if (isPlaying) stopPlayback() else { if (currentIndex >= songData.size) currentIndex = 0; startPlayback() }
        updatePlayerUI()
    }

    private fun changeSpeed(delta: Float) {
        currentSpeed = (currentSpeed + delta).coerceIn(0.5f, 5f); currentSpeed = Math.round(currentSpeed * 10f) / 10f
        if (this::tvSpeed.isInitialized) tvSpeed.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        if (isPlaying) { stopPlayback(); startPlayback() }
    }

    private fun startPlayback() {
        if (songData.isEmpty() || currentIndex >= songData.size) return
        isPlaying = true
        playRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying || currentIndex >= songData.size) { resetPlayState(); return }
                val t0 = songData[currentIndex].first
                val pts = mutableListOf<Pair<Float, Float>>()
                while (currentIndex < songData.size && songData[currentIndex].first - t0 <= clickToleranceMs) {
                    val p = keyPositions.getOrNull(songData[currentIndex].second)
                    if (p != null && p.first != -1f) pts.add(p)
                    currentIndex++
                }
                if (pts.isNotEmpty()) SkyAccessibilityService.instance?.clickPoints(pts)
                updatePlayerUI()
                if (currentIndex < songData.size) { val wait = songData[currentIndex].first - t0; handler.postDelayed(this, maxOf((wait / currentSpeed).toLong(), 1L)) } else resetPlayState()
            }
        }
        handler.post(playRunnable!!)
    }

    private fun resetPlayState() {
        stopPlayback(); currentIndex = 0
        handler.post { if (isPlayerMode && this::seekBar.isInitialized) updatePlayerUI(); if (autoBackToList && isPlayerMode) handler.postDelayed({ showFullPanel(currentPage) }, 600) }
    }

    private fun stopPlayback() { isPlaying = false; playRunnable?.let { handler.removeCallbacks(it) }; playRunnable = null }

    private fun readLocalFileInfo(file: File): List<String> {
        try {
            var text = file.readText(Charsets.UTF_8); if (text.contains("\u0000")) text = file.readText(Charsets.UTF_16LE)
            val content = text.replace("\u0000", "").replace("\uFEFF", "")
            var name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringBefore("-")
            var author = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
            val bpm = Regex("\"bpm\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: "0"
            var model = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: ""
            if (model.isBlank() && name.contains(" || ")) model = name.substringAfter(" || ")
            if (author.isBlank()) author = "未知作者"; if (name.isBlank()) name = file.nameWithoutExtension
            val notes = Regex("\"time\"\\s*:\\s*\\d+").findAll(content).count()
            val duration = if (notes > 0) { val last = Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content).lastOrNull()?.groupValues?.getOrNull(1) ?: "0"; val sec = last.toLong() / 1000; String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60) } else "0:00"
            return listOf(name, author, duration, notes.toString(), "", bpm, model)
        } catch (_: Exception) { return listOf(file.nameWithoutExtension, "未知作者", "0:00", "0", "", "0", "") }
    }

    private fun calculateDuration(notes: List<Pair<Long, Int>>): String {
        if (notes.isEmpty()) return "0:00"
        val sec = notes.last().first / 1000
        return String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
    }

    private fun directLoadLocalFile(file: File) {
        thread {
            try {
                var content = file.readText(Charsets.UTF_8); if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
                content = content.replace("\u0000", "").replace("\uFEFF", "")
                val times = Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content).map { it.groupValues[1].toLong() }.toList()
                val keys = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"").findAll(content).map { it.groupValues[1].toInt() }.toList()
                var name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringBefore("-")
                var author = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
                val bpm = Regex("\"bpm\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: "0"
                var model = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: ""
                if (model.isBlank() && name.contains(" || ")) model = name.substringAfter(" || ")
                if (author.isBlank()) author = "未知作者"; if (name.isBlank()) name = file.nameWithoutExtension
                if (times.isEmpty() || keys.isEmpty()) { handler.post { Toast.makeText(this, "空乐谱", Toast.LENGTH_SHORT).show() }; return@thread }
                val notes = times.indices.mapNotNull { i -> if (i < keys.size) Pair(times[i], keys[i]) else null }.sortedBy { it.first }
                handler.post {
                    songData = notes; currentIndex = 0; currentSongName = name; currentAuthor = author; currentBpm = bpm; currentModel = model; currentNotesCount = notes.size; currentDuration = calculateDuration(notes)
                    val mode = songKeyModeMap[file.absolutePath] ?: keyMode; if (keyPositions.size != mode) loadKeyPositions(mode)
                    preparePlay()
                }
            } catch (_: Exception) { handler.post { Toast.makeText(this, "读取异常", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun startCalibration(mode: Int = keyMode) {
        if (!this::floatView.isInitialized) return
        floatView.visibility = View.GONE; floatView.removeAllViews()
        val dm = resources.displayMetrics; val screenW = dm.widthPixels; val screenH = dm.heightPixels
        val dotSize = (42 * density).toInt(); val half = dotSize / 2
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }; calibrationViews.clear()
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }; calibrationBtnContainer = null
        keyPositions.clear(); val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)

        if (mode == 15) {
            val keyW = screenW * 0.12f; val keyH = keyW * 1.05f; val gapX = screenW * 0.018f; val gapY = screenH * 0.012f
            val gridW = 5 * keyW + 4 * gapX
            var baseX = (screenW - gridW) / 2f + keyW / 2f; var baseY = screenH * 0.42f + keyH / 2f
            val sx = prefs.getFloat("key_15_0_x", -1f); if (sx != -1f) { baseX = sx; baseY = prefs.getFloat("key_15_0_y", baseY) }
            for (i in 0 until 15) { val row = i / 5; val col = i % 5; keyPositions.add(Pair(baseX + col * (keyW + gapX), baseY + row * (keyH + gapY))) }
        } else {
            val keyW = screenW * 0.16f; val keyH = keyW * 1.05f; val gapX = screenW * 0.03f; val gapY = screenH * 0.02f
            val gridW = 3 * keyW + 2 * gapX
            var baseX = (screenW - gridW) / 2f + keyW / 2f; var baseY = screenH * 0.45f + keyH / 2f
            val sx = prefs.getFloat("key_9_0_x", -1f); if (sx != -1f) { baseX = sx; baseY = prefs.getFloat("key_9_0_y", baseY) }
            for (i in 0 until 9) { val row = i / 3; val col = i % 3; keyPositions.add(Pair(baseX + col * (keyW + gapX), baseY + row * (keyH + gapY))) }
        }

        val keyW15 = screenW * 0.12f; val keyH15 = keyW15 * 1.05f; val gapX15 = screenW * 0.018f; val gapY15 = screenH * 0.012f
        val keyW9 = screenW * 0.16f; val keyH9 = keyW9 * 1.05f; val gapX9 = screenW * 0.03f; val gapY9 = screenH * 0.02f

        for (i in 0 until mode) {
            val dot = TextView(this).apply {
                text = "${i + 1}"; setTextColor(c(themeAccent)); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC0B1120")); setStroke((2 * density).toInt(), c(themeAccent)) }
            }
            val lp = WindowManager.LayoutParams(dotSize, dotSize, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START; lp.x = keyPositions[i].first.toInt() - half; lp.y = keyPositions[i].second.toInt() - half

            dot.setOnTouchListener(object : View.OnTouchListener {
                var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
                override fun onTouch(v: View?, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY; return true }
                        MotionEvent.ACTION_MOVE -> {
                            lp.x = ix + (e.rawX - tx).toInt(); lp.y = iy + (e.rawY - ty).toInt(); windowManager.updateViewLayout(dot, lp)
                            val cx = (lp.x + half).toFloat(); val cy = (lp.y + half).toFloat(); keyPositions[i] = Pair(cx, cy)
                            if (i == 0) { for (j in 1 until mode) {
                                val row = if (mode == 15) j / 5 else j / 3; val col = if (mode == 15) j % 5 else j % 3
                                val nx = if (mode == 15) cx + col * (keyW15 + gapX15) else cx + col * (keyW9 + gapX9)
                                val ny = if (mode == 15) cy + row * (keyH15 + gapY15) else cy + row * (keyH9 + gapY9)
                                keyPositions[j] = Pair(nx, ny)
                                val op = calibrationViews[j].layoutParams as WindowManager.LayoutParams; op.x = nx.toInt() - half; op.y = ny.toInt() - half
                                windowManager.updateViewLayout(calibrationViews[j], op)
                            } }
                            return true
                        }
                    }
                    return false
                }
            })
            windowManager.addView(dot, lp); calibrationViews.add(dot)
        }

        val btnSave = Button(this).apply {
            text = "✔ 保存${mode}键"; setTextColor(Color.WHITE); textSize = 12f; setPadding((18 * density).toInt(), (8 * density).toInt(), (18 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply { setColor(c("#34D399")); cornerRadius = 8f }
            setOnClickListener { saveKeyPositions(mode); keyMode = mode; prefs.edit().putInt("key_mode", mode).apply(); exitCalibration(); Toast.makeText(this@FloatingWindowService, "已保存 ${mode}键", Toast.LENGTH_SHORT).show() }
        }
        val btnCancel = Button(this).apply {
            text = "✖ 取消"; setTextColor(Color.WHITE); textSize = 12f; setPadding((18 * density).toInt(), (8 * density).toInt(), (18 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply { setColor(c("#F87171")); cornerRadius = 8f }
            setOnClickListener { loadKeyPositions(); exitCalibration() }
        }
        calibrationBtnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            addView(btnSave); addView(Space(this@FloatingWindowService).apply { layoutParams = LinearLayout.LayoutParams((12 * density).toInt(), 1) }); addView(btnCancel)
        }
        val bp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.START; x = (20 * density).toInt(); y = (30 * density).toInt() }
        windowManager.addView(calibrationBtnContainer, bp)
    }

    private fun exitCalibration() {
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }; calibrationViews.clear()
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }; calibrationBtnContainer = null
        floatView.visibility = View.VISIBLE; showMiniBall()
    }

    private fun loadKeyPositions(forceMode: Int? = null) {
        val mode = forceMode ?: keyMode; keyPositions.clear()
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        for (i in 0 until mode) keyPositions.add(Pair(prefs.getFloat("key_${mode}_${i}_x", -1f), prefs.getFloat("key_${mode}_${i}_y", -1f)))
    }

    private fun saveKeyPositions(mode: Int = keyMode) {
        val ed = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE).edit()
        for (i in keyPositions.indices) { ed.putFloat("key_${mode}_${i}_x", keyPositions[i].first); ed.putFloat("key_${mode}_${i}_y", keyPositions[i].second) }
        ed.apply()
    }

    override fun onDestroy() {
        super.onDestroy(); stopPlayback(); handler.removeCallbacksAndMessages(null)
        if (this::floatView.isInitialized) { try { windowManager.removeView(floatView) } catch (_: Exception) {} }
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }; calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
