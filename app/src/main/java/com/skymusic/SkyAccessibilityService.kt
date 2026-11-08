package com.skymusic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class SkyAccessibilityService : AccessibilityService() {
    companion object {
        var instance: SkyAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { instance = null }

    fun clickPoints(points: List<Pair<Float, Float>>, duration: Long = 50L) {
        if (points.isEmpty() || instance == null) return
        val builder = GestureDescription.Builder()
        for (p in points) {
            val path = Path().apply { moveTo(p.first, p.second) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        }
        try { dispatchGesture(builder.build(), null, null) } catch (e: Exception) {}
    }
}
