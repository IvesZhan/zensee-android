package com.zensee.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zensee.android.R
import com.zensee.android.domain.StatsTrendPoint
import kotlin.math.max

class StatsTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    enum class ChartMode { BAR, LINE }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f.dp
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subtleBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.zs_primary_dark),
            (0.08f * 255).toInt()
        )
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f.dp
        color = ContextCompat.getColor(context, R.color.zs_primary)
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val averagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.zs_stats_gold),
            (0.35f * 255).toInt()
        )
        strokeWidth = 1.5f.dp
    }
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.zs_background)
    }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.dp
    }

    private var points: List<StatsTrendPoint> = emptyList()
    private var averageMinutes: Int = 0
    private var chartMode: ChartMode = ChartMode.BAR

    fun setData(
        points: List<StatsTrendPoint>,
        averageMinutes: Int,
        chartMode: ChartMode
    ) {
        this.points = points
        this.averageMinutes = averageMinutes
        this.chartMode = chartMode
        requestLayout()
        invalidate()
    }

    fun scrollToToday(scrollView: HorizontalScrollView) {
        val todayIndex = points.indexOfFirst { it.isToday }
        if (todayIndex < 0) return
        post {
            val target = (todayIndex * itemWidthPx() + itemWidthPx() / 2f - scrollView.width / 2f).toInt()
            scrollView.smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    fun averageBadgeTop(badgeHeight: Int): Float {
        if (averageMinutes <= 0 || points.isEmpty() || height == 0) return 0f
        val maxValue = max(points.maxOfOrNull { it.value } ?: 0, 1)
        val ratio = (averageMinutes.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
        val lineY = when (chartMode) {
            ChartMode.BAR -> 12f.dp + 160f.dp - (160f.dp * ratio)
            ChartMode.LINE -> 6f.dp + 180f.dp - (180f.dp * ratio)
        }
        val desiredTop = lineY - badgeHeight - 6f.dp
        val minTop = 4f.dp
        val maxTop = (height - badgeHeight - 24f.dp).coerceAtLeast(minTop)
        return desiredTop.coerceIn(minTop, maxTop)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = max((points.size * itemWidthPx()).toInt(), 300.dp)
        val desiredHeight = 220.dp
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        when (chartMode) {
            ChartMode.BAR -> drawBarChart(canvas)
            ChartMode.LINE -> drawLineChart(canvas)
        }
    }

    private fun drawBarChart(canvas: Canvas) {
        val top = 12f.dp
        val chartHeight = 160f.dp
        val labelBaseY = top + chartHeight + 28f.dp
        val maxValue = max(points.maxOfOrNull { it.value } ?: 0, 1)
        val barWidth = minOf(18f.dp, itemWidthPx() * 0.42f)

        drawAverageLine(canvas, top, chartHeight, maxValue)

        points.forEachIndexed { index, point ->
            val centerX = index * itemWidthPx() + itemWidthPx() / 2f
            val ratio = point.value.toFloat() / maxValue.toFloat()
            val barHeight = if (point.value > 0) max(2f.dp, chartHeight * ratio) else 2f.dp
            val rect = RectF(centerX - barWidth / 2f, top + chartHeight - barHeight, centerX + barWidth / 2f, top + chartHeight)
            if (point.isToday) {
                barPaint.shader = LinearGradient(
                    rect.centerX(),
                    rect.top,
                    rect.centerX(),
                    rect.bottom,
                    intArrayOf(
                        ContextCompat.getColor(context, R.color.zs_stats_gold),
                        ContextCompat.getColor(context, R.color.zs_primary)
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, 4f.dp, 4f.dp, barPaint)
                barPaint.shader = null
            } else if (point.value > 0) {
                barPaint.color = ColorUtils.setAlphaComponent(
                    ContextCompat.getColor(context, R.color.zs_primary),
                    (0.65f * 255).toInt()
                )
                canvas.drawRoundRect(rect, 4f.dp, 4f.dp, barPaint)
            } else {
                canvas.drawRoundRect(rect, 4f.dp, 4f.dp, subtleBarPaint)
            }
            drawLabel(canvas, point, centerX, labelBaseY)
        }
    }

    private fun drawLineChart(canvas: Canvas) {
        val top = 6f.dp
        val chartHeight = 180f.dp
        val labelBaseY = top + chartHeight + 20f.dp
        val maxValue = max(points.maxOfOrNull { it.value } ?: 0, 1)

        drawAverageLine(canvas, top, chartHeight, maxValue)

        val linePath = Path()
        val areaPath = Path()
        points.forEachIndexed { index, point ->
            val x = index * itemWidthPx() + itemWidthPx() / 2f
            val y = top + chartHeight - (chartHeight * (point.value.toFloat() / maxValue.toFloat()))
            if (index == 0) {
                linePath.moveTo(x, y)
                areaPath.moveTo(x, top + chartHeight)
                areaPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
        }
        val lastX = (points.lastIndex * itemWidthPx()) + itemWidthPx() / 2f
        areaPath.lineTo(lastX, top + chartHeight)
        areaPath.close()

        areaPaint.shader = LinearGradient(
            0f,
            top,
            0f,
            top + chartHeight,
            intArrayOf(
                ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.zs_primary), (0.12f * 255).toInt()),
                ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.zs_primary), 0)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(areaPath, areaPaint)
        canvas.drawPath(linePath, linePaint)

        points.forEachIndexed { index, point ->
            val x = index * itemWidthPx() + itemWidthPx() / 2f
            val y = top + chartHeight - (chartHeight * (point.value.toFloat() / maxValue.toFloat()))
            if (point.value > 0 || point.isToday) {
                pointStrokePaint.color = ContextCompat.getColor(
                    context,
                    if (point.isToday) R.color.zs_stats_gold else R.color.zs_primary
                )
                canvas.drawCircle(x, y, 4f.dp, pointFillPaint)
                canvas.drawCircle(x, y, 4f.dp, pointStrokePaint)
            }
            drawLabel(canvas, point, x, labelBaseY)
        }
    }

    private fun drawAverageLine(canvas: Canvas, top: Float, chartHeight: Float, maxValue: Int) {
        if (averageMinutes <= 0) return
        val ratio = averageMinutes.toFloat() / maxValue.toFloat()
        val y = top + chartHeight - (chartHeight * ratio)
        canvas.drawLine(0f, y, width.toFloat(), y, averagePaint)
    }

    private fun drawLabel(canvas: Canvas, point: StatsTrendPoint, x: Float, y: Float) {
        textPaint.color = ContextCompat.getColor(
            context,
            if (point.isToday) R.color.zs_stats_gold else R.color.zs_text_subtle
        )
        textPaint.isFakeBoldText = point.isToday
        canvas.drawText(if (point.label.isBlank()) " " else point.label, x, y, textPaint)
    }

    private fun itemWidthPx(): Float = if (points.size <= 7) 45f.dp else 32f.dp

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density
}
