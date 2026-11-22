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

/**
 * SkyMusic 0.0.9 · 谱面图片导出器
 * ------------------------------------------------------------------
 * 内存安全策略：
 *   · 单页像素上限 800 万（约 32MB ARGB_8888）
 *   · 逐页绘制 → 立即写盘 → 立即 recycle
 *   · 长图流式分块写入，每块 1500px
 *   · 长图总像素上限 5000 万
 *   · 绘制失败自动降采样重试
 *   · 导出时在目录写入 debug.txt 记录尺寸信息
 */
object SheetImageExporter {

    private const val MAX_PAGE_PIXELS = 8_000_000f
    private const val MAX_LONG_PIXELS = 50_000_000.0
    private const val LONG_CHUNK_H = 1500

    private const val MAX_NAME_LENGTH = 60
    private const val FONT_STACK = "sans-serif"
    private const val WATERMARK_TEXT = "Bilibili@_OneStar"
    private const val APP_NAME = "SkyMusic"
    private const val LONG_SUFFIX = "-Full"

    // ============================ 对外入口 ============================

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
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                return fail("无法创建目录：" + rootDir.absolutePath)
            }

            val pageDir = if (longOnly) rootDir else File(rootDir, "分页")
            if (!longOnly && !pageDir.exists() && !pageDir.mkdirs()) {
                return fail("无法创建分页目录：" + pageDir.absolutePath)
            }

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

            // 调试信息
            val debugInfo = buildString {
                append("size=").append(size.width).append("x").append(size.height).append('\n')
                append("scale=").append(scale).append('\n')
                append("cols=").append(st.cols).append('\n')
                append("rowsPerPage=").append(st.rowsPerPage).append('\n')
                append("cell=").append(st.cell).append('\n')
                append("cellGap=").append(st.cellGap).append('\n')
                append("pad=").append(st.pad).append('\n')
                append("gridGapX=").append(st.gridGapX).append('\n')
                append("gridGapY=").append(st.gridGapY).append('\n')
                append("infoItems=").append(infoItems.size).append('\n')
                append("pages=").append(parsedPages.size).append('\n')
                append("rows=").append(rows).append('\n')
                append("showInfoPanel=").append(st.showInfoPanel).append('\n')
                append("showTime=").append(st.showTime).append('\n')
                append("noteShape=").append(st.noteShape).append('\n')
                append("decoType=").append(st.decoType).append('\n')
                append("bgPattern=").append(st.bgPattern).append('\n')
            }

            // 写 debug.txt
            try {
                File(rootDir, "debug.txt").writeText(debugInfo, Charsets.UTF_8)
            } catch (_: Exception) { }

            if (mergeLong || longOnly) {
                val pageFiles = ArrayList<File>(parsedPages.size)
                for (i in parsedPages.indices) {
                    val pageIndex = pagesArr.optJSONObject(i)?.optInt("index", i) ?: i
                    onProgress?.invoke("正在绘制第 ${i + 1} / $total 页", (i * 60) / total)

                    val bitmap = drawPageFast(
                        parsedPages[i], songName, rows, st, pageIndex, pageCount,
                        infoItems, size, scale
                    ) ?: return fail("第 ${pageIndex + 1} 页绘制失败（内存不足）")

                    if (bitmap.width <= 1 || bitmap.height <= 1) {
                        bitmap.recycle()
                        return fail("第 ${pageIndex + 1} 页绘制失败（尺寸异常）")
                    }

                    val fileName = if (pageIndex == 0) {
                        "$safeName.png"
                    } else {
                        "$safeName-${pageIndex + 1}.png"
                    }
                    val target = File(pageDir, fileName)
                    writeBitmap(bitmap, target)
                    pageFiles.add(target)

                    if (!longOnly) files.add(fileName)
                    bitmap.recycle()
                }

                               onProgress?.invoke("正在拼接长图…", 70)

                // 先算一下会不会拆成多张
                var willSplit = false
                run {
                    var mw = 0
                    var th = 0L
                    for (f in pageFiles) {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                        if (o.outWidth > mw) mw = o.outWidth
                        th += o.outHeight.toLong()
                    }
                    th += ((st.pad * 0.6f).roundToInt().coerceAtLeast(8)).toLong() * (pageFiles.size - 1)
                    var sc = 1f
                    val px = mw.toDouble() * th.toDouble()
                    if (px > MAX_LONG_PIXELS) sc = sqrt(MAX_LONG_PIXELS / px).toFloat()
                    val h = (th * sc).roundToInt()
                    willSplit = h > 16384
                }

                val targetDir = if (willSplit) {
                    if (!longDir.exists() && !longDir.mkdirs()) {
                        return fail("无法创建长图目录：" + longDir.absolutePath)
                    }
                    longDir
                } else {
                    rootDir
                }

                val longFiles = mergePagesStreaming(pageFiles, targetDir, safeName, st, onProgress)
                    ?: return fail("长图拼接失败（内存不足）")

                for (f in longFiles) {
                    files.add(f.name)
                }


                onProgress?.invoke("完成", 100)
            } else {
                for (i in parsedPages.indices) {
                    val pageIndex = pagesArr.optJSONObject(i)?.optInt("index", i) ?: i
                    onProgress?.invoke("正在绘制第 ${i + 1} / $total 页", (i * 90) / total)

                    val bitmap = drawPageFast(
                        parsedPages[i], songName, rows, st, pageIndex, pageCount,
                        infoItems, size, scale
                    ) ?: return fail("第 ${pageIndex + 1} 页绘制失败（内存不足）")

                    if (bitmap.width <= 1 || bitmap.height <= 1) {
                        bitmap.recycle()
                        return fail("第 ${pageIndex + 1} 页绘制失败（尺寸异常）")
                    }

                    val fileName = if (pageIndex == 0) {
                        "$safeName.png"
                    } else {
                        "$safeName-${pageIndex + 1}.png"
                    }
                    writeBitmap(bitmap, File(pageDir, fileName))
                    files.add(fileName)
                    bitmap.recycle()
                }
                onProgress?.invoke("完成", 100)
            }

            return JSONObject()
                .put("ok", true)
                .put("dir", rootDir.absolutePath)
                .put("files", JSONArray(files))
                .put("debug", debugInfo)
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

        /**
     * 流式拼接长图，超过 MAX_DIM 时拆成多张
     * @return 生成的文件列表，失败返回 null
     */
    private fun mergePagesStreaming(
        pageFiles: List<File>,
        targetDir: File,
        baseName: String,
        st: Style,
        onProgress: ((String, Int) -> Unit)?
    ): List<File>? {
        if (pageFiles.isEmpty()) return null

        val bounds = ArrayList<Pair<Int, Int>>(pageFiles.size)
        var maxW = 0
        var totalH = 0L
        val gap = (st.pad * 0.6f).roundToInt().coerceAtLeast(8)

        for (f in pageFiles) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            bounds.add(Pair(opts.outWidth, opts.outHeight))
            if (opts.outWidth > maxW) maxW = opts.outWidth
            totalH += opts.outHeight.toLong()
        }
        totalH += gap.toLong() * (pageFiles.size - 1)

        if (maxW <= 0 || totalH <= 0) return null

        var scale = 1f
        val pixels = maxW.toDouble() * totalH.toDouble()
        if (pixels > MAX_LONG_PIXELS) {
            scale = sqrt(MAX_LONG_PIXELS / pixels).toFloat()
        }

        val outW = max(1, (maxW * scale).roundToInt())
        val outH = max(1, (totalH * scale).roundToInt())

        // 单边上限 16384，超过拆成多张
        val MAX_DIM = 16384
        val partCount = (outH + MAX_DIM - 1) / MAX_DIM
        val partH = (outH + partCount - 1) / partCount

        val results = ArrayList<File>(partCount)

        try {
            for (part in 0 until partCount) {
                val partTop = part * partH
                val partBottom = min(outH, partTop + partH)
                val thisH = partBottom - partTop
                if (thisH <= 0) continue

                val needBytes = outW.toLong() * thisH.toLong() * 4L
                val availBytes = Runtime.getRuntime().maxMemory() -
                        Runtime.getRuntime().totalMemory() +
                        Runtime.getRuntime().freeMemory()
                if (needBytes > availBytes * 0.5) {
                    return null
                }

                val partBitmap = Bitmap.createBitmap(outW, thisH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(partBitmap)
                canvas.drawColor(argb(st.bg, 1f))

                val paint = Paint(Paint.FILTER_BITMAP_FLAG)

                var curY = 0f
                for (i in 0 until pageFiles.size) {
                    val (pw, ph) = bounds[i]
                    val dstW = pw * scale
                    val dstH = ph * scale
                    val pageTop = curY
                    val pageBottom = curY + dstH

                    if (pageBottom >= partTop && pageTop <= partBottom) {
                        val pageBmp = decodeScaled(pageFiles[i], dstW.toInt(), dstH.toInt())
                        if (pageBmp != null) {
                            val left = (outW - dstW) / 2f
                            val drawY = pageTop - partTop
                            canvas.drawBitmap(pageBmp, null, RectF(left, drawY, left + dstW, drawY + dstH), paint)
                            pageBmp.recycle()
                        }
                    }

                    curY += dstH + gap * scale
                }

                val fileName = if (partCount == 1) {
                    "$baseName$LONG_SUFFIX.png"
                } else {
                    "$baseName$LONG_SUFFIX-${part + 1}.png"
                }
                val target = File(targetDir, fileName)
                writeBitmap(partBitmap, target)
                partBitmap.recycle()
                results.add(target)

                onProgress?.invoke(
                    "正在拼接长图 ${part + 1} / $partCount",
                    70 + (part * 25) / partCount
                )
            }

            return results
        } catch (e: OutOfMemoryError) {
            return null
        }
    }


    private fun decodeScaled(file: File, targetW: Int, targetH: Int): Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            var sample = 1
            while (opts.outWidth / (sample * 2) >= targetW && opts.outHeight / (sample * 2) >= targetH) {
                sample *= 2
            }

            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (e: OutOfMemoryError) {
            null
        }
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

        val availBytes = Runtime.getRuntime().maxMemory() -
                Runtime.getRuntime().totalMemory() +
                Runtime.getRuntime().freeMemory()
        val maxBytes = (availBytes * 0.15).toLong()

        while (scale > 0.5f) {
            val pixels = base * scale * scale
            val bytes = pixels.toLong() * 4L
            if (pixels <= MAX_PAGE_PIXELS && bytes <= maxBytes) break
            scale -= 0.1f
        }
        return max(0.5f, scale)
    }

    // ============================ 绘制 ============================

    private fun drawPageFast(
        page: PageData, songName: String, rows: Int, st: Style,
        pageIndex: Int, pageCount: Int, infoItems: List<Pair<String, String>>,
        size: Size, scale: Float
    ): Bitmap? {
        var bitmap = tryDrawPage(page, songName, rows, st, pageIndex, pageCount, infoItems, size, scale)
        if (bitmap != null && bitmap.width > 1 && bitmap.height > 1) return bitmap

        bitmap?.recycle()
        val retryScale = max(0.5f, scale * 0.7f)
        bitmap = tryDrawPage(page, songName, rows, st, pageIndex, pageCount, infoItems, size, retryScale)
        if (bitmap != null && bitmap.width > 1 && bitmap.height > 1) return bitmap

        bitmap?.recycle()
        return null
    }

    private fun tryDrawPage(
        page: PageData, songName: String, rows: Int, st: Style,
        pageIndex: Int, pageCount: Int, infoItems: List<Pair<String, String>>,
        size: Size, scale: Float
    ): Bitmap? {
        val outW = max(1, (size.width * scale).roundToInt())
        val outH = max(1, (size.height * scale).roundToInt())

        val bitmap = try {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }

        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            drawVerticalGradient(canvas, size.width, size.height, st.bg, st.bgGlow)
            if (st.showStars) drawStars(canvas, size.width, size.height)
            drawBgPattern(canvas, size.width, size.height, st, fill, stroke)
            drawDeco(canvas, size.width, size.height, st, fill, stroke)
            drawWatermark(canvas, size.width, size.height, st)

            textPaint.apply {
                typeface = Typeface.create(FONT_STACK, Typeface.BOLD)
                textSize = st.titleSize
                color = argb(st.titleColor, 1f)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(songName, size.width / 2f, st.pad - textPaint.fontMetrics.top, textPaint)

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

            val infoTop = st.pad + size.headerH
            if (st.showInfoPanel && infoItems.isNotEmpty()) {
                drawInfoPanel(canvas, st.pad, infoTop, size.bodyW, infoItems, st)
            }

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

            drawAntiTheftNoise(canvas, size.width, size.height, fill)

            return bitmap
        } catch (e: OutOfMemoryError) {
            bitmap.recycle()
            return null
        } catch (e: Throwable) {
            bitmap.recycle()
            return null
        }
    }

    private fun drawBgPattern(canvas: Canvas, w: Float, h: Float, st: Style, fill: Paint, stroke: Paint) {
        if (st.bgPattern == "none") return
        stroke.color = argb(st.gridLine, 0.05f)
        stroke.strokeWidth = 1f
        val step = 40f
        if (st.bgPattern == "grid") {
            var x = 0f
            while (x < w) { canvas.drawLine(x, 0f, x, h, stroke); x += step }
            var y = 0f
            while (y < h) { canvas.drawLine(0f, y, w, y, stroke); y += step }
        } else if (st.bgPattern == "dots") {
            fill.color = argb(st.gridLine, 0.05f)
            var x = 0f
            while (x < w) {
                var y = 0f
                while (y < h) { canvas.drawCircle(x, y, 1.5f, fill); y += 30f }
                x += 30f
            }
        } else if (st.bgPattern == "diagonal") {
            for (i in -(w.toInt())..h.toInt() step 30) {
                canvas.drawLine(i.toFloat(), 0f, i + h, h, stroke)
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
            if (st.decoType == "stars") {
                canvas.drawCircle(x, y, rnd.nextFloat() * 1.5f + 0.5f, fill)
            } else if (st.decoType == "snow") {
                canvas.drawLine(x - 4f, y, x + 4f, y, stroke)
                canvas.drawLine(x, y - 4f, x, y + 4f, stroke)
            } else if (st.decoType == "bubbles") {
                canvas.drawCircle(x, y, rnd.nextFloat() * 6f + 2f, stroke)
            } else if (st.decoType == "geometric") {
                val path = Path()
                path.moveTo(x, y - 3f)
                path.lineTo(x + 3f, y + 2f)
                path.lineTo(x - 3f, y + 2f)
                path.close()
                canvas.drawPath(path, fill)
            }
        }
    }

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

            val maxValueW = colW - labelW - 16f
            var value = item.second
            if (valuePaint.measureText(value) > maxValueW) {
                while (value.length > 1 && valuePaint.measureText(value + "…") > maxValueW) {
                    value = value.substring(0, value.length - 1)
                }
                value += "…"
            }
            canvas.drawText(value, ix + labelW + 8f, baseline, valuePaint)
        }

        val lineY = y + rowCount * rowH + 6f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = argb(st.dividerColor, st.dividerAlpha)
        }
        canvas.drawLine(x, lineY, x + width, lineY, linePaint)
    }

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
                if (st.noteShape == "circle") {
                    path.addCircle(bx + st.cell / 2f, by + st.cell / 2f, st.cell / 2f, Path.Direction.CW)
                } else if (st.noteShape == "diamond") {
                    path.moveTo(bx + st.cell / 2f, by)
                    path.lineTo(bx + st.cell, by + st.cell / 2f)
                    path.lineTo(bx + st.cell / 2f, by + st.cell)
                    path.lineTo(bx, by + st.cell / 2f)
                    path.close()
                } else {
                    path.addRoundRect(block, st.gridRadius, st.gridRadius, Path.Direction.CW)
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

    private fun drawAntiTheftNoise(c: Canvas, w: Float, h: Float, paint: Paint) {
        paint.style = Paint.Style.FILL
        val rnd = Random(20260101L)
        val colors = intArrayOf(Color.WHITE, Color.BLACK, Color.RED, Color.BLUE)
        for (i in 0 until 3000) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            paint.color = colors[i % 4]
            paint.alpha = 12
            c.drawRect(x, y, x + 2f, y + 2f, paint)
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
            columns.add(ColumnData(colObj.optLong("t", 0L), values.filter { it in 0 until rows }.toIntArray()))
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
        s = s.replace(Regex("[\\\\/:*?\"<>\\r\\n\\t]"), "_")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH).trim()
        if (s.isEmpty() || s == "." || s == "..") s = "未命名乐谱"
        return s
    }

    // ============================ 样式 ============================

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
