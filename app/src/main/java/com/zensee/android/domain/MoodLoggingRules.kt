package com.zensee.android.domain

data class MoodLogSubmission(
    val mood: String,
    val note: String?,
    val durationMinutes: Int?
)

object MoodLoggingRules {
    fun canSubmit(selectedMood: String?): Boolean = !selectedMood.isNullOrBlank()

    fun buildSubmission(
        selectedMood: String?,
        noteText: String,
        durationMinutes: Int?
    ): MoodLogSubmission? {
        val mood = selectedMood?.takeIf { it.isNotBlank() } ?: return null
        return MoodLogSubmission(
            mood = mood,
            note = noteText.takeIf { it.isNotBlank() },
            durationMinutes = durationMinutes
        )
    }
}
