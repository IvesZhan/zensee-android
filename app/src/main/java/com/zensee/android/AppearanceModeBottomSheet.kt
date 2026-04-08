package com.zensee.android

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zensee.android.databinding.BottomSheetAppearanceModeBinding

class AppearanceModeBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetAppearanceModeBinding? = null
    private val binding get() = _binding!!

    var onModeSelected: ((AppearanceMode) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_ZenSee_BottomSheetDialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAppearanceModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val selectedMode = SettingsManager.appearanceMode()
        bindOption(binding.appearanceLightRow, binding.appearanceLightRadio, AppearanceMode.LIGHT, selectedMode)
        bindOption(binding.appearanceDarkRow, binding.appearanceDarkRadio, AppearanceMode.DARK, selectedMode)
        bindOption(binding.appearanceSystemRow, binding.appearanceSystemRadio, AppearanceMode.SYSTEM, selectedMode)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { sheet, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            sheet.updatePadding(bottom = 12.dp + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.let { bottomSheetDialog ->
            bottomSheetDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            bottomSheetDialog.window?.decorView?.setBackgroundColor(Color.TRANSPARENT)
            bottomSheetDialog.window?.setDimAmount(0.48f)
            bottomSheetDialog.window?.navigationBarColor =
                ContextCompat.getColor(requireContext(), R.color.zs_bottom_sheet_fill)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bottomSheetDialog.window?.navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bottomSheetDialog.window?.isNavigationBarContrastEnforced = false
            }
            bottomSheetDialog.findViewById<ViewGroup>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                background = ColorDrawable(Color.TRANSPARENT)
                setBackgroundColor(Color.TRANSPARENT)
                elevation = 0f
                clipToOutline = false
                updatePadding(bottom = 0)
                (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
                ViewCompat.setOnApplyWindowInsetsListener(this) { sheet, insets ->
                    sheet.updatePadding(bottom = 0)
                    (sheet.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
                    insets
                }
                (parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
                (parent as? ViewGroup)?.apply {
                    setPadding(paddingLeft, paddingTop, paddingRight, 0)
                    (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
                }
            }
            bottomSheetDialog.behavior.apply {
                isGestureInsetBottomIgnored = true
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onDestroyView() {
        onModeSelected = null
        _binding = null
        super.onDestroyView()
    }

    private fun bindOption(
        row: View,
        radioButton: com.google.android.material.radiobutton.MaterialRadioButton,
        mode: AppearanceMode,
        selectedMode: AppearanceMode
    ) {
        radioButton.isChecked = selectedMode == mode
        row.setOnClickListener {
            onModeSelected?.invoke(mode)
            dismissAllowingStateLoss()
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "appearance_mode_bottom_sheet"
    }
}
