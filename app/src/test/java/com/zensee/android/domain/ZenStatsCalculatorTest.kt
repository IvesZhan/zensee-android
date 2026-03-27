package com.zensee.android.domain

import com.zensee.android.model.MeditationSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ZenStatsCalculatorTest {

    @Test
    fun `calculate aggregates streak weekly minutes and totals from sessions`() {
        val today = LocalDate.of(2026, 3, 23)
        val sessions = listOf(
            MeditationSessionSummary(sessionDate = today, durationMinutes = 25),
            MeditationSessionSummary(sessionDate = today.minusDays(1), durationMinutes = 20),
            MeditationSessionSummary(sessionDate = today.minusDays(2), durationMinutes = 15),
            MeditationSessionSummary(sessionDate = today.minusDays(8), durationMinutes = 35)
        )

        val stats = ZenStatsCalculator.calculate(
            sessions = sessions,
            today = today
        )

        assertEquals(4, stats.totalDays)
        assertEquals(95, stats.totalMinutes)
        assertEquals(3, stats.streakDays)
        assertEquals(listOf(25, 0, 0, 0, 0, 0, 0), stats.weeklyMinutes)
        assertEquals(25, stats.weeklyAverageMinutes)
        assertEquals(25, stats.yearHeatmapByDate[today])
        assertEquals(35, stats.yearHeatmapByDate[today.minusDays(8)])
    }
}
