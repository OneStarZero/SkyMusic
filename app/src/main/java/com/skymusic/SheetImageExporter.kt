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
import java.util.Random
import kotlin.math.max
import kotlin.math.roundToInt

object SheetImageExporter {

    private const val MAX_NAME_LENGTH = 60
    private const val FONT_STACK = "sans-serif"
    private const val WATERMARK_TEXT = "Bilibili@_OneStar"
    private const val APP_NAME = "SkyMusic"
    private const val LONG_SUFFIX = "-Full"
    private const val FALLBACK_BG = "#0B1120"
    private const val SIZE_MULT = 5f
    private const val MAX_LONG_HEIGHT = 100000

    fun export(
        baseDir: File,
        payloadJson: String,
        onProgress: ((String, Int) -> Unit)? = null
    ): JSONObject {
        try {
            val root = JSONObject(payloadJson)
            val songName = root.optString("songName", "未命名乐谱").ifBlank { "未命名乐谱" }
            val rows = root.optInt("rows", 15).coerceIn(1, 15)
            val cols = root.optInt("cols", 8).coerceIn(3, 12)
            val rowsPerPage = root.optInt("rowsPerPage", 15).coerceIn(6, 20)
            val st = Style.from(root.optJSONObject("style")).copy(cols = cols, rowsPerPage = rowsPerPage)
            val mergeLong = root.optBoolean("mergeLong", false)
            val longOnly = root.optBoolean("longOnly", false)
            val totalPages = root.optInt("totalPages", 0)
            val infoItems = parseInfoItems(root.optJSONArray("infoItems"))
            val pagesArr = root.optJSONArray("pages")

            if (pagesArr == null || pagesArr.length() == 0) return fail("没有选中任何页面")

            val safeName = sanitize(songName)
            val rootDir = File(baseDir, safeName)
            if (!rootDir.exists() && !rootDir.mkdirs()) return fail("无法创建目录")

            val pageDir = if (longOnly) null else File(rootDir, "分页")
            if (pageDir != null && !pageDir.exists() && !pageDir.mkdirs()) return fail("无法创建分页目录")

            val files = ArrayList<String>()
            val pageCount = if (totalPages > 0) totalPages else pagesArr.length()
            val total = pagesArr.length()

            val parsedPages = ArrayList<PageData>(total)
            for (i in 0 until total) {
                val pageObj = pagesArr.optJSONObject(i) ?: continue
                parsedPages.add(parsePage(pageObj))
            }

            val size = measure(rows, st, infoItems.size)

            val rendered = ArrayList<Bitmap>(parsedPages.size)
            try {
                for (i in parsedPages.indices) {
                    val pageIndex = pagesArr.optJSONObject(i)?.optInt("index", i) ?: i
                    onProgress?.invoke("正在绘制第 ${i + 1} / $total 页", (i * 80) / total)

                    val isFirst = (i == 0)
                    val isLongMode = (longOnly || mergeLong)

                    val bitmap = drawPage(
                        parsedPages[i], songName, rows, st, pageIndex, pageCount,
                        infoItems, size,
                        showTitle = true,
                        showInfo = if (isLongMode) isFirst else true,
                        showWatermark = true
                    ) ?: return fail("绘制失败")

                    if (!longOnly && pageDir != null) {
                        val fileName = if (pageIndex == 0) "$safeName.png" else "$safeName-${pageIndex + 1}.png"
                        writeBitmap(bitmap, File(pageDir, fileName))
                        files.add(fileName)
                    }

                    if (mergeLong || longOnly) {
                        rendered.add(bitmap)
                    } else {
                        bitmap.recycle()
                    }
                }

                if ((mergeLong || longOnly) && rendered.isNotEmpty()) {
                    onProgress?.invoke("正在拼接长图…", 85)
                    val longBitmap = mergePages(rendered, st)
                    if (longBitmap == null) {
                        return fail("长图过高（超过 ${MAX_LONG_HEIGHT}px），请减少页数或行数")
                    }
                    val longName = "$safeName$LONG_SUFFIX.png"
                    writeBitmap(longBitmap, File(rootDir, longName))
                    files.add(longName)
                    longBitmap.recycle()
                }
                onProgress?.invoke("完成", 100)
            } finally {
                for (bmp in rendered) { if (!bmp.isRecycled) bmp.recycle() }
            }

            return JSONObject().put("ok", true).put("dir", rootDir.absolutePath).put("files", JSONArray(files))
        } catch (t: Throwable) {
            return fail(t.message ?: "未知错误")
        }
    }

    private fun fail(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private fun writeBitmap(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
    }

    private fun mergePages(pages: List<Bitmap>, st: Style): Bitmap? {
        if (pages.isEmpty()) return null

        val gap = (st.pad * 0.6f * SIZE_MULT).roundToInt().coerceAtLeast(8)
        var maxW = 0
        var totalH = 0L
        for (bmp in pages) {
            if (bmp.width > maxW) maxW = bmp.width
            totalH += bmp.height.toLong()
        }
        totalH += gap.toLong() * (pages.size - 1)
        if (maxW <= 0 || totalH <= 0) return null
        if (totalH > MAX_LONG_HEIGHT) return null

        val outW = maxW
        val outH = totalH.toInt()

        val longBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(longBitmap)
        canvas.drawColor(argb(st.bg, 1f))

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = 0f
        for (bmp in pages) {
            val left = (outW - bmp.width) / 2f
            canvas.drawBitmap(bmp, left, y, paint)
            y += bmp.height + gap
        }

        drawTiledWatermark(canvas, outW, outH, st)

        return longBitmap
    }

    private fun drawTiledWatermark(canvas: Canvas, w: Int, h: Int, st: Style) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.watermarkColor, 0.035f)
            textSize = 36f * SIZE_MULT
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
        }
        val stepX = 380f * SIZE_MULT
        val stepY = 260f * SIZE_MULT
        var y = 80f * SIZE_MULT
        while (y < h) {
            var x = 80f * SIZE_MULT
            while (x < w) {
                canvas.save()
                canvas.rotate(-30f, x, y)
                canvas.drawText(WATERMARK_TEXT, x, y, paint)
                canvas.restore()
                x += stepX
            }
            y += stepY
        }
    }

    private fun parsePage(obj: JSONObject): PageData {
        val columns = ArrayList<ColumnData>()
        val arr = obj.optJSONArray("columns") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val t = c.optLong("t", 0L)
            val rowsArr = c.optJSONArray("rows") ?: JSONArray()
            val rowList = ArrayList<Int>()
            for (j in 0 until rowsArr.length()) {
                rowList.add(rowsArr.optInt(j, -1))
            }
            columns.add(ColumnData(t, rowList))
        }
        return PageData(columns)
    }

    private fun parseInfoItems(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val list = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(Pair(o.optString("label", ""), o.optString("value", "")))
        }
        return list
    }

    private fun sanitize(name: String): String {
        var s = name.trim()
        if (s.isEmpty()) s = "未命名乐谱"
        s = s.replace(Regex("[\\\\/:*?\"<>\\r\\n\\t]"), "_")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH).trim()
        return if (s.isEmpty() || s == "." || s == "..") "未命名乐谱" else s
    }

    private fun argb(hex: String, alpha: Float): Int {
        val c = parseColorSafe(hex)
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun parseColorSafe(input: String): Int {
        val s = input.trim()
        if (s.isEmpty()) return Color.parseColor(FALLBACK_BG)

        if (s.startsWith("hsl", ignoreCase = true)) {
            val nums = Regex("""[\d.]+""").findAll(s).map { it.value.toFloat() }.toList()
            if (nums.size >= 3) return hslToColor(nums[0] / 360f, nums[1] / 100f, nums[2] / 100f)
            return Color.parseColor(FALLBACK_BG)
        }

        if (s.startsWith("rgb", ignoreCase = true)) {
            val nums = Regex("""[\d.]+""").findAll(s).map { it.value.toInt() }.toList()
            if (nums.size >= 3) {
                return Color.rgb(
                    nums[0].coerceIn(0, 255),
                    nums[1].coerceIn(0, 255),
                    nums[2].coerceIn(0, 255)
                )
            }
            return Color.parseColor(FALLBACK_BG)
        }

        return try {
            Color.parseColor(s)
        } catch (_: Exception) {
            Color.parseColor(FALLBACK_BG)
        }
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Int {
        if (s == 0f) {
            val v = (l * 255).toInt().coerceIn(0, 255)
            return Color.rgb(v, v, v)
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val r = hue2rgb(p, q, h + 1f / 3f)
        val g = hue2rgb(p, q, h)
        val b = hue2rgb(p, q, h - 1f / 3f)
        return Color.rgb(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255)
        )
    }

    private fun hue2rgb(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    private fun measure(rows: Int, st: Style, infoCount: Int): Size {
        val gCols = if (rows == 9) 3 else 5
        val gRows = 3
        val gridW = gCols * st.cell + (gCols - 1) * st.cellGap
        val gridH = gRows * st.cell + (gRows - 1) * st.cellGap
        val timeH = (st.timeSize * 2.2f).roundToInt()

        val headerH = (st.titleSize * 1.4f + 10 + st.metaSize * 1.5f + 26).roundToInt()

        var infoH = 0
        if (st.showInfoPanel && infoCount > 0) {
            val infoRows = (infoCount + 1) / 2
            val infoRowH = (st.infoSize * 2.0f).roundToInt()
            infoH = infoRows * infoRowH + 28
        }

        val footerH = (st.watermarkSize * 2.6f).roundToInt()

        val cc = st.cols.coerceAtLeast(1)
        val rpp = st.rowsPerPage.coerceAtLeast(1)

        val bodyW = cc * gridW + (cc - 1) * st.gridGapX
        val bodyH = rpp * (gridH + timeH) + (rpp - 1) * st.gridGapY

        val w = ((st.pad * 2 + bodyW) * SIZE_MULT).roundToInt()
        val h = ((st.pad * 2 + headerH + infoH + bodyH + footerH) * SIZE_MULT).roundToInt()
        return Size(w, h)
    }

    private fun drawPage(
        page: PageData, songName: String, rows: Int, st: Style,
        pageIndex: Int, pageCount: Int, infoItems: List<Pair<String, String>>,
        size: Size,
        showTitle: Boolean,
        showInfo: Boolean,
        showWatermark: Boolean
    ): Bitmap? {
        val w = size.width
        val h = size.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        canvas.drawColor(argb(st.bg, 1f))
        canvas.save()
        canvas.scale(SIZE_MULT, SIZE_MULT)

        val logicalW = size.width / SIZE_MULT
        val logicalH = size.height / SIZE_MULT

        drawBgPattern(canvas, logicalW, logicalH, st)
        drawDeco(canvas, logicalW, logicalH, st)

        val pageText = "${pageIndex + 1} / $pageCount"
        val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.metaColor, 1f)
            textSize = st.metaSize
            textAlign = Paint.Align.RIGHT
        }
        val pageWidth = pagePaint.measureText(pageText)

        var titleSize = st.titleSize
        if (showTitle) {
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(st.titleColor, 1f)
                textSize = st.titleSize
                typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val maxTitleWidth = logicalW - st.pad * 2 - pageWidth - 16f
            while (titlePaint.measureText(songName) > maxTitleWidth && titleSize > 10f) {
                titleSize -= 1f
                titlePaint.textSize = titleSize
            }
            canvas.drawText(songName, logicalW / 2f, st.pad + titleSize, titlePaint)
        }

        val pageY = if (showTitle) st.pad + titleSize + st.metaSize + 8f else st.pad + st.metaSize
        canvas.drawText(pageText, logicalW - st.pad, pageY, pagePaint)

        val gCols = if (rows == 9) 3 else 5
        val gRows = 3
        val gridW = gCols * st.cell + (gCols - 1) * st.cellGap
        val gridH = gRows * st.cell + (gRows - 1) * st.cellGap
        val timeH = st.timeSize * 2.2f

        val bodyTop = pageY + 26f
        val bodyLeft = st.pad.toFloat()

        val cc = st.cols.coerceAtLeast(1)
        val rpp = st.rowsPerPage.coerceAtLeast(1)

        for (idx in page.columns.indices) {
            val col = page.columns[idx]
            val cx = idx % cc
            val cy = idx / cc
            if (cy >= rpp) break

            val cellLeft = bodyLeft + cx * (gridW + st.gridGapX)
            val cellTop = bodyTop + cy * (gridH + timeH + st.gridGapY)

            drawMiniGrid(canvas, col, rows, st, cellLeft, cellTop, gCols, gRows)

            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(st.timeColor, st.timeAlpha)
                textSize = st.timeSize
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(formatMs(col.t), cellLeft + gridW / 2f, cellTop + gridH + timeH * 0.8f, timePaint)
        }

        if (showInfo) {
            val infoTop = bodyTop + rpp * (gridH + timeH) + (rpp - 1) * st.gridGapY + 20f
            drawInfoPanel(canvas, infoItems, st, logicalW, infoTop)
        }

        if (showWatermark && st.showWatermark) {
            drawWatermark(canvas, logicalW, logicalH, st)
        }

        canvas.restore()
        return bmp
    }

    private fun drawMiniGrid(
        canvas: Canvas, col: ColumnData, rows: Int, st: Style,
        left: Float, top: Float, gCols: Int, gRows: Int
    ) {
        val cell = st.cell
        val gap = st.cellGap
        val radius = st.gridRadius.toFloat()

        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = argb(st.gridLine, st.gridLineAlpha)
        }
        val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = argb(st.noteFill, 1f)
        }
        val activeAltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = argb(st.noteFillAlt, 1f)
        }

        for (r in 0 until gRows) {
            for (c in 0 until gCols) {
                val x = left + c * (cell + gap)
                val y = top + r * (cell + gap)
                canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), radius, radius, cellPaint)
            }
        }

        for (rowIdx in col.rows) {
            if (rowIdx < 0 || rowIdx >= rows) continue
            val c = rowIdx % gCols
            val r = rowIdx / gCols
            if (r >= gRows) continue
            val x = left + c * (cell + gap)
            val y = top + r * (cell + gap)
            val rect = RectF(x, y, x + cell, y + cell)
            val paint = if (rowIdx % 2 == 0) activePaint else activeAltPaint

            if (st.noteShape == "skystudio") {
                canvas.drawRoundRect(rect, radius, radius, paint)
                val diamondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.argb(217, 11, 17, 32)
                }
                val cx = x + cell / 2f
                val cy = y + cell / 2f
                val d = cell * 0.28f
                val path = Path().apply {
                    moveTo(cx, cy - d)
                    lineTo(cx + d, cy)
                    lineTo(cx, cy + d)
                    lineTo(cx - d, cy)
                    close()
                }
                canvas.drawPath(path, diamondPaint)
            } else {
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private fun formatMs(ms: Long): String {
        val t = max(0, ms)
        val m = t / 60000
        val s = (t % 60000) / 1000
        return "$m:" + (if (s < 10) "0$s" else "$s")
    }

    private fun drawInfoPanel(canvas: Canvas, items: List<Pair<String, String>>, st: Style, logicalW: Float, top: Float) {
        if (items.isEmpty()) return
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.infoLabelColor, 1f)
            textSize = st.infoSize
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.infoValueColor, 1f)
            textSize = st.infoSize
            textAlign = Paint.Align.RIGHT
        }
        var y = top
        for ((label, value) in items) {
            canvas.drawText(label, st.pad.toFloat(), y, labelPaint)
            canvas.drawText(value, logicalW - st.pad, y, valuePaint)
            y += 22f
        }
    }

    private fun drawBgPattern(canvas: Canvas, w: Float, h: Float, st: Style) {
        if (st.bgPattern == "none") return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.gridLine, 0.04f)
            strokeWidth = 1f
        }
        when (st.bgPattern) {
            "grid" -> {
                var x = 0f
                while (x < w) { canvas.drawLine(x, 0f, x, h, paint); x += 40f }
                var y = 0f
                while (y < h) { canvas.drawLine(0f, y, w, y, paint); y += 40f }
            }
            "dots" -> {
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(st.gridLine, 0.08f) }
                var x = 20f
                while (x < w) {
                    var y = 20f
                    while (y < h) { canvas.drawCircle(x, y, 1.5f, dotPaint); y += 40f }
                    x += 40f
                }
            }
            "diagonal" -> {
                var i = -h.toInt()
                while (i < w) {
                    canvas.drawLine(i.toFloat(), 0f, i + h, h, paint)
                    i += 40
                }
            }
        }
    }

    private fun drawDeco(canvas: Canvas, w: Float, h: Float, st: Style) {
        if (st.decoType == "none") return
        val rnd = Random(42)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(st.gridLine, 0.15f) }
        when (st.decoType) {
            "stars" -> repeat(30) {
                canvas.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 1f + rnd.nextFloat() * 2f, paint)
            }
            "snow" -> repeat(40) {
                canvas.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 2f, paint)
            }
            "bubbles" -> repeat(15) {
                canvas.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 8f + rnd.nextFloat() * 20f, paint)
            }
            "geometric" -> repeat(10) {
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h
                val s = 10f + rnd.nextFloat() * 20f
                canvas.drawRect(x, y, x + s, y + s, paint)
            }
        }
    }

    private fun drawWatermark(canvas: Canvas, logicalW: Float, logicalH: Float, st: Style) {
        val baseY = logicalH - st.pad / 2f

        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.logoColor, 0.55f)
            textSize = st.logoSize
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
        }
        canvas.drawText(APP_NAME, st.pad.toFloat(), baseY, logoPaint)

        val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.watermarkColor, st.watermarkAlpha)
            textSize = st.watermarkSize
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(WATERMARK_TEXT, logicalW - st.pad, baseY, wmPaint)
    }

    data class ColumnData(val t: Long, val rows: List<Int>)
    data class PageData(val columns: List<ColumnData>)
    data class Size(val width: Int, val height: Int)

    class Style(
        val bg: String, val bgGlow: String, val gridLine: String, val gridLineAlpha: Float,
        val noteFill: String, val noteFillAlt: String, val noteStroke: String, val noteStrokeAlpha: Float,
        val titleColor: String, val metaColor: String, val timeColor: String, val timeAlpha: Float,
        val watermarkColor: String, val watermarkAlpha: Float, val logoColor: String,
        val infoLabelColor: String, val infoValueColor: String,
        val dividerColor: String, val dividerAlpha: Float,
        val cell: Int, val cellGap: Int, val gridRadius: Int, val cols: Int, val rowsPerPage: Int,
        val gridGapX: Int, val gridGapY: Int, val pad: Int,
        val titleSize: Float, val metaSize: Float, val timeSize: Float,
        val watermarkSize: Float, val logoSize: Float, val infoSize: Float,
        val showStars: Boolean, val showWatermark: Boolean, val showInfoPanel: Boolean, val showTime: Boolean,
        val decoType: String, val bgPattern: String, val noteShape: String, val titleAlign: String
    ) {
        fun copy(cols: Int = this.cols, rowsPerPage: Int = this.rowsPerPage): Style {
            return Style(
                bg = bg, bgGlow = bgGlow, gridLine = gridLine, gridLineAlpha = gridLineAlpha,
                noteFill = noteFill, noteFillAlt = noteFillAlt, noteStroke = noteStroke, noteStrokeAlpha = noteStrokeAlpha,
                titleColor = titleColor, metaColor = metaColor, timeColor = timeColor, timeAlpha = timeAlpha,
                watermarkColor = watermarkColor, watermarkAlpha = watermarkAlpha, logoColor = logoColor,
                infoLabelColor = infoLabelColor, infoValueColor = infoValueColor,
                dividerColor = dividerColor, dividerAlpha = dividerAlpha,
                cell = cell, cellGap = cellGap, gridRadius = gridRadius, cols = cols, rowsPerPage = rowsPerPage,
                gridGapX = gridGapX, gridGapY = gridGapY, pad = pad,
                titleSize = titleSize, metaSize = metaSize, timeSize = timeSize,
                watermarkSize = watermarkSize, logoSize = logoSize, infoSize = infoSize,
                showStars = showStars, showWatermark = showWatermark, showInfoPanel = showInfoPanel, showTime = showTime,
                decoType = decoType, bgPattern = bgPattern, noteShape = noteShape, titleAlign = titleAlign
            )
        }

        companion object {
            fun from(o: JSONObject?): Style {
                if (o == null) return defaults()
                return Style(
                    bg = o.optString("bg", FALLBACK_BG),
                    bgGlow = o.optString("bgGlow", "#1E1B4B"),
                    gridLine = o.optString("gridLine", "#7DD3FC"),
                    gridLineAlpha = o.optDouble("gridLineAlpha", 0.22).toFloat(),
                    noteFill = o.optString("noteFill", "#7DD3FC"),
                    noteFillAlt = o.optString("noteFillAlt", "#A78BFA"),
                    noteStroke = o.optString("noteStroke", "#FFFFFF"),
                    noteStrokeAlpha = o.optDouble("noteStrokeAlpha", 0.40).toFloat(),
                    titleColor = o.optString("titleColor", "#F8FAFC"),
                    metaColor = o.optString("metaColor", "#94A3B8"),
                    timeColor = o.optString("timeColor", "#CBD5E1"),
                    timeAlpha = o.optDouble("timeAlpha", 0.72).toFloat(),
                    watermarkColor = o.optString("watermarkColor", "#7DD3FC"),
                    watermarkAlpha = o.optDouble("watermarkAlpha", 0.055).toFloat(),
                    logoColor = o.optString("logoColor", "#7DD3FC"),
                    infoLabelColor = o.optString("infoLabelColor", "#94A3B8"),
                    infoValueColor = o.optString("infoValueColor", "#E2E8F0"),
                    dividerColor = o.optString("dividerColor", "#7DD3FC"),
                    dividerAlpha = o.optDouble("dividerAlpha", 0.14).toFloat(),
                    cell = o.optInt("cell", 22),
                    cellGap = o.optInt("cellGap", 4),
                    gridRadius = o.optInt("gridRadius", 5),
                    cols = o.optInt("cols", 8),
                    rowsPerPage = o.optInt("rowsPerPage", 15),
                    gridGapX = o.optInt("gridGapX", 26),
                    gridGapY = o.optInt("gridGapY", 30),
                    pad = o.optInt("pad", 40),
                    titleSize = o.optDouble("titleSize", 30.0).toFloat(),
                    metaSize = o.optDouble("metaSize", 14.0).toFloat(),
                    timeSize = o.optDouble("timeSize", 13.0).toFloat(),
                    watermarkSize = o.optDouble("watermarkSize", 13.0).toFloat(),
                    logoSize = o.optDouble("logoSize", 15.0).toFloat(),
                    infoSize = o.optDouble("infoSize", 12.0).toFloat(),
                    showStars = o.optBoolean("showStars", true),
                    showWatermark = o.optBoolean("showWatermark", true),
                    showInfoPanel = o.optBoolean("showInfoPanel", true),
                    showTime = o.optBoolean("showTime", true),
                    decoType = o.optString("decoType", "none"),
                    bgPattern = o.optString("bgPattern", "none"),
                    noteShape = o.optString("noteShape", "square"),
                    titleAlign = o.optString("titleAlign", "center")
                )
            }

            fun defaults(): Style = from(null)
        }
    }
}
