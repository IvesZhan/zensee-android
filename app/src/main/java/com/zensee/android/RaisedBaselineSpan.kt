package com.zensee.android

import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import kotlin.math.roundToInt

class RaisedBaselineSpan(
    private val ratio: Float
) : MetricAffectingSpan() {
    override fun updateDrawState(textPaint: TextPaint) {
        applyShift(textPaint)
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        applyShift(textPaint)
    }

    private fun applyShift(textPaint: TextPaint) {
        textPaint.baselineShift += (-textPaint.textSize * ratio).roundToInt()
    }
}
