package com.skymusic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class SkyAccessibilityService : AccessibilityService() {
    companion object { var instance: SkyAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { instance = null }

    /**
     * 核心多点触控方法
     * 将传入的所有点位打包成一个 GestureDescription，实现真正的多指并发按压
     */
    fun clickPoints(points: List<Pair<Float, Float>>) {
        if (points.isEmpty() || instance == null) return
        
        // 🌟 极致性能配置：支持最多 10 指并发（如果传入更多则截断，防止系统崩溃）
        val safePoints = if (points.size > 10) points.take(10) else points

        val builder = GestureDescription.Builder()
        val startTime = 0L 
        // 按压持续时间，80ms 是经过测试在光遇里判定最灵敏的时长
        val duration = 80L 

        for (p in safePoints) {
            val path = Path().apply { 
                moveTo(p.first, p.second) 
                // 加一条 1px 的微小线段，防止某些老旧机型把只有起点的 Path 当作无效点击
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