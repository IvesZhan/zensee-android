package com.zensee.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zensee.android.R
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

class StatsYearHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val todayStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp
        color = ContextCompat.getColor(context, R.color.zs_stats_gold)
    }
    private val monthTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.zs_text_subtle)
        textSize = 10f.dp
    }
    private val cellRect = RectF()

    private var yearHeatmapByDate: Map<LocalDate, Int> = emptyMap()
    private var today: LocalDate = LocalDate.now()

    fun setData(yearHeatmapByDate: Map<LocalDate, Int>, today: LocalDate = LocalDate.now()) {
        this.yearHeatmapByDate = yearHeatmapByDate
        this.today = today
        requestLayout()
        invalidate()
    }

    fun scrollToToday(scrollView: HorizontalScrollView) {
        post {
            val target = (weekIndex(today) * weekWidthPx() - scrollView.width / 2f).toInt()
            scrollView.smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = max((totalWeeks() * weekWidthPx()).toInt(), 220.dp)
        val desiredHeight = (22 + 10 + (7 * 10) + (6 * 4) + 4).dp
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentYear = today.year
        val monthLabelY = 10f.dp

        (1..today.monthValue).forEach { month ->
            val firstDay = LocalDate.of(currentYear, month, 1)
            val x = weekIndex(firstDay) * weekWidthPx()
            canvas.drawText("${month}月", x, monthLabelY, monthTextPaint)
        }

        val gridTop = 32f.dp
        for (week in 0 until totalWeeks()) {
            for (day in 0 until 7) {
                val date = firstDisplayedWeekStart().plusDays((week * 7 + day).toLong())
                val left = week * weekWidthPx()
                val top = gridTop + day * (cellSizePx() + cellSpacingPx())
                cellRect.set(left, top, left + cellSizePx(), top + cellSizePx())

                if (date.year != currentYear || date.isAfter(today)) {
                    continue
                }

                cellPaint.color = colorFor(date)
                canvas.drawRoundRect(cellRect, 2f.dp, 2f.dp, cellPaint)

                if (date == today) {
                    canvas.drawRoundRect(cellRect, 2f.dp, 2f.dp, todayStrokePaint)
                }
            }
        }
    }

    private fun colorFor(date: LocalDate): Int {
        val minutes = yearHeatmapByDate[date] ?: 0
        if (minutes <= 0) {
            return ColorUtils.setAlphaComponent(
                ContextCompat.getColor(context, R.color.zs_primary_dark),
                (0.06f * 255).toInt()
            )
        }
        val value = (minutes / 60f).coerceIn(0f, 1f)
        return if (value < 0.5f) {
            ColorUtils.setAlphaComponent(
                ContextCompat.getColor(context, R.color.zs_primary),
                ((0.25f + value * 0.6f) * 255).toInt()
            )
        } else {
            ColorUtils.setAlphaComponent(
                ContextCompat.getColor(context, R.color.zs_stats_gold),
                ((0.4f + (value - 0.5f) * 1.2f) * 255).toInt()
            )
        }
    }

    private fun totalWeeks(): Int {
        val days = java.time.temporal.ChronoUnit.DAYS.between(firstDisplayedWeekStart(), currentWeekStart()).toInt()
        return max(days / 7 + 1, 1)
    }

    private fun currentWeekStart(): LocalDate =
        today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    private fun firstDisplayedWeekStart(): LocalDate =
        LocalDate.of(today.year, 1, 1).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    private fun weekIndex(date: LocalDate): Int {
        val weekStart = date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val days = java.time.temporal.ChronoUnit.DAYS.between(firstDisplayedWeekStart(), weekStart).toInt()
        return max(days / 7, 0)
    }

    private fun weekWidthPx(): Float = cellSizePx() + cellSpacingPx()

    private fun cellSizePx(): Float = 10f.dp

    private fun cellSpacingPx(): Float = 4f.dp

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density
}
