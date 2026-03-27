package com.zensee.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsTrendBuilderTest {
    @Test
    fun `build week trend aligns monday through sunday and marks today`() {
        val today = LocalDate.of(2026, 3, 25)
        val minutesByDate = mapOf(
            LocalDate.of(2026, 3, 23) to 20,
            LocalDate.of(2026, 3, 24) to 35,
            LocalDate.of(2026, 3, 25) to 15
        )

        val points = StatsTrendBuilder.build(
            minutesByDate = minutesByDate,
            period = StatsPeriod.WEEK,
            today = today
        )

        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), points.map { it.label })
        assertEquals(listOf(20, 35, 15, 0, 0, 0, 0), points.map { it.value })
        assertTrue(points[2].isToday)
    }
}
