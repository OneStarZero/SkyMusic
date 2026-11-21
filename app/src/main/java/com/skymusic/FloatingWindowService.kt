package com.skymusic

import android.app.AlertDialog
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
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
    private lateinit var tvNoteProgress: TextView
    private lateinit var tvTimeProgress: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var tvSpeed: TextView
    private lateinit var scrollView: ScrollView

    private var panelRootView: LinearLayout? = null
    private var playerRootView: LinearLayout? = null

    private val handler = Handler(Looper.getMainLooper())
    private var playRunnable: Runnable? = null

    private val basePath = "/storage/emulated/0/SkyMusic-OneStar"
    private val appSheetsPath = "$basePath/AppSheets"
    private val localSheetsPath = "$basePath/LocalSheets"

    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var playerParams: WindowManager.LayoutParams

    private var currentPage = 0
    private var lastRenderedPage = -1
    private var lastScrollY = 0
    private var density = 1.0f
    private var cachedSongList: List<Triple<File, List<String>, String>>? = null
    private var refreshSessionId = 0

    private val playBgDrawable = GradientDrawable().apply { cornerRadius = 6f }
    private val pauseBgDrawable = GradientDrawable().apply { cornerRadius = 6f }

    private var calibrationViews = mutableListOf<TextView>()
    private var calibrationBtnContainer: LinearLayout? = null

    // 设置项
    private var keyMode = 15
    private var defaultSpeed = 1.0f
    private var clickToleranceMs = 25L
    private var ballSize = 60
    private var panelAlpha = 0.85f
    private var autoBackToList = true
    private val songKeyModeMap = mutableMapOf<String, Int>()

    // 经典清晰色板
    private var themeAccent = "#38BDF8"
    private var themeBgPanel = "#D90F172A"
    private var themeTopBar = "#162033"
    private var themeBottomBar = "#0F172A"
    private var themeDivider = "#24FFFFFF"
    private var themeTextMain = "#F8FAFC"
    private var themeTextSoft = "#94A3B8"

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
        panelAlpha = prefs.getFloat("panel_alpha", 0.85f).coerceIn(0.35f, 1.0f)
        autoBackToList = prefs.getBoolean("auto_back_list", true)
        themeAccent = prefs.getString("theme_accent", "#38BDF8") ?: "#38BDF8"

        prefs.all.forEach { (k, v) ->
            if (k.startsWith("song_mode_") && v is Int) {
                songKeyModeMap[k.removePrefix("song_mode_")] = v
            }
        }

        updatePanelAlphaColor(panelAlpha)
        playBgDrawable.setColor(Color.parseColor("#22C55E"))
        pauseBgDrawable.setColor(Color.parseColor("#EF4444"))

        loadKeyPositions()
        File(appSheetsPath).mkdirs()
        File(localSheetsPath).mkdirs()
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun updatePanelAlphaColor(alpha: Float) {
        panelAlpha = alpha.coerceIn(0.35f, 1.0f)
        val a = (panelAlpha * 255).toInt().coerceIn(90, 255)
        themeBgPanel = String.format("#%02X0F172A", a)
        val bgColor = c(themeBgPanel, "#D90F172A")
        panelRootView?.setBackgroundColor(bgColor)
        playerRootView?.setBackgroundColor(bgColor)
    }

    private fun enableMarquee(tv: TextView) {
        tv.setSingleLine(true)
        tv.ellipsize = TextUtils.TruncateAt.MARQUEE
        tv.marqueeRepeatLimit = -1
        tv.isSelected = true
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
    }

    private fun c(hex: String, fb: String = "#FFFFFF"): Int {
        return try { Color.parseColor(hex) } catch (_: Exception) { Color.parseColor(fb) }
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60L, totalSec % 60L)
    }

    private fun totalDurationMs(): Long {
        if (songData.isEmpty()) return 0L
        return songData.last().first.coerceAtLeast(0L)
    }

    private fun currentTimeMs(): Long {
        if (songData.isEmpty()) return 0L
        val idx = currentIndex.coerceIn(0, songData.size - 1)
        return songData[idx].first.coerceAtLeast(0L)
    }

    private fun findIndexByTimeMs(targetMs: Long): Int {
        if (songData.isEmpty()) return 0
        var low = 0
        var high = songData.size - 1
        var ans = songData.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (songData[mid].first >= targetMs) {
                ans = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return ans.coerceIn(0, songData.size - 1)
    }

    private fun jumpToNoteIndex(targetIdx: Int) {
        if (songData.isEmpty()) return
        val clamped = targetIdx.coerceIn(0, songData.size - 1)
        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()
        currentIndex = clamped
        updatePlayerUI()
        if (wasPlaying) startPlayback()
    }

    private fun showJumpToNoteDialog() {
        if (songData.isEmpty()) {
            Toast.makeText(this, "暂无音符数据", Toast.LENGTH_SHORT).show()
            return
        }
        val maxNote = songData.size
        val input = EditText(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "输入音符序号 (1 ~ $maxNote)"
            setText((currentIndex + 1).coerceIn(1, maxNote).toString())
            setSelection(text.length)
        }
        val container = FrameLayout(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)).apply {
            setPadding(dp(20f), dp(12f), dp(20f), dp(4f))
            addView(input)
        }

        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
            .setTitle("跳转到指定音符")
            .setMessage("当前第 ${(currentIndex + 1).coerceAtMost(maxNote)} / $maxNote 个音符")
            .setView(container)
            .setPositiveButton("跳转") { _, _ ->
                val raw = input.text.toString().trim()
                val num = raw.toIntOrNull()
                if (num == null || num < 1 || num > maxNote) {
                    Toast.makeText(this, "请输入 1 到 $maxNote 之间的音符数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                jumpToNoteIndex(num - 1)
                Toast.makeText(this, "已跳转到第 $num 个音符", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
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
                    if (!isMiniMode && !isPlayerMode) {
                        currentPage = 1
                        showFullPanel(1, resetScroll = true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (!this::floatView.isInitialized) createFloatingWindow()
        return START_STICKY
    }

    private fun createFloatingWindow() {
        try {
            floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            val dm = resources.displayMetrics
            val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            panelParams = WindowManager.LayoutParams(
                (dm.widthPixels * if (land) 0.36f else 0.56f).toInt(),
                (dm.heightPixels * if (land) 0.60f else 0.50f).toInt(),
                type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (dm.widthPixels * 0.06f).toInt()
                y = (dm.heightPixels * 0.16f).toInt()
            }

            ballParams = WindowManager.LayoutParams(
                ballSize, ballSize, type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }

            val pw = (dm.widthPixels * if (land) 0.36f else 0.54f).toInt()
            playerParams = WindowManager.LayoutParams(
                pw, WindowManager.LayoutParams.WRAP_CONTENT, type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (dm.widthPixels - pw) / 2
                y = dm.heightPixels - dp(165f)
            }

            windowManager.addView(floatView, ballParams)
            showMiniBall()
        } catch (_: Exception) {
            Toast.makeText(this, "悬浮窗启动失败", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams, locked: () -> Boolean = { false }) {
        var ix = 0
        var iy = 0
        var tx = 0f
        var ty = 0f
        var dragging = false
        val slop = 8 * density
        view.setOnTouchListener { v, e ->
            if (locked()) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == 1) {
                        val dx = e.rawX - tx; val dy = e.rawY - ty
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                        if (dragging) {
                            params.x = ix + dx.toInt(); params.y = iy + dy.toInt()
                            windowManager.updateViewLayout(floatView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging && e.pointerCount == 1) v.performClick()
                    dragging = false; true
                }
                else -> false
            }
        }
    }

    private fun showMiniBall() {
        if (!this::floatView.isInitialized) return
        isMiniMode = true; isPlayerMode = false
        panelRootView = null; playerRootView = null
        floatView.removeAllViews()
        ballParams.width = ballSize; ballParams.height = ballSize

        val ball = TextView(this).apply {
            text = "✦"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(c("#FDE047")); setShadowLayer(8f, 0f, 0f, c("#EAB308"))
        }
        val wrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC0F172A"))
                setStroke(dp(1.5f).coerceAtLeast(1), c(themeAccent))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        wrap.addView(ball); floatView.addView(wrap)
        enableDrag(wrap, ballParams)
        wrap.setOnClickListener { showFullPanel(currentPage, resetScroll = false) }
        windowManager.updateViewLayout(floatView, ballParams)
    }

    private fun captureCurrentScrollY() {
        if (this::scrollView.isInitialized) {
            lastScrollY = scrollView.scrollY
        }
    }

    private fun restoreScrollY() {
        if (!this::scrollView.isInitialized) return
        val targetY = lastScrollY
        scrollView.post {
            if (this::scrollView.isInitialized) {
                scrollView.scrollTo(0, targetY)
            }
        }
        handler.postDelayed({
            if (this::scrollView.isInitialized && !isMiniMode && !isPlayerMode) {
                scrollView.scrollTo(0, targetY)
            }
        }, 40)
    }

    private fun addDivider() {
        if (!this::listContainer.isInitialized) return
        listContainer.addView(View(this).apply {
            setBackgroundColor(c(themeDivider))
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        })
    }

    private fun showFullPanel(page: Int = 0, resetScroll: Boolean = false) {
        if (!this::floatView.isInitialized) return
        if (!isMiniMode && !isPlayerMode && this::scrollView.isInitialized && !resetScroll) {
            lastScrollY = scrollView.scrollY
        }
        if (resetScroll || lastRenderedPage != page) {
            lastScrollY = 0
        }
        lastRenderedPage = page
        currentPage = page

        isMiniMode = false; isPlayerMode = false
        playerRootView = null
        floatView.removeAllViews()

        updatePanelAlphaColor(panelAlpha)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(c(themeBgPanel, "#D90F172A"))
                setStroke(dp(1f).coerceAtLeast(1), Color.parseColor("#38FFFFFF"))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14f).toFloat())
                }
            }
            clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        panelRootView = root

        // 顶部栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(9f), dp(10f), dp(9f))
            setBackgroundColor(c(themeTopBar, "#162033"))
        }
        titleText = TextView(this).apply {
            text = when (page) { 0 -> "本地曲库"; 1 -> "App 发送"; else -> "设置" }
            setTextColor(c(themeTextMain))
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        enableMarquee(titleText)

        val btnRefresh = TextView(this).apply {
            text = "↻"; textSize = 15f; setTextColor(c(themeAccent))
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            setOnClickListener {
                captureCurrentScrollY()
                if (currentPage == 0) cachedSongList = null
                showFullPanel(currentPage, resetScroll = false)
            }
        }
        val btnClose = TextView(this).apply {
            text = "✕"; textSize = 15f; setTextColor(c(themeTextMain))
            setPadding(dp(8f), dp(2f), dp(4f), dp(2f))
            setOnClickListener {
                captureCurrentScrollY()
                showMiniBall()
            }
        }
        topBar.addView(titleText); topBar.addView(btnRefresh); topBar.addView(btnClose)
        root.addView(topBar); enableDrag(topBar, panelParams)

        root.addView(View(this).apply {
            setBackgroundColor(c(themeDivider))
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        })

        // 列表滚动区
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(listContainer); root.addView(scrollView)

        // 底部导航栏
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c(themeBottomBar, "#0F172A"))
            layoutParams = LinearLayout.LayoutParams(-1, dp(46f))
        }
        fun nav(icon: String, label: String, targetPage: Int): LinearLayout {
            val active = currentPage == targetPage
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setOnClickListener { showFullPanel(targetPage, resetScroll = true) }
                addView(TextView(this@FloatingWindowService).apply {
                    text = icon; textSize = 15f
                    setTextColor(if (active) c(themeAccent) else c(themeTextSoft))
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@FloatingWindowService).apply {
                    text = label; textSize = 10.5f
                    if (active) setTypeface(null, Typeface.BOLD)
                    setTextColor(if (active) c(themeAccent) else c(themeTextSoft))
                    gravity = Gravity.CENTER
                })
            }
        }
        bottom.addView(nav("◱", "本地", 0))
        bottom.addView(nav("☁", "发送", 1))
        bottom.addView(nav("⚙", "设置", 2))
        root.addView(bottom)

        floatView.addView(root); windowManager.updateViewLayout(floatView, panelParams)

        switchPage(page)
        restoreScrollY()
    }

    private fun switchPage(page: Int) {
        if (!this::listContainer.isInitialized) return
        listContainer.removeAllViews()
        when (page) {
            0 -> {
                if (cachedSongList != null) {
                    renderLocalList(cachedSongList!!)
                    restoreScrollY()
                    return
                }
                val loadingTv = TextView(this).apply {
                    text = "扫描中..."; setTextColor(c(themeTextSoft)); textSize = 13f
                    setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                }
                enableMarquee(loadingTv)
                listContainer.addView(loadingTv)

                refreshSessionId++
                val sid = refreshSessionId
                thread {
                    try {
                        val folder = File(localSheetsPath)
                        val files = folder.listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList()
                        val list = files.map { Triple(it, readLocalFileInfo(it), "") }
                        cachedSongList = list
                        handler.post {
                            if (sid == refreshSessionId && this::listContainer.isInitialized && !isMiniMode && !isPlayerMode && currentPage == 0) {
                                listContainer.removeAllViews()
                                renderLocalList(list)
                                restoreScrollY()
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            1 -> {
                if (songData.isNotEmpty()) {
                    addSongToList(currentSongName, currentAuthor, currentDuration, currentNotesCount, currentBpm, currentModel, null) { preparePlay() }
                    addDivider()
                }
                val files = File(appSheetsPath).listFiles { f -> f.extension.equals("txt", true) }?.toList() ?: emptyList()
                if (files.isEmpty() && songData.isEmpty()) {
                    val emptyTv = TextView(this).apply {
                        text = "暂无发送的谱面"; setTextColor(c(themeTextSoft)); textSize = 12.5f
                        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                    }
                    enableMarquee(emptyTv)
                    listContainer.addView(emptyTv)
                } else {
                    for ((idx, f) in files.withIndex()) {
                        val info = readLocalFileInfo(f)
                        addSongToList(info[0], info[1], info[2], info[3].toInt(), info[5], info[6], f) { directLoadLocalFile(f) }
                        if (idx < files.lastIndex) addDivider()
                    }
                }
            }
            2 -> showSettingsPage()
        }
    }

    private fun refreshSettingsKeepScroll() {
        captureCurrentScrollY()
        showFullPanel(2, resetScroll = false)
    }

    private fun showSettingsPage() {
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)

        fun sectionTitle(t: String) {
            val tv = TextView(this).apply {
                text = t
                setTextColor(c(themeAccent))
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
            }
            enableMarquee(tv)
            listContainer.addView(tv)
        }

        fun item(t: String, s: String, click: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                setOnClickListener {
                    captureCurrentScrollY()
                    click()
                }
            }
            val tvTitle = TextView(this).apply {
                text = t
                setTextColor(c(themeTextMain))
                textSize = 13.5f
            }
            enableMarquee(tvTitle)
            row.addView(tvTitle)

            if (s.isNotEmpty()) {
                val tvSub = TextView(this).apply {
                    text = s
                    setTextColor(c(themeTextSoft))
                    textSize = 11f
                    setPadding(0, dp(2f), 0, 0)
                }
                enableMarquee(tvSub)
                row.addView(tvSub)
            }
            listContainer.addView(row)
            addDivider()
        }

        fun alphaSliderItem() {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvLabel = TextView(this).apply {
                text = "悬浮窗透明度"
                setTextColor(c(themeTextMain))
                textSize = 13.5f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            enableMarquee(tvLabel)
            val pct = (panelAlpha * 100f).toInt().coerceIn(35, 100)
            val tvVal = TextView(this).apply {
                text = "$pct%"
                setTextColor(c(themeAccent))
                textSize = 12f
                setSingleLine(true)
            }
            top.addView(tvLabel)
            top.addView(tvVal)
            row.addView(top)

            val sb = SeekBar(this).apply {
                max = 65
                progress = (pct - 35).coerceIn(0, 65)
                layoutParams = LinearLayout.LayoutParams(-1, dp(22f)).apply {
                    topMargin = dp(4f)
                }
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                        captureCurrentScrollY()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val currentPct = (35 + p).coerceIn(35, 100)
                            tvVal.text = "$currentPct%"
                            updatePanelAlphaColor(currentPct / 100f)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        captureCurrentScrollY()
                    }
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val p = seekBar?.progress ?: return
                        val currentPct = (35 + p).coerceIn(35, 100)
                        tvVal.text = "$currentPct%"
                        updatePanelAlphaColor(currentPct / 100f)
                        prefs.edit().putFloat("panel_alpha", panelAlpha).apply()
                    }
                })
            }
            row.addView(sb)
            listContainer.addView(row)
            addDivider()
        }

        sectionTitle("键位与校准")
        item("15 键校准", "标准 5×3 网格") {
            if (checkAccessibility()) startCalibration(15)
        }
        item("9 键校准", "标准 3×3 网格") {
            if (checkAccessibility()) startCalibration(9)
        }
        item("默认键位", "当前 ${keyMode} 键") {
            keyMode = if (keyMode == 15) 9 else 15
            prefs.edit().putInt("key_mode", keyMode).apply()
            loadKeyPositions()
            refreshSettingsKeepScroll()
        }

        sectionTitle("演奏")
        item("默认倍速", String.format(Locale.getDefault(), "%.1fx", defaultSpeed)) {
            defaultSpeed += 0.1f
            if (defaultSpeed > 3f) defaultSpeed = 0.5f
            defaultSpeed = Math.round(defaultSpeed * 10f) / 10f
            currentSpeed = defaultSpeed
            prefs.edit().putFloat("default_speed", defaultSpeed).apply()
            refreshSettingsKeepScroll()
        }
        item("点击容差", "${clickToleranceMs}ms") {
            clickToleranceMs = when (clickToleranceMs) { 15L -> 25L; 25L -> 40L; else -> 15L }
            prefs.edit().putLong("click_tolerance", clickToleranceMs).apply()
            refreshSettingsKeepScroll()
        }
        item("播完回列表", if (autoBackToList) "开" else "关") {
            autoBackToList = !autoBackToList
            prefs.edit().putBoolean("auto_back_list", autoBackToList).apply()
            refreshSettingsKeepScroll()
        }

        sectionTitle("显示")
        alphaSliderItem()

        sectionTitle("权限与更多")
        item("清除单曲键位覆盖", "全部恢复全局默认键位") {
            val ed = prefs.edit()
            songKeyModeMap.keys.toList().forEach { ed.remove("song_mode_$it") }
            ed.apply()
            songKeyModeMap.clear()
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
        }
        item("权限入口", "悬浮窗 / 无障碍") {
            val dialog = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                .setTitle("权限")
                .setItems(arrayOf("悬浮窗", "无障碍")) { _, w ->
                    startActivity(
                        if (w == 0) {
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        } else {
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }.create()
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            dialog.show()
        }
    }

    private fun renderLocalList(list: List<Triple<File, List<String>, String>>) {
        if (list.isEmpty()) {
            val tv = TextView(this).apply {
                text = "LocalSheets 为空"; setTextColor(c(themeTextSoft)); textSize = 13f
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            }
            enableMarquee(tv)
            listContainer.addView(tv)
            return
        }
        for ((idx, item) in list.withIndex()) {
            val (file, info, _) = item
            addSongToList(info[0], info[1], info[2], info[3].toInt(), info[5], info[6], file) { directLoadLocalFile(file) }
            if (idx < list.lastIndex) addDivider()
        }
    }

    private fun addSongToList(
        songName: String,
        author: String,
        duration: String,
        notes: Int,
        bpm: String,
        model: String,
        file: File?,
        onClick: () -> Unit
    ) {
        if (!this::listContainer.isInitialized) return
        val pathKey = file?.absolutePath ?: "__memory__"
        val songMode = songKeyModeMap[pathKey] ?: keyMode
        val pureName = if (songName.contains(" || ")) songName.substringBefore(" || ") else songName
        val modelName = when {
            model.isNotBlank() -> model
            songName.contains(" || ") -> songName.substringAfter(" || ")
            else -> "未知模型"
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(10f), dp(8f))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                rightMargin = dp(6f)
            }
            setOnClickListener { captureCurrentScrollY(); onClick() }
        }

        val title = TextView(this).apply {
            text = "$pureName · $modelName"
            setTextColor(c(themeTextMain)); textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
        }
        enableMarquee(title)
        textCol.addView(title)

        val subTv = TextView(this).apply {
            text = "$author · $duration · ${notes}音符 · ${songMode}键"
            setTextColor(c(themeTextSoft)); textSize = 11f
            setPadding(0, dp(2f), 0, 0)
        }
        enableMarquee(subTv)
        textCol.addView(subTv)
        row.addView(textCol)

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        fun iconBtn(t: String, color: Int? = null, click: () -> Unit) = TextView(this).apply {
            text = t; textSize = 13f; gravity = Gravity.CENTER
            val w = dp(24f)
            layoutParams = LinearLayout.LayoutParams(w, w)
            if (color != null) setTextColor(color) else setTextColor(c(themeTextSoft))
            setOnClickListener { captureCurrentScrollY(); click() }
        }

        btns.addView(iconBtn("ⓘ") { showSongDetailDialog(pureName, author, duration, notes, bpm, modelName, file, songMode) })
        btns.addView(iconBtn(if (songMode == 15) "15" else "9", c(themeAccent)) {
            val nm = if (songMode == 15) 9 else 15
            songKeyModeMap[pathKey] = nm
            getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE).edit().putInt("song_mode_$pathKey", nm).apply()
            showFullPanel(currentPage, resetScroll = false)
        })
        btns.addView(iconBtn("✕", c("#F87171")) {
            if (file == null) {
                Toast.makeText(this, "内存谱面", Toast.LENGTH_SHORT).show()
                return@iconBtn
            }
            val d = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                .setTitle("删除").setMessage(file.name)
                .setPositiveButton("删除") { _, _ ->
                    if (file.delete()) {
                        if (currentPage == 0) cachedSongList = null
                        showFullPanel(currentPage, resetScroll = false)
                    }
                }
                .setNegativeButton("取消", null).create()
            d.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            d.show()
        })
        btns.addView(iconBtn("▷", c("#4ADE80")) { onClick() })

        row.addView(btns)
        listContainer.addView(row)
    }

    private fun showSongDetailDialog(name: String, author: String, duration: String, notes: Int, bpm: String, model: String, file: File?, mode: Int) {
        val msg = "歌名：$name\n作者：$author\n模型：$model\nBPM：$bpm\n音符：$notes\n时长：$duration\n键位：${mode}键\n文件：${file?.name ?: "mem"}"
        val d = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
            .setTitle("谱面详情").setMessage(msg).setPositiveButton("确定", null).create()
        d.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        d.show()
    }

    private fun showPlayerPanel() {
        if (!this::floatView.isInitialized) return
        isMiniMode = false; isPlayerMode = true
        panelRootView = null
        floatView.removeAllViews()

        val dm = resources.displayMetrics
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        playerParams.width = (dm.widthPixels * if (land) 0.36f else 0.54f).toInt()
        updatePanelAlphaColor(panelAlpha)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(c(themeBgPanel, "#D90F172A"))
                setStroke(dp(1f).coerceAtLeast(1), Color.parseColor("#38FFFFFF"))
            }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14f).toFloat())
                }
            }
            clipToOutline = true; layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        playerRootView = layout

        val title = TextView(this).apply {
            text = if (currentModel.isNotBlank()) "$currentSongName · $currentModel" else currentSongName
            setTextColor(c(themeTextMain)); textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        enableMarquee(title)
        layout.addView(title)

        val infoLine = buildString {
            append(if (currentAuthor.isNotBlank()) currentAuthor else "未知作者")
            append(" · ").append(currentDuration)
            append(" · ").append(currentNotesCount).append("音符")
            if (currentBpm.isNotBlank() && currentBpm != "0") {
                append(" · BPM ").append(currentBpm)
            }
        }
        val authorTv = TextView(this).apply {
            text = infoLine
            setTextColor(c(themeTextSoft)); textSize = 10.5f
            setPadding(0, dp(2f), 0, dp(2f))
        }
        enableMarquee(authorTv)
        layout.addView(authorTv)

        // 状态 + 可点击跳转的音符进度行
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1f), 0, dp(2f))
        }

        tvStatus = TextView(this).apply {
            setTextColor(c(themeAccent)); textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                rightMargin = dp(6f)
            }
        }
        enableMarquee(tvStatus)
        statusRow.addView(tvStatus)

        tvNoteProgress = TextView(this).apply {
            setTextColor(c(themeTextMain)); textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setSingleLine(true)
            setPadding(dp(5f), dp(1f), dp(5f), dp(1f))
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(Color.parseColor("#2A38BDF8"))
            }
            setOnClickListener { showJumpToNoteDialog() }
        }
        statusRow.addView(tvNoteProgress)
        layout.addView(statusRow)

        // 时间进度条 + 右侧播放时间进度
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1f), 0, dp(2f))
        }

        val totalMs = totalDurationMs()
        seekBar = SeekBar(this).apply {
            max = if (totalMs > 0L) totalMs.toInt().coerceAtLeast(1) else 100
            layoutParams = LinearLayout.LayoutParams(0, dp(20f), 1f).apply {
                rightMargin = dp(6f)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser || songData.isEmpty()) return
                    val dur = totalDurationMs()
                    if (dur > 0L) {
                        val targetMs = p.toLong().coerceIn(0L, dur)
                        currentIndex = findIndexByTimeMs(targetMs)
                    } else if (songData.size > 1) {
                        currentIndex = (p / 100f * (songData.size - 1)).toInt()
                    }
                    updatePlayerUI(syncSeekBar = false)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (isPlaying && songData.isNotEmpty()) {
                        stopPlayback()
                        startPlayback()
                    }
                }
            })
        }
        progressRow.addView(seekBar)

        tvTimeProgress = TextView(this).apply {
            text = "0:00/$currentDuration"
            setTextColor(c(themeTextSoft)); textSize = 10f
            setSingleLine(true)
        }
        progressRow.addView(tvTimeProgress)
        layout.addView(progressRow)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(28f))
        }

        btnPlayPause = Button(this).apply {
            text = "▶"; textSize = 12f; setTextColor(Color.WHITE)
            background = playBgDrawable; setOnClickListener { togglePlayback() }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        row.addView(btnPlayPause)

        val speed = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#44000000"))
                cornerRadius = dp(6f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1.5f).apply {
                leftMargin = dp(6f); rightMargin = dp(6f)
            }
        }
        speed.addView(Button(this).apply {
            text = "−"; textSize = 12f; setTextColor(Color.WHITE)
            background = null; setOnClickListener { changeSpeed(-0.1f) }
        }, LinearLayout.LayoutParams(dp(24f), -1))

        tvSpeed = TextView(this).apply {
            text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
            setTextColor(c(themeAccent)); textSize = 10.5f; gravity = Gravity.CENTER
        }
        enableMarquee(tvSpeed)
        speed.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))

        speed.addView(Button(this).apply {
            text = "+"; textSize = 12f; setTextColor(Color.WHITE)
            background = null; setOnClickListener { changeSpeed(0.1f) }
        }, LinearLayout.LayoutParams(dp(24f), -1))
        row.addView(speed)

        row.addView(Button(this).apply {
            text = "✕"; textSize = 12f; setTextColor(Color.WHITE)
            background = pauseBgDrawable
            setOnClickListener { stopPlayback(); showFullPanel(currentPage, resetScroll = false) }
            layoutParams = LinearLayout.LayoutParams(0, -1, 0.6f)
        })
        layout.addView(row)

        enableDrag(layout, playerParams) { isPlaying }
        floatView.addView(layout); windowManager.updateViewLayout(floatView, playerParams); updatePlayerUI()
    }

    private fun updatePlayerUI(syncSeekBar: Boolean = true) {
        if (!isPlayerMode || !this::floatView.isInitialized) return
        val total = songData.size
        val displayNote = if (total == 0) 0 else (currentIndex + 1).coerceIn(1, total)
        val curMs = currentTimeMs()
        val durMs = totalDurationMs()

        if (this::tvNoteProgress.isInitialized) {
            tvNoteProgress.text = "$displayNote/$total 音符 ↴"
        }
        if (this::tvTimeProgress.isInitialized) {
            tvTimeProgress.text = "${formatTimeMs(curMs)}/${formatTimeMs(durMs)}"
        }
        if (syncSeekBar && this::seekBar.isInitialized) {
            if (durMs > 0L) {
                seekBar.max = durMs.toInt().coerceAtLeast(1)
                seekBar.progress = curMs.toInt().coerceIn(0, seekBar.max)
            } else {
                seekBar.max = 100
                seekBar.progress = if (total <= 1) 0 else (currentIndex.toFloat() / (total - 1) * 100).toInt()
            }
        }

        val sp = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        if (isPlaying) {
            tvStatus.text = "▶ 播放中  |  $sp"
            btnPlayPause.text = "⏸"; btnPlayPause.background = pauseBgDrawable
        } else {
            tvStatus.text = "暂停  |  $sp"
            btnPlayPause.text = "▶"; btnPlayPause.background = playBgDrawable
        }
    }

    private fun checkAccessibility(): Boolean {
        if (SkyAccessibilityService.instance == null) {
            Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        return true
    }

    private fun preparePlay() {
        if (!checkAccessibility()) return
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            Toast.makeText(this, "请先校准键位", Toast.LENGTH_SHORT).show()
            return
        }
        stopPlayback(); currentIndex = 0
        handler.postDelayed({ showPlayerPanel(); updatePlayerUI() }, 50)
    }

    private fun togglePlayback() {
        if (songData.isEmpty() || !checkAccessibility()) return
        if (isPlaying) stopPlayback() else {
            if (currentIndex >= songData.size) currentIndex = 0
            startPlayback()
        }
        updatePlayerUI()
    }

    private fun changeSpeed(delta: Float) {
        currentSpeed = (currentSpeed + delta).coerceIn(0.5f, 5f)
        currentSpeed = Math.round(currentSpeed * 10f) / 10f
        if (this::tvSpeed.isInitialized) tvSpeed.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        if (isPlaying) { stopPlayback(); startPlayback() }
        else updatePlayerUI(syncSeekBar = false)
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
                if (currentIndex < songData.size) {
                    val wait = songData[currentIndex].first - t0
                    handler.postDelayed(this, maxOf((wait / currentSpeed).toLong(), 1L))
                } else {
                    resetPlayState()
                }
            }
        }
        handler.post(playRunnable!!)
    }

    private fun resetPlayState() {
        stopPlayback(); currentIndex = 0
        handler.post {
            if (isPlayerMode && this::seekBar.isInitialized) updatePlayerUI()
            if (autoBackToList && isPlayerMode) {
                handler.postDelayed({ showFullPanel(currentPage, resetScroll = false) }, 600)
            }
        }
    }

    private fun stopPlayback() {
        isPlaying = false
        playRunnable?.let { handler.removeCallbacks(it) }
        playRunnable = null
    }

    private fun readLocalFileInfo(file: File): List<String> {
        try {
            var text = file.readText(Charsets.UTF_8)
            if (text.contains("\u0000")) text = file.readText(Charsets.UTF_16LE)
            val content = text.replace("\u0000", "").replace("\uFEFF", "")
            var name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1)
                ?: file.nameWithoutExtension.substringBefore("-")
            var author = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1)
                ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
            val bpm = Regex("\"bpm\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: "0"
            var model = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: ""
            if (model.isBlank() && name.contains(" || ")) model = name.substringAfter(" || ")
            if (author.isBlank()) author = "未知作者"
            if (name.isBlank()) name = file.nameWithoutExtension
            val notes = Regex("\"time\"\\s*:\\s*\\d+").findAll(content).count()
            val duration = if (notes > 0) {
                val last = Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content).lastOrNull()?.groupValues?.getOrNull(1) ?: "0"
                val sec = last.toLong() / 1000
                String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
            } else {
                "0:00"
            }
            return listOf(name, author, duration, notes.toString(), "", bpm, model)
        } catch (_: Exception) {
            return listOf(file.nameWithoutExtension, "未知作者", "0:00", "0", "", "0", "")
        }
    }

    private fun calculateDuration(notes: List<Pair<Long, Int>>): String {
        if (notes.isEmpty()) return "0:00"
        val sec = notes.last().first / 1000
        return String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
    }

    private fun directLoadLocalFile(file: File) {
        thread {
            try {
                var content = file.readText(Charsets.UTF_8)
                if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
                content = content.replace("\u0000", "").replace("\uFEFF", "")
                val times = Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content).map { it.groupValues[1].toLong() }.toList()
                val keys = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"").findAll(content).map { it.groupValues[1].toInt() }.toList()
                var name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1)
                    ?: file.nameWithoutExtension.substringBefore("-")
                var author = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1)
                    ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
                val bpm = Regex("\"bpm\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: "0"
                var model = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: ""
                if (model.isBlank() && name.contains(" || ")) model = name.substringAfter(" || ")
                if (author.isBlank()) author = "未知作者"
                if (name.isBlank()) name = file.nameWithoutExtension
                if (times.isEmpty() || keys.isEmpty()) {
                    handler.post { Toast.makeText(this, "空乐谱", Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                val notes = times.indices.mapNotNull { i -> if (i < keys.size) Pair(times[i], keys[i]) else null }.sortedBy { it.first }
                handler.post {
                    songData = notes; currentIndex = 0; currentSongName = name; currentAuthor = author
                    currentBpm = bpm; currentModel = model; currentNotesCount = notes.size
                    currentDuration = calculateDuration(notes)
                    val mode = songKeyModeMap[file.absolutePath] ?: keyMode
                    if (keyPositions.size != mode) loadKeyPositions(mode)
                    preparePlay()
                }
            } catch (_: Exception) {
                handler.post { Toast.makeText(this, "读取异常", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun startCalibration(mode: Int = keyMode) {
        if (!this::floatView.isInitialized) return
        floatView.visibility = View.GONE; floatView.removeAllViews()
        val dm = resources.displayMetrics; val screenW = dm.widthPixels; val screenH = dm.heightPixels
        val dotSize = dp(42f); val half = dotSize / 2
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }; calibrationViews.clear()
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }; calibrationBtnContainer = null
        keyPositions.clear(); val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)

        if (mode == 15) {
            val keyW = screenW * 0.12f; val keyH = keyW * 1.05f; val gapX = screenW * 0.018f; val gapY = screenH * 0.012f
            val gridW = 5 * keyW + 4 * gapX
            var baseX = (screenW - gridW) / 2f + keyW / 2f; var baseY = screenH * 0.42f + keyH / 2f
            val sx = prefs.getFloat("key_15_0_x", -1f); if (sx != -1f) { baseX = sx; baseY = prefs.getFloat("key_15_0_y", baseY) }
            for (i in 0 until 15) {
                val row = i / 5; val col = i % 5
                keyPositions.add(Pair(baseX + col * (keyW + gapX), baseY + row * (keyH + gapY)))
            }
        } else {
            val keyW = screenW * 0.16f; val keyH = keyW * 1.05f; val gapX = screenW * 0.03f; val gapY = screenH * 0.02f
            val gridW = 3 * keyW + 2 * gapX
            var baseX = (screenW - gridW) / 2f + keyW / 2f; var baseY = screenH * 0.45f + keyH / 2f
            val sx = prefs.getFloat("key_9_0_x", -1f); if (sx != -1f) { baseX = sx; baseY = prefs.getFloat("key_9_0_y", baseY) }
            for (i in 0 until 9) {
                val row = i / 3; val col = i % 3
                keyPositions.add(Pair(baseX + col * (keyW + gapX), baseY + row * (keyH + gapY)))
            }
        }

        val keyW15 = screenW * 0.12f; val keyH15 = keyW15 * 1.05f; val gapX15 = screenW * 0.018f; val gapY15 = screenH * 0.012f
        val keyW9 = screenW * 0.16f; val keyH9 = keyW9 * 1.05f; val gapX9 = screenW * 0.03f; val gapY9 = screenH * 0.02f

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        for (i in 0 until mode) {
            val dot = TextView(this).apply {
                text = "${i + 1}"; setTextColor(c(themeAccent)); textSize = 16f
                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC0F172A"))
                    setStroke(dp(2f), c(themeAccent))
                }
            }
            val lp = WindowManager.LayoutParams(
                dotSize, dotSize, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = keyPositions[i].first.toInt() - half
            lp.y = keyPositions[i].second.toInt() - half

            dot.setOnTouchListener(object : View.OnTouchListener {
                var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
                override fun onTouch(v: View?, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY; return true }
                        MotionEvent.ACTION_MOVE -> {
                            lp.x = ix + (e.rawX - tx).toInt(); lp.y = iy + (e.rawY - ty).toInt()
                            windowManager.updateViewLayout(dot, lp)
                            val cx = (lp.x + half).toFloat(); val cy = (lp.y + half).toFloat()
                            keyPositions[i] = Pair(cx, cy)
                            if (i == 0) {
                                for (j in 1 until mode) {
                                    val row = if (mode == 15) j / 5 else j / 3
                                    val col = if (mode == 15) j % 5 else j % 3
                                    val nx = if (mode == 15) cx + col * (keyW15 + gapX15) else cx + col * (keyW9 + gapX9)
                                    val ny = if (mode == 15) cy + row * (keyH15 + gapY15) else cy + row * (keyH9 + gapY9)
                                    keyPositions[j] = Pair(nx, ny)
                                    val op = calibrationViews[j].layoutParams as WindowManager.LayoutParams
                                    op.x = nx.toInt() - half; op.y = ny.toInt() - half
                                    windowManager.updateViewLayout(calibrationViews[j], op)
                                }
                            }
                            return true
                        }
                    }
                    return false
                }
            })
            windowManager.addView(dot, lp); calibrationViews.add(dot)
        }

        val btnSave = Button(this).apply {
            text = "✔ 保存${mode}键"; setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
            background = GradientDrawable().apply { setColor(c("#22C55E")); cornerRadius = dp(6f).toFloat() }
            setOnClickListener {
                saveKeyPositions(mode); keyMode = mode
                prefs.edit().putInt("key_mode", mode).apply()
                exitCalibration()
                Toast.makeText(this@FloatingWindowService, "已保存 ${mode}键", Toast.LENGTH_SHORT).show()
            }
        }
        val btnCancel = Button(this).apply {
            text = "✖ 取消"; setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
            background = GradientDrawable().apply { setColor(c("#EF4444")); cornerRadius = dp(6f).toFloat() }
            setOnClickListener { loadKeyPositions(); exitCalibration() }
        }
        calibrationBtnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(10f), 0)
            addView(btnSave)
            addView(Space(this@FloatingWindowService).apply { layoutParams = LinearLayout.LayoutParams(dp(12f), 1) })
            addView(btnCancel)
        }
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(20f); y = dp(30f)
        }
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
        for (i in 0 until mode) {
            keyPositions.add(Pair(prefs.getFloat("key_${mode}_${i}_x", -1f), prefs.getFloat("key_${mode}_${i}_y", -1f)))
        }
    }

    private fun saveKeyPositions(mode: Int = keyMode) {
        val ed = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE).edit()
        for (i in keyPositions.indices) {
            ed.putFloat("key_${mode}_${i}_x", keyPositions[i].first)
            ed.putFloat("key_${mode}_${i}_y", keyPositions[i].second)
        }
        ed.apply()
    }

    override fun onDestroy() {
        super.onDestroy(); stopPlayback(); handler.removeCallbacksAndMessages(null)
        if (this::floatView.isInitialized) {
            try { windowManager.removeView(floatView) } catch (_: Exception) {}
        }
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
