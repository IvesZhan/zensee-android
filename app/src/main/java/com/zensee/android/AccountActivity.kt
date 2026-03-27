package com.zensee.android

import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.zensee.android.databinding.ActivityAccountBinding
import kotlin.concurrent.thread

class AccountActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAccountBinding
    private lateinit var saveNicknameLoadingButton: LoadingButtonController
    private lateinit var updatePasswordLoadingButton: LoadingButtonController
    private var originalNickname: String = ""
    private var isDeletingAccount = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarStyler.apply(this)

        if (!AuthManager.state().isAuthenticated) {
            finish()
            return
        }

        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        saveNicknameLoadingButton = LoadingButtonController(
            binding.saveNicknameButton,
            binding.saveNicknameProgress
        )
        updatePasswordLoadingButton = LoadingButtonController(
            binding.updatePasswordButton,
            binding.updatePasswordProgress
        )
        setSupportActionBar(binding.accountToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.account_title)
        binding.accountToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        applyWindowInsets()

        originalNickname = AuthManager.state().nickname.ifBlank { AuthManager.state().emailPrefix }
        binding.nicknameInput.setText(originalNickname)
        binding.saveNicknameButton.setOnClickListener { saveNickname() }
        binding.updatePasswordButton.setOnClickListener { updatePassword() }
        binding.signOutButton.setOnClickListener { confirmSignOut() }
        binding.deleteAccountButton.setOnClickListener { confirmDeleteAccount() }
        binding.nicknameInput.doAfterTextChanged { updateActionStates() }
        binding.newPasswordInput.doAfterTextChanged { updateActionStates() }
        binding.confirmPasswordInput.doAfterTextChanged { updateActionStates() }
        updateActionStates()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun saveNickname() {
        val nickname = binding.nicknameInput.text?.toString().orEmpty().trim()
        if (!canSaveNickname()) return
        AuthUi.dismissKeyboard(this)
        setLoading(true, saveNicknameLoadingButton)
        thread {
            try {
                AuthManager.updateNickname(nickname)
                runOnUiThread {
                    originalNickname = nickname
                    setLoading(false)
                    binding.accountMessageText.isVisible = true
                    binding.accountMessageText.setTextColor(getColor(R.color.zs_primary))
                    binding.accountMessageText.text = getString(R.string.nickname_saved)
                    setResult(RESULT_OK)
                    updateActionStates()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    setLoading(false)
                    binding.accountMessageText.isVisible = true
                    binding.accountMessageText.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.accountMessageText.text = getString(R.string.operation_failed)
                    updateActionStates()
                }
            }
        }
    }

    private fun updatePassword() {
        val newPassword = binding.newPasswordInput.text?.toString().orEmpty()
        val confirm = binding.confirmPasswordInput.text?.toString().orEmpty()
        if (!canSavePassword()) return
        if (newPassword.length < 6) {
            Toast.makeText(this, getString(R.string.login_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (newPassword != confirm) {
            Toast.makeText(this, getString(R.string.password_mismatch), Toast.LENGTH_SHORT).show()
            return
        }
        AuthUi.dismissKeyboard(this)
        setLoading(true, updatePasswordLoadingButton)
        thread {
            try {
                AuthManager.updatePassword(newPassword)
                runOnUiThread {
                    setLoading(false)
                    binding.newPasswordInput.text?.clear()
                    binding.confirmPasswordInput.text?.clear()
                    binding.accountMessageText.isVisible = true
                    binding.accountMessageText.setTextColor(getColor(R.color.zs_primary))
                    binding.accountMessageText.text = getString(R.string.password_updated)
                    Toast.makeText(this, getString(R.string.password_updated), Toast.LENGTH_SHORT)
                        .show()
                    updateActionStates()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    setLoading(false)
                    binding.accountMessageText.isVisible = true
                    binding.accountMessageText.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.accountMessageText.text = getString(R.string.operation_failed)
                    updateActionStates()
                }
            }
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.sign_out))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sign_out) { _, _ ->
                AuthManager.signOut()
                setResult(RESULT_OK)
                Toast.makeText(this, getString(R.string.sign_out_success), Toast.LENGTH_SHORT).show()
                finish()
            }
            .show()
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_account))
            .setMessage(getString(R.string.delete_account_confirm_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_action) { _, _ ->
                confirmDeleteAccountFinal()
            }
            .show()
    }

    private fun confirmDeleteAccountFinal() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_account_final_title))
            .setMessage(getString(R.string.delete_account_final_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                AuthUi.dismissKeyboard(this)
                showDeleteLoading(true)
                thread {
                    try {
                        AuthManager.deleteAccountAndData()
                        runOnUiThread {
                            showDeleteLoading(false)
                            Toast.makeText(
                                this,
                                getString(R.string.account_deleted),
                                Toast.LENGTH_SHORT
                            ).show()
                            setResult(RESULT_OK)
                            finish()
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            showDeleteLoading(false)
                            Toast.makeText(
                                this,
                                getString(R.string.operation_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun setLoading(
        loading: Boolean,
        activeButton: LoadingButtonController? = null
    ) {
        saveNicknameLoadingButton.setLoading(loading && activeButton === saveNicknameLoadingButton)
        updatePasswordLoadingButton.setLoading(loading && activeButton === updatePasswordLoadingButton)
        val canInteract = !loading && !isDeletingAccount
        binding.saveNicknameButton.isEnabled = canInteract && canSaveNickname()
        binding.updatePasswordButton.isEnabled = canInteract && canSavePassword()
        binding.signOutButton.isEnabled = canInteract
        binding.deleteAccountButton.isEnabled = canInteract
        binding.nicknameInput.isEnabled = canInteract
        binding.newPasswordInput.isEnabled = canInteract
        binding.confirmPasswordInput.isEnabled = canInteract
    }

    private fun updateActionStates() {
        if (isDeletingAccount) return
        binding.saveNicknameButton.isEnabled = canSaveNickname()
        binding.updatePasswordButton.isEnabled = canSavePassword()
        binding.passwordMismatchText.isVisible = hasPasswordMismatch()
    }

    private fun canSaveNickname(): Boolean {
        val nickname = binding.nicknameInput.text?.toString().orEmpty().trim()
        return nickname.isNotBlank() && nickname != originalNickname
    }

    private fun canSavePassword(): Boolean {
        val newPassword = binding.newPasswordInput.text?.toString().orEmpty()
        val confirm = binding.confirmPasswordInput.text?.toString().orEmpty()
        return newPassword.length >= 6 && newPassword == confirm
    }

    private fun hasPasswordMismatch(): Boolean {
        val newPassword = binding.newPasswordInput.text?.toString().orEmpty()
        val confirm = binding.confirmPasswordInput.text?.toString().orEmpty()
        return confirm.isNotEmpty() && newPassword != confirm
    }

    private fun showDeleteLoading(loading: Boolean) {
        isDeletingAccount = loading
        binding.deleteAccountLoadingOverlay.isVisible = loading
        setLoading(loading)
        if (!loading) {
            updateActionStates()
        }
    }

    private fun applyWindowInsets() {
        val toolbarTop = binding.accountToolbar.paddingTop
        val footerBottomPadding = binding.accountFooter.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.accountRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.accountToolbar.setPadding(
                binding.accountToolbar.paddingLeft,
                toolbarTop + systemBars.top,
                binding.accountToolbar.paddingRight,
                binding.accountToolbar.paddingBottom
            )
            binding.accountFooter.setPadding(
                binding.accountFooter.paddingLeft,
                binding.accountFooter.paddingTop,
                binding.accountFooter.paddingRight,
                footerBottomPadding + systemBars.bottom
            )
            insets
        }
    }
}
