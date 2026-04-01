package com.zensee.android

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zensee.android.databinding.BottomSheetMeditationSetupBinding

class MeditationSetupBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetMeditationSetupBinding? = null
    private val binding get() = _binding!!
    private var durationMinutes: Int = 30
    private var cooldownMinutes: Int = 0
    private var showingSoundSelection = false

    private val soundAdapter = MeditationSoundAdapter(
        onSoundSelected = { sound -> handleSoundSelected(sound) },
        onPreviewPressed = { sound -> handlePreviewPressed(sound) }
    )

    private val previewStateListener: (String?) -> Unit = { previewingKey ->
        if (_binding != null) {
            soundAdapter.updatePreview(previewingKey)
        }
    }

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
        binding.durationDial.onValueChanged = { value ->
            durationMinutes = value
            if (cooldownMinutes >= durationMinutes) {
                cooldownMinutes = listOf(0, 5, 10, 15).filter { it < durationMinutes }.lastOrNull() ?: 0
            }
            updateDurationDisplay()
            updateCooldownUi()
        }

        binding.soundRecyclerView.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.soundRecyclerView.adapter = soundAdapter
        ZenAudioManager.addPreviewStateListener(previewStateListener)

        binding.coolDownMenuButton.setOnClickListener { showCooldownMenu(it) }
        binding.soundPickerButton.setOnClickListener { showSoundSelection() }
        binding.soundBackButton.setOnClickListener { showMainSetup() }
        binding.confirmMeditationButton.setOnClickListener {
            ZenAudioManager.stopPreview()
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

        syncSelectedSoundUi()
        updateDurationDisplay()
        updateCooldownUi()
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
            bottomSheetDialog.window?.setDimAmount(if (isNightMode()) 0.82f else 0.52f)
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
        ZenAudioManager.stopPreview()
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

    private fun syncSelectedSoundUi() {
        val selectedSound = ZenAudioManager.selectedMeditationSound()
        binding.selectedSoundText.text = selectedSound.displayName
        soundAdapter.updateSelection(selectedSound.storageKey)
        soundAdapter.updatePreview(ZenAudioManager.previewingSoundKey())
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

    private fun showSoundSelection() {
        if (showingSoundSelection) return
        showingSoundSelection = true
        binding.soundSelectionContent.alpha = 0f
        binding.soundSelectionContent.isVisible = true
        binding.soundSelectionContent.animate().alpha(1f).setDuration(180L).start()
        binding.mainSetupContent.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                if (_binding != null) {
                    binding.mainSetupContent.isVisible = false
                }
            }
            .start()
        scrollToSelectedSound()
    }

    private fun showMainSetup() {
        if (!showingSoundSelection) return
        showingSoundSelection = false
        ZenAudioManager.stopPreview()
        binding.mainSetupContent.alpha = 0f
        binding.mainSetupContent.isVisible = true
        binding.mainSetupContent.animate().alpha(1f).setDuration(180L).start()
        binding.soundSelectionContent.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                if (_binding != null) {
                    binding.soundSelectionContent.isVisible = false
                }
            }
            .start()
    }

    private fun handleSoundSelected(sound: MeditationSound) {
        val previousSound = ZenAudioManager.selectedMeditationSound()
        if (previousSound.storageKey != sound.storageKey) {
            ZenAudioManager.stopPreview()
            ZenAudioManager.setSelectedMeditationSound(sound.storageKey)
            syncSelectedSoundUi()
        }
    }

    private fun handlePreviewPressed(sound: MeditationSound) {
        val result = ZenAudioManager.togglePreview(sound.storageKey)
        if (result == ZenAudioManager.PreviewToggleResult.SOUND_DISABLED) {
            showSoundDisabledAlert()
        }
        soundAdapter.updatePreview(ZenAudioManager.previewingSoundKey())
    }

    private fun showSoundDisabledAlert() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.meditation_sound_disabled_title)
            .setMessage(R.string.meditation_sound_disabled_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.go_to_profile) { _, _ ->
                dismissAllowingStateLoss()
                (activity as? MainActivity)?.openProfileTab()
            }
            .show()
    }

    private fun scrollToSelectedSound() {
        val selectedIndex = MeditationSoundCatalog.sounds.indexOfFirst {
            it.storageKey == ZenAudioManager.selectedMeditationSound().storageKey
        }.coerceAtLeast(0)
        binding.soundRecyclerView.post {
            (binding.soundRecyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(selectedIndex, 0)
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
        ZenAudioManager.stopPreview()
        ZenAudioManager.removePreviewStateListener(previewStateListener)
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
