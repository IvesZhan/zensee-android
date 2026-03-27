package com.zensee.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zensee.android.R
import kotlin.math.roundToInt

class DialRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val tickSpacing = 12f.dp
    private val indicatorWidth = 4.dp
    private val indicatorHeight = 40.dp
    private val indicatorBottomOffset = 10.dp

    private var range: IntRange = 5..120
    private var currentValue = 30
    private var isUserDragging = false
    private var suppressScrollCallback = false
    private var snapAnimator: ValueAnimator? = null

    private val scrollView = object : HorizontalScrollView(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val handled = super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    isUserDragging = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserDragging = false
                    post { snapToNearestTick(animated = true) }
                }
            }
            return handled
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            dispatchValueFromScroll(l)
        }

        override fun fling(velocityX: Int) {
            super.fling((velocityX * 0.35f).roundToInt())
        }
    }

    private val tickStrip = TickStripView(context)

    var onValueChanged: ((Int) -> Unit)? = null

    var value: Int
        get() = currentValue
        set(newValue) {
            currentValue = newValue.coerceIn(range.first, range.last)
            if (width == 0) {
                post { scrollToValue(currentValue, animated = false) }
            } else {
                scrollToValue(currentValue, animated = false)
            }
        }

    init {
        clipChildren = false
        clipToPadding = false

        scrollView.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(
                tickStrip,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(IndicatorView(context), LayoutParams(indicatorWidth, indicatorHeight, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = indicatorBottomOffset
        })

        post { updateViewport() }
    }

    fun configure(range: IntRange, initialValue: Int) {
        this.range = range
        tickStrip.setRange(range, tickSpacing)
        currentValue = initialValue.coerceIn(range.first, range.last)
        post { updateViewport() }
    }

    override fun onDetachedFromWindow() {
        snapAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) updateViewport()
    }

    private fun updateViewport() {
        if (width == 0) return
        val horizontalInset = width / 2
        scrollView.setPadding(horizontalInset, 0, horizontalInset, 0)
        tickStrip.setRange(range, tickSpacing)
        scrollToValue(currentValue, animated = false)
    }

    private fun scrollToValue(targetValue: Int, animated: Boolean) {
        val targetScrollX = ((targetValue - range.first) * tickSpacing).roundToInt()
        snapAnimator?.cancel()
        if (!animated) {
            suppressScrollCallback = true
            scrollView.scrollTo(targetScrollX, 0)
            suppressScrollCallback = false
            return
        }

        val start = scrollView.scrollX
        snapAnimator = ValueAnimator.ofInt(start, targetScrollX).apply {
            duration = 180L
            addUpdateListener { animator ->
                suppressScrollCallback = true
                scrollView.scrollTo(animator.animatedValue as Int, 0)
                suppressScrollCallback = false
            }
            start()
        }
    }

    private fun dispatchValueFromScroll(scrollX: Int) {
        if (suppressScrollCallback) return
        val maxIndex = range.last - range.first
        val nearestIndex = (scrollX / tickSpacing).roundToInt().coerceIn(0, maxIndex)
        val newValue = range.first + nearestIndex
        if (newValue == currentValue) return

        currentValue = newValue
        if (isUserDragging) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onValueChanged?.invoke(newValue)
    }

    private fun snapToNearestTick(animated: Boolean) {
        val maxIndex = range.last - range.first
        val nearestIndex = (scrollView.scrollX / tickSpacing).roundToInt().coerceIn(0, maxIndex)
        val snappedValue = range.first + nearestIndex
        if (snappedValue != currentValue) {
            currentValue = snappedValue
            onValueChanged?.invoke(snappedValue)
        }
        scrollToValue(snappedValue, animated)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density
}

private class IndicatorView(context: Context) : View(context) {
    private val cornerRadius = 999f.dp
    private val rect = RectF()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.zs_primary),
            (0.12f * 255).roundToInt()
        )
        maskFilter = android.graphics.BlurMaskFilter(8f.dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.zs_primary)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp
        color = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.zs_white),
            (0.18f * 255).roundToInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            width - paddingRight.toFloat(),
            height - paddingBottom.toFloat()
        )

        val glowRect = RectF(rect).apply { inset(-2f.dp, -2f.dp) }
        canvas.drawRoundRect(glowRect, cornerRadius, cornerRadius, glowPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bodyPaint)

        highlightPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.top,
            intArrayOf(
                ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.zs_white), (0.28f * 255).roundToInt()),
                ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.zs_white), (0.08f * 255).roundToInt()),
                ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.zs_primary_dark), (0.16f * 255).roundToInt())
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, highlightPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, edgePaint)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density
}

private class TickStripView(context: Context) : View(context) {
    private val shortTick = 12f.dp
    private val mediumTick = 20f.dp
    private val longTick = 32f.dp
    private val tickWidth = 1.5f.dp
    private val tickBottomInset = 10f.dp
    private val stripHeight = 120.dp

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.zs_text_subtle),
            (0.4f * 255).roundToInt()
        )
        strokeCap = Paint.Cap.ROUND
        strokeWidth = tickWidth
    }

    private var range: IntRange = 5..120
    private var tickSpacing = 12f.dp

    fun setRange(range: IntRange, tickSpacing: Float) {
        this.range = range
        this.tickSpacing = tickSpacing
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = ((range.last - range.first) * tickSpacing).roundToInt() + paddingLeft + paddingRight + 1
        val resolvedWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val resolvedHeight = resolveSize(stripHeight, heightMeasureSpec)
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bottom = height - tickBottomInset
        for (minute in range) {
            val index = minute - range.first
            val x = index * tickSpacing
            val tickHeight = when {
                minute % 10 == 0 -> longTick
                minute % 5 == 0 -> mediumTick
                else -> shortTick
            }
            canvas.drawLine(x, bottom, x, bottom - tickHeight, tickPaint)
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density
}
