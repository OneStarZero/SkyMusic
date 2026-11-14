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

    fun clickPoints(points: List<Pair<Float, Float>>) {
        if (points.isEmpty() || instance == null) return
        val safePoints = if (points.size > 5) points.take(5) else points // 防闪退保护

        val builder = GestureDescription.Builder()
        val startTime = 0L 
        val duration = 80L

        for (p in safePoints) {
            val path = Path().apply { 
                moveTo(p.first, p.second) 
                lineTo(p.first + 1f, p.second + 1f) 
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, startTime, duration))
        }
        try { dispatchGesture(builder.build(), null, null) } catch (e: Exception) { e.printStackTrace() }
    }
}