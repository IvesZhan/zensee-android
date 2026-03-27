package com.zensee.android

import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.isVisible

class LoadingButtonController(
    private val button: AppCompatButton,
    private val spinner: ProgressBar
) {
    private var isLoading = false
    private var originalText: CharSequence = button.text

    fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        if (loading) {
            originalText = button.text
            button.text = ""
            button.isEnabled = false
            spinner.isVisible = true
        } else {
            button.text = originalText
            button.isEnabled = true
            spinner.isVisible = false
        }
    }
}
