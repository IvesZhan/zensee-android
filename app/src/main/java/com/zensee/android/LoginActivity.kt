package com.zensee.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.zensee.android.databinding.ActivityLoginBinding
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_RESUME_MAINLAND_SOCIAL_LOGIN = "resume_mainland_social_login"
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var submitLoadingButton: LoadingButtonController
    private var isLoading = false
    private var activeMainlandProvider: MainlandSocialProvider? = null
    private var awaitingMainlandSocialCallback = false
    private val blockBackWhileLoadingCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }
    private val socialAuthTag = "MainlandSocialAuth"

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
        onBackPressedDispatcher.addCallback(this, blockBackWhileLoadingCallback)
        submitLoadingButton = LoadingButtonController(
            binding.loginSubmitButton,
            binding.loginSubmitProgress
        )

        setSupportActionBar(binding.loginToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.login_title)
        binding.loginToolbar.setNavigationOnClickListener {
            if (!isLoading) {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        binding.loginBrandTitleText.text = getString(R.string.brand_name)
        binding.loginSubtitleText.text = getString(R.string.login_subtitle)
        binding.loginQuickSignInSection.isVisible = MainlandSocialAuthManager.shouldShowEntry(this)

        binding.loginSubmitButton.setOnClickListener { submit() }
        binding.loginWeChatButton.setOnClickListener {
            startMainlandSocialSignIn(MainlandSocialProvider.WECHAT)
        }
        binding.loginDingTalkButton.setOnClickListener {
            startMainlandSocialSignIn(MainlandSocialProvider.DINGTALK)
        }
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

        if (intent.getBooleanExtra(EXTRA_RESUME_MAINLAND_SOCIAL_LOGIN, false)) {
            resumePendingMainlandSocialLoginIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RESUME_MAINLAND_SOCIAL_LOGIN, false)) {
            resumePendingMainlandSocialLoginIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        resumePendingMainlandSocialLoginIfNeeded()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!isLoading) {
            onBackPressedDispatcher.onBackPressed()
        }
        return true
    }

    private fun submit() {
        if (isLoading) return
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
        setLoading(loading = true)
        thread {
            try {
                AuthManager.signIn(email, password)
                runOnUiThread {
                    setLoading(loading = false)
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setLoading(loading = false)
                    showError(AuthUi.localizedError(this, error.message.orEmpty()))
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.loginErrorText.isVisible = message.isNotBlank()
        binding.loginErrorText.text = message
    }

    private fun startMainlandSocialSignIn(provider: MainlandSocialProvider) {
        if (isLoading) return
        showError("")
        AuthUi.dismissKeyboard(this)

        try {
            Log.d(socialAuthTag, "start provider=${provider.rawValue}")
            MainlandSocialAuthManager.startAuthorization(this, provider)
            awaitingMainlandSocialCallback = true
            setLoading(loading = true, provider = provider)
        } catch (error: Exception) {
            awaitingMainlandSocialCallback = false
            setLoading(loading = false)
            Log.e(socialAuthTag, "start failed provider=${provider.rawValue} message=${error.message}", error)
            showError(error.message.orEmpty().ifBlank { getString(R.string.operation_failed) })
        }
    }

    private fun resumePendingMainlandSocialLoginIfNeeded() {
        val result = MainlandSocialAuthManager.consumePendingResult(this)
        if (result == null) {
            if (awaitingMainlandSocialCallback && activeMainlandProvider != null) {
                Log.d(
                    socialAuthTag,
                    "resume without result provider=${activeMainlandProvider?.rawValue.orEmpty()}"
                )
                setLoading(loading = false)
            }
            return
        }

        val request = MainlandSocialAuthManager.pendingRequest(this)
        MainlandSocialAuthManager.clearPendingRequest(this)
        awaitingMainlandSocialCallback = false
        Log.d(
            socialAuthTag,
            "resume result provider=${result.provider.rawValue} codeLength=${result.code?.length ?: 0} state=${result.state.orEmpty()} error=${result.errorMessage.orEmpty()}"
        )

        if (request == null || request.provider != result.provider) {
            setLoading(loading = false)
            Log.e(
                socialAuthTag,
                "resume request mismatch requestProvider=${request?.provider?.rawValue.orEmpty()} resultProvider=${result.provider.rawValue}"
            )
            showError(getString(R.string.login_social_state_invalid))
            return
        }

        if (!result.errorMessage.isNullOrBlank()) {
            setLoading(loading = false)
            showError(result.errorMessage)
            return
        }

        val code = result.code?.trim().orEmpty()
        if (code.isBlank()) {
            setLoading(loading = false)
            showError(getString(R.string.login_social_failed))
            return
        }

        if (!result.state.isNullOrBlank() && result.state != request.state) {
            setLoading(loading = false)
            showError(getString(R.string.login_social_state_invalid))
            return
        }

        setLoading(loading = true, provider = result.provider)
        thread {
            try {
                AuthManager.signInWithMainlandProvider(
                    provider = result.provider,
                    code = code,
                    state = result.state
                )
                runOnUiThread {
                    setLoading(loading = false)
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setLoading(loading = false)
                    Log.e(
                        socialAuthTag,
                        "exchange failed provider=${result.provider.rawValue} message=${error.message}",
                        error
                    )
                    showError(error.message.orEmpty().ifBlank { getString(R.string.login_social_failed) })
                }
            }
        }
    }

    private fun setLoading(
        loading: Boolean,
        provider: MainlandSocialProvider? = null
    ) {
        isLoading = loading
        activeMainlandProvider = if (loading) provider else null
        blockBackWhileLoadingCallback.isEnabled = loading
        submitLoadingButton.setLoading(loading)
        binding.forgotPasswordButton.isEnabled = !loading
        binding.goSignUpButton.isEnabled = !loading
        binding.emailInput.isEnabled = !loading
        binding.passwordInput.isEnabled = !loading
        binding.loginScrollView.isEnabled = !loading
        binding.loginWeChatButton.isEnabled = !loading
        binding.loginDingTalkButton.isEnabled = !loading
        binding.loginWeChatButton.alpha = if (loading && provider != MainlandSocialProvider.WECHAT) 0.45f else 1f
        binding.loginDingTalkButton.alpha = if (loading && provider != MainlandSocialProvider.DINGTALK) 0.45f else 1f
        binding.loginWeChatProgress.isVisible = loading && provider == MainlandSocialProvider.WECHAT
        binding.loginDingTalkProgress.isVisible = loading && provider == MainlandSocialProvider.DINGTALK
    }
}
