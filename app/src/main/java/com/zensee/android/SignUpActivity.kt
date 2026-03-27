package com.zensee.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.zensee.android.databinding.ActivitySignUpBinding
import kotlin.concurrent.thread

class SignUpActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignUpBinding
    private lateinit var submitLoadingButton: LoadingButtonController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.initialize(this)
        if (AuthManager.state().isAuthenticated) {
            setResult(RESULT_OK)
            finish()
            return
        }

        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyEdgeToEdge(this, binding.signUpRoot, binding.signUpToolbar)
        submitLoadingButton = LoadingButtonController(
            binding.signUpSubmitButton,
            binding.signUpSubmitProgress
        )

        setSupportActionBar(binding.signUpToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.sign_up_title)
        binding.signUpToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.signUpBrandTitleText.text = getString(R.string.brand_name)
        binding.signUpSubtitleText.text =
            getString(R.string.sign_up_subtitle, getString(R.string.brand_name))

        binding.signUpSubmitButton.setOnClickListener { submit() }
        binding.backLoginButton.setOnClickListener { finish() }
        binding.termsLinkButton.setOnClickListener {
            openExternalUrl(getString(R.string.terms_of_service_url))
        }
        binding.privacyLinkButton.setOnClickListener {
            openExternalUrl(getString(R.string.privacy_policy_url))
        }
        binding.confirmPasswordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        binding.emailInput.doAfterTextChanged { updateValidationHints() }
        binding.passwordInput.doAfterTextChanged { updateValidationHints() }
        binding.confirmPasswordInput.doAfterTextChanged { updateValidationHints() }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun submit() {
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()

        updateValidationHints()
        val error = when {
            !AuthUi.isEmailValid(email) || password.length < 6 ->
                getString(R.string.login_required)
            password != confirmPassword ->
                getString(R.string.password_mismatch)
            !binding.agreeCheckBox.isChecked ->
                getString(R.string.agreement_required)
            else -> null
        }
        if (error != null) {
            showError(error)
            return
        }

        showError("")
        showSuccess("")
        AuthUi.dismissKeyboard(this)
        setLoading(true)
        thread {
            try {
                val result = AuthManager.signUp(email, password)
                runOnUiThread {
                    setLoading(false)
                    if (result.signedIn) {
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        showSuccess(result.message)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(AuthUi.localizedError(this, error.message.orEmpty()))
                }
            }
        }
    }

    private fun updateValidationHints() {
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()
        binding.emailHintText.isVisible = email.isNotBlank() && !AuthUi.isEmailValid(email)
        binding.passwordMismatchText.isVisible =
            confirmPassword.isNotBlank() && password != confirmPassword
    }

    private fun openExternalUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showError(message: String) {
        binding.signUpErrorText.isVisible = message.isNotBlank()
        binding.signUpErrorText.text = message
    }

    private fun showSuccess(message: String) {
        binding.signUpSuccessText.isVisible = message.isNotBlank()
        binding.signUpSuccessText.text = message
    }

    private fun setLoading(loading: Boolean) {
        submitLoadingButton.setLoading(loading)
        binding.backLoginButton.isEnabled = !loading
        binding.agreeCheckBox.isEnabled = !loading
        binding.termsLinkButton.isEnabled = !loading
        binding.privacyLinkButton.isEnabled = !loading
        binding.emailInput.isEnabled = !loading
        binding.passwordInput.isEnabled = !loading
        binding.confirmPasswordInput.isEnabled = !loading
    }
}
