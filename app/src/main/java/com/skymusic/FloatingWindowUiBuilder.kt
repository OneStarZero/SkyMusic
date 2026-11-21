package com.skymusic

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

internal class FloatingWindowUiBuilder(
    private val context: Context,
    private val dp: (Float) -> Int,
    private val colorOf: (String, String) -> Int,
    private val playAppearAnimation: (View) -> Unit,
    private val applyGlassBlur: (View?, Boolean) -> Unit,
    private val buildTitleWithBlueModel: (String, String) -> CharSequence,
    private val captureScrollY: () -> Unit,
    private val onRequestDisallowIntercept: (Boolean) -> Unit
) {

    fun shapeDrawable(
        fillColor: Int,
        cornerRadiusDp: Float = 0f,
        strokeWidthDp: Float = 0f,
        strokeColor: Int = Color.TRANSPARENT,
        isOval: Boolean = false
    ): GradientDrawable = GradientDrawable().apply {
        shape = if (isOval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!isOval && cornerRadiusDp > 0f) cornerRadius = dp(cornerRadiusDp).toFloat()
        setColor(fillColor)
        if (strokeWidthDp > 0f) {
            setStroke(dp(strokeWidthDp).coerceAtLeast(1), strokeColor)
        }
    }

    fun marqueeText(
        text: CharSequence,
        textColor: Int,
        textSizeSp: Float,
        bold: Boolean = false,
        gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
        lp: LinearLayout.LayoutParams? = null,
        pad: IntArray? = null
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(textColor)
        textSize = textSizeSp
        if (bold) setTypeface(null, Typeface.BOLD)
        this.gravity = gravity
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
        isFocusable = true
        isFocusableInTouchMode = true
        if (lp != null) layoutParams = lp
        if (pad != null && pad.size == 4) setPadding(pad[0], pad[1], pad[2], pad[3])
    }

    fun buildMiniBall(
        accentColor: Int,
        onClickBall: (FrameLayout) -> Unit
    ): FrameLayout {
        val ball = TextView(context).apply {
            text = "✦"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(colorOf("#FDE047", "#FDE047"))
            setShadowLayer(6f, 0f, 0f, colorOf("#EAB308", "#EAB308"))
        }
        val wrap = FrameLayout(context).apply {
            background = shapeDrawable(Color.parseColor("#CC0F172A"), isOval = true)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        wrap.addView(ball)
        playAppearAnimation(wrap)
        wrap.setOnClickListener { onClickBall(wrap) }
        return wrap
    }

    data class PanelViews(
        val root: LinearLayout,
        val titleText: TextView,
        val scrollView: ScrollView,
        val listContainer: LinearLayout
    )

    data class PanelTheme(
        val pageTitle: String,
        val currentPage: Int,
        val bgPanelColor: Int,
        val topBarColor: Int,
        val bottomBarColor: Int,
        val dividerColor: Int,
        val textMainColor: Int,
        val textSoftColor: Int,
        val accentColor: Int,
        val enableBlur: Boolean
    )

    fun buildPanel(
        theme: PanelTheme,
        onRefresh: () -> Unit,
        onClose: () -> Unit,
        onSelectPage: (Int) -> Unit,
        attachDrag: (View) -> Unit
    ): PanelViews {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = shapeDrawable(
                fillColor = theme.bgPanelColor,
                cornerRadiusDp = 14f,
                strokeWidthDp = 1f,
                strokeColor = Color.parseColor("#38FFFFFF")
            )
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14f).toFloat())
                }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        applyGlassBlur(root, theme.enableBlur)

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(9f), dp(10f), dp(9f))
            setBackgroundColor(theme.topBarColor)
        }
        val titleText = marqueeText(
            text = theme.pageTitle,
            textColor = theme.textMainColor,
            textSizeSp = 14.5f,
            bold = true,
            lp = LinearLayout.LayoutParams(0, -2, 1f)
        )
        val btnRefresh = TextView(context).apply {
            text = "↻"
            textSize = 15f
            setTextColor(theme.accentColor)
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            setOnClickListener { onRefresh() }
        }
        val btnClose = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(theme.textMainColor)
            setPadding(dp(8f), dp(2f), dp(4f), dp(2f))
            setOnClickListener { onClose() }
        }
        topBar.addView(titleText)
        topBar.addView(btnRefresh)
        topBar.addView(btnClose)
        root.addView(topBar)
        attachDrag(topBar)

        root.addView(View(context).apply {
            setBackgroundColor(theme.dividerColor)
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        })

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.bottomBarColor)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46f))
        }
        fun navItem(icon: String, label: String, targetPage: Int): LinearLayout {
            val active = theme.currentPage == targetPage
            val fg = if (active) theme.accentColor else theme.textSoftColor
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setOnClickListener {
                    it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                        it.animate().scaleX(1f).scaleY(1f)
                            .setInterpolator(OvershootInterpolator(1.5f)).setDuration(120).start()
                    }.start()
                    onSelectPage(targetPage)
                }
                addView(TextView(context).apply {
                    text = icon
                    textSize = 15f
                    setTextColor(fg)
                    gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = 10.5f
                    if (active) setTypeface(null, Typeface.BOLD)
                    setTextColor(fg)
                    gravity = Gravity.CENTER
                })
            }
        }
        bottom.addView(navItem("◱", "本地", 0))
        bottom.addView(navItem("☁", "发送", 1))
        bottom.addView(navItem("⚙", "设置", 2))
        root.addView(bottom)

        playAppearAnimation(root)
        return PanelViews(root, titleText, scrollView, listContainer)
    }

    fun buildAlphaSliderRow(
        panelAlpha: Float,
        textMainColor: Int,
        accentColor: Int,
        onPreviewAlpha: (Float) -> Unit,
        onCommitAlpha: (Float) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pct = (panelAlpha * 100f).toInt().coerceIn(35, 100)
        val tvVal = TextView(context).apply {
            text = "$pct%"
            setTextColor(accentColor)
            textSize = 12f
            setSingleLine(true)
        }
        top.addView(marqueeText("悬浮窗透明度", textMainColor, 13.5f, lp = LinearLayout.LayoutParams(0, -2, 1f)))
        top.addView(tvVal)
        row.addView(top)

        val sb = SeekBar(context).apply {
            max = 65
            progress = (pct - 35).coerceIn(0, 65)
            layoutParams = LinearLayout.LayoutParams(-1, dp(22f)).apply { topMargin = dp(4f) }
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        captureScrollY()
                        onRequestDisallowIntercept(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        onRequestDisallowIntercept(false)
                    }
                }
                false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val currentPct = (35 + p).coerceIn(35, 100)
                    tvVal.text = "$currentPct%"
                    onPreviewAlpha(currentPct / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    captureScrollY()
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val p = seekBar?.progress ?: return
                    val currentPct = (35 + p).coerceIn(35, 100)
                    tvVal.text = "$currentPct%"
                    onCommitAlpha(currentPct / 100f)
                }
            })
        }
        row.addView(sb)
        return row
    }

    data class PlayerViews(
        val root: LinearLayout,
        val tvStatus: TextView,
        val tvNoteProgress: TextView,
        val tvTimeProgress: TextView,
        val seekBar: SeekBar,
        val btnPlayPause: Button,
        val tvSpeed: TextView
    )

    data class PlayerTheme(
        val songName: String,
        val model: String,
        val infoLine: String,
        val durationText: String,
        val speedText: String,
        val totalDurationMs: Long,
        val bgPanelColor: Int,
        val textMainColor: Int,
        val textSoftColor: Int,
        val accentColor: Int,
        val playBg: GradientDrawable,
        val pauseBg: GradientDrawable,
        val enableBlur: Boolean
    )

    fun buildPlayer(
        theme: PlayerTheme,
        onJumpClick: () -> Unit,
        onSeekChanged: (Int, Boolean) -> Unit,
        onSeekStop: () -> Unit,
        onTogglePlay: () -> Unit,
        onSpeedStep: (Float) -> Unit,
        onClosePlayer: () -> Unit
    ): PlayerViews {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = shapeDrawable(
                fillColor = theme.bgPanelColor,
                cornerRadiusDp = 14f,
                strokeWidthDp = 1f,
                strokeColor = Color.parseColor("#38FFFFFF")
            )
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14f).toFloat())
                }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        applyGlassBlur(layout, theme.enableBlur)

        layout.addView(marqueeText(buildTitleWithBlueModel(theme.songName, theme.model), theme.textMainColor, 13f, bold = true))
        layout.addView(
            marqueeText(
                theme.infoLine,
                theme.textSoftColor,
                10.5f,
                pad = intArrayOf(0, dp(2f), 0, dp(2f))
            )
        )

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1f), 0, dp(2f))
        }
        val tvStatus = marqueeText("", theme.accentColor, 10.5f, lp = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6f) })
        val tvNoteProgress = TextView(context).apply {
            setTextColor(theme.textMainColor)
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setSingleLine(true)
            setPadding(dp(5f), dp(1f), dp(5f), dp(1f))
            background = shapeDrawable(Color.parseColor("#2A38BDF8"), cornerRadiusDp = 4f)
            setOnClickListener {
                it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    onJumpClick()
                }.start()
            }
        }
        statusRow.addView(tvStatus)
        statusRow.addView(tvNoteProgress)
        layout.addView(statusRow)

        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1f), 0, dp(2f))
        }
        val seekBar = SeekBar(context).apply {
            max = if (theme.totalDurationMs > 0L) theme.totalDurationMs.toInt().coerceAtLeast(1) else 100
            layoutParams = LinearLayout.LayoutParams(0, dp(20f), 1f).apply { rightMargin = dp(6f) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    onSeekChanged(p, fromUser)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    onSeekStop()
                }
            })
        }
        val tvTimeProgress = TextView(context).apply {
            text = "0:00/${theme.durationText}"
            setTextColor(theme.textSoftColor)
            textSize = 10f
            setSingleLine(true)
        }
        progressRow.addView(seekBar)
        progressRow.addView(tvTimeProgress)
        layout.addView(progressRow)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(30f))
        }
        val btnPlayPause = Button(context).apply {
            text = "▶"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = theme.playBg
            setOnClickListener {
                it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(1.6f)).setDuration(120).start()
                }.start()
                onTogglePlay()
            }
            layoutParams = LinearLayout.LayoutParams(0, -1, 0.9f)
        }
        row.addView(btnPlayPause)

        fun speedStepBtn(symbol: String, delta: Float): TextView = TextView(context).apply {
            text = symbol
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            background = shapeDrawable(
                fillColor = Color.parseColor("#2E38BDF8"),
                cornerRadiusDp = 6f,
                strokeWidthDp = 1f,
                strokeColor = Color.parseColor("#447DD3FC")
            )
            setOnClickListener { v ->
                v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(65).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(1.6f)).setDuration(110).start()
                }.start()
                onSpeedStep(delta)
            }
        }

        val speed = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3f), dp(2f), dp(3f), dp(2f))
            background = shapeDrawable(
                fillColor = Color.parseColor("#55090D16"),
                cornerRadiusDp = 8f,
                strokeWidthDp = 1f,
                strokeColor = Color.parseColor("#2BFFFFFF")
            )
            layoutParams = LinearLayout.LayoutParams(0, -1, 1.75f).apply {
                leftMargin = dp(6f)
                rightMargin = dp(6f)
            }
        }
        val tvSpeed = marqueeText(
            text = theme.speedText,
            textColor = theme.accentColor,
            textSizeSp = 11f,
            bold = true,
            gravity = Gravity.CENTER,
            pad = intArrayOf(dp(2f), 0, dp(2f), 0)
        )
        speed.addView(speedStepBtn("−", -0.1f), LinearLayout.LayoutParams(dp(26f), -1))
        speed.addView(tvSpeed, LinearLayout.LayoutParams(0, -2, 1f))
        speed.addView(speedStepBtn("+", 0.1f), LinearLayout.LayoutParams(dp(26f), -1))
        row.addView(speed)

        row.addView(Button(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = theme.pauseBg
            setOnClickListener { onClosePlayer() }
            layoutParams = LinearLayout.LayoutParams(0, -1, 0.6f)
        })
        layout.addView(row)

        playAppearAnimation(layout)
        return PlayerViews(layout, tvStatus, tvNoteProgress, tvTimeProgress, seekBar, btnPlayPause, tvSpeed)
    }
}