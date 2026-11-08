package com.skymusic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper

class SkyAccessibilityService : AccessibilityService() {
    companion object {
        var instance: SkyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    
    override fun onInterrupt() {
        instance = null
    }

    /**
     * 优化后的多指并发点击
     * @param points 需要同时点击的坐标点集合
     */
    fun clickPoints(points: List<Pair<Float, Float>>) {
        if (points.isEmpty() || instance == null) return

        val builder = GestureDescription.Builder()
        
        // 核心优化：强制所有按压的时间轴完全一致
        val startTime = 0L 
        val duration = 80L // 增加按压时长，让系统更容易识别为多指和弦

        for (p in points) {
            val path = Path().apply { 
                moveTo(p.first, p.second) 
                // 有些机型如果只 moveTo 不 lineTo，可能会判定异常
                lineTo(p.first + 1f, p.second + 1f) 
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, startTime, duration))
        }

        try {
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
