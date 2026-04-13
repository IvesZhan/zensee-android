package com.zensee.android

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zensee.android.data.GroupRepository
import com.zensee.android.data.ZenRepository
import com.zensee.android.databinding.BottomSheetGroupPracticeDurationBinding
import com.zensee.android.model.MeditationSessionSummary
import kotlin.concurrent.thread

class GroupPracticeDurationBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetGroupPracticeDurationBinding? = null
    private val binding get() = _binding!!
    private var durationMinutes: Int = DEFAULT_DURATION_MINUTES
    private var isSaving = false
    private var pendingSession: MeditationSessionSummary? = null
    private var saveButtonController: LoadingButtonController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_ZenSee_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetGroupPracticeDurationBinding.inflate(inflater, container, false)
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

        durationMinutes = arguments?.getInt(KEY_DURATION, DEFAULT_DURATION_MINUTES) ?: DEFAULT_DURATION_MINUTES
        saveButtonController = LoadingButtonController(
            binding.groupPracticeSaveButton,
            binding.groupPracticeSaveSpinner
        )
        binding.groupPracticeDurationDial.configure(10..90, durationMinutes)
        binding.groupPracticeDurationDial.onValueChanged = { value ->
            durationMinutes = value
            updateDurationDisplay()
        }
        binding.groupPracticeSaveButton.setOnClickListener {
            submitGroupPractice()
        }
        updateDurationDisplay()
        renderSavingState()
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
        val hostActivity = requireActivity()
        if (hostActivity is MainActivity) {
            SystemBarStyler.apply(hostActivity, hostActivity.getColor(R.color.zs_surface))
        } else {
            SystemBarStyler.apply(hostActivity)
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        saveButtonController = null
        _binding = null
    }

    private fun submitGroupPractice() {
        if (isSaving) return
        val groupId = arguments?.getString(KEY_GROUP_ID).orEmpty()
        if (groupId.isBlank()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.group_practice_save_failed),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        setSaving(true)
        thread(name = "zensee-group-practice-sheet") {
            var localSession = pendingSession
            val alreadySavedLocally = localSession != null
            val result = runCatching {
                if (localSession == null) {
                    localSession = ZenRepository.saveGroupPracticeSession(
                        durationMinutes = durationMinutes,
                        syncAfterSave = false
                    )
                }
                val session = requireNotNull(localSession)
                ZenRepository.ensureSessionSynced(session)
                GroupRepository.shareSessions(listOf(session.id), groupId)
                session
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                result.onSuccess {
                    pendingSession = null
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.group_practice_save_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    parentFragmentManager.setFragmentResult(
                        REQUEST_KEY,
                        Bundle()
                    )
                    dismissAllowingStateLoss()
                }.onFailure { error ->
                    pendingSession = localSession
                    setSaving(false)
                    val messageRes = if (alreadySavedLocally || localSession != null) {
                        R.string.group_practice_save_partial
                    } else {
                        R.string.group_practice_save_failed
                    }
                    Toast.makeText(
                        requireContext(),
                        GroupUi.errorMessage(requireContext(), error, messageRes),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun setSaving(saving: Boolean) {
        if (isSaving == saving) return
        isSaving = saving
        renderSavingState()
    }

    private fun renderSavingState() {
        saveButtonController?.setLoading(isSaving)
        binding.groupPracticeDurationDial.isEnabled = !isSaving
        isCancelable = !isSaving
        (dialog as? BottomSheetDialog)?.setCanceledOnTouchOutside(!isSaving)
    }

    private fun updateDurationDisplay() {
        binding.groupPracticeDurationText.text = formatDuration(durationMinutes)
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

    private fun isNightMode(): Boolean {
        val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        const val REQUEST_KEY = "group_practice_duration"
        const val TAG = "group_practice_duration"
        const val KEY_DURATION = "duration_minutes"
        private const val KEY_GROUP_ID = "group_id"
        private const val DEFAULT_DURATION_MINUTES = 45

        fun newInstance(groupId: String, durationMinutes: Int = DEFAULT_DURATION_MINUTES): GroupPracticeDurationBottomSheet {
            return GroupPracticeDurationBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(KEY_GROUP_ID, groupId)
                    putInt(KEY_DURATION, durationMinutes)
                }
            }
        }
    }
}
