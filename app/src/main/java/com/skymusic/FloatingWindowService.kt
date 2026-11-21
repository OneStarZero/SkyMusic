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
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
    private var panelAlpha = 0.92f
    private var autoBackToList = true
    private var compactList = true
    private val songKeyModeMap = mutableMapOf<String, Int>()

    // 主题系统（简化版，移除色相轮）
    private var themeAccent = "#7DD3FC"
    private var themeBgPanel = "#EB0B1120"
    private var themeTopBar = "#1E293B"
    private var themeCard = "#1E293B"
    private var themeTextMain = "#F8FAFC"
    private var themeTextSoft = "#CBD5E1"
    private val songKeyModeMap = mutableMapOf<String, Int>()

    private var presetAccentColors = listOf(
        "#7DD3FC",  // Azure
        "#FDE047",  // Yellow
        "#34D399",  // Emerald
        "#C084FC",  // Purple
        "#F472B6",  // Pink
        "#FFFFFF"   // White
    )

    private val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
    var syncedWebTheme = false
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density

        prefs.all.forEach { (k, v) ->
            if (k.startsWith("key_mode_") && v is Int) songKeyModeMap[k.removePrefix("key_mode_")] = v as Int
        }

        keyMode = prefs.getInt("key_mode", 15)
        defaultSpeed = prefs.getFloat("default_speed", 1.0f)
        currentSpeed = defaultSpeed
        clickToleranceMs = prefs.getLong("click_tolerance", 25L)
        ballSize = prefs.getInt("ball_size", 60)
        panelAlpha = prefs.getFloat("panel_alpha", 0.92f)
        autoBackToList = prefs.getBoolean("auto_back_list", true)
        compactList = prefs.getBoolean("compact_list", true)
        
        applyTheme(kotlin.math.min(1, prefs.getFloat("theme_accent_index", 0) / 5f))
        playBgDrawable.setColor(Color.parseColor("#34D399"))
        pauseBgDrawable.setColor(Color.parseColor("#F87171"))

        loadKeyPositions()
        File(appSheetsPath).mkdirs()
        File(localSheetsPath).mkdirs()
    }

    private fun applyTheme(hue: Float) {
        val hueInt = (hue * 360).toInt()
        val hexColor = String.format("#%06X", 0xFFFFFF and Color.HSVToColor(floatArrayOf(hueInt, 0.85f, 0.72f)))
        themeAccent = hexColor
        
        val topHueInt = (hueInt + 90).toInt()
        val topColor = String.format("#%06X", 0xFFFFFF and Color.HSVToColor(floatArrayOf(topHueInt % 360, 0.32f, 0.18f)))
        themeTopBar = topColor
        
        val cardHueInt = (hueInt + 120).toInt()
        val cardColor = String.format("#%06X", 0xFFFFFF and Color.HSVToColor(floatArrayOf(cardHueInt % 360, 0.28f, 0.14f)))
        themeCard = cardColor
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

        val bottomNav = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL; 
            setBackgroundColor(Color.parseColor("#CC0B1120")); 
            layoutParams = LinearLayout.LayoutParams(-1, WindowManager.LayoutParams.WRAP_CONTENT) 
        }
        
        fun nav(icon: String, label: String, click: () -> Unit) {
            btnNav = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { click() }
                addView(TextView(this@FloatingWindowService).apply { text = icon; textSize = 18f; setTextColor(c(themeTextMain)); gravity = Gravity.CENTER })
                addView(TextView(this@FloatingWindowService).apply { text = label; setTextColor(c(themeTextSoft)); textSize = 10f; gravity = Gravity.CENTER })
            }
        }
        bottomNav.addView(nav("◱", "本地", { currentPage = 0; showFullPanel(0) }), LinearLayout.LayoutParams(0, -1, 1f))
        bottomNav.addView(nav("☁", "发送", { currentPage = 1; showFullPanel(1) }), LinearLayout.LayoutParams(0, -1, 1f))
        bottomNav.addView(nav("⚙", "设置", { currentPage = 2; showFullPanel(2) }), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(bottomNav)

        floatView.addView(root); windowManager.updateViewLayout(floatView, panelParams)
        
        switchPage(page)
    }

    private var btnNav: TextView

    private fun switchPage(page: Int) {
        if (!this::listContainer.isInitialized) return
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
        fun title(t: String) = TextView(this).apply { 
            text = t; setTextColor(c(themeAccent)); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(6, 14, 6, 6) 
        }
        
        fun simpleItem(t: String, s: String, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12); setBackgroundColor(c(themeCard, "#1E293B")); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 14f) } }
            clipToOutline = true; setOnClickListener { click() }
            addView(TextView(this@FloatingWindowService).apply { text = t; setTextColor(c(themeTextMain, "#F8FAFC")); textSize = 13f })
            addView(TextView(this@FloatingWindowService).apply { text = s; setTextColor(c(themeTextSoft, "#CBD5E1")); textSize = 11f; setPadding(0, 3, 0, 0) })
        }

        listContainer.addView(title("🎹 键位"))
        listContainer.addView(simpleItem("15 键校准", "标准 5×3 网格") { if (checkAccessibility()) startCalibration(15) })
        listContainer.addView(simpleItem("9 键校准", "标准 3×3 网格") { if (checkAccessibility()) startCalibration(9) })
        listContainer.addView(simpleItem("切换默认键位", "${when (keyMode == 15) "9" else "15"} 键") { keyMode = if (keyMode == 15) 9 else 15; prefs.edit().putInt("key_mode", keyMode).apply(); loadKeyPositions(); showFullPanel(2) })

        listContainer.addView(title("🎵 演奏"))
        listContainer.addView(simpleItem("默认倍速", String.format(Locale.getDefault(), "%.1fx", defaultSpeed)) { defaultSpeed += 0.1f; if (defaultSpeed > 3f) defaultSpeed = 0.5f; defaultSpeed = kotlin.math.round(defaultSpeed * 10f) / 10f; currentSpeed = defaultSpeed; prefs.edit().putFloat("default_speed", defaultSpeed).apply(); showFullPanel(2) })
        listContainer.addView(simpleItem("点击容差", "${clickToleranceMs}ms") { clickToleranceMs = when (clickToleranceMs) { 15L -> 25L; 25L -> 40L; else -> 15L }; prefs.edit().putLong("click_tolerance", clickToleranceMs).apply(); showFullPanel(2) })
        listContainer.addView(simpleItem("播完回列表", if (autoBackToList) "开" else "关") { autoBackToList = !autoBackToList; prefs.edit().putBoolean("auto_back_list", autoBackToList).apply(); showFullPanel(2) })

        ListContainer.addView(title("🎨 外观"))
        listContainer.addView(simpleItem("强调色（轮换）", presetAccentColors.first()) { 
            val currentIndex = presetAccentColors.indexOf(themeAccent)
            themeAccent = presetAccentColors[(currentIndex + 1) % presetAccentColors.size]
            prefs.edit().putString("theme_accent", themeAccent).apply()
            showFullPanel(2) 
        })

        listContainer.addView(simpleItem("关闭导航栏", if (compactList) "开" else "关") { compactList = !compactList; prefs.edit().putBoolean("compact_list", compactList).apply(); showFullPanel(2) })
        listContainer.addView(simpleItem("面板透明度", "${(panelAlpha * 100).toInt()}%") { 
            panelAlpha = when { panelAlpha < 0.70f -> 0.70f; panelAlpha < 0.85f -> 0.85f; panelAlpha < 0.92f -> 0.92f; else -> 0.60f }
            prefs.edit().putFloat("panel_alpha", panelAlpha).apply()
            showFullPanel(2) 
        })

        listContainer.addView(title("⚙️ 其他"))
        listContainer.addView(simpleItem("清除单曲键位覆盖", "全部恢复全局键位") { 
            val ed = prefs.edit()
            songKeyModeMap.keys.toList().forEach { ed.remove(it) }
            ed.apply()
            songKeyModeMap.clear()
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
        })
        listContainer.addView(simpleItem("权限入口", "悬浮窗 / 无障碍") { 
            startActivity(if (currentPage == 0) Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")) else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
    }

    private fun renderLocalList(list: List<Triple<File, List<String>, String>>) {
        if (list.isEmpty()) { listContainer.addView(TextView(this).apply { text = "本地曲库为空"; setTextColor(c(themeTextSoft)); setPadding(12, 12, 12, 12) }); return }
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
            setBackgroundColor(c/themeCard, "#1E293B")); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 14f) } }
            clipToOutline = true
        }

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { onClick() } }

        val title = TextView(this).apply {
            setTextColor(c/themeTextMain, "#F8FAFC"); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true; isFocusable = true; isFocusableInTouchMode = true
        }
        textCol.addView(title)

        if (compactList) {
            // 🌟 移除作者和模型信息，只保留基本数据
            textCol.addView(TextView(this).apply { text = "$duration ·  ${songMode}键"; setTextColor(c/themeTextSoft, "#CBD5E1")); textSize = 11f; setSingleLine(true); setPadding(0, (3 * density).toInt(), 0, 0) })
        } else {
            // 🌟 使用简化的显示方式
            textCol.addView(TextView(this).apply { text = "暂无作者信息"; setTextColor(Color.parseColor("#94A3B8")); textSize = 10f; setSingleLine(true); setPadding(0, (3 * density).toInt(), 0, 0) })
        }
        card.addView(textCol)

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        
        fun iconBtn(t: String, color: Int? = null) = TextView(this).apply { 
            text = t; textSize = 16f; setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0); 
            if (color != null) setTextColor(color) else setTextColor(c/themeTextSoft, "#CBD5E1")
        }
        
        btns.addView(iconBtn("♻") { Toast.makeText(this, "无法预览谱面", Toast.LENGTH_SHORT).show() })
        btns.addView(iconBtn(if (songMode == 15) "15" else "9", c/themeAccent, "#7DD3FC")) { val nm = if (songMode == 15) 9 else 15; songKeyModeMap[pathKey] = nm; prefs.edit().putInt("song_mode_$pathKey", nm).apply(); showFullPanel(currentPage) }
        btns.addView(iconBtn("✕") {
            if (file == null) { Toast.makeText(this, "内存谱面", Toast.LENGTH_SHORT).show(); return@iconBtn }
            val d = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)).setTitle("删除").setMessage(file.name).setPositiveButton("删除") { _, _ -> if (file.delete()) { if (currentPage == 0) { cachedSongList = null; showFullPanel(0) } else showFullPanel(1) } }.setNegativeButton("取消", null).create()
            d.window?.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE); d.show()
        })
        btns.addView(iconBtn("▶", c("#34D399")) { onClick() })
        
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
            orientation = LinearLayout.VERTICAL; setBackgroundColor(c/themeBgPanel, "#EE0B1120"))
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 16f) } }
            clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val title = TextView(this).apply {
            text = "$currentSongName ·  $currentModel"; setTextColor(c/themeTextMain, "#F8FAFC"); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true; isFocusable = true; isFocusableInTouchMode = true
        }
        layout.addView(title)
        layout.addView(TextView(this).apply { text = "$currentAuthor ·  $currentDuration"; setTextColor(c/themeTextSoft, "#CBD5E1"); textSize = 9f; setPadding(0, 2, 0, 4) })
        tvStatus = TextView(this).apply { setTextColor(c/themeAccent, "#7DD3FC"); textSize = 9f; setPadding(0, 0, 0, 4) }
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
        tvSpeed = TextView(this).apply { text = "1.0x"; setTextColor(c/themeAccent, "#7DD3FC"); textSize = 10f; gravity = Gravity.CENTER }
        speed.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))
        speed.addView(Button(this).apply { text = "+"; textSize = 12f; setTextColor(Color.WHITE); background = null; setOnClickListener { changeSpeed(0.1f) } }, LinearLayout.LayoutParams((24 * density).toInt(), -1))
        row.addView(speed)
        
        row.addView(Button(this).apply { text = "✕"; textSize = 12f; setTextColor(Color.WHITE); background = pauseBgDrawable; setOnClickListener { stopPlayback(); showFullPanel(currentPage) }; layoutParams = LinearLayout.LayoutParams(0, -1, 0.6f) })
        layout.addView(row)

        enableDrag(layout, playerParams) { isPlaying }
        floatView.addView(layout); windowManager.updateViewLayout(floatView, playerParams); updatePlayerUI()
    }

    override fun onDestroy() {
        super.onDestroy(); stopPlayback(); handler.removeCallbacksAndMessages(null)
        if (this::floatView.isInitialized) { try { windowManager.removeView(floatView) } catch (_: Exception) {} }
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }; calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
