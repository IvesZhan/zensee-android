package com.zensee.android.domain

import com.zensee.android.model.MeditationSessionSummary
import com.zensee.android.model.ZenStatsSnapshot
import java.time.DayOfWeek
import java.time.LocalDate

object ZenStatsCalculator {
    fun calculate(
        sessions: List<MeditationSessionSummary>,
        today: LocalDate = LocalDate.now()
    ): ZenStatsSnapshot {
        val minutesByDate = sessions
            .groupBy { it.sessionDate }
            .mapValues { (_, values) -> values.sumOf { it.durationMinutes } }
        val yearStart = today.withDayOfYear(1)
        val yearHeatmapByDate = minutesByDate.filterKeys { !it.isBefore(yearStart) }

        val streakDays = buildStreak(minutesByDate, today)
        val weeklyMinutes = buildWeeklyMinutes(minutesByDate, today)
        val activeDaysThisWeek = weeklyMinutes.filter { it > 0 }
        val weeklyAverage = if (activeDaysThisWeek.isEmpty()) 0 else activeDaysThisWeek.sum() / activeDaysThisWeek.size

        return ZenStatsSnapshot(
            totalDays = minutesByDate.size,
            totalMinutes = minutesByDate.values.sum(),
            streakDays = streakDays,
            weeklyMinutes = weeklyMinutes,
            weeklyAverageMinutes = weeklyAverage,
            heatmapByDate = minutesByDate,
            yearHeatmapByDate = yearHeatmapByDate
        )
    }

    private fun buildStreak(
        minutesByDate: Map<LocalDate, Int>,
        today: LocalDate
    ): Int {
        var cursor = today
        var streak = 0
        while (minutesByDate.containsKey(cursor)) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun buildWeeklyMinutes(
        minutesByDate: Map<LocalDate, Int>,
        today: LocalDate
    ): List<Int> {
        val monday = today.with(DayOfWeek.MONDAY)
        return (0..6).map { offset ->
            minutesByDate[monday.plusDays(offset.toLong())] ?: 0
        }
    }
}
