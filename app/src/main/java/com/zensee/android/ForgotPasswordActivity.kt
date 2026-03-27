package com.zensee.android

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.zensee.android.databinding.ActivityForgotPasswordBinding
import kotlin.concurrent.thread

class ForgotPasswordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityForgotPasswordBinding
    private lateinit var submitLoadingButton: LoadingButtonController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.initialize(this)

        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyEdgeToEdge(this, binding.forgotPasswordRoot, binding.forgotPasswordToolbar)
        submitLoadingButton = LoadingButtonController(
            binding.sendResetButton,
            binding.sendResetProgress
        )

        setSupportActionBar(binding.forgotPasswordToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.forgot_password_title)
        binding.forgotPasswordToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.forgotPasswordEmailInput.setText(
            intent.getStringExtra(EXTRA_EMAIL).orEmpty().trim()
        )

        binding.sendResetButton.setOnClickListener { submit() }
        binding.forgotPasswordEmailInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun submit() {
        val email = binding.forgotPasswordEmailInput.text?.toString().orEmpty().trim()
        if (!AuthUi.isEmailValid(email)) {
            showError(getString(R.string.valid_email_required))
            showSuccess("")
            return
        }

        showError("")
        showSuccess("")
        AuthUi.dismissKeyboard(this)
        setLoading(true)
        thread {
            try {
                AuthManager.resetPassword(email)
                runOnUiThread {
                    setLoading(false)
                    showSuccess(getString(R.string.reset_link_sent, email))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(AuthUi.localizedError(this, error.message.orEmpty()))
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.forgotPasswordErrorText.isVisible = message.isNotBlank()
        binding.forgotPasswordErrorText.text = message
    }

    private fun showSuccess(message: String) {
        binding.forgotPasswordSuccessText.isVisible = message.isNotBlank()
        binding.forgotPasswordSuccessText.text = message
    }

    private fun setLoading(loading: Boolean) {
        submitLoadingButton.setLoading(loading)
        binding.forgotPasswordEmailInput.isEnabled = !loading
    }

    companion object {
        const val EXTRA_EMAIL = "extra_email"
    }
}
