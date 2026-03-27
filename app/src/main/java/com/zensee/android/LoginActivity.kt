package com.zensee.android

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.zensee.android.databinding.ActivityLoginBinding
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var submitLoadingButton: LoadingButtonController

    private val signUpLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.initialize(this)
        if (AuthManager.state().isAuthenticated) {
            setResult(RESULT_OK)
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyEdgeToEdge(this, binding.loginRoot, binding.loginToolbar)
        submitLoadingButton = LoadingButtonController(
            binding.loginSubmitButton,
            binding.loginSubmitProgress
        )

        setSupportActionBar(binding.loginToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.login_title)
        binding.loginToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.loginBrandTitleText.text = getString(R.string.brand_name)
        binding.loginSubtitleText.text = getString(R.string.login_subtitle)

        binding.loginSubmitButton.setOnClickListener { submit() }
        binding.forgotPasswordButton.setOnClickListener {
            startActivity(
                Intent(this, ForgotPasswordActivity::class.java)
                    .putExtra(
                        ForgotPasswordActivity.EXTRA_EMAIL,
                        binding.emailInput.text?.toString().orEmpty().trim()
                    )
            )
        }
        binding.goSignUpButton.setOnClickListener {
            signUpLauncher.launch(Intent(this, SignUpActivity::class.java))
        }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
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
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val error = when {
            !AuthUi.isEmailValid(email) || password.length < 6 ->
                getString(R.string.login_required)
            else -> null
        }
        if (error != null) {
            showError(error)
            return
        }

        showError("")
        AuthUi.dismissKeyboard(this)
        setLoading(true)
        thread {
            try {
                AuthManager.signIn(email, password)
                runOnUiThread {
                    setLoading(false)
                    setResult(RESULT_OK)
                    finish()
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
        binding.loginErrorText.isVisible = message.isNotBlank()
        binding.loginErrorText.text = message
    }

    private fun setLoading(loading: Boolean) {
        submitLoadingButton.setLoading(loading)
        binding.forgotPasswordButton.isEnabled = !loading
        binding.goSignUpButton.isEnabled = !loading
        binding.emailInput.isEnabled = !loading
        binding.passwordInput.isEnabled = !loading
    }
}
