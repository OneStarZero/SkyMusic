package com.skymusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SkyMusic 0.0.9 · 谱面图片导出器
 * ------------------------------------------------------------------
 * 接收前端 sheet-image.js 计算好的 layout，用 Canvas 逐页绘制成
 * Sky Studio 风格方块谱 PNG，写入 <baseDir>/谱面名称/。
 *
 * 支持：
 *   · 只导出用户勾选的页（pages 数组已由前端过滤）
 *   · 可选额外输出一张「所有页上下拼接」的完整长图
 *   · 信息面板（infoItems）逐页绘制
 */
object SheetImageExporter {

    private const val MAX_PIXELS = 40_000_000L
    private const val MAX_LONG_PIXELS = 120_000_000L
    private const val MAX_NAME_LENGTH = 60
    private const val FONT_STACK = "sans-serif"
    private const val WATERMARK_TEXT = "Bilibili@_OneStar"
    private const val APP_NAME = "SkyMusic"
    private const val IMAGE_FOLDER_NAME = "谱面名称"
    private const val LONG_SUFFIX = "-Full"

    // ============================ 对外入口 ============================

    fun export(baseDir: File, payloadJson: String): JSONObject {
        try {
            val root = JSONObject(payloadJson)
            val songName = root.optString("songName", "未命名乐谱").ifBlank { "未命名乐谱" }
            val rows = root.optInt("rows", 15).coerceIn(1, 15)
            val st = Style.from(root.optJSONObject("style"))
            val mergeLong = root.optBoolean("mergeLong", false)
            val totalPages = root.optInt("totalPages", 0)
            val infoItems = parseInfoItems(root.optJSONArray("infoItems"))
            val pagesArr = root.optJSONArray("pages")

            if (pagesArr == null || pagesArr.length() == 0) {
                return fail("没有选中任何页面")
            }

            val dir = File(baseDir, IMAGE_FOLDER_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                return fail("无法创建目录：" + dir.absolutePath)
            }

            val safeName = sanitize(songName)
            val files = ArrayList<String>()
            val pageCount = if (totalPages > 0) totalPages else pagesArr.length()

            // —— 逐页导出 ——
            val rendered = ArrayList<Bitmap>(pagesArr.length())
            try {
                for (i in 0 until pagesArr.length()) {
                    val pageObj = pagesArr.optJSONObject(i) ?: continue
                    val pageIndex = pageObj.optInt("index", i)
                    val bitmap = drawPage(
                        pageObj, songName, rows, st, pageIndex, pageCount, infoItems
                    ) ?: return fail("第 " + (pageIndex + 1) + " 页绘制失败")

                    val fileName = if (pageIndex == 0) {
                        "$safeName.png"
                    } else {
                        "$safeName-${pageIndex + 1}.png"
                    }
                    writeBitmap(bitmap, File(dir, fileName))
                    files.add(fileName)

                    if (mergeLong) rendered.add(bitmap) else bitmap.recycle()
                }

                // —— 可选：完整长图 ——
                if (mergeLong && rendered.isNotEmpty()) {
                    val longBitmap = mergePages(rendered, st)
                    if (longBitmap != null) {
                        val longName = "$safeName$LONG_SUFFIX.png"
                        writeBitmap(longBitmap, File(dir, longName))
                        files.add(longName)
                        longBitmap.recycle()
                    }
                }
            } finally {
                for (bmp in rendered) {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }

            if (files.isEmpty()) return fail("没有生成任何图片")

            return JSONObject()
                .put("ok", true)
                .put("dir", dir.absolutePath)
                .put("files", JSONArray(files))
        } catch (t: Throwable) {
            return fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun fail(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private fun writeBitmap(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
    }

    // ============================ 长图拼接 ============================

    /**
     * 把所有页上下拼接成一张长图。
     * 若总像素超过 MAX_LONG_PIXELS，则整体等比降采样。
     */
    private fun mergePages(pages: List<Bitmap>, st: Style): Bitmap? {
        if (pages.isEmpty()) return null

        val gap = (st.pad * 0.6f).roundToInt().coerceAtLeast(8)
        var maxW = 0
        var totalH = 0L
        for (bmp in pages) {
            if (bmp.width > maxW) maxW = bmp.width
            totalH += bmp.height.toLong()
        }
        totalH += gap.toLong() * (pages.size - 1)

        if (maxW <= 0 || totalH <= 0) return null

        // 像素上限保护
        var scale = 1f
        val pixels = maxW.toDouble() * totalH.toDouble()
        if (pixels > MAX_LONG_PIXELS) {
            scale = sqrt(MAX_LONG_PIXELS / pixels).toFloat()
        }

        val outW = max(1, (maxW * scale).roundToInt())
        val outH = max(1, (totalH * scale).roundToInt())

        val longBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(longBitmap)

        // 底色，避免降采样后边缘透出
        canvas.drawColor(argb(st.bg, 1f))

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = 0f
        for (bmp in pages) {
            val dstW = bmp.width * scale
            val dstH = bmp.height * scale
            val left = (outW - dstW) / 2f
            val rect = RectF(left, y, left + dstW, y + dstH)
            canvas.drawBitmap(bmp, null, rect, paint)
            y += dstH + gap * scale
        }

        return longBitmap
    }

    // ============================ 尺寸计算 ============================

    private fun gridColsFor(rows: Int): Int = if (rows == 9) 3 else 5

    private fun gridRowsFor(rows: Int): Int = 3

        private fun measure(rows: Int, st: Style, infoItemCount: Int): Size {
        val gCols = gridColsFor(rows)
        val gRows = gridRowsFor(rows)
        val gridW = gCols * st.cell + (gCols - 1) * st.cellGap
        val gridH = gRows * st.cell + (gRows - 1) * st.cellGap
        val timeH = st.timeSize * 2.2f

        val headerH = st.titleSize * 1.4f + 10f + st.metaSize * 1.5f + 26f

        var infoH = 0f
        if (st.showInfoPanel && infoItemCount > 0) {
            val infoRows = (infoItemCount + 1) / 2
            val infoRowH = st.infoSize * 2.0f
            infoH = infoRows * infoRowH + 18f
        }

        val footerH = st.watermarkSize * 2.6f

        val bodyW = st.cols * gridW + (st.cols - 1) * st.gridGapX
        val bodyH = st.rowsPerPage * (gridH + timeH) + (st.rowsPerPage - 1) * st.gridGapY

        return Size(
            width = st.pad * 2f + bodyW,
            height = st.pad * 2f + headerH + infoH + bodyH + footerH,
            headerH = headerH,
            infoH = infoH,
            footerH = footerH,
            bodyH = bodyH,
            gridW = gridW,
            gridH = gridH,
            timeH = timeH,
            gCols = gCols,
            gRows = gRows
        )
    }


    private fun computeScale(width: Float, height: Float): Float {
        var scale = 2f
        val base = width * height
        if (base <= 0f) return 1f
        while (scale > 0.5f && base * scale * scale > MAX_PIXELS) {
            scale -= 0.1f
        }
        return max(0.4f, scale)
    }

    // ============================ 绘制 ============================

    private fun drawPage(
        pageObj: JSONObject,
        songName: String,
        rows: Int,
        st: Style,
        pageIndex: Int,
        pageCount: Int,
        infoItems: List<Pair<String, String>>
    ): Bitmap? {
        val page = parsePage(pageObj, rows)
        val size = measure(rows, st, infoItems.size)
        val scale = computeScale(size.width, size.height)
        val outW = max(1, (size.width * scale).roundToInt())
        val outH = max(1, (size.height * scale).roundToInt())

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // —— 背景 ——
        drawVerticalGradient(canvas, size.width, size.height, st.bg, st.bgGlow)
        if (st.showStars) drawStars(canvas, size.width, size.height)
        drawWatermark(canvas, size.width, size.height, st)

        // —— 标题 ——
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
            textSize = st.titleSize
            color = argb(st.titleColor, 1f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(songName, size.width / 2f, st.pad - titlePaint.fontMetrics.top, titlePaint)

        // —— 页码 ——
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            textSize = st.metaSize
            color = argb(st.metaColor, 1f)
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText(
            "${pageIndex + 1} / $pageCount",
            st.pad,
            st.pad + st.titleSize * 1.4f + 10f - metaPaint.fontMetrics.top,
            metaPaint
        )

        // —— 信息面板 ——
        val infoTop = st.pad + size.headerH
        if (st.showInfoPanel && infoItems.isNotEmpty()) {
            drawInfoPanel(canvas, st.pad, infoTop, size.bodyW, infoItems, st)
        }

        // —— 正文 ——
        val bodyTop = infoTop + size.infoH
        val bodyLeft = st.pad

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            textSize = st.timeSize
            color = argb(st.timeColor, st.timeAlpha)
            textAlign = Paint.Align.CENTER
        }

        for (i in page.columns.indices) {
            val col = page.columns[i]
            val gx = bodyLeft + (i % st.cols) * (size.gridW + st.gridGapX)
            val gy = bodyTop + (i / st.cols) * (size.gridH + size.timeH + st.gridGapY)

            val activeCells = ArrayList<Int>(col.rows.size)
            for (r in col.rows) {
                if (r < 0 || r >= rows) continue
                val gridRow = r / size.gCols
                val gridCol = r % size.gCols
                if (gridRow < size.gRows) activeCells.add(gridRow * size.gCols + gridCol)
            }

            drawKeyGrid(canvas, gx, gy, size.gCols, size.gRows, st, activeCells, fill, stroke)

            canvas.drawText(
                formatMs(col.timeMs),
                gx + size.gridW / 2f,
                gy + size.gridH + 8f - timePaint.fontMetrics.top,
                timePaint
            )
        }

        // —— 页脚 ——
        val footY = st.pad + size.headerH + size.infoH + size.bodyH + size.footerH / 2f
        val logoSize = st.logoSize * 1.6f
        val nameWidth = 90f
        val totalW = logoSize + 8f + nameWidth
        val startX = (size.width - totalW) / 2f

        drawLogo(canvas, startX, footY - logoSize / 2f, logoSize, st, fill, stroke)

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
            textSize = st.logoSize
            color = argb(st.logoColor, 1f)
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText(
            APP_NAME,
            startX + logoSize + 8f,
            footY - (namePaint.fontMetrics.ascent + namePaint.fontMetrics.descent) / 2f,
            namePaint
        )

        return bitmap
    }

    /** 信息面板：两列网格 */
    private fun drawInfoPanel(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        items: List<Pair<String, String>>,
        st: Style
    ) {
        if (items.isEmpty()) return

        val colCount = 2
        val rowCount = (items.size + colCount - 1) / colCount
        val colW = width / colCount
        val rowH = st.infoSize * 2.0f

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            textSize = st.infoSize
            color = argb(st.infoLabelColor, 0.85f)
            textAlign = Paint.Align.LEFT
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
            textSize = st.infoSize
            color = argb(st.infoValueColor, 1f)
            textAlign = Paint.Align.LEFT
        }

        for (i in items.indices) {
            val item = items[i]
            val col = i % colCount
            val row = i / colCount
            val ix = x + col * colW
            val baseline = y + row * rowH + rowH / 2f -
                    (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f

            canvas.drawText(item.first, ix, baseline, labelPaint)
            val labelW = labelPaint.measureText(item.first)
            canvas.drawText(item.second, ix + labelW + 8f, baseline, valuePaint)
        }

        // 分割线
        val lineY = y + rowCount * rowH + 6f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = argb(st.dividerColor, st.dividerAlpha)
        }
        canvas.drawLine(x, lineY, x + width, lineY, linePaint)
    }

    private fun drawKeyGrid(
        canvas: Canvas,
        x: Float,
        y: Float,
        gCols: Int,
        gRows: Int,
        st: Style,
        activeCells: List<Int>,
        fill: Paint,
        stroke: Paint
    ) {
        val block = RectF()

        for (r in 0 until gRows) {
            for (c in 0 until gCols) {
                val bx = x + c * (st.cell + st.cellGap)
                val by = y + r * (st.cell + st.cellGap)
                val isActive = activeCells.contains(r * gCols + c)

                block.set(bx, by, bx + st.cell, by + st.cell)

                if (isActive) {
                    val t = if (gRows > 1) r.toFloat() / (gRows - 1) else 0f
                    fill.color = argb(mix(st.noteFill, st.noteFillAlt, t), 1f)
                    canvas.drawRoundRect(block, st.gridRadius, st.gridRadius, fill)
                    stroke.color = argb(st.noteStroke, st.noteStrokeAlpha)
                    stroke.strokeWidth = 1f
                    canvas.drawRoundRect(block, st.gridRadius, st.gridRadius, stroke)
                } else {
                    stroke.color = argb(st.gridLine, st.gridLineAlpha)
                    stroke.strokeWidth = 1.2f
                    canvas.drawRoundRect(block, st.gridRadius, st.gridRadius, stroke)
                }
            }
        }
    }

    private fun drawWatermark(canvas: Canvas, width: Float, height: Float, st: Style) {
        if (!st.showWatermark) return

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            textSize = st.watermarkSize
            color = argb(st.watermarkColor, st.watermarkAlpha)
            textAlign = Paint.Align.CENTER
        }

        val stepX = st.watermarkSize * 11f
        val stepY = st.watermarkSize * 7f

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(-45f)

        val diag = sqrt(width * width + height * height)
        val halfW = diag / 2f + stepX
        val halfH = diag / 2f + stepY

        var rowIndex = 0
        var y = -halfH
        while (y <= halfH) {
            val offset = if (rowIndex % 2 == 0) 0f else stepX / 2f
            var x = -halfW + offset
            while (x <= halfW) {
                canvas.drawText(WATERMARK_TEXT, x, y, textPaint)
                x += stepX
            }
            y += stepY
            rowIndex += 1
        }

        canvas.restore()
    }

    private fun drawLogo(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        st: Style,
        fill: Paint,
        stroke: Paint
    ) {
        val cx = x + size / 2f
        val cy = y + size / 2f
        val r = size / 2f

        stroke.color = argb(st.logoColor, 0.85f)
        stroke.strokeWidth = 1.6f
        canvas.drawCircle(cx, cy, r - 1f, stroke)

        val path = Path()
        val spikes = 4
        val outer = r * 0.62f
        val inner = r * 0.24f
        for (i in 0 until spikes * 2) {
            val rad = (Math.PI / spikes) * i - Math.PI / 2
            val dist = if (i % 2 == 0) outer else inner
            val px = cx + (cos(rad) * dist).toFloat()
            val py = cy + (sin(rad) * dist).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fill.color = argb(st.logoColor, 1f)
        canvas.drawPath(path, fill)
    }

    private fun drawVerticalGradient(
        canvas: Canvas,
        width: Float,
        height: Float,
        from: Int,
        to: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val steps = 32
        val stepH = height / steps
        for (i in 0 until steps) {
            val t = i.toFloat() / (steps - 1)
            paint.color = argb(mix(from, to, t), 1f)
            canvas.drawRect(0f, i * stepH, width, (i + 1) * stepH + 1f, paint)
        }
    }

    private fun drawStars(canvas: Canvas, width: Float, height: Float) {
        val band = min(height, 900f)
        val area = width * band
        val count = max(12, min(220, (area / 4200f).roundToInt()))
        val rnd = java.util.Random(20260101L)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until count) {
            val x = rnd.nextFloat() * width
            val y = rnd.nextFloat() * band
            val r = rnd.nextFloat() * 1.1f + 0.35f
            val a = rnd.nextFloat() * 0.22f + 0.03f
            starPaint.color = argb(0xE2E8F0, a)
            canvas.drawCircle(x, y, r, starPaint)
        }
    }

    // ============================ 数据解析 ============================

    private class PageData(val columns: List<ColumnData>)

    private class ColumnData(val timeMs: Long, val rows: IntArray)

    private fun parsePage(pageObj: JSONObject, rows: Int): PageData {
        val arr = pageObj.optJSONArray("columns") ?: JSONArray()
        val columns = ArrayList<ColumnData>(arr.length())

        for (i in 0 until arr.length()) {
            val colObj = arr.optJSONObject(i) ?: continue
            val rowsArr = colObj.optJSONArray("rows") ?: JSONArray()
            val values = IntArray(rowsArr.length()) { idx -> rowsArr.optInt(idx, -1) }
            columns.add(
                ColumnData(
                    timeMs = colObj.optLong("t", 0L),
                    rows = values.filter { it in 0 until rows }.toIntArray()
                )
            )
        }

        return PageData(columns)
    }

    private fun parseInfoItems(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val label = obj.optString("label", "").trim()
            val value = obj.optString("value", "").trim()
            if (label.isEmpty()) continue
            out.add(Pair(label, value.ifEmpty { "—" }))
        }
        return out
    }

    // ============================ 颜色与文本工具 ============================

    private fun argb(rgb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    private fun mix(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val fr = (from shr 16) and 0xFF
        val fg = (from shr 8) and 0xFF
        val fb = from and 0xFF
        val tr = (to shr 16) and 0xFF
        val tg = (to shr 8) and 0xFF
        val tb = to and 0xFF
        val r = (fr + (tr - fr) * k).roundToInt().coerceIn(0, 255)
        val g = (fg + (tg - fg) * k).roundToInt().coerceIn(0, 255)
        val b = (fb + (tb - fb) * k).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun formatMs(ms: Long): String {
        val total = if (ms < 0) 0 else ms
        val m = total / 60000
        val s = (total % 60000) / 1000
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun sanitize(name: String): String {
        var s = name.trim()
        if (s.isEmpty()) return "未命名乐谱"
        s = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH).trim()
        if (s.isEmpty()) s = "未命名乐谱"
        if (s == "." || s == "..") s = "未命名乐谱"
        return s
    }

    // ============================ 样式 ============================

    private class Size(
        val width: Float,
        val height: Float,
        val headerH: Float,
        val infoH: Float,
        val footerH: Float,
        val bodyH: Float,
        val gridW: Float,
        val gridH: Float,
        val timeH: Float,
        val gCols: Int,
        val gRows: Int
    )

    private class Style {
        var bg: Int = 0x0B1120
        var bgGlow: Int = 0x1E1B4B
        var gridLine: Int = 0x7DD3FC
        var gridLineAlpha: Float = 0.22f
        var noteFill: Int = 0x7DD3FC
        var noteFillAlt: Int = 0xA78BFA
        var noteStroke: Int = 0xFFFFFF
        var noteStrokeAlpha: Float = 0.40f
        var titleColor: Int = 0xF8FAFC
        var metaColor: Int = 0x94A3B8
        var timeColor: Int = 0xCBD5E1
        var timeAlpha: Float = 0.72f
        var watermarkColor: Int = 0x7DD3FC
        var watermarkAlpha: Float = 0.055f
        var logoColor: Int = 0x7DD3FC
        var infoLabelColor: Int = 0x94A3B8
        var infoValueColor: Int = 0xE2E8F0
        var dividerColor: Int = 0x7DD3FC
        var dividerAlpha: Float = 0.14f

        var cell: Float = 22f
        var cellGap: Float = 4f
        var gridRadius: Float = 5f
        var cols: Int = 5
        var rowsPerPage: Int = 6
        var gridGapX: Float = 26f
        var gridGapY: Float = 30f
        var pad: Float = 40f

        var titleSize: Float = 30f
        var metaSize: Float = 14f
        var timeSize: Float = 13f
        var watermarkSize: Float = 13f
        var logoSize: Float = 15f
        var infoSize: Float = 12f

        var showStars: Boolean = true
        var showWatermark: Boolean = true
        var showInfoPanel: Boolean = true

        companion object {
            fun from(o: JSONObject?): Style {
                val s = Style()
                if (o == null) return s

                s.bg = rgb(o.optString("bg"), s.bg)
                s.bgGlow = rgb(o.optString("bgGlow"), s.bgGlow)
                s.gridLine = rgb(o.optString("gridLine"), s.gridLine)
                s.gridLineAlpha = f(o, "gridLineAlpha", s.gridLineAlpha)
                s.noteFill = rgb(o.optString("noteFill"), s.noteFill)
                s.noteFillAlt = rgb(o.optString("noteFillAlt"), s.noteFillAlt)
                s.noteStroke = rgb(o.optString("noteStroke"), s.noteStroke)
                s.noteStrokeAlpha = f(o, "noteStrokeAlpha", s.noteStrokeAlpha)
                s.titleColor = rgb(o.optString("titleColor"), s.titleColor)
                s.metaColor = rgb(o.optString("metaColor"), s.metaColor)
                s.timeColor = rgb(o.optString("timeColor"), s.timeColor)
                s.timeAlpha = f(o, "timeAlpha", s.timeAlpha)
                s.watermarkColor = rgb(o.optString("watermarkColor"), s.watermarkColor)
                s.watermarkAlpha = f(o, "watermarkAlpha", s.watermarkAlpha)
                s.logoColor = rgb(o.optString("logoColor"), s.logoColor)
                s.infoLabelColor = rgb(o.optString("infoLabelColor"), s.infoLabelColor)
                s.infoValueColor = rgb(o.optString("infoValueColor"), s.infoValueColor)
                s.dividerColor = rgb(o.optString("dividerColor"), s.dividerColor)
                s.dividerAlpha = f(o, "dividerAlpha", s.dividerAlpha)

                s.cell = f(o, "cell", s.cell)
                s.cellGap = f(o, "cellGap", s.cellGap)
                s.gridRadius = f(o, "gridRadius", s.gridRadius)
                s.cols = o.optInt("cols", s.cols).coerceIn(1, 20)
                s.rowsPerPage = o.optInt("rowsPerPage", s.rowsPerPage).coerceIn(1, 20)
                s.gridGapX = f(o, "gridGapX", s.gridGapX)
                s.gridGapY = f(o, "gridGapY", s.gridGapY)
                s.pad = f(o, "pad", s.pad)

                s.titleSize = f(o, "titleSize", s.titleSize)
                s.metaSize = f(o, "metaSize", s.metaSize)
                s.timeSize = f(o, "timeSize", s.timeSize)
                s.watermarkSize = f(o, "watermarkSize", s.watermarkSize)
                s.logoSize = f(o, "logoSize", s.logoSize)
                s.infoSize = f(o, "infoSize", s.infoSize)

                s.showStars = o.optBoolean("showStars", s.showStars)
                s.showWatermark = o.optBoolean("showWatermark", s.showWatermark)
                s.showInfoPanel = o.optBoolean("showInfoPanel", s.showInfoPanel)
                return s
            }

            private fun rgb(value: String?, fallback: Int): Int {
                if (value.isNullOrBlank()) return fallback
                val v = value.trim()
                if (!v.startsWith("#")) return fallback
                return try {
                    val hex = if (v.length == 4) {
                        "#" + v[1] + v[1] + v[2] + v[2] + v[3] + v[3]
                    } else {
                        v
                    }
                    Color.parseColor(hex) and 0x00FFFFFF
                } catch (e: IllegalArgumentException) {
                    fallback
                }
            }

            private fun f(o: JSONObject, key: String, def: Float): Float {
                val v = o.optDouble(key, def.toDouble())
                return if (v.isNaN() || v.isInfinite()) def else v.toFloat()
            }
        }
    }
}
