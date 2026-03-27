package com.zensee.android

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zensee.android.databinding.BottomSheetMeditationSetupBinding

class MeditationSetupBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetMeditationSetupBinding? = null
    private val binding get() = _binding!!
    private var durationMinutes: Int = 30
    private var cooldownMinutes: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_ZenSee_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMeditationSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.clipToOutline = true
        binding.root.elevation = 0f
        val baseBottomPadding = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { sheet, insets ->
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            sheet.updatePadding(bottom = baseBottomPadding + navigationBottom)
            insets
        }

        durationMinutes = arguments?.getInt(KEY_DURATION, savedDurationMinutes()) ?: savedDurationMinutes()
        cooldownMinutes = arguments?.getInt(KEY_COOL_DOWN, savedCooldownMinutes()) ?: savedCooldownMinutes()
        cooldownMinutes = cooldownMinutes.takeIf { it < durationMinutes } ?: 0

        binding.durationDial.configure(5..120, durationMinutes)
        updateDurationDisplay()
        updateCooldownUi()

        binding.durationDial.onValueChanged = { value ->
            durationMinutes = value
            if (cooldownMinutes >= durationMinutes) {
                cooldownMinutes = listOf(0, 5, 10, 15).filter { it < durationMinutes }.lastOrNull() ?: 0
            }
            updateDurationDisplay()
            updateCooldownUi()
        }

        binding.coolDownMenuButton.setOnClickListener { showCooldownMenu(it) }
        binding.confirmMeditationButton.setOnClickListener {
            persistSelection()
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                Bundle().apply {
                    putInt(KEY_DURATION, durationMinutes)
                    putInt(KEY_COOL_DOWN, cooldownMinutes)
                }
            )
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().let { activity ->
            SystemBarStyler.setNavigationBarColor(
                activity = activity,
                navigationBarColor = ContextCompat.getColor(activity, R.color.zs_bottom_sheet_fill)
            )
        }
        (dialog as? BottomSheetDialog)?.let { bottomSheetDialog ->
            bottomSheetDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            bottomSheetDialog.window?.decorView?.setBackgroundColor(Color.TRANSPARENT)
            bottomSheetDialog.window?.setDimAmount(if (isNightMode()) 0.78f else 0.46f)
            bottomSheetDialog.window?.navigationBarColor = ContextCompat.getColor(requireContext(), R.color.zs_bottom_sheet_fill)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bottomSheetDialog.window?.navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bottomSheetDialog.window?.isNavigationBarContrastEnforced = false
            }
            bottomSheetDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                background = ColorDrawable(Color.TRANSPARENT)
                setBackgroundColor(Color.TRANSPARENT)
                elevation = 0f
                clipToOutline = false
                (parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
                updatePadding(bottom = 0)
                (layoutParams as? MarginLayoutParams)?.bottomMargin = 0
                ViewCompat.setOnApplyWindowInsetsListener(this) { sheet, insets ->
                    sheet.updatePadding(bottom = 0)
                    (sheet.layoutParams as? MarginLayoutParams)?.bottomMargin = 0
                    insets
                }
                (parent as? ViewGroup)?.apply {
                    setPadding(paddingLeft, paddingTop, paddingRight, 0)
                    (layoutParams as? MarginLayoutParams)?.bottomMargin = 0
                }
            }
            bottomSheetDialog.behavior.isGestureInsetBottomIgnored = true
            bottomSheetDialog.behavior.skipCollapsed = true
            bottomSheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        val hostActivity = requireActivity()
        if (hostActivity is MainActivity) {
            SystemBarStyler.apply(hostActivity, hostActivity.getColor(R.color.zs_surface))
        } else {
            SystemBarStyler.apply(hostActivity)
        }
        super.onDismiss(dialog)
    }

    private fun updateDurationDisplay() {
        binding.durationDisplayText.text = formatDuration(durationMinutes)
    }

    private fun updateCooldownUi() {
        binding.coolDownValueText.text = if (cooldownMinutes == 0) {
            getString(R.string.none_short)
        } else {
            getString(R.string.cool_down_option_minutes, cooldownMinutes)
        }
    }

    private fun showCooldownMenu(anchor: View) {
        PopupMenu(requireContext(), anchor, Gravity.NO_GRAVITY, 0, R.style.Widget_ZenSee_PopupMenu).apply {
            menu.add(0, 0, 0, getString(R.string.no_cool_down))
            if (durationMinutes > 5) menu.add(0, 5, 1, getString(R.string.cool_down_option_minutes, 5))
            if (durationMinutes > 10) menu.add(0, 10, 2, getString(R.string.cool_down_option_minutes, 10))
            if (durationMinutes > 15) menu.add(0, 15, 3, getString(R.string.cool_down_option_minutes, 15))

            setOnMenuItemClickListener { item ->
                cooldownMinutes = item.itemId
                updateCooldownUi()
                true
            }
            show()
        }
    }

    private fun formatDuration(minutes: Int): String {
        if (minutes < 60) return getString(R.string.meditation_duration_minutes, minutes)
        val hours = minutes / 60
        val remain = minutes % 60
        return if (remain == 0) {
            getString(R.string.duration_hours, hours)
        } else {
            getString(R.string.duration_hours_minutes, hours, remain)
        }
    }

    private fun persistSelection() {
        prefs().edit()
            .putInt(PREF_DURATION, durationMinutes)
            .putInt(PREF_COOLDOWN, cooldownMinutes)
            .apply()
    }

    private fun savedDurationMinutes(): Int {
        val value = prefs().getInt(PREF_DURATION, 30)
        return if (value in 5..120) value else 30
    }

    private fun savedCooldownMinutes(): Int {
        val value = prefs().getInt(PREF_COOLDOWN, 0)
        return if (value in listOf(0, 5, 10, 15)) value else 0
    }

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private fun isNightMode(): Boolean {
        val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_KEY = "meditation_setup"
        const val KEY_DURATION = "duration_minutes"
        const val KEY_COOL_DOWN = "cool_down_minutes"
        private const val PREFS_NAME = "zensee_meditation_setup"
        private const val PREF_DURATION = "last_duration_minutes"
        private const val PREF_COOLDOWN = "last_cooldown_minutes"
    }
}
