package com.skymusic.log

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SkyMusic 统一日志系统
 * - 记录时间（毫秒精度）、线程名、类型
 * - 同时输出到 Logcat 和文件
 * - 文件：/storage/emulated/0/SkyMusic-OneStar/DebugAndLogs/yyyy-MM-dd_HH-mm-ss.log
 */
object SkyLog {

    private const val TAG = "SkyMusic"
    private const val LOG_DIR = "/storage/emulated/0/SkyMusic-OneStar/DebugAndLogs"

    enum class Level(val label: String) {
        DEBUG("DEBUG"),
        INFO("INFO"),
        WARN("WARN"),
        ERROR("ERROR")
    }

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SkyLog-Writer").apply { isDaemon = true }
    }

    private val initialized = AtomicBoolean(false)
    private var logFile: File? = null

    /** 初始化（App 启动时调用一次） */
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            val dir = File(LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val name = fileFormat.format(Date()) + ".log"
            logFile = File(dir, name)
            writeRaw("[${timeFormat.format(Date())}][${Thread.currentThread().name}][INFO] ===== SkyMusic 日志开始 =====")
        } catch (t: Throwable) {
            Log.e(TAG, "日志初始化失败", t)
        }
    }

    fun d(message: String) = log(Level.DEBUG, message)
    fun i(message: String) = log(Level.INFO, message)
    fun w(message: String) = log(Level.WARN, message)
    fun e(message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
        log(Level.ERROR, msg)
    }

    /** 核心写入 */
    fun log(level: Level, message: String) {
        val threadName = Thread.currentThread().name
        val time = timeFormat.format(Date())
        val line = "[$time][$threadName][${level.label}] $message"

        // Logcat
        when (level) {
            Level.DEBUG -> Log.d(TAG, line)
            Level.INFO -> Log.i(TAG, line)
            Level.WARN -> Log.w(TAG, line)
            Level.ERROR -> Log.e(TAG, line)
        }

        // 文件（异步）
        writeRaw(line)
    }

    private fun writeRaw(line: String) {
        val file = logFile ?: return
        executor.execute {
            try {
                file.appendText(line + "\n", Charsets.UTF_8)
            } catch (_: Throwable) {
            }
        }
    }

    /** 当前日志文件路径 */
    fun currentLogPath(): String? = logFile?.absolutePath
}
