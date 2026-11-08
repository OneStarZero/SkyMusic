package com.skymusic

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class FlowLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val mLineHeight = mutableListOf<Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        val childCount = childCount
        var lineWidth = 0
        var totalHeight = 0
        var maxWidth = 0
        var i = 0
        mLineHeight.clear()

        while (i < childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (lineWidth + childWidth > width - paddingLeft - paddingRight) {
                maxWidth = Math.max(maxWidth, lineWidth)
                mLineHeight.add(totalHeight)
                totalHeight += childHeight
                lineWidth = childWidth
            } else {
                lineWidth += childWidth
                totalHeight = Math.max(totalHeight, childHeight)
            }
            i++
        }
        maxWidth = Math.max(maxWidth, lineWidth)
        mLineHeight.add(totalHeight)
        
        totalHeight = 0
        for (h in mLineHeight) {
            totalHeight += h
        }
        
        setMeasuredDimension(
            if (widthMode == MeasureSpec.EXACTLY) width else maxWidth + paddingLeft + paddingRight,
            if (heightMode == MeasureSpec.EXACTLY) height else totalHeight + paddingTop + paddingBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var lineWidth = 0
        var lineTop = paddingTop
        var lineIndex = 0
        var maxHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (lineWidth + childWidth + lp.leftMargin + lp.rightMargin > width) {
                lineTop += mLineHeight[lineIndex]
                lineWidth = 0
                lineIndex++
            }
            val left = paddingLeft + lineWidth + lp.leftMargin
            val top = lineTop + lp.topMargin
            val right = left + childWidth
            val bottom = top + childHeight
            child.layout(left, top, right, bottom)

            lineWidth += childWidth + lp.leftMargin + lp.rightMargin
            maxHeight = Math.max(maxHeight, childHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }
}
