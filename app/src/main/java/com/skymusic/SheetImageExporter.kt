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
    private const val WATERMARK_UID = "Bilibili UID: 1283676246"
    private const val APP_NAME = "SkyMusic"
    private const val LONG_SUFFIX = "-Full"
    private const val TRIM_PADDING = 40
    private const val MAX_PAGE_PIXELS = 12_000_000f
    private const val MAX_LONG_PIXELS = 40_000_000.0

    // ============================ 入口 ============================

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

            // ★ 关键：cols / rowsPerPage 在前端是顶层字段，不在 style 里
            st.cols = root.optInt("cols", st.cols).coerceIn(1, 20)
            st.rowsPerPage = root.optInt("rowsPerPage", st.rowsPerPage).coerceIn(1, 30)

            val mergeLong = root.optBoolean("mergeLong", false)
            val longOnly = root.optBoolean("longOnly", false)
            val totalPages = root.optInt("totalPages", 0)
            val infoItems = parseInfoItems(root.optJSONArray("infoItems"))
            val pagesArr = root.optJSONArray("pages")

            if (pagesArr == null || pagesArr.length() == 0) {
                return fail("没有选中任何页面")
            }
            // ... 后面不变


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
                parsedPages.add(parsePage(pageObj, rows))
            }

            val size = measure(rows, st, infoItems.size)
            val scale = computeScale(size.width, size.height)

            // ---------- 分页导出 ----------
            if (!longOnly && pageDir != null) {
                for (i in parsedPages.indices) {
                    val pageIndex = pagesArr.optJSONObject(i)?.optInt("index", i) ?: i
                    onProgress?.invoke("正在绘制第 ${i + 1} / $total 页", (i * 70) / total)

                    val bitmap = drawPageFast(
                        parsedPages[i], songName, rows, st, pageIndex, pageCount,
                        infoItems, size, scale
                    ) ?: return fail("绘制失败")

                    val fileName = if (pageIndex == 0) "$safeName.png" else "$safeName-${pageIndex + 1}.png"
                    writeBitmap(bitmap, File(pageDir, fileName))
                    files.add(fileName)
                    bitmap.recycle()
                }
            }

            // ---------- 长图导出 ----------
            if (mergeLong || longOnly) {
                onProgress?.invoke("正在生成长图…", 80)
                val longName = "$safeName$LONG_SUFFIX.png"
                val longOk = writeLongImage(
                    File(rootDir, longName), parsedPages, songName, rows, st,
                    pageCount, infoItems, size, scale, onProgress
                )
                if (longOk) files.add(longName)
            }

            onProgress?.invoke("完成", 100)
            return JSONObject()
                .put("ok", true)
                .put("dir", rootDir.absolutePath)
                .put("files", JSONArray(files))
        } catch (oom: OutOfMemoryError) {
            return fail("内存不足，请减少页数或行数")
        } catch (t: Throwable) {
            return fail(t.message ?: "未知错误")
        }
    }

    private fun fail(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private fun writeBitmap(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
    }

    // ============================ 长图（流式绘制 + 末尾裁剪） ============================

    private fun writeLongImage(
        target: File,
        pages: List<PageData>,
        songName: String,
        rows: Int,
        st: Style,
        pageCount: Int,
        infoItems: List<Pair<String, String>>,
        size: Size,
        scale: Float,
        onProgress: ((String, Int) -> Unit)?
    ): Boolean {
        if (pages.isEmpty()) return false

        val pageW = max(1, (size.width * scale).roundToInt())
        val pageH = max(1, (size.height * scale).roundToInt())
        val gap = (st.pad * 0.6f * scale).roundToInt().coerceAtLeast(8)
        val totalH = pageH * pages.size + gap * (pages.size - 1)

        // 长图总像素超限时整体再缩一次
        var longScale = 1f
        val pixels = pageW.toDouble() * totalH.toDouble()
        if (pixels > MAX_LONG_PIXELS) {
            longScale = sqrt(MAX_LONG_PIXELS / pixels).toFloat()
        }
        val outW = max(1, (pageW * longScale).roundToInt())
        val outH = max(1, (totalH * longScale).roundToInt())

        try {
            // RGB_565 省一半内存
            val longBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565)
            val canvas = Canvas(longBitmap)
            canvas.drawColor(argb(st.bg, 1f))

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var y = 0f
            for (i in pages.indices) {
                onProgress?.invoke("正在绘制第 ${i + 1} / ${pages.size} 页", 10 + (i * 70) / pages.size)

                val pageBmp = drawPageFast(
                    pages[i], songName, rows, st, i, pageCount, infoItems, size, scale
                ) ?: return false

                val dstW = pageBmp.width * longScale
                val dstH = pageBmp.height * longScale
                val left = (outW - dstW) / 2f
                canvas.drawBitmap(pageBmp, null, RectF(left, y, left + dstW, y + dstH), paint)
                pageBmp.recycle()
                y += dstH + gap * longScale
            }

            // 长图整体重铺一次水印（跨页连贯）
            drawTiledWatermark(canvas, outW.toFloat(), outH.toFloat(), st)

            // 末尾空白裁剪
            val trimmedH = trimBottomBlank(longBitmap, argb(st.bg, 1f))
            val finalBitmap = if (trimmedH < outH) {
                Bitmap.createBitmap(longBitmap, 0, 0, outW, trimmedH)
            } else {
                longBitmap
            }

            onProgress?.invoke("正在保存…", 92)
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
        val step = 4

        var lastContentY = -1

        // 从底部向上扫描
        var y = h - 1
        while (y >= 0) {
            if (rowHasContent(bitmap, y, w, bgR, bgG, bgB, threshold)) {
                lastContentY = y
                break
            }
            y -= step
        }

        if (lastContentY < 0) return h

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

    // ============================ 尺寸计算 ============================

    private fun measure(rows: Int, st: Style, infoCount: Int): Size {
        val gCols = if (rows == 9) 3 else 5
        val gRows = 3

        val gridW = gCols * st.cell + (gCols - 1) * st.cellGap
        val gridH = gRows * st.cell + (gRows - 1) * st.cellGap
        val timeH = st.timeSize * 2.2f

        val headerH = st.titleSize * 1.4f + 10f + st.metaSize * 1.5f + 26f

        var infoH = 0f
        if (st.showInfoPanel && infoCount > 0) {
            infoH = ((infoCount + 1) / 2) * (st.infoSize * 2.0f) + 28f
        }

        val footerH = st.watermarkSize * 2.6f
        val bodyW = st.cols * gridW + (st.cols - 1) * st.gridGapX
        val bodyH = st.rowsPerPage * (gridH + timeH) + (st.rowsPerPage - 1) * st.gridGapY

        return Size(
            width = st.pad * 2f + bodyW,
            height = st.pad * 2f + headerH + infoH + bodyH + footerH,
            headerH = headerH, infoH = infoH, footerH = footerH,
            bodyW = bodyW, bodyH = bodyH,
            gridW = gridW, gridH = gridH, timeH = timeH,
            gCols = gCols, gRows = gRows
        )
    }

    private fun computeScale(width: Float, height: Float): Float {
        var scale = 2f
        val base = width * height
        if (base <= 0f) return 1f
        while (scale > 0.5f && base * scale * scale > MAX_PAGE_PIXELS) scale -= 0.1f
        return max(0.4f, scale)
    }

    // ============================ 单页绘制 ============================

    private fun drawPageFast(
        page: PageData, songName: String, rows: Int, st: Style,
        pageIndex: Int, pageCount: Int, infoItems: List<Pair<String, String>>,
        size: Size, scale: Float
    ): Bitmap? {
        return try {
            val outW = max(1, (size.width * scale).roundToInt())
            val outH = max(1, (size.height * scale).roundToInt())

            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            // 背景层
            drawVerticalGradient(canvas, size.width, size.height, st.bg, st.bgGlow)
            if (st.showStars) drawStars(canvas, size.width, size.height)
            drawBgPattern(canvas, size.width, size.height, st, fill, stroke)
            drawDeco(canvas, size.width, size.height, st, fill, stroke)
            drawWatermark(canvas, size.width, size.height, st)

            // 标题
            textPaint.apply {
                typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
                textSize = st.titleSize
                color = argb(st.titleColor, 1f)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(songName, size.width / 2f, st.pad - textPaint.fontMetrics.top, textPaint)

            // 页码
            textPaint.apply {
                typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
                textSize = st.metaSize
                color = argb(st.metaColor, 1f)
                textAlign = Paint.Align.LEFT
            }
            canvas.drawText(
                "${pageIndex + 1} / $pageCount",
                st.pad,
                st.pad + st.titleSize * 1.4f + 10f - textPaint.fontMetrics.top,
                textPaint
            )

            // 信息面板
            val infoTop = st.pad + size.headerH
            if (st.showInfoPanel && infoItems.isNotEmpty()) {
                drawInfoPanel(canvas, st.pad, infoTop, size.bodyW, infoItems, st)
            }

            // 网格区
            val bodyTop = infoTop + size.infoH
            val bodyLeft = st.pad

            textPaint.apply {
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

                if (st.showTime) {
                    canvas.drawText(
                        formatMs(col.timeMs),
                        gx + size.gridW / 2f,
                        gy + size.gridH + 8f - textPaint.fontMetrics.top,
                        textPaint
                    )
                }
            }

            // 底部 Logo + 名称
            val footY = st.pad + size.headerH + size.infoH + size.bodyH + size.footerH / 2f
            val logoSize = st.logoSize * 1.6f
            val nameWidth = 90f
            val startX = (size.width - (logoSize + 8f + nameWidth)) / 2f

            drawLogo(canvas, startX, footY - logoSize / 2f, logoSize, st, fill, stroke)

            textPaint.apply {
                typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
                textSize = st.logoSize
                color = argb(st.logoColor, 1f)
                textAlign = Paint.Align.LEFT
            }
            canvas.drawText(
                APP_NAME,
                startX + logoSize + 8f,
                footY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f,
                textPaint
            )

            // 防盗噪点
            drawAntiTheftNoise(canvas, size.width, size.height, fill)

            bitmap
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    // ============================ 背景层 ============================

    private fun drawVerticalGradient(canvas: Canvas, width: Float, height: Float, from: Int, to: Int) {
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
        val rnd = Random(20260101L)
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

    private fun drawBgPattern(canvas: Canvas, w: Float, h: Float, st: Style, fill: Paint, stroke: Paint) {
        if (st.bgPattern == "none") return
        stroke.color = argb(st.gridLine, 0.05f)
        stroke.strokeWidth = 1f
        val step = 40f
        when (st.bgPattern) {
            "grid" -> {
                var x = 0f
                while (x < w) { canvas.drawLine(x, 0f, x, h, stroke); x += step }
                var y = 0f
                while (y < h) { canvas.drawLine(0f, y, w, y, stroke); y += step }
            }
            "dots" -> {
                fill.color = argb(st.gridLine, 0.05f)
                var x = 0f
                while (x < w) {
                    var y = 0f
                    while (y < h) { canvas.drawCircle(x, y, 1.5f, fill); y += 30f }
                    x += 30f
                }
            }
            "diagonal" -> {
                var i = -w.toInt()
                while (i < h.toInt()) {
                    canvas.drawLine(i.toFloat(), 0f, i + h, h, stroke)
                    i += 30
                }
            }
        }
    }

    private fun drawDeco(canvas: Canvas, w: Float, h: Float, st: Style, fill: Paint, stroke: Paint) {
        if (st.decoType == "none") return
        val rnd = Random(20260101L + w.toLong())
        fill.color = argb(st.gridLine, 0.1f)
        stroke.color = argb(st.gridLine, 0.15f)
        stroke.strokeWidth = 1f

        for (i in 0 until 50) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            when (st.decoType) {
                "stars" -> canvas.drawCircle(x, y, rnd.nextFloat() * 1.5f + 0.5f, fill)
                "snow" -> {
                    canvas.drawLine(x - 4f, y, x + 4f, y, stroke)
                    canvas.drawLine(x, y - 4f, x, y + 4f, stroke)
                }
                "bubbles" -> canvas.drawCircle(x, y, rnd.nextFloat() * 6f + 2f, stroke)
                "geometric" -> {
                    val path = Path()
                    path.moveTo(x, y - 3f)
                    path.lineTo(x + 3f, y + 2f)
                    path.lineTo(x - 3f, y + 2f)
                    path.close()
                    canvas.drawPath(path, fill)
                }
            }
        }
    }

    // ============================ 信息面板 ============================

    private fun drawInfoPanel(
        canvas: Canvas, x: Float, y: Float, width: Float,
        items: List<Pair<String, String>>, st: Style
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

        val lineY = y + rowCount * rowH + 6f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = argb(st.dividerColor, st.dividerAlpha)
        }
        canvas.drawLine(x, lineY, x + width, lineY, linePaint)
    }

    // ============================ 按键网格 ============================

    private fun drawKeyGrid(
        canvas: Canvas, x: Float, y: Float, gCols: Int, gRows: Int,
        st: Style, activeCells: List<Int>, fill: Paint, stroke: Paint
    ) {
        val block = RectF()
        for (r in 0 until gRows) {
            for (c in 0 until gCols) {
                val bx = x + c * (st.cell + st.cellGap)
                val by = y + r * (st.cell + st.cellGap)
                val isActive = activeCells.contains(r * gCols + c)
                block.set(bx, by, bx + st.cell, by + st.cell)

                val path = Path()
                when (st.noteShape) {
                    "circle" -> path.addCircle(
                        bx + st.cell / 2f, by + st.cell / 2f, st.cell / 2f, Path.Direction.CW
                    )
                    "diamond" -> {
                        path.moveTo(bx + st.cell / 2f, by)
                        path.lineTo(bx + st.cell, by + st.cell / 2f)
                        path.lineTo(bx + st.cell / 2f, by + st.cell)
                        path.lineTo(bx, by + st.cell / 2f)
                        path.close()
                    }
                    else -> path.addRoundRect(block, st.gridRadius, st.gridRadius, Path.Direction.CW)
                }

                if (isActive) {
                    val t = if (gRows > 1) r.toFloat() / (gRows - 1) else 0f
                    fill.color = argb(mix(st.noteFill, st.noteFillAlt, t), 1f)
                    canvas.drawPath(path, fill)
                    if (st.noteStrokeAlpha > 0) {
                        stroke.color = argb(st.noteStroke, st.noteStrokeAlpha)
                        stroke.strokeWidth = 1f
                        canvas.drawPath(path, stroke)
                    }
                } else {
                    stroke.color = argb(st.gridLine, st.gridLineAlpha)
                    stroke.strokeWidth = 1.2f
                    canvas.drawPath(path, stroke)
                }
            }
        }
    }

    // ============================ 水印 ============================

    /** 单页水印：三重交替斜向铺满 */
    private fun drawWatermark(canvas: Canvas, width: Float, height: Float, st: Style) {
        if (!st.showWatermark) return
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            textSize = st.watermarkSize
            textAlign = Paint.Align.CENTER
        }
        val stepX = st.watermarkSize * 11f
        val stepY = st.watermarkSize * 7f

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(-30f)

        val diag = sqrt(width * width + height * height)
        val halfW = diag / 2f + stepX
        val halfH = diag / 2f + stepY

        var rowIndex = 0
        var y = -halfH
        while (y <= halfH) {
            val offset = if (rowIndex % 2 == 0) 0f else stepX / 2f
            var x = -halfW + offset
            var colIndex = 0
            while (x <= halfW) {
                val idx = (rowIndex * 7 + colIndex) % 3
                val (text, alpha) = when (idx) {
                    0 -> APP_NAME to 0.06f
                    1 -> WATERMARK_TEXT to 0.05f
                    else -> WATERMARK_UID to 0.045f
                }
                textPaint.color = argb(st.watermarkColor, alpha)
                canvas.drawText(text, x, y, textPaint)
                x += stepX
                colIndex++
            }
            y += stepY
            rowIndex++
        }
        canvas.restore()
    }

    /** 长图水印：整体重铺一次，跨页连贯 */
    private fun drawTiledWatermark(canvas: Canvas, width: Float, height: Float, st: Style) {
        if (!st.showWatermark) return
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(FONT_STACK, Typeface.NORMAL)
            textSize = st.watermarkSize * 3f
            textAlign = Paint.Align.CENTER
        }
        val stepX = st.watermarkSize * 33f
        val stepY = st.watermarkSize * 21f

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(-30f)

        val diag = sqrt(width * width + height * height)
        val halfW = diag / 2f + stepX
        val halfH = diag / 2f + stepY

        var rowIndex = 0
        var y = -halfH
        while (y <= halfH) {
            val offset = if (rowIndex % 2 == 0) 0f else stepX / 2f
            var x = -halfW + offset
            var colIndex = 0
            while (x <= halfW) {
                val idx = (rowIndex * 7 + colIndex) % 3
                val (text, alpha) = when (idx) {
                    0 -> APP_NAME to 0.06f
                    1 -> WATERMARK_TEXT to 0.05f
                    else -> WATERMARK_UID to 0.045f
                }
                textPaint.color = argb(st.watermarkColor, alpha)
                canvas.drawText(text, x, y, textPaint)
                x += stepX
                colIndex++
            }
            y += stepY
            rowIndex++
        }
        canvas.restore()
    }

    // ============================ Logo ============================

    private fun drawLogo(
        canvas: Canvas, x: Float, y: Float, size: Float,
        st: Style, fill: Paint, stroke: Paint
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

    // ============================ 防盗噪点 ============================

    private fun drawAntiTheftNoise(c: Canvas, w: Float, h: Float, paint: Paint) {
        paint.style = Paint.Style.FILL
        val rnd = Random(20260101L)
        for (i in 0 until 3000) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            paint.color = when (i % 4) {
                0 -> Color.WHITE
                1 -> Color.BLACK
                2 -> Color.RED
                else -> Color.BLUE
            }
            paint.alpha = 12
            c.drawRect(x, y, x + 2f, y + 2f, paint)
        }
    }

    // ============================ 解析 ============================

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
                    colObj.optLong("t", 0L),
                    values.filter { it in 0 until rows }.toIntArray()
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

    // ============================ 颜色工具 ============================

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

    /** 支持 #RRGGBB / #AARRGGBB / hsl(h,s%,l%) / rgb(r,g,b) */
    private fun rgb(input: String, fallback: Int): Int {
        val s = input.trim()
        if (s.isEmpty()) return fallback

        // hsl(h, s%, l%)
        if (s.startsWith("hsl", ignoreCase = true)) {
            val m = Regex("""hsl\(\s*([\d.]+)\s*,\s*([\d.]+)%\s*,\s*([\d.]+)%\s*\)""").find(s)
            if (m != null) {
                val h = m.groupValues[1].toFloat() / 360f
                val sat = m.groupValues[2].toFloat() / 100f
                val light = m.groupValues[3].toFloat() / 100f
                return hslToRgb(h, sat, light)
            }
            return fallback
        }

        // rgb(r, g, b) / rgba(r, g, b, a)
        if (s.startsWith("rgb", ignoreCase = true)) {
            val m = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""").find(s)
            if (m != null) {
                val r = m.groupValues[1].toInt().coerceIn(0, 255)
                val g = m.groupValues[2].toInt().coerceIn(0, 255)
                val b = m.groupValues[3].toInt().coerceIn(0, 255)
                return (r shl 16) or (g shl 8) or b
            }
            return fallback
        }

        // #RRGGBB / #AARRGGBB / #RGB
        return try {
            when {
                s.startsWith("#") && s.length == 7 -> {
                    val v = s.substring(1).toLong(16).toInt()
                    v and 0x00FFFFFF
                }
                s.startsWith("#") && s.length == 9 -> {
                    val v = s.substring(1).toLong(16).toInt()
                    v and 0x00FFFFFF
                }
                s.startsWith("#") && s.length == 4 -> {
                    val r = s[1].toString().repeat(2)
                    val g = s[2].toString().repeat(2)
                    val b = s[3].toString().repeat(2)
                    ("$r$g$b").toLong(16).toInt() and 0x00FFFFFF
                }
                else -> fallback
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Int {
        if (s == 0f) {
            val v = (l * 255).roundToInt().coerceIn(0, 255)
            return (v shl 16) or (v shl 8) or v
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val r = hue2rgb(p, q, h + 1f / 3f)
        val g = hue2rgb(p, q, h)
        val b = hue2rgb(p, q, h - 1f / 3f)
        val ri = (r * 255).roundToInt().coerceIn(0, 255)
        val gi = (g * 255).roundToInt().coerceIn(0, 255)
        val bi = (b * 255).roundToInt().coerceIn(0, 255)
        return (ri shl 16) or (gi shl 8) or bi
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

    private fun f(o: JSONObject, key: String, def: Float): Float {
        val v = o.optDouble(key, def.toDouble())
        return if (v.isNaN() || v.isInfinite()) def else v.toFloat()
    }

    // ============================ 工具 ============================

    private fun formatMs(ms: Long): String {
        val total = if (ms < 0) 0 else ms
        val m = total / 60000
        val s = (total % 60000) / 1000
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun sanitize(name: String): String {
        var s = name.trim()
        if (s.isEmpty()) return "未命名乐谱"
        s = s.replace(Regex("[\\\\/:*?\"<>\\r\\n\\t]"), "_")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH).trim()
        if (s.isEmpty() || s == "." || s == "..") s = "未命名乐谱"
        return s
    }

    // ============================ 数据类 ============================

    private class Size(
        val width: Float, val height: Float,
        val headerH: Float, val infoH: Float, val footerH: Float,
        val bodyW: Float, val bodyH: Float,
        val gridW: Float, val gridH: Float, val timeH: Float,
        val gCols: Int, val gRows: Int
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
        var showTime: Boolean = true

        var noteShape: String = "square"
        var decoType: String = "none"
        var bgPattern: String = "none"

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
                s.showTime = o.optBoolean("showTime", s.showTime)

                s.noteShape = o.optString("noteShape", s.noteShape)
                s.decoType = o.optString("decoType", s.decoType)
                s.bgPattern = o.optString("bgPattern", s.bgPattern)

                return s
            }

            fun defaults(): Style = from(null)
        }
    }
}
