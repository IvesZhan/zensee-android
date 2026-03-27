package com.zensee.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodLoggingRulesTest {
    @Test
    fun `canSubmit requires selected mood`() {
        assertFalse(MoodLoggingRules.canSubmit(null))
        assertFalse(MoodLoggingRules.canSubmit(""))
        assertTrue(MoodLoggingRules.canSubmit("平静"))
    }

    @Test
    fun `buildSubmission drops blank note and keeps duration`() {
        val submission = MoodLoggingRules.buildSubmission(
            selectedMood = "专注",
            noteText = "   ",
            durationMinutes = 25
        )

        assertEquals("专注", submission?.mood)
        assertNull(submission?.note)
        assertEquals(25, submission?.durationMinutes)
    }
}
