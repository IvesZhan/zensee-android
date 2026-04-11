package com.zensee.android.model

import java.time.Instant
import java.time.LocalDate

data class MeditationSessionSummary(
    val id: String = "",
    val sessionDate: LocalDate,
    val durationMinutes: Int,
    val startedAt: Instant = Instant.EPOCH,
    val endedAt: Instant = Instant.EPOCH
)

data class MoodRecord(
    val id: String,
    val mood: String,
    val note: String?,
    val meditationDuration: Int?,
    val createdAt: Instant
)

data class HistoryDayGroup(
    val id: String,
    val date: LocalDate,
    val sessions: List<MeditationSessionSummary>
) {
    val totalMinutes: Int
        get() = sessions.sumOf { it.durationMinutes }
}

data class MoodDayGroup(
    val id: String,
    val date: LocalDate,
    val records: List<MoodRecord>
)

data class ZenQuote(
    val text: String,
    val source: String
)

data class HomeSnapshot(
    val todayMinutes: Int,
    val streakDays: Int,
    val totalDays: Int,
    val currentMood: String,
    val quote: ZenQuote,
    val recentMoods: List<MoodRecord>
)

data class ZenStatsSnapshot(
    val totalDays: Int,
    val totalMinutes: Int,
    val streakDays: Int,
    val weeklyMinutes: List<Int>,
    val weeklyAverageMinutes: Int,
    val heatmapByDate: Map<LocalDate, Int>,
    val yearHeatmapByDate: Map<LocalDate, Int>
)

data class GroupCard(
    val id: String,
    val name: String,
    val description: String,
    val memberCount: Int,
    val isJoined: Boolean
)

data class ProfileSnapshot(
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
    val streakDays: Int,
    val totalDays: Int,
    val totalMinutes: Int
)
