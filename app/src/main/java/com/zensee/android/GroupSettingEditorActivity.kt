package com.zensee.android

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupSettingEditorBinding
import kotlin.concurrent.thread

class GroupSettingEditorActivity : AppCompatActivity() {
    private enum class EditorType {
        GROUP_NAME,
        GROUP_DESCRIPTION,
        GROUP_NICKNAME;

        companion object {
            fun from(rawValue: String?): EditorType {
                return entries.firstOrNull { it.name == rawValue } ?: GROUP_NAME
            }
        }
    }

    private lateinit var binding: ActivityGroupSettingEditorBinding
    private lateinit var saveButtonController: LoadingButtonController
    private lateinit var editorType: EditorType
    private lateinit var originalValue: String
    private lateinit var groupId: String
    private var isSaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.state().isAuthenticated) {
            finish()
            return
        }

        binding = ActivityGroupSettingEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        saveButtonController = LoadingButtonController(
            binding.groupSettingSaveButton,
            binding.groupSettingSaveProgress
        )
        editorType = EditorType.from(intent.getStringExtra(EXTRA_EDITOR_TYPE))
        originalValue = intent.getStringExtra(EXTRA_INITIAL_VALUE).orEmpty()
        groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        if (groupId.isBlank()) {
            finish()
            return
        }

        setSupportActionBar(binding.groupSettingEditorToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        binding.groupSettingEditorToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        AuthUi.applyEdgeToEdge(this, binding.groupSettingEditorRoot, binding.groupSettingEditorToolbar)

        configureInputs()
        binding.groupSettingSaveButton.setOnClickListener { confirmSave() }
        binding.groupSettingSingleLineInput.doAfterTextChanged {
            binding.groupSettingEditorMessageText.isVisible = false
            updateSaveButtonState()
        }
        binding.groupSettingMultilineInput.doAfterTextChanged {
            binding.groupSettingEditorMessageText.isVisible = false
            updateSaveButtonState()
        }
        updateSaveButtonState()
        focusInput()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun configureInputs() {
        val isMultiline = editorType == EditorType.GROUP_DESCRIPTION
        val placeholder = when (editorType) {
            EditorType.GROUP_NAME -> getString(R.string.group_edit_name_placeholder)
            EditorType.GROUP_DESCRIPTION -> getString(R.string.group_edit_description_placeholder)
            EditorType.GROUP_NICKNAME -> getString(R.string.group_edit_nickname_placeholder)
        }
        val saveTitle = when (editorType) {
            EditorType.GROUP_NAME -> getString(R.string.group_save_name)
            EditorType.GROUP_DESCRIPTION -> getString(R.string.group_save_description)
            EditorType.GROUP_NICKNAME -> getString(R.string.group_save_nickname)
        }
        binding.groupSettingSaveButton.text = saveTitle

        binding.groupSettingSingleLineInputLayout.isVisible = !isMultiline
        binding.groupSettingMultilineInputLayout.isVisible = isMultiline
        binding.groupSettingSingleLineInputLayout.hint = placeholder
        binding.groupSettingMultilineInputLayout.hint = placeholder

        if (isMultiline) {
            binding.groupSettingMultilineInput.setText(originalValue)
            binding.groupSettingMultilineInput.setSelection(binding.groupSettingMultilineInput.text?.length ?: 0)
        } else {
            binding.groupSettingSingleLineInput.setText(originalValue)
            binding.groupSettingSingleLineInput.setSelection(binding.groupSettingSingleLineInput.text?.length ?: 0)
        }
    }

    private fun currentValue(): String {
        return if (editorType == EditorType.GROUP_DESCRIPTION) {
            binding.groupSettingMultilineInput.text?.toString().orEmpty().trim()
        } else {
            binding.groupSettingSingleLineInput.text?.toString().orEmpty().trim()
        }
    }

    private fun updateSaveButtonState() {
        if (isSaving) return
        val changed = currentValue() != originalValue.trim()
        val valid = when (editorType) {
            EditorType.GROUP_NAME -> currentValue().length in 2..24
            EditorType.GROUP_DESCRIPTION -> currentValue().length <= 120
            EditorType.GROUP_NICKNAME -> currentValue().length in 1..24
        }
        binding.groupSettingSaveButton.isEnabled = changed && valid
    }

    private fun confirmSave() {
        val value = currentValue()
        if (!binding.groupSettingSaveButton.isEnabled) return

        val title = when (editorType) {
            EditorType.GROUP_NAME -> getString(R.string.group_edit_name_confirm_title)
            EditorType.GROUP_DESCRIPTION -> getString(R.string.group_edit_description_confirm_title)
            EditorType.GROUP_NICKNAME -> getString(R.string.group_edit_nickname_confirm_title)
        }
        val message = when (editorType) {
            EditorType.GROUP_NAME -> getString(R.string.group_edit_name_confirm_message, value)
            EditorType.GROUP_DESCRIPTION -> getString(R.string.group_edit_description_confirm_message)
            EditorType.GROUP_NICKNAME -> getString(R.string.group_edit_nickname_confirm_message, value)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save_action)) { _, _ ->
                saveValue(value)
            }
            .show()
    }

    private fun saveValue(value: String) {
        if (isSaving) return
        isSaving = true
        AuthUi.dismissKeyboard(this)
        saveButtonController.setLoading(true)
        setInputEnabled(false)
        thread(name = "zensee-group-setting-save") {
            val result = runCatching {
                when (editorType) {
                    EditorType.GROUP_NAME -> GroupRepository.updateGroupName(groupId, value)
                    EditorType.GROUP_DESCRIPTION -> GroupRepository.updateGroupDescription(groupId, value)
                    EditorType.GROUP_NICKNAME -> GroupRepository.updateGroupNickname(groupId, value)
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                isSaving = false
                saveButtonController.setLoading(false)
                setInputEnabled(true)
                result.onSuccess {
                    val successMessage = when (editorType) {
                        EditorType.GROUP_NAME -> getString(R.string.group_name_saved)
                        EditorType.GROUP_DESCRIPTION -> getString(R.string.group_description_saved)
                        EditorType.GROUP_NICKNAME -> getString(R.string.group_nickname_saved)
                    }
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, true)
                            .putExtra(EXTRA_SAVED_VALUE, it)
                    )
                    android.widget.Toast.makeText(this, successMessage, android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { error ->
                    binding.groupSettingEditorMessageText.isVisible = true
                    binding.groupSettingEditorMessageText.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.groupSettingEditorMessageText.text = GroupUi.errorMessage(this, error)
                    updateSaveButtonState()
                }
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        binding.groupSettingSingleLineInput.isEnabled = enabled
        binding.groupSettingMultilineInput.isEnabled = enabled
    }

    private fun focusInput() {
        val target = if (editorType == EditorType.GROUP_DESCRIPTION) {
            binding.groupSettingMultilineInput
        } else {
            binding.groupSettingSingleLineInput
        }
        target.requestFocus()
        target.post {
            val inputMethodManager = getSystemService(InputMethodManager::class.java)
            inputMethodManager?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_EDITOR_TYPE = "extra_group_editor_type"
        const val EXTRA_INITIAL_VALUE = "extra_group_initial_value"
        const val EXTRA_SAVED_VALUE = "extra_group_saved_value"

        fun newGroupNameIntent(activity: AppCompatActivity, groupId: String, initialValue: String): Intent {
            return Intent(activity, GroupSettingEditorActivity::class.java)
                .putExtra(EXTRA_GROUP_ID, groupId)
                .putExtra(EXTRA_EDITOR_TYPE, EditorType.GROUP_NAME.name)
                .putExtra(EXTRA_INITIAL_VALUE, initialValue)
        }

        fun newGroupDescriptionIntent(activity: AppCompatActivity, groupId: String, initialValue: String): Intent {
            return Intent(activity, GroupSettingEditorActivity::class.java)
                .putExtra(EXTRA_GROUP_ID, groupId)
                .putExtra(EXTRA_EDITOR_TYPE, EditorType.GROUP_DESCRIPTION.name)
                .putExtra(EXTRA_INITIAL_VALUE, initialValue)
        }

        fun newGroupNicknameIntent(activity: AppCompatActivity, groupId: String, initialValue: String): Intent {
            return Intent(activity, GroupSettingEditorActivity::class.java)
                .putExtra(EXTRA_GROUP_ID, groupId)
                .putExtra(EXTRA_EDITOR_TYPE, EditorType.GROUP_NICKNAME.name)
                .putExtra(EXTRA_INITIAL_VALUE, initialValue)
        }
    }
}
