package com.zensee.android

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemBarStyler {
    fun apply(
        activity: Activity,
        navigationBarColor: Int = activity.getColor(R.color.zs_background)
    ) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        applyChrome(
            activity = activity,
            navigationBarColor = navigationBarColor
        )
    }

    fun setNavigationBarColor(activity: Activity, navigationBarColor: Int) {
        applyChrome(
            activity = activity,
            navigationBarColor = navigationBarColor
        )
    }

    private fun applyChrome(activity: Activity, navigationBarColor: Int) {
        val window = activity.window
        val isDark = isDarkMode(activity)
        window.setBackgroundDrawable(ColorDrawable(activity.getColor(R.color.zs_background)))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = navigationBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    private fun isDarkMode(activity: Activity): Boolean {
        return (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
}
