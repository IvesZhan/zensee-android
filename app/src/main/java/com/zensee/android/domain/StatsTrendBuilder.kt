package com.zensee.android.domain

import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class StatsPeriod {
    WEEK,
    MONTH,
    LAST_30_DAYS
}

data class StatsTrendPoint(
    val label: String,
    val value: Int,
    val isToday: Boolean
)

object StatsTrendBuilder {
    fun build(
        minutesByDate: Map<LocalDate, Int>,
        period: StatsPeriod,
        today: LocalDate = LocalDate.now()
    ): List<StatsTrendPoint> {
        return when (period) {
            StatsPeriod.WEEK -> buildWeek(minutesByDate, today)
            StatsPeriod.MONTH -> buildMonth(minutesByDate, today)
            StatsPeriod.LAST_30_DAYS -> buildLast30Days(minutesByDate, today)
        }
    }

    private fun buildWeek(
        minutesByDate: Map<LocalDate, Int>,
        today: LocalDate
    ): List<StatsTrendPoint> {
        val monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val labels = listOf("一", "二", "三", "四", "五", "六", "日")
        return (0..6).map { offset ->
            val date = monday.plusDays(offset.toLong())
            StatsTrendPoint(
                label = labels[offset],
                value = minutesByDate[date] ?: 0,
                isToday = date == today
            )
        }
    }

    private fun buildMonth(
        minutesByDate: Map<LocalDate, Int>,
        today: LocalDate
    ): List<StatsTrendPoint> {
        val startOfMonth = today.withDayOfMonth(1)
        val daysInMonth = today.lengthOfMonth()
        return (1..daysInMonth).map { day ->
            val date = startOfMonth.withDayOfMonth(day)
            val label = when {
                day == 1 -> "${date.monthValue}月"
                day == 10 || day == 20 -> day.toString()
                date == today -> "今"
                else -> ""
            }
            StatsTrendPoint(
                label = label,
                value = minutesByDate[date] ?: 0,
                isToday = date == today
            )
        }
    }

    private fun buildLast30Days(
        minutesByDate: Map<LocalDate, Int>,
        today: LocalDate
    ): List<StatsTrendPoint> {
        return (29 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val label = when {
                offset == 0 -> "今"
                offset % 7 == 0 -> date.dayOfMonth.toString()
                else -> ""
            }
            StatsTrendPoint(
                label = label,
                value = minutesByDate[date] ?: 0,
                isToday = date == today
            )
        }
    }
}
