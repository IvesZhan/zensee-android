package com.zensee.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.android.dingtalk.openauth.AuthLoginParam
import com.android.dingtalk.openauth.DDAuthApiFactory
import com.android.dingtalk.openauth.utils.DDAuthConstant
import com.android.dingtalk.openauth.utils.DDAuthUtil
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import java.security.MessageDigest
import java.util.UUID

enum class MainlandSocialProvider(
    val rawValue: String
) {
    WECHAT("wechat"),
    DINGTALK("dingtalk");

    companion object {
        fun fromRawValue(rawValue: String?): MainlandSocialProvider? {
            return entries.firstOrNull { it.rawValue == rawValue?.trim()?.lowercase() }
        }
    }
}

data class MainlandSocialPendingRequest(
    val provider: MainlandSocialProvider,
    val state: String
)

data class MainlandSocialPendingResult(
    val provider: MainlandSocialProvider,
    val code: String?,
    val state: String?,
    val errorMessage: String?
)

object MainlandSocialAuthManager {
    private const val TAG = "MainlandSocialAuth"
    private const val PREFS_NAME = "zensee_mainland_social_auth"
    private const val KEY_PENDING_PROVIDER = "pending_provider"
    private const val KEY_PENDING_STATE = "pending_state"
    private const val KEY_RESULT_PROVIDER = "result_provider"
    private const val KEY_RESULT_CODE = "result_code"
    private const val KEY_RESULT_STATE = "result_state"
    private const val KEY_RESULT_ERROR = "result_error"

    private const val WECHAT_SCOPE = "snsapi_userinfo"
    private const val DINGTALK_SCOPE = "openid"
    private const val DINGTALK_RESPONSE_TYPE = "code"
    private const val DINGTALK_PROMPT = "consent"
    private const val DINGTALK_CLIENT_TYPE = "android"
    private const val DINGTALK_SDK_VERSION = 20210101
    private const val DINGTALK_AUTH_BASE_URL = "https://login.dingtalk.com/oauth2/auth?"

    fun shouldShowEntry(context: Context): Boolean {
        return GroupShareCoordinator.isMainlandChinaRegion(context)
    }

    fun startAuthorization(activity: Activity, provider: MainlandSocialProvider) {
        clearPendingResult(activity)
        Log.d(TAG, "authorize request provider=${provider.rawValue}")

        when (provider) {
            MainlandSocialProvider.WECHAT -> startWeChatAuthorization(activity)
            MainlandSocialProvider.DINGTALK -> startDingTalkAuthorization(activity)
        }
    }

    fun createWeChatApi(context: Context): IWXAPI {
        return WXAPIFactory.createWXAPI(
            context.applicationContext,
            BuildConfig.WECHAT_APP_ID,
            true
        )
    }

    fun pendingRequest(context: Context): MainlandSocialPendingRequest? {
        val provider = MainlandSocialProvider.fromRawValue(
            prefs(context).getString(KEY_PENDING_PROVIDER, null)
        ) ?: return null
        val state = prefs(context).getString(KEY_PENDING_STATE, null)?.trim().orEmpty()
        if (state.isEmpty()) {
            clearPendingRequest(context)
            return null
        }
        return MainlandSocialPendingRequest(provider = provider, state = state)
    }

    fun clearPendingRequest(context: Context) {
        prefs(context).edit()
            .remove(KEY_PENDING_PROVIDER)
            .remove(KEY_PENDING_STATE)
            .apply()
    }

    fun consumePendingResult(context: Context): MainlandSocialPendingResult? {
        val provider = MainlandSocialProvider.fromRawValue(
            prefs(context).getString(KEY_RESULT_PROVIDER, null)
        ) ?: return null
        val result = MainlandSocialPendingResult(
            provider = provider,
            code = prefs(context).getString(KEY_RESULT_CODE, null)?.trim()?.ifEmpty { null },
            state = prefs(context).getString(KEY_RESULT_STATE, null)?.trim()?.ifEmpty { null },
            errorMessage = prefs(context).getString(KEY_RESULT_ERROR, null)?.trim()?.ifEmpty { null }
        )
        clearPendingResult(context)
        return result
    }

    fun handleWeChatResponse(context: Context, response: SendAuth.Resp) {
        Log.d(
            TAG,
            "wechat callback errCode=${response.errCode} codeLength=${response.code?.length ?: 0} state=${response.state.orEmpty()} errStr=${response.errStr.orEmpty()}"
        )
        when (response.errCode) {
            0 -> {
                val code = response.code?.trim().orEmpty()
                if (code.isBlank()) {
                    storeFailureResult(
                        context = context,
                        provider = MainlandSocialProvider.WECHAT,
                        message = context.getString(R.string.login_wechat_auth_failed)
                    )
                    return
                }

                storePendingResult(
                    context = context,
                    result = MainlandSocialPendingResult(
                        provider = MainlandSocialProvider.WECHAT,
                        code = code,
                        state = response.state?.trim(),
                        errorMessage = null
                    )
                )
            }

            -2 -> storeFailureResult(
                context = context,
                provider = MainlandSocialProvider.WECHAT,
                message = context.getString(R.string.login_wechat_canceled)
            )

            -4 -> storeFailureResult(
                context = context,
                provider = MainlandSocialProvider.WECHAT,
                message = context.getString(R.string.login_wechat_auth_failed)
            )

            else -> {
                val errorMessage = response.errStr?.trim().orEmpty()
                storeFailureResult(
                    context = context,
                    provider = MainlandSocialProvider.WECHAT,
                    message = errorMessage.ifBlank {
                        context.getString(R.string.login_wechat_failed)
                    }
                )
            }
        }
    }

    fun handleDingTalkCallback(context: Context, intent: Intent?) {
        val authCode = intent?.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_AUTH_CODE)?.trim().orEmpty()
        val state = intent?.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_STATE)?.trim()?.ifEmpty { null }
        val error = intent?.getStringExtra(DDAuthConstant.CALLBACK_EXTRA_ERROR)?.trim().orEmpty()
        Log.d(
            TAG,
            "dingtalk callback codeLength=${authCode.length} state=${state.orEmpty()} error=${error.orEmpty()}"
        )

        if (authCode.isNotBlank()) {
            storePendingResult(
                context = context,
                result = MainlandSocialPendingResult(
                    provider = MainlandSocialProvider.DINGTALK,
                    code = authCode,
                    state = state,
                    errorMessage = null
                )
            )
            return
        }

        val message = when {
            error.contains("access_denied", ignoreCase = true) ||
                error.contains("cancel", ignoreCase = true) ->
                context.getString(R.string.login_dingtalk_canceled)

            error.isNotBlank() -> error
            else -> context.getString(R.string.login_dingtalk_failed)
        }
        storeFailureResult(
            context = context,
            provider = MainlandSocialProvider.DINGTALK,
            message = message
        )
    }

    fun handleCallbackDispatchFailure(
        context: Context,
        provider: MainlandSocialProvider
    ) {
        Log.e(TAG, "callback dispatch failure provider=${provider.rawValue}")
        val message = when (provider) {
            MainlandSocialProvider.WECHAT -> context.getString(R.string.login_wechat_failed)
            MainlandSocialProvider.DINGTALK -> context.getString(R.string.login_dingtalk_failed)
        }
        storeFailureResult(context, provider, message)
    }

    fun resumeLoginActivity(context: Context) {
        Log.d(TAG, "resume login activity")
        val intent = Intent(context, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            putExtra(LoginActivity.EXTRA_RESUME_MAINLAND_SOCIAL_LOGIN, true)
        }
        context.startActivity(intent)
    }

    private fun startWeChatAuthorization(activity: Activity) {
        val appId = BuildConfig.WECHAT_APP_ID.trim()
        if (appId.isEmpty()) {
            throw IllegalStateException(activity.getString(R.string.login_wechat_app_id_missing))
        }

        val api = createWeChatApi(activity)
        if (!api.registerApp(appId)) {
            throw IllegalStateException(activity.getString(R.string.login_wechat_register_failed))
        }
        if (!api.isWXAppInstalled) {
            throw IllegalStateException(activity.getString(R.string.login_wechat_not_installed))
        }
        if (api.getWXAppSupportAPI() <= 0) {
            throw IllegalStateException(activity.getString(R.string.login_wechat_version_too_low))
        }

        val state = randomState()
        savePendingRequest(
            context = activity,
            request = MainlandSocialPendingRequest(
                provider = MainlandSocialProvider.WECHAT,
                state = state
            )
        )

        val request = SendAuth.Req().apply {
            scope = WECHAT_SCOPE
            this.state = state
            nonAutomatic = false
        }

        if (!api.sendReq(request)) {
            clearPendingRequest(activity)
            throw IllegalStateException(activity.getString(R.string.login_wechat_failed))
        }
        Log.d(TAG, "wechat sendReq success state=$state")
    }

    private fun startDingTalkAuthorization(activity: Activity) {
        val clientId = BuildConfig.DINGTALK_CLIENT_ID.trim()
        if (clientId.isEmpty()) {
            throw IllegalStateException(activity.getString(R.string.login_dingtalk_app_key_missing))
        }

        val redirectUri = BuildConfig.DINGTALK_REDIRECT_URI.trim()
        if (redirectUri.isEmpty()) {
            throw IllegalStateException(activity.getString(R.string.login_dingtalk_redirect_missing))
        }

        if (!DDAuthUtil.isDDAppInstalled(activity)) {
            throw IllegalStateException(activity.getString(R.string.login_dingtalk_not_installed))
        }
        if (!DDAuthUtil.isDDSupportAPI(activity)) {
            throw IllegalStateException(activity.getString(R.string.login_dingtalk_version_too_low))
        }

        val state = randomState()
        val nonce = UUID.randomUUID().toString()
        val identifier = activity.packageName
        val signature = packageSignatureMd5(activity, identifier)
        val requestPreview = buildDingTalkRequestPreview(
            clientId = clientId,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            identifier = identifier,
            signature = signature
        )
        Log.d(
            TAG,
            "dingtalk request clientId=$clientId identifier=$identifier sdkSignature=${signature.orEmpty()} scope=$DINGTALK_SCOPE redirect=$redirectUri previewUrl=$requestPreview"
        )
        savePendingRequest(
            context = activity,
            request = MainlandSocialPendingRequest(
                provider = MainlandSocialProvider.DINGTALK,
                state = state
            )
        )

        val param = AuthLoginParam.AuthLoginParamBuilder.newBuilder()
            .appId(clientId)
            .redirectUri(redirectUri)
            .state(state)
            .nonce(nonce)
            .scope(DINGTALK_SCOPE)
            .responseType(DINGTALK_RESPONSE_TYPE)
            .prompt(DINGTALK_PROMPT)
            .targetPackageName(DDAuthConstant.DD_APP_PACKAGE)
            .forceTarget(true)
            .build()

        try {
            DDAuthApiFactory.createDDAuthApi(activity, param).authLogin()
            Log.d(
                TAG,
                "dingtalk authLogin invoked state=$state identifier=$identifier sdkSignature=${signature.orEmpty()} scope=$DINGTALK_SCOPE redirect=$redirectUri"
            )
        } catch (error: Exception) {
            clearPendingRequest(activity)
            throw IllegalStateException(
                error.message?.takeIf { it.isNotBlank() }
                    ?: activity.getString(R.string.login_dingtalk_auth_start_failed)
            )
        }
    }

    private fun savePendingRequest(
        context: Context,
        request: MainlandSocialPendingRequest
    ) {
        prefs(context).edit()
            .putString(KEY_PENDING_PROVIDER, request.provider.rawValue)
            .putString(KEY_PENDING_STATE, request.state)
            .apply()
    }

    private fun storeFailureResult(
        context: Context,
        provider: MainlandSocialProvider,
        message: String
    ) {
        storePendingResult(
            context = context,
            result = MainlandSocialPendingResult(
                provider = provider,
                code = null,
                state = pendingRequest(context)?.state,
                errorMessage = message
            )
        )
    }

    private fun storePendingResult(
        context: Context,
        result: MainlandSocialPendingResult
    ) {
        Log.d(
            TAG,
            "store result provider=${result.provider.rawValue} codeLength=${result.code?.length ?: 0} state=${result.state.orEmpty()} error=${result.errorMessage.orEmpty()}"
        )
        prefs(context).edit()
            .putString(KEY_RESULT_PROVIDER, result.provider.rawValue)
            .putString(KEY_RESULT_CODE, result.code)
            .putString(KEY_RESULT_STATE, result.state)
            .putString(KEY_RESULT_ERROR, result.errorMessage)
            .apply()
    }

    private fun clearPendingResult(context: Context) {
        prefs(context).edit()
            .remove(KEY_RESULT_PROVIDER)
            .remove(KEY_RESULT_CODE)
            .remove(KEY_RESULT_STATE)
            .remove(KEY_RESULT_ERROR)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun randomState(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    private fun buildDingTalkRequestPreview(
        clientId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        identifier: String,
        signature: String?
    ): String {
        val uri = Uri.parse(DINGTALK_AUTH_BASE_URL)
        return uri.buildUpon()
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", DINGTALK_RESPONSE_TYPE)
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scope", DINGTALK_SCOPE)
            .appendQueryParameter("nonce", nonce)
            .appendQueryParameter("state", state)
            .appendQueryParameter("prompt", DINGTALK_PROMPT)
            .appendQueryParameter("sdk_version", DINGTALK_SDK_VERSION.toString())
            .appendQueryParameter("identifier", identifier)
            .apply {
                if (!signature.isNullOrBlank()) {
                    appendQueryParameter("signature", signature)
                }
            }
            .appendQueryParameter("client_type", DINGTALK_CLIENT_TYPE)
            .build()
            .toString()
    }

    private fun packageSignatureMd5(context: Context, packageName: String): String? {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            val firstSignature = signatures?.firstOrNull()?.toByteArray() ?: return null
            MessageDigest.getInstance("MD5")
                .digest(firstSignature)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        } catch (error: Exception) {
            Log.w(TAG, "dingtalk signature md5 unavailable: ${error.message.orEmpty()}")
            null
        }
    }
}
