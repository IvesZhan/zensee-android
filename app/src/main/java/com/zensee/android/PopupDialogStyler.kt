package com.zensee.android

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager

object PopupDialogStyler {
    fun apply(
        dialog: Dialog,
        horizontalMarginDp: Int = 24,
        maxWidthDp: Int = 360,
        dimAmount: Float = 0.22f,
        blurRadiusDp: Int = 22
    ) {
        val window = dialog.window ?: return
        val density = dialog.context.resources.displayMetrics.density
        val horizontalMarginPx = (horizontalMarginDp * density).toInt()
        val maxWidthPx = (maxWidthDp * density).toInt()
        val targetWidth = (dialog.context.resources.displayMetrics.widthPixels - horizontalMarginPx * 2)
            .coerceAtMost(maxWidthPx)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(dimAmount)
        window.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = (blurRadiusDp * density).toInt()
            }
            window.setBackgroundBlurRadius((blurRadiusDp * density).toInt())
        }
    }
}
