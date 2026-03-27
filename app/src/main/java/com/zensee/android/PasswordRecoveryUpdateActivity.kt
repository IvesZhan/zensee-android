package com.zensee.android

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.zensee.android.databinding.ActivityPasswordRecoveryUpdateBinding
import kotlin.concurrent.thread

class PasswordRecoveryUpdateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPasswordRecoveryUpdateBinding
    private lateinit var submitLoadingButton: LoadingButtonController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.initialize(this)

        binding = ActivityPasswordRecoveryUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyEdgeToEdge(this, binding.passwordRecoveryRoot, binding.passwordRecoveryToolbar)
        submitLoadingButton = LoadingButtonController(
            binding.updatePasswordButton,
            binding.updatePasswordProgress
        )

        setSupportActionBar(binding.passwordRecoveryToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.reset_password_nav_title)
        binding.passwordRecoveryToolbar.setNavigationOnClickListener { handleClose() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleClose()
        })

        binding.updatePasswordButton.setOnClickListener { submit() }
        resolveRecoveryState()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveRecoveryState()
    }

    private fun resolveRecoveryState() {
        val url = intent?.dataString
        if (!url.isNullOrBlank()) {
            setLoading(true)
            thread {
                try {
                    AuthManager.handlePasswordRecoveryUrl(url)
                    runOnUiThread {
                        setLoading(false)
                        renderRecoveryEmail()
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        setLoading(false)
                        showError(getString(R.string.invalid_recovery_link))
                    }
                }
            }
            return
        }

        if (AuthManager.hasPendingPasswordRecovery()) {
            renderRecoveryEmail()
        } else {
            showError(getString(R.string.invalid_recovery_link))
        }
    }

    private fun renderRecoveryEmail() {
        val email = AuthManager.pendingRecoveryEmail().orEmpty()
        binding.passwordRecoverySubtitleText.text =
            getString(R.string.password_recovery_verified_email, email)
        showError("")
    }

    private fun submit() {
        val newPassword = binding.newPasswordInput.text?.toString().orEmpty()
        val confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()

        when {
            newPassword.length < 6 -> {
                showError(getString(R.string.new_password_min_length))
                return
            }
            newPassword != confirmPassword -> {
                showError(getString(R.string.password_mismatch))
                return
            }
        }

        AuthUi.dismissKeyboard(this)
        setLoading(true)
        showError("")
        showSuccess("")
        thread {
            try {
                AuthManager.completePasswordRecovery(newPassword)
                runOnUiThread {
                    setLoading(false)
                    showSuccess(getString(R.string.password_updated))
                }
            } catch (_: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.password_recovery_failed))
                }
            }
        }
    }

    private fun handleClose() {
        if (AuthManager.hasPendingPasswordRecovery()) {
            AuthManager.cancelPasswordRecovery()
        }
        finish()
    }

    private fun showError(message: String) {
        binding.passwordRecoveryErrorText.isVisible = message.isNotBlank()
        binding.passwordRecoveryErrorText.text = message
    }

    private fun showSuccess(message: String) {
        binding.passwordRecoverySuccessText.isVisible = message.isNotBlank()
        binding.passwordRecoverySuccessText.text = message
    }

    private fun setLoading(loading: Boolean) {
        submitLoadingButton.setLoading(loading)
        binding.newPasswordInput.isEnabled = !loading
        binding.confirmPasswordInput.isEnabled = !loading
    }
}
