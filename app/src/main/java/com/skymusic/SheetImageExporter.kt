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
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object SheetImageExporter {

    private const val MAX_NAME_LENGTH = 60
    private const val FONT_STACK = "sans-serif"
    private const val WATERMARK_TEXT = "Bilibili@_OneStar"
    private const val APP_NAME = "SkyMusic"
    private const val LONG_SUFFIX = "-Full"

    fun export(
        baseDir: File,
        payloadJson: String,
        onProgress: ((String, Int) -> Unit)? = null
    ): JSONObject {
        try {
            val root = JSONObject(payloadJson)
            val songName = root.optString("songName", "未命名乐谱").ifBlank { "未命名乐谱" }
            val rows = root.optInt("rows", 15).coerceIn(1, 15)
            val st = Style.from(root.optJSONObject("style"))
            val mergeLong = root.optBoolean("mergeLong", false)
            val longOnly = root.optBoolean("longOnly", false)
            val totalPages = root.optInt("totalPages", 0)
            val infoItems = parseInfoItems(root.optJSONArray("infoItems"))
            val pagesArr = root.optJSONArray("pages")

            if (pagesArr == null || pagesArr.length() == 0) {
                return fail("没有选中任何页面")
            }

            val safeName = sanitize(songName)
            val rootDir = File(baseDir, safeName)
            if (!rootDir.exists() && !rootDir.mkdirs()) return fail("无法创建目录")

            // 只有「分页模式」才创建分页目录
            val pageDir = if (longOnly) null else File(rootDir, "分页")
            if (pageDir != null && !pageDir.exists() && !pageDir.mkdirs()) return fail("无法创建分页目录")

            val files = ArrayList<String>()
            val pageCount = if (totalPages > 0) totalPages else pagesArr.length()
            val total = pagesArr.length()

            val parsedPages = ArrayList<PageData>(total)
            for (i in 0 until total) {
                val pageObj = pagesArr.optJSONObject(i) ?: continue
                parsedPages.add(parsePage(pageObj, rows))
            }

            val size = measure(rows, st, infoItems.size)
            val scale = computeScale(size.width, size.height)

            val rendered = ArrayList<Bitmap>(parsedPages.size)
            try {
                for (i in parsedPages.indices) {
                    val pageIndex = pagesArr.optJSONObject(i)?.optInt("index", i) ?: i
                    onProgress?.invoke("正在绘制第 ${i + 1} / $total 页", (i * 80) / total)

                    val bitmap = drawPageFast(
                        parsedPages[i], songName, rows, st, pageIndex, pageCount,
                        infoItems, size, scale
                    ) ?: return fail("绘制失败")

                    // 只有「分页模式」才写分页 PNG
                    if (!longOnly && pageDir != null) {
                        val fileName = if (pageIndex == 0) "$safeName.png" else "$safeName-${pageIndex + 1}.png"
                        writeBitmap(bitmap, File(pageDir, fileName))
                        files.add(fileName)
                    }

                    if (mergeLong || longOnly) {
                        rendered.add(bitmap)
                    } else {
                        bitmap.recycle()
                        System.gc()
                    }
                }

                if ((mergeLong || longOnly) && rendered.isNotEmpty()) {
                    onProgress?.invoke("正在拼接长图…", 85)
                    val longBitmap = mergePages(rendered, st)
                    if (longBitmap != null) {
                        val longName = "$safeName$LONG_SUFFIX.png"
                        writeBitmap(longBitmap, File(rootDir, longName))
                        files.add(longName)
                        longBitmap.recycle()
                    }
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

    // ============================ 长图拼接 ============================

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

        var scale = 1f
        val pixels = maxW.toDouble() * totalH.toDouble()
        if (pixels > 40_000_000.0) scale = sqrt(40_000_000.0 / pixels).toFloat()

        val outW = max(1, (maxW * scale).roundToInt())
        val outH = max(1, (totalH * scale).roundToInt())

        val longBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(longBitmap)
        canvas.drawColor(argb(st.bg, 1f))

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = 0f
        for (bmp in pages) {
            val dstW = bmp.width * scale
            val dstH = bmp.height * scale
            val left = (outW - dstW) / 2f
            canvas.drawBitmap(bmp, null, RectF(left, y, left + dstW, y + dstH), paint)
            y += dstH + gap * scale
        }
        return longBitmap
    }

    // ============================ 以下为原有绘制逻辑（保持不变） ============================

    private fun parsePage(obj: JSONObject, rows: Int): PageData {
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
        val c = try { Color.parseColor(hex) } catch (_: Exception) { Color.WHITE }
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun computeScale(w: Int, h: Int): Float {
        val pixels = w.toDouble() * h.toDouble()
        return if (pixels > 24_000_000.0) sqrt(24_000_000.0 / pixels).toFloat() else 1f
    }

    private fun measure(rows: Int, st: Style, infoCount: Int): Size {
        val cols = st.cols.coerceAtLeast(1)
        val cell = st.cell
        val gap = st.cellGap
        val gridW = cols * cell + (cols - 1) * gap
        val gridH = rows * cell + (rows - 1) * gap
        val w = st.pad * 2 + gridW
        val h = st.pad * 2 + gridH + 120 + infoCount * 22
        return Size(w, h)
    }

    private fun drawPageFast(
        page: PageData, songName: String, rows: Int, st: Style,
        pageIndex: Int, pageCount: Int, infoItems: List<Pair<String, String>>,
        size: Size, scale: Float
    ): Bitmap? {
        val w = (size.width * scale).roundToInt().coerceAtLeast(1)
        val h = (size.height * scale).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)

        canvas.drawColor(argb(st.bg, 1f))
        drawBgPattern(canvas, size, st)
        drawDeco(canvas, size, st)

        // 标题
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.titleColor, 1f)
            textSize = st.titleSize
            typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
            textAlign = when (st.titleAlign) {
                "left" -> Paint.Align.LEFT
                "right" -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
        }
        val titleX = when (st.titleAlign) {
            "left" -> st.pad.toFloat()
            "right" -> (size.width - st.pad).toFloat()
            else -> size.width / 2f
        }
        canvas.drawText(songName, titleX, st.pad + st.titleSize, titlePaint)

        // 页码
        val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.metaColor, 1f)
            textSize = st.metaSize
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("${pageIndex + 1} / $pageCount", (size.width - st.pad).toFloat(), st.pad + st.titleSize, pagePaint)

        // 网格
        val gridTop = st.pad + st.titleSize + 30f
        drawKeyGrid(canvas, page, rows, st, st.pad.toFloat(), gridTop)

        // 信息面板
        drawInfoPanel(canvas, infoItems, st, size, gridTop + rows * (st.cell + st.cellGap) + 20f)

        // 水印
        if (st.showWatermark) drawWatermark(canvas, size, st)

        return bmp
    }

    private fun drawKeyGrid(canvas: Canvas, page: PageData, rows: Int, st: Style, left: Float, top: Float) {
        val cols = st.cols.coerceAtLeast(1)
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

        // 绘制所有格子
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = left + c * (cell + gap)
                val y = top + r * (cell + gap)
                val rect = RectF(x, y, x + cell, y + cell)
                canvas.drawRoundRect(rect, radius, radius, cellPaint)
            }
        }

        // 绘制激活音符
        for (col in page.columns) {
            for (rowIdx in col.rows) {
                if (rowIdx < 0 || rowIdx >= rows) continue
                val c = (rowIdx % cols)
                val r = (rowIdx / cols)
                if (r >= rows) continue
                val x = left + c * (cell + gap)
                val y = top + r * (cell + gap)
                val rect = RectF(x, y, x + cell, y + cell)

                val paint = if (rowIdx % 2 == 0) activePaint else activeAltPaint

                if (st.noteShape == "skystudio") {
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    // 内部菱形
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
    }

    private fun drawInfoPanel(canvas: Canvas, items: List<Pair<String, String>>, st: Style, size: Size, top: Float) {
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
            canvas.drawText(value, (size.width - st.pad).toFloat(), y, valuePaint)
            y += 22f
        }
    }

    private fun drawBgPattern(canvas: Canvas, size: Size, st: Style) {
        if (st.bgPattern == "none") return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.gridLine, 0.04f)
            strokeWidth = 1f
        }
        when (st.bgPattern) {
            "grid" -> {
                var x = 0f
                while (x < size.width) {
                    canvas.drawLine(x, 0f, x, size.height.toFloat(), paint)
                    x += 40f
                }
                var y = 0f
                while (y < size.height) {
                    canvas.drawLine(0f, y, size.width.toFloat(), y, paint)
                    y += 40f
                }
            }
            "dots" -> {
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = argb(st.gridLine, 0.08f)
                }
                var x = 20f
                while (x < size.width) {
                    var y = 20f
                    while (y < size.height) {
                        canvas.drawCircle(x, y, 1.5f, dotPaint)
                        y += 40f
                    }
                    x += 40f
                }
            }
            "diagonal" -> {
                var i = -size.height
                while (i < size.width) {
                    canvas.drawLine(i.toFloat(), 0f, (i + size.height).toFloat(), size.height.toFloat(), paint)
                    i += 40
                }
            }
        }
    }

    private fun drawDeco(canvas: Canvas, size: Size, st: Style) {
        if (st.decoType == "none") return
        val rnd = Random(42)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.gridLine, 0.15f)
        }
        when (st.decoType) {
            "stars" -> {
                repeat(30) {
                    val x = rnd.nextFloat() * size.width
                    val y = rnd.nextFloat() * size.height
                    val r = 1f + rnd.nextFloat() * 2f
                    canvas.drawCircle(x, y, r, paint)
                }
            }
            "snow" -> {
                repeat(40) {
                    val x = rnd.nextFloat() * size.width
                    val y = rnd.nextFloat() * size.height
                    canvas.drawCircle(x, y, 2f, paint)
                }
            }
            "bubbles" -> {
                repeat(15) {
                    val x = rnd.nextFloat() * size.width
                    val y = rnd.nextFloat() * size.height
                    val r = 8f + rnd.nextFloat() * 20f
                    canvas.drawCircle(x, y, r, paint)
                }
            }
            "geometric" -> {
                repeat(10) {
                    val x = rnd.nextFloat() * size.width
                    val y = rnd.nextFloat() * size.height
                    val s = 10f + rnd.nextFloat() * 20f
                    canvas.drawRect(x, y, x + s, y + s, paint)
                }
            }
        }
    }

    private fun drawWatermark(canvas: Canvas, size: Size, st: Style) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.watermarkColor, st.watermarkAlpha)
            textSize = st.watermarkSize
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(WATERMARK_TEXT, size.width / 2f, size.height - st.pad / 2f, paint)
    }

    // ============================ 数据类 ============================

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
        companion object {
            fun from(o: JSONObject?): Style {
                if (o == null) return defaults()
                return Style(
                    bg = o.optString("bg", "#0B1120"),
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
                    cols = o.optInt("cols", 5),
                    rowsPerPage = o.optInt("rowsPerPage", 6),
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
