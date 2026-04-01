package com.zensee.android.widget

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

internal object DialRulerHaptics {
    private const val FALLBACK_DURATION_MS = 8L
    private const val FALLBACK_AMPLITUDE = 32

    fun shouldTrigger(isUserDragging: Boolean, oldValue: Int, newValue: Int): Boolean {
        return isUserDragging && oldValue != newValue
    }

    fun feedbackConstantForSdk(sdkInt: Int = Build.VERSION.SDK_INT): Int {
        return if (sdkInt >= 34) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    }

    fun shouldUsePredefinedVibration(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= Build.VERSION_CODES.Q
    }

    fun fallbackDurationMs(): Long = FALLBACK_DURATION_MS

    fun fallbackAmplitude(): Int = FALLBACK_AMPLITUDE

    fun performTick(view: View) {
        val performed = view.performHapticFeedback(
            feedbackConstantForSdk(),
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        if (performed) return

        val vibrator = vibratorFor(view.context) ?: return
        if (!vibrator.hasVibrator()) return

        if (shouldUsePredefinedVibration()) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    fallbackDurationMs(),
                    fallbackAmplitude()
                )
            )
        }
    }

    private fun vibratorFor(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
