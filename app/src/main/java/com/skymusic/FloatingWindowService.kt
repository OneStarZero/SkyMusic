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
    private var currentFilePath: String? = null

    private var isMiniMode = true
    private var isPlayerMode = false

    private lateinit var listContainer: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var tvSpeed: TextView

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

    private val playBgDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#10B981")); cornerRadius = 6f
    }
    private val pauseBgDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#EF4444")); cornerRadius = 6f
    }

    private var calibrationViews = mutableListOf<TextView>()
    private var calibrationBtnContainer: LinearLayout? = null

    // ===== 新增设置相关 =====
    private var keyMode = 15                 // 全局默认键位数 15 / 9
    private var defaultSpeed = 1.0f
    private var clickToleranceMs = 25L
    private val songKeyModeMap = mutableMapOf<String, Int>() // 单曲键位覆盖

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density

        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        keyMode = prefs.getInt("key_mode", 15)
        defaultSpeed = prefs.getFloat("default_speed", 1.0f)
        currentSpeed = defaultSpeed
        clickToleranceMs = prefs.getLong("click_tolerance", 25L)

        // 读取单曲键位覆盖
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("song_mode_") && v is Int) {
                songKeyModeMap[k.removePrefix("song_mode_")] = v
            }
        }

        loadKeyPositions()
        File(appSheetsPath).let { if (!it.exists()) it.mkdirs() }
        File(localSheetsPath).let { if (!it.exists()) it.mkdirs() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isNewData = intent?.getBooleanExtra("is_new_data", false) ?: false
        if (isNewData) {
            intent?.getSerializableExtra("song_data")?.let {
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
                    currentFilePath = null

                    if (!this::floatView.isInitialized) createFloatingWindow()
                    if (!isMiniMode && !isPlayerMode) {
                        currentPage = 1
                        showFullPanel(1)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (!this::floatView.isInitialized) createFloatingWindow()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!this::floatView.isInitialized) return

        val dm = resources.displayMetrics
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        val panelWidthRatio = if (isLandscape) 0.35f else 0.55f
        val panelHeightRatio = if (isLandscape) 0.50f else 0.45f

        panelParams.width = (dm.widthPixels * panelWidthRatio).toInt()
        panelParams.height = (dm.heightPixels * panelHeightRatio).toInt()

        playerParams.width = if (isLandscape) (dm.widthPixels * 0.30f).toInt() else (dm.widthPixels * 0.45f).toInt()
        if (playerParams.y > dm.heightPixels - 200) {
            playerParams.y = (dm.heightPixels * 0.7).toInt()
        }

        try {
            when {
                isMiniMode -> windowManager.updateViewLayout(floatView, ballParams)
                isPlayerMode -> windowManager.updateViewLayout(floatView, playerParams)
                else -> {
                    floatView.requestLayout()
                    windowManager.updateViewLayout(floatView, panelParams)
                    showFullPanel(currentPage)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createFloatingWindow() {
        try {
            floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            val dm = resources.displayMetrics

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val panelWidthRatio = if (isLandscape) 0.35f else 0.55f
            val panelHeightRatio = if (isLandscape) 0.50f else 0.45f

            panelParams = WindowManager.LayoutParams(
                (dm.widthPixels * panelWidthRatio).toInt(),
                (dm.heightPixels * panelHeightRatio).toInt(),
                layoutType, baseFlags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (dm.widthPixels * 0.075).toInt()
                y = (dm.heightPixels * 0.2).toInt()
            }

            ballParams = WindowManager.LayoutParams(
                60, 60, layoutType, baseFlags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }

            val playerWidth = if (isLandscape) (dm.widthPixels * 0.30f).toInt() else (dm.widthPixels * 0.45f).toInt()
            playerParams = WindowManager.LayoutParams(
                playerWidth, WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType, baseFlags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (dm.widthPixels - playerWidth) / 2
                y = dm.heightPixels - 400
            }

            windowManager.addView(floatView, ballParams)
            showMiniBall()
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败，请检查权限", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams, isLocked: () -> Boolean = { false }) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = 8 * density

        view.setOnTouchListener { v, event ->
            if (isLocked()) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (!isDragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = initialX + deltaX.toInt()
                            params.y = initialY + deltaY.toInt()
                            windowManager.updateViewLayout(floatView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && event.pointerCount == 1) {
                        v.performClick()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun showMiniBall() {
        if (!this::floatView.isInitialized) return
        isMiniMode = true
        isPlayerMode = false
        floatView.removeAllViews()

        val ball = TextView(this).apply {
            text = "✦"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FDE047"))
            setShadowLayer(8f, 0f, 0f, Color.parseColor("#EAB308"))
        }
        val ballWrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#55FFFFFF"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        ballWrapper.addView(ball)
        floatView.addView(ballWrapper)

        enableDrag(ballWrapper, ballParams)
        ballWrapper.setOnClickListener { showFullPanel(currentPage) }
        windowManager.updateViewLayout(floatView, ballParams)
    }

    private fun showFullPanel(initialPage: Int = 0) {
        if (!this::floatView.isInitialized) return
        isMiniMode = false
        isPlayerMode = false
        floatView.removeAllViews()

        val dm = resources.displayMetrics
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val panelWidthRatio = if (isLandscape) 0.35f else 0.55f
        val panelHeightRatio = if (isLandscape) 0.50f else 0.45f

        panelParams.width = (dm.widthPixels * panelWidthRatio).toInt()
        panelParams.height = (dm.heightPixels * panelHeightRatio).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6101418"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 15f)
                }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(15, 15, 15, 15)
            setBackgroundColor(Color.parseColor("#2D3748"))
        }

        titleText = TextView(this).apply {
            text = when (initialPage) {
                0 -> "本地曲库 (按住拖动)"
                1 -> "App 发送 (按住拖动)"
                else -> "设置 (按住拖动)"
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val btnRefresh = TextView(this).apply {
            text = "↻ 刷新"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            setPadding(15, 0, 15, 0)
            setOnClickListener {
                when (currentPage) {
                    0 -> {
                        cachedSongList = null
                        showFullPanel(0)
                    }
                    1 -> showFullPanel(1)
                    2 -> showFullPanel(2)
                }
            }
        }

        val btnClose = TextView(this).apply {
            text = "✖"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(10, 0, 5, 0)
            setOnClickListener { showMiniBall() }
        }

        topBar.addView(titleText)
        topBar.addView(btnRefresh)
        topBar.addView(btnClose)
        rootLayout.addView(topBar)

        enableDrag(topBar, panelParams)

        rootLayout.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        })

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(10, 10, 10, 10)
        }
        scrollView.addView(listContainer)
        rootLayout.addView(scrollView)

        // ===== 底部导航：本地 / 发送 / 设置 =====
        val bottomBarHeight = (45 * density).toInt()
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1F26"))
            layoutParams = LinearLayout.LayoutParams(-1, bottomBarHeight)
        }

        fun createNavBtn(icon: String, label: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
                addView(TextView(this@FloatingWindowService).apply {
                    text = icon
                    textSize = 16f
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@FloatingWindowService).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    gravity = Gravity.CENTER
                })
            }
        }

        val btnNavLocal = createNavBtn("📂", "本地") { currentPage = 0; showFullPanel(0) }
        val btnNavApp = createNavBtn("☁️", "发送") { currentPage = 1; showFullPanel(1) }
        val btnNavSet = createNavBtn("⚙️", "设置") { currentPage = 2; showFullPanel(2) }

        bottomBar.addView(btnNavLocal, LinearLayout.LayoutParams(0, -1, 1f))
        bottomBar.addView(btnNavApp, LinearLayout.LayoutParams(0, -1, 1f))
        bottomBar.addView(btnNavSet, LinearLayout.LayoutParams(0, -1, 1f))
        rootLayout.addView(bottomBar)

        floatView.addView(rootLayout)
        windowManager.updateViewLayout(floatView, panelParams)

        switchPage(initialPage)
    }

    private fun switchPage(page: Int) {
        if (!this::listContainer.isInitialized) return
        listContainer.removeAllViews()

        when (page) {
            0 -> {
                if (cachedSongList != null) {
                    renderLocalList(cachedSongList!!)
                    return
                }

                val loadingTv = TextView(this).apply {
                    text = "正在扫描并解析..."
                    setTextColor(Color.GRAY)
                    textSize = 13f
                    setPadding(20, 20, 20, 20)
                }
                listContainer.addView(loadingTv)

                refreshSessionId++
                val currentSession = refreshSessionId

                thread {
                    try {
                        val filesList = mutableListOf<File>()
                        val localFolder = File(localSheetsPath)
                        if (localFolder.exists()) {
                            filesList.addAll(
                                localFolder.listFiles { f -> f.extension.equals("txt", true) }?.toList()
                                    ?: emptyList()
                            )
                        }

                        val songInfoList = filesList.map { file ->
                            Triple(file, readLocalFileInfo(file), "")
                        }
                        cachedSongList = songInfoList

                        handler.post {
                            if (refreshSessionId == currentSession &&
                                this::listContainer.isInitialized &&
                                !isMiniMode && !isPlayerMode
                            ) {
                                listContainer.removeAllViews()
                                renderLocalList(songInfoList)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            1 -> {
                if (songData.isNotEmpty()) {
                    addSongToList(
                        currentSongName, currentAuthor, currentDuration,
                        currentNotesCount, currentBpm, currentModel, null
                    ) { preparePlay() }
                }

                val appFolder = File(appSheetsPath)
                val appFiles = appFolder.listFiles { f -> f.extension.equals("txt", true) }?.toList()
                    ?: emptyList()

                if (appFiles.isEmpty() && songData.isEmpty()) {
                    listContainer.addView(TextView(this).apply {
                        text = "暂无 App 发送的谱面"
                        setTextColor(Color.GRAY)
                        textSize = 12f
                        setPadding(10, 10, 10, 10)
                    })
                } else {
                    for (file in appFiles) {
                        val info = readLocalFileInfo(file)
                        addSongToList(
                            info[0], info[1], info[2], info[3].toInt(),
                            info[5], info[6], file
                        ) { directLoadLocalFile(file) }
                    }
                }
            }
            2 -> showSettingsPage()
        }
    }

    // ==================== 设置页 ====================
    private fun showSettingsPage() {
        if (!this::listContainer.isInitialized) return
        listContainer.removeAllViews()

        fun sectionTitle(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(8, 16, 8, 8)
            }
        }

        fun settingItem(title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 14, 12, 14)
                setOnClickListener { onClick() }
                setBackgroundColor(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = 8
                }
                addView(TextView(this@FloatingWindowService).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 14f
                })
                addView(TextView(this@FloatingWindowService).apply {
                    text = subtitle
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11f
                    setPadding(0, 4, 0, 0)
                })
            }
        }

        // 键位校准
        listContainer.addView(sectionTitle("🎹 键位校准"))
        listContainer.addView(settingItem(
            "15 键校准",
            "当前全局：${keyMode} 键"
        ) {
            if (checkAccessibility()) startCalibration(15)
        })
        listContainer.addView(settingItem(
            "9 键校准",
            "带偏移的真实乐器布局"
        ) {
            if (checkAccessibility()) startCalibration(9)
        })

        // 演奏模式
        listContainer.addView(sectionTitle("🎵 演奏模式"))
        listContainer.addView(settingItem(
            "默认键位数",
            "当前：${keyMode} 键（点击切换 15 ↔ 9）"
        ) {
            keyMode = if (keyMode == 15) 9 else 15
            getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
                .edit().putInt("key_mode", keyMode).apply()
            loadKeyPositions()
            Toast.makeText(this, "已切换为 ${keyMode} 键", Toast.LENGTH_SHORT).show()
            showFullPanel(2)
        })

        // 其他设置
        listContainer.addView(sectionTitle("⚙️ 其他设置"))
        listContainer.addView(settingItem(
            "默认倍速",
            "当前：${String.format(Locale.getDefault(), "%.1fx", defaultSpeed)}（点击 +0.1）"
        ) {
            defaultSpeed += 0.1f
            if (defaultSpeed > 3.0f) defaultSpeed = 0.5f
            defaultSpeed = (Math.round(defaultSpeed * 10f) / 10f)
            currentSpeed = defaultSpeed
            getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
                .edit().putFloat("default_speed", defaultSpeed).apply()
            Toast.makeText(this, "默认倍速：${String.format(Locale.getDefault(), "%.1fx", defaultSpeed)}", Toast.LENGTH_SHORT).show()
            showFullPanel(2)
        })

        listContainer.addView(settingItem(
            "点击容差",
            "当前：${clickToleranceMs}ms（同时间戳合并窗口）"
        ) {
            clickToleranceMs = when (clickToleranceMs) {
                15L -> 25L
                25L -> 40L
                else -> 15L
            }
            getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
                .edit().putLong("click_tolerance", clickToleranceMs).apply()
            Toast.makeText(this, "容差：${clickToleranceMs}ms", Toast.LENGTH_SHORT).show()
            showFullPanel(2)
        })

        listContainer.addView(settingItem(
            "权限快捷入口",
            "悬浮窗权限 / 无障碍服务"
        ) {
            val options = arrayOf("悬浮窗权限", "无障碍服务")
            val dialog = AlertDialog.Builder(
                ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            )
                .setTitle("权限设置")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }.create()
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE
            )
            dialog.show()
        })
    }

    private fun renderLocalList(songInfoList: List<Triple<File, List<String>, String>>) {
        if (!this::listContainer.isInitialized) return
        if (songInfoList.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "未找到本地乐谱。\n请将 txt 放入 LocalSheets/"
                setTextColor(Color.GRAY)
                textSize = 12f
                setPadding(10, 10, 10, 10)
            })
            return
        }
        for ((file, info, _) in songInfoList) {
            addSongToList(
                info[0], info[1], info[2], info[3].toInt(),
                info[5], info[6], file
            ) { directLoadLocalFile(file) }
        }
    }

    // ==================== 歌曲卡片（增强版） ====================
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

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val padTopBottom = if (isLandscape) (6 * density).toInt() else (10 * density).toInt()
        val titleSize = if (isLandscape) 14f else 15f
        val subSize = if (isLandscape) 10f else 11f
        val btnSize = if (isLandscape) 15f else 18f

        val pathKey = file?.absolutePath ?: "__memory__"
        val songMode = songKeyModeMap[pathKey] ?: keyMode

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), padTopBottom, (8 * density).toInt(), padTopBottom)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { onClick() }
        }

        val titleTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = titleSize
            setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        if (songName.contains(" || ")) {
            val spannable = SpannableString(songName)
            val splitIndex = songName.indexOf(" || ")
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#38BDF8")),
                splitIndex, songName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            titleTv.text = spannable
        } else {
            titleTv.text = songName
        }
        textLayout.addView(titleTv)

        val subContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, if (isLandscape) (2 * density).toInt() else (4 * density).toInt(), 0, 0)
        }

        val speedStr = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        val line1 = TextView(this).apply {
            text = "作者: $author  ||  模型: ${if (model.isNotBlank()) model else "未知"}"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = subSize
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

        val line2 = TextView(this).apply {
            text = "音符:$notes  ||  BPM:$bpm  ||  时长:$duration  ||  ${songMode}键  ||  $speedStr"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = (subSize - 1f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

        subContainer.addView(line1)
        subContainer.addView(line2)
        textLayout.addView(subContainer)
        card.addView(textLayout)

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, -1
            )
        }

        // 详情按钮
        val btnInfo = TextView(this).apply {
            text = "ℹ️"
            textSize = btnSize
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            setOnClickListener {
                showSongDetailDialog(songName, author, duration, notes, bpm, model, file, songMode)
            }
        }
        btnLayout.addView(btnInfo)

        // 键位模式切换
        val btnMode = TextView(this).apply {
            text = if (songMode == 15) "15" else "9"
            textSize = (btnSize - 2f)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            setOnClickListener {
                val newMode = if (songMode == 15) 9 else 15
                songKeyModeMap[pathKey] = newMode
                getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
                    .edit().putInt("song_mode_$pathKey", newMode).apply()
                Toast.makeText(this@FloatingWindowService, "该谱面已设为 ${newMode} 键", Toast.LENGTH_SHORT).show()
                // 刷新当前页
                showFullPanel(currentPage)
            }
        }
        btnLayout.addView(btnMode)

        // 删除
        val btnDelete = TextView(this).apply {
            text = "🗑️"
            textSize = btnSize
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            setOnClickListener {
                if (file != null) {
                    val contextWrapper = ContextThemeWrapper(
                        this@FloatingWindowService,
                        android.R.style.Theme_DeviceDefault_Dialog_Alert
                    )
                    val dialog = AlertDialog.Builder(contextWrapper)
                        .setTitle("确认删除")
                        .setMessage("确定要删除该谱面吗？\n\n路径：\n${file.absolutePath}")
                        .setPositiveButton("删除") { _, _ ->
                            if (file.exists()) {
                                if (file.delete()) {
                                    Toast.makeText(this@FloatingWindowService, "已删除", Toast.LENGTH_SHORT).show()
                                    if (currentPage == 0) {
                                        cachedSongList = null
                                        showFullPanel(0)
                                    } else {
                                        showFullPanel(1)
                                    }
                                } else {
                                    Toast.makeText(this@FloatingWindowService, "删除失败，请检查权限", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .create()

                    dialog.window?.setType(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_PHONE
                    )
                    dialog.show()
                } else {
                    Toast.makeText(this@FloatingWindowService, "该谱面在内存中，退出 App 自动清除", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnLayout.addView(btnDelete)

        // 播放
        val btnPlay = TextView(this).apply {
            text = "▶"
            textSize = (btnSize + 2f)
            setTextColor(Color.parseColor("#10B981"))
            setPadding((4 * density).toInt(), 0, 0, 0)
            setOnClickListener { onClick() }
        }
        btnLayout.addView(btnPlay)

        card.addView(btnLayout)
        listContainer.addView(card)
    }

    private fun showSongDetailDialog(
        songName: String,
        author: String,
        duration: String,
        notes: Int,
        bpm: String,
        model: String,
        file: File?,
        songMode: Int
    ) {
        val msg = buildString {
            append("歌名：$songName\n")
            append("作者：$author\n")
            append("模型：${if (model.isNotBlank()) model else "未知"}\n")
            append("BPM：$bpm\n")
            append("音符数：$notes\n")
            append("时长：$duration\n")
            append("键位模式：${songMode} 键\n")
            append("文件：${file?.absolutePath ?: "内存谱面（未保存）"}")
        }

        val dialog = AlertDialog.Builder(
            ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        )
            .setTitle("谱面详情")
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .create()

        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }

    private fun showPlayerPanel() {
        if (!this::floatView.isInitialized) return
        isMiniMode = false
        isPlayerMode = true
        floatView.removeAllViews()

        val dm = resources.displayMetrics
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        playerParams.width = if (isLandscape) (dm.widthPixels * 0.30f).toInt() else (dm.widthPixels * 0.45f).toInt()

        val playerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0F172A"))
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 12f)
                }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val titleTv = TextView(this).apply {
            text = "《$currentSongName》"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        playerLayout.addView(titleTv)

        val line1 = TextView(this).apply {
            text = "作者:\( currentAuthor  ||  模型: \){if (currentModel.isNotBlank()) currentModel else "未知"}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 2, 0, 0)
        }
        val line2 = TextView(this).apply {
            text = "音符:$currentNotesCount  ||  BPM:$currentBpm"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 9f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 1, 0, 4)
        }
        playerLayout.addView(line1)
        playerLayout.addView(line2)

        tvStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 9f
            setPadding(0, 0, 0, 4)
        }
        playerLayout.addView(tvStatus)

        seekBar = SeekBar(this).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(-1, (20 * density).toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && songData.size > 1) {
                        currentIndex = (progress / 100f * (songData.size - 1)).toInt()
                        updatePlayerUI()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        playerLayout.addView(seekBar)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, (30 * density).toInt())
        }

        btnPlayPause = Button(this).apply {
            text = "▶"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = playBgDrawable
            setOnClickListener { togglePlayback() }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        btnRow.addView(btnPlayPause)

        val speedControl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33000000"))
                cornerRadius = 6f
            }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1.5f).apply {
                leftMargin = 6
                rightMargin = 6
            }
        }
        val btnSpeedDown = Button(this).apply {
            text = "-"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = null
            setPadding(0, 0, 0, 0)
            setOnClickListener { changeSpeed(-0.1f) }
        }
        tvSpeed = TextView(this).apply {
            text = "1.0x"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            gravity = Gravity.CENTER
        }
        val btnSpeedUp = Button(this).apply {
            text = "+"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = null
            setPadding(0, 0, 0, 0)
            setOnClickListener { changeSpeed(0.1f) }
        }

        speedControl.addView(btnSpeedDown, LinearLayout.LayoutParams((24 * density).toInt(), -1))
        speedControl.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))
        speedControl.addView(btnSpeedUp, LinearLayout.LayoutParams((24 * density).toInt(), -1))
        btnRow.addView(speedControl)

        val btnClose = Button(this).apply {
            text = "✖"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = 6f
            }
            setOnClickListener {
                stopPlayback()
                showFullPanel(currentPage)
            }
            layoutParams = LinearLayout.LayoutParams(0, -1, 0.6f)
        }
        btnRow.addView(btnClose)

        playerLayout.addView(btnRow)

        enableDrag(playerLayout, playerParams) { isPlaying }

        floatView.addView(playerLayout)
        windowManager.updateViewLayout(floatView, playerParams)

        updatePlayerUI()
    }

    private fun updatePlayerUI() {
        if (!isPlayerMode || !this::floatView.isInitialized) return

        val progressPercent = if (songData.size <= 1) 0 else (currentIndex.toFloat() / (songData.size - 1) * 100).toInt()
        seekBar.progress = progressPercent

        val speedStr = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        if (isPlaying) {
            tvStatus.text = "▶ \( currentIndex/ \){songData.size}  |  $speedStr"
            btnPlayPause.text = "⏸"
            btnPlayPause.background = pauseBgDrawable
        } else {
            tvStatus.text = "已暂停  |  $speedStr"
            btnPlayPause.text = "▶"
            btnPlayPause.background = playBgDrawable
        }
    }

    private fun checkAccessibility(): Boolean {
        if (SkyAccessibilityService.instance == null) {
            Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return false
        }
        return true
    }

    private fun preparePlay() {
        if (!checkAccessibility()) return
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            Toast.makeText(this, "请先校准键位！", Toast.LENGTH_SHORT).show()
            return
        }

        stopPlayback()
        currentIndex = 0

        handler.postDelayed({
            showPlayerPanel()
            updatePlayerUI()
        }, 50)
    }

    private fun togglePlayback() {
        if (songData.isEmpty()) return
        if (!checkAccessibility()) return
        if (isPlaying) {
            stopPlayback()
        } else {
            if (currentIndex >= songData.size) {
                currentIndex = 0
            }
            startPlayback()
        }
        updatePlayerUI()
    }

    private fun changeSpeed(delta: Float) {
        currentSpeed += delta
        currentSpeed = currentSpeed.coerceIn(0.5f, 5.0f)
        currentSpeed = Math.round(currentSpeed * 10f) / 10f
        if (this::tvSpeed.isInitialized) {
            tvSpeed.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        }

        handler.postDelayed({
            cachedSongList?.let {
                if (!isMiniMode && !isPlayerMode && currentPage == 0 && this::listContainer.isInitialized) {
                    listContainer.removeAllViews()
                    renderLocalList(it)
                }
            }
        }, 100)

        if (isPlaying) {
            stopPlayback()
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (songData.isEmpty() || currentIndex >= songData.size) return
        isPlaying = true
        playRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying || currentIndex >= songData.size) {
                    resetPlayState()
                    return
                }

                val currentTime = songData[currentIndex].first
                val pointsToClick = mutableListOf<Pair<Float, Float>>()

                while (currentIndex < songData.size &&
                    songData[currentIndex].first - currentTime <= clickToleranceMs
                ) {
                    val keyIndex = songData[currentIndex].second
                    val point = keyPositions.getOrNull(keyIndex) ?: Pair(0f, 0f)
                    if (point.first != -1f) {
                        pointsToClick.add(point)
                    }
                    currentIndex++
                }

                if (pointsToClick.isNotEmpty()) {
                    SkyAccessibilityService.instance?.clickPoints(pointsToClick)
                }

                updatePlayerUI()

                if (currentIndex < songData.size) {
                    val nextInterval = songData[currentIndex].first - currentTime
                    handler.postDelayed(this, maxOf((nextInterval / currentSpeed).toLong(), 1L))
                } else {
                    resetPlayState()
                }
            }
        }
        handler.post(playRunnable!!)
    }

    private fun resetPlayState() {
        stopPlayback()
        currentIndex = 0
        handler.post {
            if (isPlayerMode && this::seekBar.isInitialized) {
                updatePlayerUI()
            }
        }
    }

    private fun stopPlayback() {
        isPlaying = false
        if (playRunnable != null) {
            handler.removeCallbacks(playRunnable!!)
            playRunnable = null
        }
    }

    private fun readLocalFileInfo(file: File): List<String> {
        try {
            var text = file.readText(Charsets.UTF_8)
            if (text.contains("\u0000")) text = file.readText(Charsets.UTF_16LE)
            val content = text.replace("\u0000", "").replace("\uFEFF", "")

            val authorRegex = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"")
            val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"")
            val bpmRegex = Regex("\"bpm\"\\s*:\\s*(\\d+)")
            val modelRegex = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"")

            var songName = nameRegex.find(content)?.groupValues?.getOrNull(1)
                ?: file.nameWithoutExtension.substringBefore("-")
            var displayAuthor = authorRegex.find(content)?.groupValues?.getOrNull(1)
                ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
            var bpm = bpmRegex.find(content)?.groupValues?.getOrNull(1) ?: "0"
            var model = modelRegex.find(content)?.groupValues?.getOrNull(1) ?: ""

            if (model.isBlank() && songName.contains(" || ")) {
                model = songName.substringAfter(" || ")
            }

            if (displayAuthor.isBlank()) displayAuthor = "未知作者"
            if (songName.isBlank()) songName = file.nameWithoutExtension

            val notesCount = Regex("\"time\"\\s*:\\s*\\d+").findAll(content).count()
            val duration = if (notesCount > 0) {
                val lastTimeStr = Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content)
                    .lastOrNull()?.groupValues?.getOrNull(1) ?: "0"
                val totalSec = lastTimeStr.toLong() / 1000
                String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
            } else "0:00"

            return listOf(songName, displayAuthor, duration, notesCount.toString(), "", bpm, model)
        } catch (e: Exception) {
            return listOf(file.nameWithoutExtension, "未知作者", "0:00", "0", "", "0", "")
        }
    }

    private fun calculateDuration(notes: List<Pair<Long, Int>>): String {
        if (notes.isEmpty()) return "0:00"
        val totalSec = notes.last().first / 1000
        return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun directLoadLocalFile(file: File) {
        thread {
            try {
                var content = file.readText(Charsets.UTF_8)
                if (content.contains("\u0000")) content = file.readText(Charsets.UTF_16LE)
                content = content.replace("\u0000", "").replace("\uFEFF", "")

                val timeRegex = Regex("\"time\"\\s*:\\s*(\\d+)")
                val keyRegex = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"")
                val authorRegex = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"")
                val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"")
                val bpmRegex = Regex("\"bpm\"\\s*:\\s*(\\d+)")
                val modelRegex = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"")

                val timeMatches = timeRegex.findAll(content).map { it.groupValues[1].toLong() }.toList()
                val keyMatches = keyRegex.findAll(content).map { it.groupValues[1].toInt() }.toList()

                var nameMatch = nameRegex.find(content)?.groupValues?.getOrNull(1)
                    ?: file.nameWithoutExtension.substringBefore("-")
                var authorMatch = authorRegex.find(content)?.groupValues?.getOrNull(1)
                    ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
                var bpmMatch = bpmRegex.find(content)?.groupValues?.getOrNull(1) ?: "0"
                var modelMatch = modelRegex.find(content)?.groupValues?.getOrNull(1) ?: ""

                if (modelMatch.isBlank() && nameMatch.contains(" || ")) {
                    modelMatch = nameMatch.substringAfter(" || ")
                }

                if (authorMatch.isBlank()) authorMatch = "未知作者"
                if (nameMatch.isBlank()) nameMatch = file.nameWithoutExtension

                if (timeMatches.isEmpty() || keyMatches.isEmpty()) {
                    handler.post {
                        Toast.makeText(this, "读取失败: 空乐谱", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                val notes = mutableListOf<Pair<Long, Int>>()
                for (i in timeMatches.indices) {
                    if (i < keyMatches.size) {
                        notes.add(Pair(timeMatches[i], keyMatches[i]))
                    }
                }

                val sortedNotes = notes.sortedBy { it.first }

                handler.post {
                    songData = sortedNotes
                    currentIndex = 0
                    currentSongName = nameMatch
                    currentAuthor = authorMatch
                    currentBpm = bpmMatch
                    currentModel = modelMatch
                    currentNotesCount = songData.size
                    currentDuration = calculateDuration(songData)
                    currentFilePath = file.absolutePath

                    // 如果该谱面有单独键位模式，优先使用
                    val mode = songKeyModeMap[file.absolutePath] ?: keyMode
                    // 确保已加载对应模式的键位
                    if (keyPositions.size != mode) {
                        loadKeyPositions(mode)
                    }

                    preparePlay()
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "读取异常", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ==================== 校准（支持 15 / 9） ====================
    private fun startCalibration(mode: Int = keyMode) {
        if (!this::floatView.isInitialized) return

        floatView.visibility = View.GONE
        floatView.removeAllViews()

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val density = resources.displayMetrics.density
        val dotSize = (42 * density).toInt()
        val halfSize = dotSize / 2
        val keyCount = mode

        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationViews.clear()
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationBtnContainer = null

        keyPositions.clear()

        if (mode == 15) {
            // 5x3 错位菱形
            val dx = screenW * 0.17f
            val dy = dx * 0.85f
            var baseX = screenW * 0.15f
            var baseY = screenH * 0.55f

            val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
            val savedX = prefs.getFloat("key_15_0_x", -1f)
            val savedY = prefs.getFloat("key_15_0_y", -1f)
            if (savedX != -1f) {
                baseX = savedX
                baseY = savedY
            }

            for (i in 0 until 15) {
                var offsetX = 0f
                var offsetY = 0f
                when (i) {
                    in 0..4 -> {
                        offsetX = i * dx
                        offsetY = 0f
                    }
                    in 5..9 -> {
                        offsetX = (i - 5) * dx + dx / 2
                        offsetY = dy
                    }
                    in 10..14 -> {
                        offsetX = (i - 10) * dx
                        offsetY = 2 * dy
                    }
                }
                keyPositions.add(Pair(baseX + offsetX, baseY + offsetY))
            }
        } else {
            // 9 键：带偏移的真实布局（方案B）
            // 布局示意：
            //   0   1   2
            // 3   4   5   （中行偏移）
            //   6   7   8
            val dx = screenW * 0.22f
            val dy = dx * 0.95f
            var baseX = screenW * 0.28f
            var baseY = screenH * 0.52f

            val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
            val savedX = prefs.getFloat("key_9_0_x", -1f)
            val savedY = prefs.getFloat("key_9_0_y", -1f)
            if (savedX != -1f) {
                baseX = savedX
                baseY = savedY
            }

            val offsets = listOf(
                Pair(0f, 0f),           // 0
                Pair(dx, 0f),           // 1
                Pair(dx * 2, 0f),       // 2
                Pair(-dx * 0.5f, dy),   // 3 中行左偏
                Pair(dx * 0.5f, dy),    // 4
                Pair(dx * 1.5f, dy),    // 5
                Pair(0f, dy * 2),       // 6
                Pair(dx, dy * 2),       // 7
                Pair(dx * 2, dy * 2)    // 8
            )
            for (i in 0 until 9) {
                keyPositions.add(Pair(baseX + offsets[i].first, baseY + offsets[i].second))
            }
        }

        for (i in 0 until keyCount) {
            val dot = TextView(this).apply {
                text = "${i + 1}"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC0F172A"))
                    setStroke((2 * density).toInt(), Color.parseColor("#38BDF8"))
                }
            }

            val params = WindowManager.LayoutParams(
                dotSize, dotSize,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = keyPositions[i].first.toInt() - halfSize
            params.y = keyPositions[i].second.toInt() - halfSize

            val dx15 = screenW * 0.17f
            val dy15 = dx15 * 0.85f
            val dx9 = screenW * 0.22f
            val dy9 = dx9 * 0.95f

            dot.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = (event.rawX - initialTouchX).toInt()
                            val deltaY = (event.rawY - initialTouchY).toInt()

                            params.x = initialX + deltaX
                            params.y = initialY + deltaY
                            windowManager.updateViewLayout(dot, params)

                            val centerX = (params.x + halfSize).toFloat()
                            val centerY = (params.y + halfSize).toFloat()
                            keyPositions[i] = Pair(centerX, centerY)

                            // 拖动 1 号键时，自动推算其余键
                            if (i == 0) {
                                if (mode == 15) {
                                    for (j in 1 until 15) {
                                        var offsetX = 0f
                                        var offsetY = 0f
                                        when (j) {
                                            in 1..4 -> {
                                                offsetX = j * dx15
                                                offsetY = 0f
                                            }
                                            in 5..9 -> {
                                                offsetX = (j - 5) * dx15 + dx15 / 2
                                                offsetY = dy15
                                            }
                                            in 10..14 -> {
                                                offsetX = (j - 10) * dx15
                                                offsetY = 2 * dy15
                                            }
                                        }
                                        val newX = centerX + offsetX
                                        val newY = centerY + offsetY
                                        keyPositions[j] = Pair(newX, newY)
                                        val otherDot = calibrationViews[j]
                                        val otherParams = otherDot.layoutParams as WindowManager.LayoutParams
                                        otherParams.x = newX.toInt() - halfSize
                                        otherParams.y = newY.toInt() - halfSize
                                        windowManager.updateViewLayout(otherDot, otherParams)
                                    }
                                } else {
                                    val offsets = listOf(
                                        Pair(0f, 0f),
                                        Pair(dx9, 0f),
                                        Pair(dx9 * 2, 0f),
                                        Pair(-dx9 * 0.5f, dy9),
                                        Pair(dx9 * 0.5f, dy9),
                                        Pair(dx9 * 1.5f, dy9),
                                        Pair(0f, dy9 * 2),
                                        Pair(dx9, dy9 * 2),
                                        Pair(dx9 * 2, dy9 * 2)
                                    )
                                    for (j in 1 until 9) {
                                        val newX = centerX + offsets[j].first
                                        val newY = centerY + offsets[j].second
                                        keyPositions[j] = Pair(newX, newY)
                                        val otherDot = calibrationViews[j]
                                        val otherParams = otherDot.layoutParams as WindowManager.LayoutParams
                                        otherParams.x = newX.toInt() - halfSize
                                        otherParams.y = newY.toInt() - halfSize
                                        windowManager.updateViewLayout(otherDot, otherParams)
                                    }
                                }
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager.addView(dot, params)
            calibrationViews.add(dot)
        }

        // 校准按钮
        val btnSave = Button(this).apply {
            text = "✔ 保存${mode}键"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = 8f
            }
            setOnClickListener {
                saveKeyPositions(mode)
                keyMode = mode
                getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
                    .edit().putInt("key_mode", mode).apply()
                exitCalibration()
                Toast.makeText(this@FloatingWindowService, "🎉 ${mode}键位已保存！", Toast.LENGTH_SHORT).show()
            }
        }

        val btnCancel = Button(this).apply {
            text = "✖ 取消"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = 8f
            }
            setOnClickListener {
                loadKeyPositions()
                exitCalibration()
                Toast.makeText(this@FloatingWindowService, "已取消校准", Toast.LENGTH_SHORT).show()
            }
        }

        val spacer = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams((12 * density).toInt(), 1)
        }

        calibrationBtnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            addView(btnSave)
            addView(spacer)
            addView(btnCancel)
        }

        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (20 * density).toInt()
            y = (30 * density).toInt()
        }

        windowManager.addView(calibrationBtnContainer, btnParams)
    }

    private fun exitCalibration() {
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationViews.clear()
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationBtnContainer = null

        floatView.visibility = View.VISIBLE
        showMiniBall()
    }

    private fun loadKeyPositions(forceMode: Int? = null) {
        val mode = forceMode ?: keyMode
        keyPositions.clear()
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        val count = mode
        for (i in 0 until count) {
            val x = prefs.getFloat("key_\( {mode}_ \){i}_x", -1f)
            val y = prefs.getFloat("key_\( {mode}_ \){i}_y", -1f)
            keyPositions.add(Pair(x, y))
        }
    }

    private fun saveKeyPositions(mode: Int = keyMode) {
        val prefs = getSharedPreferences("SkyMusicPrefs", MODE_PRIVATE)
        val editor = prefs.edit()
        for (i in keyPositions.indices) {
            editor.putFloat("key_\( {mode}_ \){i}_x", keyPositions[i].first)
            editor.putFloat("key_\( {mode}_ \){i}_y", keyPositions[i].second)
        }
        editor.apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        handler.removeCallbacksAndMessages(null)
        if (this::floatView.isInitialized) {
            try {
                windowManager.removeView(floatView)
            } catch (_: Exception) {
            }
        }
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}