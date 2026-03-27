package com.zensee.android

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

object AuthUi {
    fun applyEdgeToEdge(
        activity: AppCompatActivity,
        root: View,
        toolbar: MaterialToolbar
    ) {
        SystemBarStyler.apply(activity)

        val toolbarTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(
                toolbar.paddingLeft,
                toolbarTopPadding + systemBars.top,
                toolbar.paddingRight,
                toolbar.paddingBottom
            )
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                systemBars.bottom
            )
            insets
        }
    }

    fun isEmailValid(email: String): Boolean {
        val parts = email.trim().split("@")
        if (parts.size != 2) return false
        val prefix = parts.first()
        val domain = parts.last()
        return prefix.isNotBlank() &&
            domain.contains(".") &&
            prefix.all { it.isLetterOrDigit() }
    }

    fun localizedError(context: Context, message: String): String {
        val normalized = message.lowercase()
        return when {
            normalized.contains("already") || normalized.contains("registered") ->
                context.getString(R.string.already_registered)
            normalized.contains("invalid login") || normalized.contains("credentials") ->
                context.getString(R.string.invalid_credentials)
            normalized.contains("network") ||
                normalized.contains("internet") ||
                normalized.contains("unable") ||
                normalized.contains("failed to connect") ->
                context.getString(R.string.network_error)
            else -> context.getString(R.string.operation_failed)
        }
    }

    fun dismissKeyboard(activity: AppCompatActivity) {
        val focusView = activity.currentFocus ?: activity.window.decorView
        focusView.clearFocus()
        val inputMethodManager =
            activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(focusView.windowToken, 0)
    }
}
