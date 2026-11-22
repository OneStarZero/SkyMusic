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
import kotlin.math.max
import kotlin.math.roundToInt

object SheetImageExporter {

    private const val MAX_NAME_LENGTH = 60
    private const val FONT_STACK = "sans-serif"
    private const val WATERMARK_TEXT = "Bilibili@_OneStar"
    private const val WATERMARK_UID = "Bilibili UID: 1283676246"
    private const val APP_NAME = "SkyMusic"
    private const val LONG_SUFFIX = "-Full"
    private const val FALLBACK_BG = "#0B1120"
    private const val SIZE_MULT = 3f
    private const val TRIM_PADDING = 40

    // ============================ 数据模型 ============================

    data class Size(val width: Int, val height: Int)

    data class Style(
        val bg: String,
        val fg: String,
        val accent: String,
        val sub: String,
        val grid: String,
        val noteBg: String,
        val noteFg: String,
        val pad: Float,
        val cell: Float,
        val cellGap: Float,
        val cols: Int,
        val rowsPerPage: Int,
        val titleSize: Float,
        val metaSize: Float,
        val timeSize: Float,
        val infoSize: Float,
        val watermarkSize: Float,
        val logoSize: Float,
        val gridRadius: Float,
        val noteShape: String
    )

    data class Note(val time: Float, val key: String)

    data class PageData(
        val index: Int,
        val startTime: Float,
        val endTime: Float,
        val notes: List<Note>
    )

    // ============================ 入口 ============================

    fun export(
        baseDir: File,
        payloadJson: String,
        onProgress: ((String, Int) -> Unit)? = null
    ): JSONObject {
        val result = JSONObject()
        try {
            val payload = JSONObject(payloadJson)

            val songName = sanitizeName(payload.optString("songName", "未命名"))
            val author = payload.optString("author", "")
            val modelName = payload.optString("modelName", "")
            val bpm = payload.optInt("bpm", 0)
            val keyCount = payload.optInt("keyCount", 15)
            val longOnly = payload.optBoolean("longOnly", false)
            val st = parseStyle(payload.optJSONObject("style"))
            val notes = parseNotes(payload.optJSONArray("notes"))
            val rows = payload.optInt("rows", st.rowsPerPage).coerceIn(1, 30)
            val cols = payload.optInt("cols", st.cols).coerceIn(2, 8)

            if (notes.isEmpty()) {
                return result.put("ok", false).put("error", "没有可导出的音符")
            }

            val style = st.copy(cols = cols, rowsPerPage = rows)

            onProgress?.invoke("正在计算布局…", 5)

            val pages = buildPages(notes, style)
            val pageCount = pages.size
            val infoItems = buildInfoItems(songName, author, modelName, bpm, keyCount, notes.size, pageCount)
            val size = measurePage(style, infoItems)

            val sheetDir = File(baseDir, songName)
            if (!sheetDir.exists()) sheetDir.mkdirs()

            val savedFiles = JSONArray()

            // ---------- 分页导出 ----------
            if (!longOnly) {
                val pageDir = File(sheetDir, "分页")
                if (!pageDir.exists()) pageDir.mkdirs()

                for (i in pages.indices) {
                    onProgress?.invoke("正在导出第 ${i + 1} / $pageCount 页", 10 + (i * 60) / pageCount)

                    val bmp = drawPage(pages[i], songName, rows, style, i, pageCount, infoItems, size)
                        ?: return result.put("ok", false).put("error", "绘制第 ${i + 1} 页失败")

                    val fileName = if (i == 0) "$songName.png" else "$songName-${i + 1}.png"
                    val target = File(pageDir, fileName)
                    writeBitmap(bmp, target)
                    bmp.recycle()
                    savedFiles.put(target.absolutePath)
                }
            }

            // ---------- 长图导出 ----------
            onProgress?.invoke("正在生成长图…", 75)
            val longTarget = File(sheetDir, "$songName$LONG_SUFFIX.png")
            val longOk = writeLongImage(
                longTarget, pages, songName, rows, style, pageCount, infoItems, size, onProgress
            )
            if (longOk) savedFiles.put(longTarget.absolutePath)

            onProgress?.invoke("完成", 100)

            return result
                .put("ok", true)
                .put("dir", sheetDir.absolutePath)
                .put("files", savedFiles)
                .put("pageCount", pageCount)
        } catch (oom: OutOfMemoryError) {
            return result.put("ok", false).put("error", "内存不足，请减少页数或行数")
        } catch (e: Throwable) {
            return result.put("ok", false).put("error", e.message ?: "未知错误")
        }
    }

    // ============================ 长图 ============================

    private fun writeLongImage(
        target: File,
        pages: List<PageData>,
        songName: String,
        rows: Int,
        st: Style,
        pageCount: Int,
        infoItems: List<Pair<String, String>>,
        size: Size,
        onProgress: ((String, Int) -> Unit)?
    ): Boolean {
        if (pages.isEmpty()) return false

        val gap = (st.pad * 0.6f * SIZE_MULT).roundToInt().coerceAtLeast(8)
        val pageH = size.height
        val totalH = pageH * pages.size + gap * (pages.size - 1)
        val outW = size.width

        try {
            val longBitmap = Bitmap.createBitmap(outW, totalH, Bitmap.Config.RGB_565)
            val canvas = Canvas(longBitmap)
            canvas.drawColor(argb(st.bg, 1f))

            var y = 0f
            for (i in pages.indices) {
                onProgress?.invoke("正在绘制第 ${i + 1} / ${pages.size} 页", 10 + (i * 70) / pages.size)

                val pageBmp = drawPage(pages[i], songName, rows, st, i, pageCount, infoItems, size)
                    ?: return false

                canvas.drawBitmap(pageBmp, 0f, y, null)
                pageBmp.recycle()
                y += pageH + gap
            }

            drawTiledWatermark(canvas, outW, totalH, st)

            val trimmedH = trimBottomBlank(longBitmap, argb(st.bg, 1f))
            val finalBitmap = if (trimmedH < totalH) {
                Bitmap.createBitmap(longBitmap, 0, 0, outW, trimmedH)
            } else {
                longBitmap
            }

            onProgress?.invoke("正在保存…", 90)
            writeBitmap(finalBitmap, target)
            if (finalBitmap !== longBitmap) finalBitmap.recycle()
            longBitmap.recycle()
            return true
        } catch (oom: OutOfMemoryError) {
            onProgress?.invoke("内存不足，请减少页数或行数", 0)
            return false
        }
    }

    // ============================ 末尾空白裁剪 ============================

    private fun trimBottomBlank(bitmap: Bitmap, bgColor: Int): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return h

        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)
        val threshold = 12

        val scanStart = (h * 0.75).toInt()
        val step = 4

        var lastContentY = h - 1

        var y = h - 1
        while (y >= scanStart) {
            if (rowHasContent(bitmap, y, w, bgR, bgG, bgB, threshold)) {
                lastContentY = y
                break
            }
            y -= step
        }

        if (lastContentY == h - 1 && !rowHasContent(bitmap, h - 1, w, bgR, bgG, bgB, threshold)) {
            y = scanStart
            while (y >= 0) {
                if (rowHasContent(bitmap, y, w, bgR, bgG, bgB, threshold)) {
                    lastContentY = y
                    break
                }
                y -= step
            }
        }

        val padded = lastContentY + TRIM_PADDING
        return padded.coerceIn(1, h)
    }

    private fun rowHasContent(
        bitmap: Bitmap,
        y: Int,
        w: Int,
        bgR: Int,
        bgG: Int,
        bgB: Int,
        threshold: Int
    ): Boolean {
        val stepX = 3
        var x = 0
        while (x < w) {
            val c = bitmap.getPixel(x, y)
            val dr = kotlin.math.abs(Color.red(c) - bgR)
            val dg = kotlin.math.abs(Color.green(c) - bgG)
            val db = kotlin.math.abs(Color.blue(c) - bgB)
            if (dr > threshold || dg > threshold || db > threshold) return true
            x += stepX
        }
        return false
    }

    // ============================ 单页绘制 ============================

    private fun drawPage(
        page: PageData,
        songName: String,
        rows: Int,
        st: Style,
        pageIndex: Int,
        pageCount: Int,
        infoItems: List<Pair<String, String>>,
        size: Size
    ): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(argb(st.bg, 1f))

            val pad = st.pad * SIZE_MULT
            var y = pad

            // ---------- 标题 ----------
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(st.fg, 1f)
                textSize = st.titleSize * SIZE_MULT
                typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
            }
            var titleSize = st.titleSize * SIZE_MULT
            val maxTitleW = size.width - pad * 2
            while (titlePaint.measureText(songName) > maxTitleW && titleSize > 12f) {
                titleSize -= 1f
                titlePaint.textSize = titleSize
            }
            canvas.drawText(songName, pad, y + titleSize, titlePaint)
            y += titleSize + st.metaSize * SIZE_MULT * 0.8f

            // ---------- 页码 ----------
            val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(st.sub, 1f)
                textSize = st.metaSize * SIZE_MULT
                typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            }
            val pageText = "第 ${pageIndex + 1} / $pageCount 页"
            canvas.drawText(pageText, pad, y + pagePaint.textSize, pagePaint)
            y += pagePaint.textSize + st.metaSize * SIZE_MULT * 0.6f

            // ---------- 信息面板 ----------
            if (infoItems.isNotEmpty()) {
                val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = argb(st.sub, 1f)
                    textSize = st.infoSize * SIZE_MULT
                    typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
                }
                val lineH = infoPaint.textSize * 1.5f
                for ((k, v) in infoItems) {
                    canvas.drawText("$k：$v", pad, y + infoPaint.textSize, infoPaint)
                    y += lineH
                }
                y += st.infoSize * SIZE_MULT * 0.5f
            }

            // ---------- 网格 ----------
            val cell = st.cell * SIZE_MULT
            val gap = st.cellGap * SIZE_MULT
            val cols = st.cols
            val gridW = cols * cell + (cols - 1) * gap
            val gridX = (size.width - gridW) / 2f

            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(st.grid, 1f)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * SIZE_MULT
            }
            val noteBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(st.noteBg, 1f)
                style = Paint.Style.FILL
            }
            val noteFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(st.noteFg, 1f)
                textSize = cell * 0.42f
                typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(st.sub, 1f)
                textSize = st.timeSize * SIZE_MULT
                typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            }

            // 按时间分组
            val groups = groupByTime(page.notes)
            val rowH = cell + gap
            val timeColW = timePaint.textSize * 3.2f

            var rowY = y
            for (g in groups) {
                if (rowY + cell > size.height - pad) break

                // 时间标签
                val tSec = g.time
                val mm = (tSec / 60).toInt()
                val ss = (tSec % 60).toInt()
                val timeStr = String.format("%02d:%02d", mm, ss)
                canvas.drawText(timeStr, pad, rowY + cell * 0.65f, timePaint)

                // 音符格
                for (n in g.notes) {
                    val col = keyToCol(n.key, cols)
                    if (col < 0) continue
                    val cx = gridX + col * (cell + gap)
                    val rect = RectF(cx, rowY, cx + cell, rowY + cell)

                    if (st.noteShape == "skystudio") {
                        val path = Path()
                        val r = st.gridRadius * SIZE_MULT
                        path.addRoundRect(rect, r, r, Path.Direction.CW)
                        canvas.drawPath(path, noteBgPaint)
                    } else {
                        val r = st.gridRadius * SIZE_MULT
                        canvas.drawRoundRect(rect, r, r, noteBgPaint)
                    }

                    val label = keyLabel(n.key)
                    if (label.isNotEmpty()) {
                        val ty = rowY + cell / 2f - (noteFgPaint.descent() + noteFgPaint.ascent()) / 2f
                        canvas.drawText(label, cx + cell / 2f, ty, noteFgPaint)
                    }
                }

                rowY += rowH
            }

            // ---------- 底部水印 ----------
            drawBottomWatermark(canvas, size, st)

            bmp
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    // ============================ 水印 ============================

    private fun drawTiledWatermark(canvas: Canvas, w: Int, h: Int, st: Style) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = st.watermarkSize * SIZE_MULT
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
        }
        val stepX = 200f * SIZE_MULT
        val stepY = 140f * SIZE_MULT

        canvas.save()
        canvas.rotate(-30f, w / 2f, h / 2f)

        val diag = kotlin.math.sqrt((w * w + h * h).toFloat())
        val startX = w / 2f - diag
        val startY = h / 2f - diag
        val endX = w / 2f + diag
        val endY = h / 2f + diag

        var idx = 0
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val (text, alpha) = when (idx % 3) {
                    0 -> APP_NAME to 0.06f
                    1 -> WATERMARK_TEXT to 0.05f
                    else -> WATERMARK_UID to 0.045f
                }
                paint.color = argb(st.sub, alpha)
                canvas.drawText(text, x, y, paint)
                x += stepX
                idx++
            }
            y += stepY
        }
        canvas.restore()
    }

    private fun drawBottomWatermark(canvas: Canvas, size: Size, st: Style) {
        val pad = st.pad * SIZE_MULT
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.accent, 0.5f)
            textSize = st.logoSize * SIZE_MULT
            typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
        }
        val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(st.sub, 0.35f)
            textSize = st.watermarkSize * SIZE_MULT
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val baseY = size.height - pad
        canvas.drawText(APP_NAME, pad, baseY, logoPaint)
        canvas.drawText(WATERMARK_TEXT, size.width - pad, baseY, wmPaint)
    }

    // ============================ 布局 ============================

    private fun buildPages(notes: List<Note>, st: Style): List<PageData> {
        val sorted = notes.sortedBy { it.time }
        val groups = groupByTime(sorted)
        val perPage = st.rowsPerPage
        val pages = mutableListOf<PageData>()

        var i = 0
        var pageIdx = 0
        while (i < groups.size) {
            val end = minOf(i + perPage, groups.size)
            val slice = groups.subList(i, end)
            val startT = slice.first().time
            val endT = slice.last().time
            val flat = mutableListOf<Note>()
            for (g in slice) flat.addAll(g.notes)
            pages.add(PageData(pageIdx, startT, endT, flat))
            pageIdx++
            i = end
        }
        if (pages.isEmpty()) pages.add(PageData(0, 0f, 0f, emptyList()))
        return pages
    }

    private data class TimeGroup(val time: Float, val notes: List<Note>)

    private fun groupByTime(notes: List<Note>): List<TimeGroup> {
        val map = LinkedHashMap<Int, MutableList<Note>>()
        for (n in notes) {
            val bucket = (n.time * 1000f / 20f).roundToInt() // 20ms 聚合
            map.getOrPut(bucket) { mutableListOf() }.add(n)
        }
        return map.entries.map { (bucket, list) ->
            TimeGroup(bucket * 20f / 1000f, list)
        }.sortedBy { it.time }
    }

    private fun measurePage(st: Style, infoItems: List<Pair<String, String>>): Size {
        val pad = st.pad * SIZE_MULT
        val cell = st.cell * SIZE_MULT
        val gap = st.cellGap * SIZE_MULT
        val cols = st.cols
        val gridW = cols * cell + (cols - 1) * gap
        val timeColW = st.timeSize * SIZE_MULT * 3.2f

        val headerH = st.titleSize * SIZE_MULT +
                st.metaSize * SIZE_MULT * 1.4f +
                st.metaSize * SIZE_MULT * 1.2f +
                infoItems.size * st.infoSize * SIZE_MULT * 1.5f +
                st.infoSize * SIZE_MULT * 0.5f

        val rowH = cell + gap
        val gridH = st.rowsPerPage * rowH

        val w = (pad * 2 + timeColW + gridW).roundToInt().coerceAtLeast(600)
        val h = (pad * 2 + headerH + gridH + st.logoSize * SIZE_MULT * 2).roundToInt().coerceAtLeast(600)
        return Size(w, h)
    }

    private fun buildInfoItems(
        songName: String,
        author: String,
        modelName: String,
        bpm: Int,
        keyCount: Int,
        noteCount: Int,
        pageCount: Int
    ): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        if (author.isNotBlank()) list.add("作者" to author)
        if (modelName.isNotBlank()) list.add("模型" to modelName)
        if (bpm > 0) list.add("BPM" to bpm.toString())
        list.add("键位" to "${keyCount}键")
        list.add("音符" to noteCount.toString())
        list.add("页数" to pageCount.toString())
        return list
    }

    // ============================ 工具 ============================

    private fun parseStyle(obj: JSONObject?): Style {
        val o = obj ?: JSONObject()
        return Style(
            bg = o.optString("bg", FALLBACK_BG),
            fg = o.optString("fg", "#E8F4FF"),
            accent = o.optString("accent", "#7DD3FC"),
            sub = o.optString("sub", "#A78BFA"),
            grid = o.optString("grid", "#2A3350"),
            noteBg = o.optString("noteBg", "#7DD3FC"),
            noteFg = o.optString("noteFg", "#0B1120"),
            pad = o.optDouble("pad", 40.0).toFloat(),
            cell = o.optDouble("cell", 22.0).toFloat(),
            cellGap = o.optDouble("cellGap", 4.0).toFloat(),
            cols = o.optInt("cols", 8),
            rowsPerPage = o.optInt("rowsPerPage", 30),
            titleSize = o.optDouble("titleSize", 30.0).toFloat(),
            metaSize = o.optDouble("metaSize", 14.0).toFloat(),
            timeSize = o.optDouble("timeSize", 13.0).toFloat(),
            infoSize = o.optDouble("infoSize", 12.0).toFloat(),
            watermarkSize = o.optDouble("watermarkSize", 13.0).toFloat(),
            logoSize = o.optDouble("logoSize", 15.0).toFloat(),
            gridRadius = o.optDouble("gridRadius", 5.0).toFloat(),
            noteShape = o.optString("noteShape", "square")
        )
    }

    private fun parseNotes(arr: JSONArray?): List<Note> {
        if (arr == null) return emptyList()
        val list = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optDouble("time", -1.0).toFloat()
            val k = o.optString("key", "")
            if (t < 0f || k.isEmpty()) continue
            list.add(Note(t, k))
        }
        return list
    }

    private fun keyToCol(key: String, cols: Int): Int {
        // key 形如 "1Key0" ~ "1Key9" / "2Key0" ~ "2Key4"
        val idx = key.filter { it.isDigit() }.takeLast(1).toIntOrNull() ?: return -1
        val row = key.firstOrNull()?.digitToIntOrNull() ?: 1
        val col = if (row == 1) idx else 10 + idx
        return if (col in 0 until cols) col else -1
    }

    private fun keyLabel(key: String): String {
        val idx = key.filter { it.isDigit() }.takeLast(1).toIntOrNull() ?: return ""
        val row = key.firstOrNull()?.digitToIntOrNull() ?: 1
        return if (row == 1) {
            if (idx == 0) "1" else (idx + 1).toString()
        } else {
            val letters = "CDEFGAB"
            letters.getOrNull(idx)?.toString() ?: ""
        }
    }

    private fun sanitizeName(raw: String): String {
        var s = raw.trim().ifEmpty { "未命名" }
        s = s.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (s.length > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH)
        return s
    }

    private fun writeBitmap(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
    }

    private fun argb(colorStr: String, alpha: Float): Int {
        val base = parseColorSafe(colorStr)
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        return Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun parseColorSafe(colorStr: String): Int {
        val s = colorStr.trim()
        try {
            if (s.startsWith("#")) {
                return when (s.length) {
                    7 -> Color.parseColor(s)
                    9 -> Color.parseColor(s)
                    4 -> {
                        val r = s[1]; val g = s[2]; val b = s[3]
                        Color.parseColor("#$r$r$g$g$b$b")
                    }
                    else -> Color.parseColor(s)
                }
            }
            if (s.startsWith("rgb")) {
                val nums = s.substringAfter("(").substringBefore(")").split(",")
                val r = nums.getOrNull(0)?.trim()?.toFloatOrNull()?.roundToInt() ?: 0
                val g = nums.getOrNull(1)?.trim()?.toFloatOrNull()?.roundToInt() ?: 0
                val b = nums.getOrNull(2)?.trim()?.toFloatOrNull()?.roundToInt() ?: 0
                return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
            if (s.startsWith("hsl")) {
                val nums = s.substringAfter("(").substringBefore(")").split(",")
                val h = nums.getOrNull(0)?.trim()?.toFloatOrNull() ?: 0f
                val sat = (nums.getOrNull(1)?.trim()?.removeSuffix("%")?.toFloatOrNull() ?: 0f) / 100f
                val l = (nums.getOrNull(2)?.trim()?.removeSuffix("%")?.toFloatOrNull() ?: 0f) / 100f
                return hslToColor(h, sat, l)
            }
            return Color.parseColor(s)
        } catch (e: Throwable) {
            return Color.parseColor(FALLBACK_BG)
        }
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 360f
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val r = hue2rgb(p, q, hh + 1f / 3f)
        val g = hue2rgb(p, q, hh)
        val b = hue2rgb(p, q, hh - 1f / 3f)
        return Color.rgb(
            (r * 255).roundToInt().coerceIn(0, 255),
            (g * 255).roundToInt().coerceIn(0, 255),
            (b * 255).roundToInt().coerceIn(0, 255)
        )
    }

    private fun hue2rgb(p: Float, q: Float, t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }
}
