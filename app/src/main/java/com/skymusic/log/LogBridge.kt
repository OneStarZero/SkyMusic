package com.skymusic.log

import android.webkit.JavascriptInterface

/**
 * 前端日志桥接
 * JS 调用：window.AndroidBridge.log('INFO', '消息')
 */
class LogBridge {

    @JavascriptInterface
    fun log(level: String, message: String) {
        val lv = when (level.uppercase()) {
            "DEBUG" -> SkyLog.Level.DEBUG
            "WARN" -> SkyLog.Level.WARN
            "ERROR" -> SkyLog.Level.ERROR
            else -> SkyLog.Level.INFO
        }
        SkyLog.log(lv, "[WebView] $message")
    }

    @JavascriptInterface
    fun getLogPath(): String {
        return SkyLog.currentLogPath() ?: ""
    }
}