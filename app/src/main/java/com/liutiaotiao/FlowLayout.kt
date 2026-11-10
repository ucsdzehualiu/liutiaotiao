package com.liutiaotiao

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 极简流式布局：子 View 从左往右排，一行放不下就换行。
 *
 * 用来承载"自定义关键词"的标签。自己写而不引 Material 的 ChipGroup，
 * 是因为 ChipGroup 要求主题继承 Theme.MaterialComponents，改动面比这个
 * 60 行的小控件大得多，还要多一个依赖。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /** 同一行内标签的水平间距。 */
    var horizontalSpacing: Int = dp(8f)

    /** 行与行之间的垂直间距。 */
    var verticalSpacing: Int = dp(8f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)

        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var maxLineWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val w = child.measuredWidth
            val h = child.measuredHeight

            // 当前行已放过东西，且再加这个就超宽 → 换行。
            if (lineWidth > 0 && lineWidth + horizontalSpacing + w > available) {
                totalHeight += lineHeight + verticalSpacing
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                lineWidth = 0
                lineHeight = 0
            }

            lineWidth += (if (lineWidth > 0) horizontalSpacing else 0) + w
            lineHeight = maxOf(lineHeight, h)
        }
        totalHeight += lineHeight
        maxLineWidth = maxOf(maxLineWidth, lineWidth)

        val contentWidth = maxLineWidth + paddingLeft + paddingRight
        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(widthSize, contentWidth)
            else -> contentWidth
        }
        val measuredHeight = resolveSize(
            totalHeight + paddingTop + paddingBottom,
            heightMeasureSpec
        )
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        val startX = paddingLeft
        var x = startX
        var y = paddingTop
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val w = child.measuredWidth
            val h = child.measuredHeight

            if (x > startX && x + w > startX + available) {
                x = startX
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }

            child.layout(x, y, x + w, y + h)
            x += w + horizontalSpacing
            lineHeight = maxOf(lineHeight, h)
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
