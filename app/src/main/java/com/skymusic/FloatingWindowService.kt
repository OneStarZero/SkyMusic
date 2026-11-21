package com.skymusic

import android.app.AlertDialog
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.LinkedHashMap
import kotlin.concurrent.thread
import kotlin.math.abs

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: FrameLayout
    private lateinit var uiBuilder: FloatingWindowUiBuilder

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
    private var lastUiUpdateMs = 0L
    private var lastRenderedNoteIndex = -1

    private val appSheetsPath = FloatingWindowConstants.APP_SHEETS_PATH
    private val localSheetsPath = FloatingWindowConstants.LOCAL_SHEETS_PATH

    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var playerParams: WindowManager.LayoutParams

    private var currentPage = 0
    private var lastRenderedPage = -1
    private var lastScrollY = 0
    private var density = 1.0f
    private var cachedSongList: List<ScannedSongItem>? = null
    private var refreshSessionId = 0

    private val maxCachedSheets = FloatingWindowConstants.MAX_CACHED_SHEETS
    private val sheetCacheLock = Any()
    private val sheetCachePool = object : LinkedHashMap<String, LoadedSheetEntry>(maxCachedSheets, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedSheetEntry>?): Boolean {
            return size > maxCachedSheets
        }
    }
    private val fileMetaCache = HashMap<String, FileMetaCacheEntry>()

    private lateinit var playBgDrawable: GradientDrawable
    private lateinit var pauseBgDrawable: GradientDrawable

    private var calibrationViews = mutableListOf<TextView>()
    private var calibrationBtnContainer: LinearLayout? = null

    // 设置项（默认最佳透明度 0.85f，悬浮球约星星再大两圈 44dp）
    private var keyMode = 15
    private var defaultSpeed = 1.0f
    private var clickToleranceMs = 25L
    private var ballSizeDp = 44f
    private var panelAlpha = 0.85f
    private var autoBackToList = true
    private val songKeyModeMap = mutableMapOf<String, Int>()

    // 经典清晰色板 + 模型高亮蓝
    private var themeAccent = "#38BDF8"
    private val modelHighlightColor by lazy { Color.parseColor("#60A5FA") }
    private var themeBgPanel = "#D90F172A"
    private var themeTopBar = "#162033"
    private var themeBottomBar = "#0F172A"
    private var themeDivider = "#24FFFFFF"
    private var themeTextMain = "#F8FAFC"
    private var themeTextSoft = "#94A3B8"

    companion object {
        private const val CHANNEL_ID = FloatingWindowConstants.CHANNEL_ID
        private const val NOTIF_ID = FloatingWindowConstants.NOTIF_ID
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density

        uiBuilder = FloatingWindowUiBuilder(
            context = this,
            dp = ::dp,
            colorOf = ::c,
            playAppearAnimation = ::playAppearAnimation,
            buildTitleWithBlueModel = ::buildTitleWithBlueModel,
            captureScrollY = ::captureCurrentScrollY,
            onRequestDisallowIntercept = { disallow ->
                if (this::scrollView.isInitialized) {
                    scrollView.requestDisallowInterceptTouchEvent(disallow)
                }
            }
        )

        playBgDrawable = uiBuilder.shapeDrawable(Color.parseColor("#22C55E"), cornerRadiusDp = 6f)
        pauseBgDrawable = uiBuilder.shapeDrawable(Color.parseColor("#EF4444"), cornerRadiusDp = 6f)

        startForegroundCompact()

        val prefs = getSharedPreferences(FloatingWindowConstants.PREFS_NAME, MODE_PRIVATE)
        keyMode = prefs.getInt("key_mode", 15)
        defaultSpeed = FloatingWindowHelpers.roundSpeed(prefs.getFloat("default_speed", 1.0f))
        currentSpeed = defaultSpeed
        clickToleranceMs = prefs.getLong("click_tolerance", 25L)
        ballSizeDp = prefs.getFloat("ball_size_dp", 44f).coerceIn(38f, 84f)
        panelAlpha = prefs.getFloat("panel_alpha", 0.85f).coerceIn(0.35f, 1.0f)
        autoBackToList = prefs.getBoolean("auto_back_list", true)
        themeAccent = prefs.getString("theme_accent", "#38BDF8") ?: "#38BDF8"

        prefs.all.forEach { (k, v) ->
            if (k.startsWith("song_mode_") && v is Int) {
                songKeyModeMap[k.removePrefix("song_mode_")] = v
            }
        }

        updatePanelAlphaColor(panelAlpha)
        loadKeyPositions()
        File(appSheetsPath).mkdirs()
        File(localSheetsPath).mkdirs()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        density = resources.displayMetrics.density
        if (!this::floatView.isInitialized) return

        val dm = resources.displayMetrics
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        panelParams.width = calcPanelWidth(dm.widthPixels, land)
        panelParams.height = (dm.heightPixels * if (land) 0.62f else 0.50f).toInt()
        playerParams.width = calcPlayerWidth(dm.widthPixels, land)

        clampWindowParams(panelParams, dm.widthPixels, dm.heightPixels)
        clampWindowParams(ballParams, dm.widthPixels, dm.heightPixels)
        clampWindowParams(playerParams, dm.widthPixels, dm.heightPixels)

        val active = when {
            isMiniMode -> ballParams
            isPlayerMode -> playerParams
            else -> panelParams
        }
        windowManager.updateViewLayout(floatView, active)
    }

    private fun startForegroundCompact() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SkyMusic 悬浮服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持悬浮窗与演奏服务稳定运行"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SkyMusic 悬浮窗已就绪")
            .setContentText("点击返回应用管理悬浮窗与谱面")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun showOverlayDialog(dialog: Dialog, showSoftInput: Boolean = false) {
        dialog.window?.setType(overlayWindowType())
        if (showSoftInput) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun getCachedFileInfo(file: File): List<String> {
        val path = file.absolutePath
        synchronized(sheetCacheLock) {
            val cached = fileMetaCache[path]
            if (FloatingWindowHelpers.isFileMetaUnchanged(cached, file)) return cached!!.info
        }
        val info = FloatingWindowHelpers.readLocalFileInfo(file)
        if (file.exists()) {
            synchronized(sheetCacheLock) {
                fileMetaCache[path] = FileMetaCacheEntry(file.length(), file.lastModified(), info)
            }
        }
        return info
    }

    private fun getCachedLoadedSheet(cacheKey: String, file: File): LoadedSheetEntry? {
        if (cacheKey.isBlank() || !file.exists()) return null
        synchronized(sheetCacheLock) {
            val entry = sheetCachePool[cacheKey] ?: return null
            if (entry.sizeBytes == file.length() && entry.lastModified == file.lastModified()) return entry
            sheetCachePool.remove(cacheKey)
            return null
        }
    }

    private fun putCachedLoadedSheet(entry: LoadedSheetEntry) {
        if (entry.cacheKey.isBlank()) return
        synchronized(sheetCacheLock) {
            sheetCachePool[entry.cacheKey] = entry
        }
    }

    private fun invalidateSheetCache(file: File) {
        val path = file.absolutePath
        synchronized(sheetCacheLock) {
            fileMetaCache.remove(path)
            val prefix = "$path#"
            val iterator = sheetCachePool.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().startsWith(prefix)) iterator.remove()
            }
        }
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun calcPanelWidth(screenW: Int, land: Boolean): Int =
        (screenW * if (land) 0.38f else 0.62f).toInt().coerceIn(dp(220f), dp(440f))

    private fun calcPlayerWidth(screenW: Int, land: Boolean): Int =
        (screenW * if (land) 0.38f else 0.60f).toInt().coerceIn(dp(230f), dp(420f))

    private fun clampWindowParams(params: WindowManager.LayoutParams, screenW: Int, screenH: Int) {
        val w = if (params.width > 0) params.width else dp(ballSizeDp)
        val h = if (params.height > 0) params.height else dp(ballSizeDp)
        params.x = params.x.coerceIn(0, (screenW - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenH - h).coerceAtLeast(0))
    }

    private fun updatePanelAlphaColor(alpha: Float) {
        panelAlpha = alpha.coerceIn(0.35f, 1.0f)
val a = (panelAlpha * 255).toInt().coerceIn(150, 255)
        themeBgPanel = String.format("#%02X0F172A", a)
        val bgColor = c(themeBgPanel, "#D90F172A")
        panelRootView?.setBackgroundColor(bgColor)
        playerRootView?.setBackgroundColor(bgColor)
    }

    private fun buildTitleWithBlueModel(songName: String, model: String): CharSequence {
        val pureName = if (songName.contains(" || ")) songName.substringBefore(" || ").trim() else songName.trim()
        val modelName = when {
            model.isNotBlank() -> model.trim()
            songName.contains(" || ") -> songName.substringAfter(" || ").trim()
            else -> ""
        }
        if (modelName.isEmpty()) return pureName
        val prefix = "$pureName · "
        return SpannableStringBuilder(prefix).apply {
            val start = length
            append(modelName)
            setSpan(ForegroundColorSpan(modelHighlightColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun playAppearAnimation(target: View) {
        target.alpha = 0f
        target.scaleX = 0.94f
        target.scaleY = 0.94f
        target.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        target.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { target.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
    }

    private fun c(hex: String, fb: String = "#FFFFFF"): Int =
        try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.parseColor(fb)
        }

    private fun totalDurationMs(): Long =
        if (songData.isEmpty()) 0L else songData.last().first.coerceAtLeast(0L)

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
        lastRenderedNoteIndex = -1
        updatePlayerUI(force = true)
        if (wasPlaying) startPlayback()
    }

    private fun showJumpToNoteDialog() {
        if (songData.isEmpty()) {
            Toast.makeText(this, "暂无音符数据", Toast.LENGTH_SHORT).show()
            return
        }
        val maxNote = songData.size
        val dialogTheme = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val input = EditText(dialogTheme).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "输入音符序号 (1 ~ $maxNote)"
            setText((currentIndex + 1).coerceIn(1, maxNote).toString())
            setSelection(text?.length ?: 0)
        }
        val container = FrameLayout(dialogTheme).apply {
            setPadding(dp(20f), dp(12f), dp(20f), dp(4f))
            addView(input)
        }
        val dialog = AlertDialog.Builder(dialogTheme)
            .setTitle("跳转到指定音符")
            .setMessage("当前第 ${(currentIndex + 1).coerceAtMost(maxNote)} / $maxNote 个音符")
            .setView(container)
            .setPositiveButton("跳转") { _, _ ->
                val num = input.text.toString().trim().toIntOrNull()
                if (num == null || num < 1 || num > maxNote) {
                    Toast.makeText(this, "请输入 1 到 $maxNote 之间的音符数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                jumpToNoteIndex(num - 1)
                Toast.makeText(this, "已跳转到第 $num 个音符", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        showOverlayDialog(dialog, showSoftInput = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("is_new_data", false) == true) {
            @Suppress("UNCHECKED_CAST")
            val incoming = intent.getSerializableExtra("song_data") as? ArrayList<Pair<Long, Int>>
            if (incoming != null) {
                songData = incoming
                stopPlayback()
                currentIndex = 0
                lastRenderedNoteIndex = -1
                currentSongName = intent.getStringExtra("song_name") ?: "App 发送谱面"
                currentAuthor = intent.getStringExtra("song_author") ?: "未知作者"
                currentModel = intent.getStringExtra("song_model") ?: ""
                currentNotesCount = songData.size
                currentDuration = FloatingWindowHelpers.calculateDuration(songData)
                currentBpm = intent.getStringExtra("song_bpm") ?: "0"
                if (!this::floatView.isInitialized) createFloatingWindow()
                if (!isMiniMode && !isPlayerMode) {
                    currentPage = 1
                    showFullPanel(1, resetScroll = true)
                }
            }
        }
        if (!this::floatView.isInitialized) createFloatingWindow()
        return START_STICKY
    }

    private fun createFloatingWindow() {
        try {
            floatView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            val type = overlayWindowType()
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

            val dm = resources.displayMetrics
            val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val ballPx = dp(ballSizeDp)

            panelParams = WindowManager.LayoutParams(
                calcPanelWidth(dm.widthPixels, land),
                (dm.heightPixels * if (land) 0.62f else 0.50f).toInt(),
                type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (dm.widthPixels * 0.06f).toInt()
                y = (dm.heightPixels * 0.16f).toInt()
            }

            ballParams = WindowManager.LayoutParams(
                ballPx, ballPx, type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(18f)
                y = (dm.heightPixels * 0.25f).toInt()
            }

            val pw = calcPlayerWidth(dm.widthPixels, land)
            playerParams = WindowManager.LayoutParams(
                pw, WindowManager.LayoutParams.WRAP_CONTENT, type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ((dm.widthPixels - pw) / 2).coerceAtLeast(0)
                y = (dm.heightPixels - dp(165f)).coerceAtLeast(0)
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
                    ix = params.x
                    iy = params.y
                    tx = e.rawX
                    ty = e.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount != 1) return@setOnTouchListener true
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val dm = resources.displayMetrics
                        val w = params.width.takeIf { it > 0 } ?: dp(ballSizeDp)
                        val h = params.height.takeIf { it > 0 } ?: dp(ballSizeDp)
                        params.x = (ix + dx.toInt()).coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
                        params.y = (iy + dy.toInt()).coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
                        windowManager.updateViewLayout(floatView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging && e.pointerCount == 1) v.performClick()
                    dragging = false
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
        panelRootView = null
        playerRootView = null
        floatView.removeAllViews()

        val ballPx = dp(ballSizeDp)
        ballParams.width = ballPx
        ballParams.height = ballPx
        clampWindowParams(ballParams, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

        val wrap = uiBuilder.buildMiniBall(c(themeAccent)) { v ->
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                showFullPanel(currentPage, resetScroll = false)
            }.start()
        }
        enableDrag(wrap, ballParams)
        floatView.addView(wrap)
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
            if (this::scrollView.isInitialized) scrollView.scrollTo(0, targetY)
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
        if (resetScroll || lastRenderedPage != page) lastScrollY = 0
        lastRenderedPage = page
        currentPage = page

        isMiniMode = false
        isPlayerMode = false
        playerRootView = null
        floatView.removeAllViews()

        val dm = resources.displayMetrics
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        panelParams.width = calcPanelWidth(dm.widthPixels, land)
        panelParams.height = (dm.heightPixels * if (land) 0.62f else 0.50f).toInt()
        clampWindowParams(panelParams, dm.widthPixels, dm.heightPixels)
        updatePanelAlphaColor(panelAlpha)

        val panelViews = uiBuilder.buildPanel(
            theme = FloatingWindowUiBuilder.PanelTheme(
                pageTitle = when (page) { 0 -> "本地曲库"; 1 -> "App 发送"; else -> "设置" },
                currentPage = page,
                bgPanelColor = c(themeBgPanel, "#D90F172A"),
                topBarColor = c(themeTopBar, "#162033"),
                bottomBarColor = c(themeBottomBar, "#0F172A"),
                dividerColor = c(themeDivider),
                textMainColor = c(themeTextMain),
                textSoftColor = c(themeTextSoft),
                accentColor = c(themeAccent),
            ),
            onRefresh = {
                captureCurrentScrollY()
                if (currentPage == 0) cachedSongList = null
                showFullPanel(currentPage, resetScroll = false)
            },
            onClose = {
                captureCurrentScrollY()
                showMiniBall()
            },
            onSelectPage = { target -> showFullPanel(target, resetScroll = true) },
            attachDrag = { bar -> enableDrag(bar, panelParams) }
        )

        panelRootView = panelViews.root
        titleText = panelViews.titleText
        scrollView = panelViews.scrollView
        listContainer = panelViews.listContainer

        floatView.addView(panelViews.root)
        windowManager.updateViewLayout(floatView, panelParams)

        switchPage(page)
        restoreScrollY()
    }

    private fun addEmptyListHint(message: String) {
        listContainer.addView(
            uiBuilder.marqueeText(
                text = message,
                textColor = c(themeTextSoft),
                textSizeSp = 13f,
                pad = intArrayOf(dp(14f), dp(14f), dp(14f), dp(14f))
            )
        )
    }

    private fun switchPage(page: Int) {
        if (!this::listContainer.isInitialized) return
        listContainer.removeAllViews()
        when (page) {
            0 -> {
                val existing = cachedSongList
                if (existing != null) {
                    renderLocalList(existing)
                    restoreScrollY()
                    return
                }
                addEmptyListHint("扫描中...")
                refreshSessionId++
                val sid = refreshSessionId
                thread {
                    try {
                        val files = File(localSheetsPath)
                            .listFiles { f -> f.extension.equals("txt", true) }
                            ?.toList()
                            ?: emptyList()
                        val list = files.map { ScannedSongItem(it, getCachedFileInfo(it), "") }
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
                    addEmptyListHint("暂无发送的谱面")
                    return
                }
                files.forEachIndexed { idx, f ->
                    val info = getCachedFileInfo(f)
                    addSongToList(info[0], info[1], info[2], info[3].toInt(), info[5], info[6], f) { directLoadLocalFile(f) }
                    if (idx < files.lastIndex) addDivider()
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
        val prefs = getSharedPreferences(FloatingWindowConstants.PREFS_NAME, MODE_PRIVATE)
        val mainColor = c(themeTextMain)
        val softColor = c(themeTextSoft)
        val accentColor = c(themeAccent)

        fun sectionTitle(t: String) {
            listContainer.addView(
                uiBuilder.marqueeText(
                    text = t,
                    textColor = accentColor,
                    textSizeSp = 11.5f,
                    bold = true,
                    pad = intArrayOf(dp(12f), dp(8f), dp(12f), dp(4f))
                )
            )
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
            row.addView(uiBuilder.marqueeText(t, mainColor, 13.5f))
            if (s.isNotEmpty()) {
                row.addView(uiBuilder.marqueeText(s, softColor, 11f, pad = intArrayOf(0, dp(2f), 0, 0)))
            }
            listContainer.addView(row)
            addDivider()
        }

        sectionTitle("键位与校准")
        item("15 键校准", "标准 5×3 网格") { if (checkAccessibility()) startCalibration(15) }
        item("9 键校准", "标准 3×3 网格") { if (checkAccessibility()) startCalibration(9) }
        item("默认键位", "当前 ${keyMode} 键") {
            keyMode = if (keyMode == 15) 9 else 15
            prefs.edit().putInt("key_mode", keyMode).apply()
            loadKeyPositions()
            refreshSettingsKeepScroll()
        }

        sectionTitle("演奏")
        item("默认倍速", FloatingWindowHelpers.formatSpeed(defaultSpeed)) {
            val next = if (defaultSpeed + 0.1f > 3f) 0.5f else defaultSpeed + 0.1f
            defaultSpeed = FloatingWindowHelpers.roundSpeed(next)
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
        listContainer.addView(
            uiBuilder.buildAlphaSliderRow(
                panelAlpha = panelAlpha,
                textMainColor = mainColor,
                accentColor = accentColor,
                onPreviewAlpha = { previewAlpha -> updatePanelAlphaColor(previewAlpha) },
                onCommitAlpha = { committedAlpha ->
                    updatePanelAlphaColor(committedAlpha)
                    prefs.edit().putFloat("panel_alpha", panelAlpha).apply()
                }
            )
        )
        addDivider()

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
            showOverlayDialog(dialog)
        }
    }

    private fun renderLocalList(list: List<ScannedSongItem>) {
        if (list.isEmpty()) {
            addEmptyListHint("LocalSheets 为空")
            return
        }
        list.forEachIndexed { idx, scanned ->
            val file = scanned.file
            val info = scanned.info
            addSongToList(info[0], info[1], info[2], info[3].toInt(), info[5], info[6], file) {
                directLoadLocalFile(file)
            }
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(10f), dp(8f))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6f) }
            setOnClickListener {
                captureCurrentScrollY()
                onClick()
            }
        }
        textCol.addView(uiBuilder.marqueeText(buildTitleWithBlueModel(pureName, modelName), c(themeTextMain), 13.5f, bold = true))
        textCol.addView(
            uiBuilder.marqueeText(
                "$author · $duration · ${notes}音符 · ${songMode}键",
                c(themeTextSoft),
                11f,
                pad = intArrayOf(0, dp(2f), 0, 0)
            )
        )
        row.addView(textCol)

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun iconBtn(t: String, color: Int? = null, click: () -> Unit) = TextView(this).apply {
            text = t
            textSize = 13f
            gravity = Gravity.CENTER
            val w = dp(24f)
            layoutParams = LinearLayout.LayoutParams(w, w)
            setTextColor(color ?: c(themeTextSoft))
            setOnClickListener {
                captureCurrentScrollY()
                click()
            }
        }

        btns.addView(iconBtn("ⓘ") {
            showSongDetailDialog(pureName, author, duration, notes, bpm, modelName, file, songMode)
        })
        btns.addView(iconBtn(if (songMode == 15) "15" else "9", c(themeAccent)) {
            val nm = if (songMode == 15) 9 else 15
            songKeyModeMap[pathKey] = nm
            getSharedPreferences(FloatingWindowConstants.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt("song_mode_$pathKey", nm)
                .apply()
            showFullPanel(currentPage, resetScroll = false)
        })
        btns.addView(iconBtn("✕", c("#F87171")) {
            if (file == null) {
                Toast.makeText(this, "内存谱面", Toast.LENGTH_SHORT).show()
                return@iconBtn
            }
            val dialog = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                .setTitle("删除")
                .setMessage(file.name)
                .setPositiveButton("删除") { _, _ ->
                    if (file.delete()) {
                        invalidateSheetCache(file)
                        if (currentPage == 0) cachedSongList = null
                        showFullPanel(currentPage, resetScroll = false)
                    }
                }
                .setNegativeButton("取消", null)
                .create()
            showOverlayDialog(dialog)
        })
        btns.addView(iconBtn("▷", c("#4ADE80")) { onClick() })

        row.addView(btns)
        listContainer.addView(row)
    }

    private fun showSongDetailDialog(
        name: String,
        author: String,
        duration: String,
        notes: Int,
        bpm: String,
        model: String,
        file: File?,
        mode: Int
    ) {
        val msg = "歌名：$name\n作者：$author\n模型：$model\nBPM：$bpm\n音符：$notes\n时长：$duration\n键位：${mode}键\n文件：${file?.name ?: "mem"}"
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
            .setTitle("谱面详情")
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .create()
        showOverlayDialog(dialog)
    }

    private fun showPlayerPanel() {
        if (!this::floatView.isInitialized) return
        isMiniMode = false
        isPlayerMode = true
        panelRootView = null
        floatView.removeAllViews()

        val dm = resources.displayMetrics
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        playerParams.width = calcPlayerWidth(dm.widthPixels, land)
        clampWindowParams(playerParams, dm.widthPixels, dm.heightPixels)
        updatePanelAlphaColor(panelAlpha)

        val infoLine = buildString {
            append(if (currentAuthor.isNotBlank()) currentAuthor else "未知作者")
            append(" · ").append(currentDuration)
            append(" · ").append(currentNotesCount).append("音符")
            if (currentBpm.isNotBlank() && currentBpm != "0") append(" · BPM ").append(currentBpm)
        }

        val pv = uiBuilder.buildPlayer(
            theme = FloatingWindowUiBuilder.PlayerTheme(
                songName = currentSongName,
                model = currentModel,
                infoLine = infoLine,
                durationText = currentDuration,
                speedText = FloatingWindowHelpers.formatSpeed(currentSpeed),
                totalDurationMs = totalDurationMs(),
                bgPanelColor = c(themeBgPanel, "#D90F172A"),
                textMainColor = c(themeTextMain),
                textSoftColor = c(themeTextSoft),
                accentColor = c(themeAccent),
                playBg = playBgDrawable,
                pauseBg = pauseBgDrawable,
            ),
            onJumpClick = ::showJumpToNoteDialog,
            onSeekChanged = { p, fromUser ->
                if (!fromUser || songData.isEmpty()) return@buildPlayer
                val dur = totalDurationMs()
                currentIndex = if (dur > 0L) {
                    findIndexByTimeMs(p.toLong().coerceIn(0L, dur))
                } else if (songData.size > 1) {
                    (p / 100f * (songData.size - 1)).toInt()
                } else {
                    0
                }
                updatePlayerUI(syncSeekBar = false, force = true)
            },
            onSeekStop = {
                if (isPlaying && songData.isNotEmpty()) {
                    stopPlayback()
                    startPlayback()
                }
            },
            onTogglePlay = ::togglePlayback,
            onSpeedStep = ::changeSpeed,
            onClosePlayer = {
                stopPlayback()
                showFullPanel(currentPage, resetScroll = false)
            }
        )

        playerRootView = pv.root
        tvStatus = pv.tvStatus
        tvNoteProgress = pv.tvNoteProgress
        tvTimeProgress = pv.tvTimeProgress
        seekBar = pv.seekBar
        btnPlayPause = pv.btnPlayPause
        tvSpeed = pv.tvSpeed

        enableDrag(pv.root, playerParams) { isPlaying }
        floatView.addView(pv.root)
        windowManager.updateViewLayout(floatView, playerParams)
        lastRenderedNoteIndex = -1
        updatePlayerUI(force = true)
    }

    private fun updatePlayerUI(syncSeekBar: Boolean = true, force: Boolean = false) {
        if (!isPlayerMode || !this::floatView.isInitialized) return
        val now = System.currentTimeMillis()
        if (!force && now - lastUiUpdateMs < 55L && currentIndex == lastRenderedNoteIndex) return
        lastUiUpdateMs = now

        val total = songData.size
        val displayNote = if (total == 0) 0 else (currentIndex + 1).coerceIn(1, total)
        val curMs = currentTimeMs()
        val durMs = totalDurationMs()

        if (this::tvNoteProgress.isInitialized && (force || displayNote != lastRenderedNoteIndex)) {
            tvNoteProgress.text = "$displayNote/$total 音符 ↴"
            lastRenderedNoteIndex = displayNote
        }
        if (this::tvTimeProgress.isInitialized) {
            tvTimeProgress.text = "${FloatingWindowHelpers.formatDurationMs(curMs)}/${FloatingWindowHelpers.formatDurationMs(durMs)}"
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

        val sp = FloatingWindowHelpers.formatSpeed(currentSpeed)
        if (isPlaying) {
            tvStatus.text = "▶ 播放中  |  $sp"
            btnPlayPause.text = "⏸"
            btnPlayPause.background = pauseBgDrawable
        } else {
            tvStatus.text = "暂停  |  $sp"
            btnPlayPause.text = "▶"
            btnPlayPause.background = playBgDrawable
        }
    }

    private fun checkAccessibility(): Boolean {
        if (SkyAccessibilityService.instance != null) return true
        Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_SHORT).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    private fun preparePlay() {
        if (!checkAccessibility()) return
        if (keyPositions.isEmpty() || keyPositions[0].first == -1f) {
            Toast.makeText(this, "请先校准键位", Toast.LENGTH_SHORT).show()
            return
        }
        stopPlayback()
        currentIndex = 0
        lastRenderedNoteIndex = -1
        handler.postDelayed({
            showPlayerPanel()
            updatePlayerUI(force = true)
        }, 50)
    }

    private fun togglePlayback() {
        if (songData.isEmpty() || !checkAccessibility()) return
        if (isPlaying) {
            stopPlayback()
        } else {
            if (currentIndex >= songData.size) currentIndex = 0
            startPlayback()
        }
        updatePlayerUI(force = true)
    }

    private fun changeSpeed(delta: Float) {
        currentSpeed = FloatingWindowHelpers.roundSpeed(currentSpeed + delta)
        if (this::tvSpeed.isInitialized) {
            tvSpeed.text = FloatingWindowHelpers.formatSpeed(currentSpeed)
        }
        if (isPlaying) {
            stopPlayback()
            startPlayback()
        } else {
            updatePlayerUI(syncSeekBar = false, force = true)
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
        stopPlayback()
        currentIndex = 0
        lastRenderedNoteIndex = -1
        handler.post {
            if (isPlayerMode && this::seekBar.isInitialized) updatePlayerUI(force = true)
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

    private fun directLoadLocalFile(file: File) {
        val mode = songKeyModeMap[file.absolutePath] ?: keyMode
        val cacheKey = FloatingWindowHelpers.buildSheetCacheKey(file.absolutePath, mode)
        val cachedEntry = getCachedLoadedSheet(cacheKey, file)
        if (cachedEntry != null) {
            applyLoadedSheet(cachedEntry, mode)
            return
        }

        thread {
            val parsed = FloatingWindowHelpers.parseLocalSheet(file)
            if (parsed == null) {
                handler.post { Toast.makeText(this, "空乐谱或读取异常", Toast.LENGTH_SHORT).show() }
                return@thread
            }
            val entry = LoadedSheetEntry(
                cacheKey = cacheKey,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                songName = parsed.songName,
                author = parsed.author,
                duration = parsed.duration,
                notesCount = parsed.notes.size,
                bpm = parsed.bpm,
                model = parsed.model,
                notes = parsed.notes
            )
            putCachedLoadedSheet(entry)
            handler.post { applyLoadedSheet(entry, mode) }
        }
    }

    private fun applyLoadedSheet(entry: LoadedSheetEntry, mode: Int) {
        songData = entry.notes
        currentIndex = 0
        currentSongName = entry.songName
        currentAuthor = entry.author
        currentBpm = entry.bpm
        currentModel = entry.model
        currentNotesCount = entry.notesCount
        currentDuration = entry.duration
        if (keyPositions.size != mode) loadKeyPositions(mode)
        preparePlay()
    }

    private fun populateCalibrationGrid(
        mode: Int,
        screenW: Int,
        screenH: Int,
        isLand: Boolean,
        prefs: SharedPreferences
    ): Pair<Float, Float> {
        val is15 = mode == 15
        val cols = if (is15) 5 else 3
        val rows = 3

        val keyW = if (is15) {
            if (isLand) screenW * 0.082f else screenW * 0.115f
        } else {
            if (isLand) screenW * 0.105f else screenW * 0.145f
        }
        val keyH = if (is15) {
            if (isLand) screenH * 0.145f else keyW * 0.92f
        } else {
            if (isLand) screenH * 0.155f else keyW * 0.92f
        }
        val gapX = if (is15) {
            if (isLand) screenW * 0.012f else screenW * 0.014f
        } else {
            if (isLand) screenW * 0.018f else screenW * 0.022f
        }
        val gapY = if (is15) {
            if (isLand) screenH * 0.016f else screenH * 0.010f
        } else {
            if (isLand) screenH * 0.020f else screenH * 0.014f
        }

        val stepX = keyW + gapX
        val stepY = keyH + gapY
        val gridW = cols * keyW + (cols - 1) * gapX
        val gridH = rows * keyH + (rows - 1) * gapY
        val yOffset = if (isLand) screenH * 0.03f else screenH * 0.05f

        var baseX = (screenW - gridW) / 2f + keyW / 2f
        var baseY = (screenH - gridH) / 2f + keyH / 2f + yOffset
        val savedX = prefs.getFloat("key_${mode}_0_x", -1f)
        if (savedX != -1f) {
            baseX = savedX
            baseY = prefs.getFloat("key_${mode}_0_y", baseY)
        }

        keyPositions.clear()
        for (i in 0 until mode) {
            keyPositions.add(Pair(baseX + (i % cols) * stepX, baseY + (i / cols) * stepY))
        }
        return Pair(stepX, stepY)
    }

    private fun startCalibration(mode: Int = keyMode) {
        if (!this::floatView.isInitialized) return
        floatView.visibility = View.GONE
        floatView.removeAllViews()

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val isLand = screenW > screenH
        val dotSize = dp(38f)
        val half = dotSize / 2
        val cols = if (mode == 15) 5 else 3

        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationViews.clear()
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationBtnContainer = null

        val prefs = getSharedPreferences(FloatingWindowConstants.PREFS_NAME, MODE_PRIVATE)
        val (stepX, stepY) = populateCalibrationGrid(mode, screenW, screenH, isLand, prefs)
        val overlayType = overlayWindowType()
        val accent = c(themeAccent)

        for (i in 0 until mode) {
            val dot = TextView(this).apply {
                text = "${i + 1}"
                setTextColor(accent)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = uiBuilder.shapeDrawable(
                    fillColor = Color.parseColor("#CC0F172A"),
                    strokeWidthDp = 1.5f,
                    strokeColor = accent,
                    isOval = true
                )
            }
            val lp = WindowManager.LayoutParams(
                dotSize, dotSize, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = keyPositions[i].first.toInt() - half
                y = keyPositions[i].second.toInt() - half
            }

            dot.setOnTouchListener(object : View.OnTouchListener {
                var ix = 0
                var iy = 0
                var tx = 0f
                var ty = 0f
                override fun onTouch(v: View?, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            lp.x = ix + (e.rawX - tx).toInt()
                            lp.y = iy + (e.rawY - ty).toInt()
                            windowManager.updateViewLayout(dot, lp)
                            val cx = (lp.x + half).toFloat()
                            val cy = (lp.y + half).toFloat()
                            keyPositions[i] = Pair(cx, cy)
                            if (i == 0) {
                                for (j in 1 until mode) {
                                    val nx = cx + (j % cols) * stepX
                                    val ny = cy + (j / cols) * stepY
                                    keyPositions[j] = Pair(nx, ny)
                                    val op = calibrationViews[j].layoutParams as WindowManager.LayoutParams
                                    op.x = nx.toInt() - half
                                    op.y = ny.toInt() - half
                                    windowManager.updateViewLayout(calibrationViews[j], op)
                                }
                            }
                            return true
                        }
                    }
                    return false
                }
            })
            windowManager.addView(dot, lp)
            calibrationViews.add(dot)
        }

        val btnSave = Button(this).apply {
            text = "✔ 保存${mode}键"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
            background = uiBuilder.shapeDrawable(c("#22C55E"), cornerRadiusDp = 6f)
            setOnClickListener {
                saveKeyPositions(mode)
                keyMode = mode
                prefs.edit().putInt("key_mode", mode).apply()
                exitCalibration()
                Toast.makeText(this@FloatingWindowService, "已保存 ${mode}键", Toast.LENGTH_SHORT).show()
            }
        }
        val btnCancel = Button(this).apply {
            text = "✖ 取消"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
            background = uiBuilder.shapeDrawable(c("#EF4444"), cornerRadiusDp = 6f)
            setOnClickListener {
                loadKeyPositions()
                exitCalibration()
            }
        }
        calibrationBtnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(10f), 0)
            addView(btnSave)
            addView(Space(this@FloatingWindowService).apply { layoutParams = LinearLayout.LayoutParams(dp(12f), 1) })
            addView(btnCancel)
        }
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(20f)
            y = dp(30f)
        }
        windowManager.addView(calibrationBtnContainer, bp)
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
        val prefs = getSharedPreferences(FloatingWindowConstants.PREFS_NAME, MODE_PRIVATE)
        for (i in 0 until mode) {
            keyPositions.add(Pair(prefs.getFloat("key_${mode}_${i}_x", -1f), prefs.getFloat("key_${mode}_${i}_y", -1f)))
        }
    }

    private fun saveKeyPositions(mode: Int = keyMode) {
        val ed = getSharedPreferences(FloatingWindowConstants.PREFS_NAME, MODE_PRIVATE).edit()
        for (i in keyPositions.indices) {
            ed.putFloat("key_${mode}_${i}_x", keyPositions[i].first)
            ed.putFloat("key_${mode}_${i}_y", keyPositions[i].second)
        }
        ed.apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        handler.removeCallbacksAndMessages(null)
        if (this::floatView.isInitialized) {
            try { windowManager.removeView(floatView) } catch (_: Exception) {}
        }
        calibrationViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }
        calibrationBtnContainer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
