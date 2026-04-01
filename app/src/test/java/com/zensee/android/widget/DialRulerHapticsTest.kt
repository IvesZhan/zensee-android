package com.zensee.android.widget

import android.os.Build
import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialRulerHapticsTest {

    @Test
    fun `triggers haptic only while user is dragging and value changes`() {
        assertTrue(DialRulerHaptics.shouldTrigger(isUserDragging = true, oldValue = 20, newValue = 21))
        assertFalse(DialRulerHaptics.shouldTrigger(isUserDragging = false, oldValue = 20, newValue = 21))
        assertFalse(DialRulerHaptics.shouldTrigger(isUserDragging = true, oldValue = 20, newValue = 20))
    }

    @Test
    fun `uses frequent tick on newer android and clock tick on older versions`() {
        val expectedNewer = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }

        assertEquals(expectedNewer, DialRulerHaptics.feedbackConstantForSdk(Build.VERSION.SDK_INT))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, DialRulerHaptics.feedbackConstantForSdk(33))
    }

    @Test
    fun `uses predefined vibrator tick on android q and above`() {
        assertTrue(DialRulerHaptics.shouldUsePredefinedVibration(29))
        assertFalse(DialRulerHaptics.shouldUsePredefinedVibration(28))
    }

    @Test
    fun `uses short fallback pulse on older android`() {
        assertEquals(8L, DialRulerHaptics.fallbackDurationMs())
        assertEquals(32, DialRulerHaptics.fallbackAmplitude())
    }
}
