package com.zensee.android

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant

data class AuthState(
    val isAuthenticated: Boolean = false,
    val userId: String? = null,
    val email: String = "",
    val nickname: String = ""
) {
    val emailPrefix: String
        get() = email.substringBefore("@", "")

    val displayName: String
        get() = nickname.ifBlank { emailPrefix.ifBlank { "未登录" } }
}

data class SignUpResult(
    val signedIn: Boolean,
    val message: String = ""
)

object AuthManager {
    private const val PREFS_NAME = "zensee_auth"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_RECOVERY_ACCESS_TOKEN = "recovery_access_token"
    private const val KEY_RECOVERY_REFRESH_TOKEN = "recovery_refresh_token"
    private const val KEY_PENDING_RECOVERY_EMAIL = "pending_recovery_email"
    const val PASSWORD_RECOVERY_REDIRECT_URL = "zensee://auth/reset-password"

    private lateinit var appContext: Context
    private var authState: AuthState = AuthState()
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var recoveryAccessToken: String? = null
    private var recoveryRefreshToken: String? = null
    private var pendingPasswordRecoveryEmail: String? = null
    private val authStateListeners = linkedSetOf<(AuthState) -> Unit>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        loadPersistedState()
    }

    fun state(): AuthState = authState

    fun accessTokenOrNull(): String? = accessToken

    fun storageKeyPrefix(): String {
        return authState.userId?.takeIf { authState.isAuthenticated }?.let { "user_$it" } ?: "guest"
    }

    fun pendingRecoveryEmail(): String? = pendingPasswordRecoveryEmail

    fun addAuthStateListener(listener: (AuthState) -> Unit) {
        synchronized(authStateListeners) {
            authStateListeners += listener
        }
    }

    fun removeAuthStateListener(listener: (AuthState) -> Unit) {
        synchronized(authStateListeners) {
            authStateListeners -= listener
        }
    }

    fun hasPendingPasswordRecovery(): Boolean {
        return !pendingPasswordRecoveryEmail.isNullOrBlank() && !recoveryAccessToken.isNullOrBlank()
    }

    fun signIn(email: String, password: String) {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        val body = SupabaseRestClient.request(
            path = "/auth/v1/token?grant_type=password",
            method = "POST",
            body = payload
        )
        val json = JSONObject(body)
        val token = json.getString("access_token")
        val refresh = json.optString("refresh_token")
        val user = json.getJSONObject("user")
        val userId = user.getString("id")
        val confirmedEmail = user.optString("email", email.trim())
        val nickname = ensureProfile(userId, confirmedEmail, token)
        persistSession(token, refresh, userId, confirmedEmail, nickname)
    }

    fun signUp(email: String, password: String): SignUpResult {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        val body = SupabaseRestClient.request(
            path = "/auth/v1/signup",
            method = "POST",
            body = payload
        )
        val json = JSONObject(body)
        val token = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val user = json.optJSONObject("user")
        if (token.isBlank() || user == null) {
            clearSession()
            return SignUpResult(
                signedIn = false,
                message = "注册成功，请查收邮件并点击确认链接后登录。"
            )
        }

        val userId = user.getString("id")
        val confirmedEmail = user.optString("email", email.trim())
        val nickname = ensureProfile(userId, confirmedEmail, token)
        persistSession(token, refresh, userId, confirmedEmail, nickname)
        return SignUpResult(signedIn = true)
    }

    fun resetPassword(email: String) {
        val payload = JSONObject().put("email", email.trim())
        SupabaseRestClient.request(
            path = "/auth/v1/recover?redirect_to=${urlEncoded(PASSWORD_RECOVERY_REDIRECT_URL)}",
            method = "POST",
            body = payload
        )
        pendingPasswordRecoveryEmail = email.trim().lowercase()
        recoveryAccessToken = null
        recoveryRefreshToken = null
        persistLocalState()
    }

    fun handlePasswordRecoveryUrl(url: String) {
        val uri = Uri.parse(url)
        if (!isPasswordRecoveryUrl(uri)) {
            throw IllegalArgumentException("Invalid password recovery url")
        }

        val params = parseUrlParams(uri)
        val errorDescription = params["error_description"]?.takeIf { it.isNotBlank() }
        if (errorDescription != null) {
            throw IllegalStateException(URLDecoder.decode(errorDescription, "UTF-8"))
        }

        val token = params["access_token"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing recovery access token")
        recoveryAccessToken = token
        recoveryRefreshToken = params["refresh_token"].orEmpty()

        val user = fetchUser(token)
        val email = user.optString("email").ifBlank {
            pendingPasswordRecoveryEmail.orEmpty()
        }
        pendingPasswordRecoveryEmail = email.ifBlank { null }?.lowercase()
        persistLocalState()
    }

    fun completePasswordRecovery(newPassword: String) {
        val token = recoveryAccessToken ?: throw IllegalStateException("No active recovery session")
        val payload = JSONObject().put("password", newPassword)
        SupabaseRestClient.request(
            path = "/auth/v1/user",
            method = "PUT",
            body = payload,
            accessToken = token
        )

        val user = fetchUser(token)
        val userId = user.getString("id")
        val email = user.optString("email").ifBlank {
            pendingPasswordRecoveryEmail.orEmpty()
        }
        val nickname = ensureProfile(userId, email, token)
        val refresh = recoveryRefreshToken.orEmpty()
        clearPendingPasswordRecovery()
        persistSession(token, refresh, userId, email, nickname)
    }

    fun cancelPasswordRecovery() {
        clearPendingPasswordRecovery()
    }

    fun signOut() {
        accessToken?.let {
            try {
                SupabaseRestClient.request(
                    path = "/auth/v1/logout",
                    method = "POST",
                    accessToken = it,
                    allowSessionRecovery = false
                )
            } catch (_: Exception) {
            }
        }
        clearSession()
    }

    fun shouldRefreshSession(message: String?, responseCode: Int? = null): Boolean {
        return !refreshToken.isNullOrBlank() && isSessionExpiredError(message, responseCode)
    }

    fun expireSessionIfNeeded(message: String?, responseCode: Int? = null): Boolean {
        val shouldExpire = isSessionExpiredError(message, responseCode)
        if (shouldExpire) {
            clearSession()
            return true
        }
        return false
    }

    fun deleteAccountAndData() {
        val userId = authState.userId ?: throw IllegalStateException("No session")
        val token = accessToken ?: throw IllegalStateException("No access token")
        SupabaseRestClient.request(
            path = "/rest/v1/meditation_sessions?user_id=${encodedEq(userId)}",
            method = "DELETE",
            accessToken = token
        )
        try {
            SupabaseRestClient.request(
                path = "/rest/v1/mood_entries?user_id=${encodedEq(userId)}",
                method = "DELETE",
                accessToken = token
            )
        } catch (_: Exception) {
        }
        SupabaseRestClient.request(
            path = "/rest/v1/profiles?id=${encodedEq(userId)}",
            method = "DELETE",
            accessToken = token
        )
        SupabaseRestClient.request(
            path = "/rest/v1/rpc/delete_user",
            method = "POST",
            body = JSONObject(),
            accessToken = token
        )
        signOut()
    }

    fun updateNickname(newNickname: String) {
        val userId = authState.userId ?: throw IllegalStateException("No session")
        val token = accessToken ?: throw IllegalStateException("No access token")
        val payload = JSONObject()
            .put("id", userId)
            .put("nickname", newNickname.trim())
            .put("updated_at", Instant.now().toString())
        SupabaseRestClient.request(
            path = "/rest/v1/profiles?on_conflict=id",
            method = "POST",
            body = payload,
            accessToken = token,
            preferMergeDuplicates = true,
            preferReturnRepresentation = true
        )
        authState = authState.copy(nickname = newNickname.trim())
        persistLocalState()
        notifyAuthStateChanged()
    }

    fun updatePassword(newPassword: String) {
        val token = accessToken ?: throw IllegalStateException("No access token")
        val payload = JSONObject().put("password", newPassword)
        SupabaseRestClient.request(
            path = "/auth/v1/user",
            method = "PUT",
            body = payload,
            accessToken = token,
            allowSessionRecovery = false
        )
    }

    @Synchronized
    fun refreshSessionIfPossible(): Boolean {
        val currentRefreshToken = refreshToken?.takeIf { it.isNotBlank() } ?: return false
        return try {
            val body = SupabaseRestClient.request(
                path = "/auth/v1/token?grant_type=refresh_token",
                method = "POST",
                body = JSONObject().put("refresh_token", currentRefreshToken),
                allowSessionRecovery = false
            )
            val json = JSONObject(body)
            val token = json.getString("access_token")
            val refreshedToken = json.optString("refresh_token").ifBlank { currentRefreshToken }
            val user = json.optJSONObject("user")
            val userId = user?.optString("id").orEmpty().ifBlank { authState.userId.orEmpty() }
            val email = user?.optString("email").orEmpty().ifBlank { authState.email }

            accessToken = token
            refreshToken = refreshedToken
            authState = AuthState(
                isAuthenticated = true,
                userId = userId.ifBlank { null } ?: authState.userId,
                email = email,
                nickname = authState.nickname
            )
            persistLocalState()
            notifyAuthStateChanged()
            true
        } catch (error: Exception) {
            if (isSessionExpiredError(error.message)) {
                clearSession()
            }
            false
        }
    }

    private fun ensureProfile(userId: String, email: String, token: String): String {
        val existing = fetchProfile(userId, token)
        if (existing != null) return existing

        val fallbackNickname = email.substringBefore("@").trim()
        if (fallbackNickname.isBlank()) return ""
        val payload = JSONObject()
            .put("id", userId)
            .put("nickname", fallbackNickname)
            .put("updated_at", Instant.now().toString())

        SupabaseRestClient.request(
            path = "/rest/v1/profiles?on_conflict=id",
            method = "POST",
            body = payload,
            accessToken = token,
            preferMergeDuplicates = true,
            preferReturnRepresentation = true
        )
        return fallbackNickname
    }

    private fun fetchProfile(userId: String, token: String): String? {
        val body = SupabaseRestClient.request(
            path = "/rest/v1/profiles?id=${encodedEq(userId)}&select=id,nickname",
            method = "GET",
            accessToken = token
        )
        val array = JSONArray(body)
        if (array.length() == 0) return null
        val nickname = array.getJSONObject(0).optString("nickname").trim()
        return nickname.ifBlank { null }
    }

    private fun persistSession(
        token: String,
        refresh: String,
        userId: String,
        email: String,
        nickname: String
    ) {
        accessToken = token
        refreshToken = refresh
        authState = AuthState(
            isAuthenticated = true,
            userId = userId,
            email = email,
            nickname = nickname
        )
        persistLocalState()
        notifyAuthStateChanged()
    }

    private fun clearSession() {
        accessToken = null
        refreshToken = null
        clearPendingPasswordRecovery()
        authState = AuthState()
        prefs().edit().clear().apply()
        notifyAuthStateChanged()
    }

    private fun loadPersistedState() {
        val storedUserId = prefs().getString(KEY_USER_ID, null)
        val storedEmail = prefs().getString(KEY_EMAIL, "") ?: ""
        val storedNickname = prefs().getString(KEY_NICKNAME, "") ?: ""
        accessToken = prefs().getString(KEY_ACCESS_TOKEN, null)
        refreshToken = prefs().getString(KEY_REFRESH_TOKEN, null)
        recoveryAccessToken = prefs().getString(KEY_RECOVERY_ACCESS_TOKEN, null)
        recoveryRefreshToken = prefs().getString(KEY_RECOVERY_REFRESH_TOKEN, null)
        pendingPasswordRecoveryEmail = prefs().getString(KEY_PENDING_RECOVERY_EMAIL, null)
        authState = if (!storedUserId.isNullOrBlank() && !accessToken.isNullOrBlank()) {
            AuthState(
                isAuthenticated = true,
                userId = storedUserId,
                email = storedEmail,
                nickname = storedNickname
            )
        } else {
            AuthState()
        }
    }

    private fun persistLocalState() {
        prefs().edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, authState.userId)
            .putString(KEY_EMAIL, authState.email)
            .putString(KEY_NICKNAME, authState.nickname)
            .putString(KEY_RECOVERY_ACCESS_TOKEN, recoveryAccessToken)
            .putString(KEY_RECOVERY_REFRESH_TOKEN, recoveryRefreshToken)
            .putString(KEY_PENDING_RECOVERY_EMAIL, pendingPasswordRecoveryEmail)
            .apply()
    }

    private fun notifyAuthStateChanged() {
        val snapshot = authState
        val listeners = synchronized(authStateListeners) {
            authStateListeners.toList()
        }
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encodedEq(value: String): String = URLEncoder.encode("eq.$value", "UTF-8")

    private fun urlEncoded(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun isSessionExpiredError(message: String?, responseCode: Int? = null): Boolean {
        val normalized = message.orEmpty().lowercase()
        return responseCode == 401 ||
            normalized.contains("jwt expired") ||
            normalized.contains("invalid jwt") ||
            normalized.contains("token has expired") ||
            normalized.contains("refresh token not found") ||
            normalized.contains("session expired")
    }

    private fun fetchUser(token: String): JSONObject {
        val body = SupabaseRestClient.request(
            path = "/auth/v1/user",
            method = "GET",
            accessToken = token
        )
        return JSONObject(body)
    }

    private fun clearPendingPasswordRecovery() {
        recoveryAccessToken = null
        recoveryRefreshToken = null
        pendingPasswordRecoveryEmail = null
        persistLocalState()
    }

    private fun parseUrlParams(uri: Uri): Map<String, String> {
        val params = linkedMapOf<String, String>()
        uri.queryParameterNames.forEach { name ->
            params[name] = uri.getQueryParameter(name).orEmpty()
        }

        val fragment = uri.encodedFragment.orEmpty()
        if (fragment.isNotBlank()) {
            fragment.split("&").forEach { part ->
                if (part.isBlank()) return@forEach
                val pair = part.split("=", limit = 2)
                val key = URLDecoder.decode(pair[0], "UTF-8")
                val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                params[key] = value
            }
        }
        return params
    }

    private fun isPasswordRecoveryUrl(uri: Uri): Boolean {
        val normalizedPath = uri.path.orEmpty().trim('/').lowercase()
        val matchesRedirect = uri.scheme == Uri.parse(PASSWORD_RECOVERY_REDIRECT_URL).scheme &&
            uri.host == Uri.parse(PASSWORD_RECOVERY_REDIRECT_URL).host &&
            normalizedPath == Uri.parse(PASSWORD_RECOVERY_REDIRECT_URL).path.orEmpty().trim('/').lowercase()
        if (matchesRedirect) return true
        return parseUrlParams(uri)["type"]?.lowercase() == "recovery"
    }
}

private object SupabaseRestClient {
    private val BASE_URL = BuildConfig.SUPABASE_URL
    private val API_KEY = BuildConfig.SUPABASE_ANON_KEY

    fun request(
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String? = null,
        preferMergeDuplicates: Boolean = false,
        preferReturnRepresentation: Boolean = false,
        allowSessionRecovery: Boolean = true
    ): String {
        val response = if (allowSessionRecovery) {
            AuthRequestRetrier.execute(
                accessTokenProvider = { AuthManager.accessTokenOrNull() ?: accessToken },
                shouldRefresh = { result ->
                    AuthManager.shouldRefreshSession(parseErrorMessage(result.body), result.code)
                },
                refreshSession = { AuthManager.refreshSessionIfPossible() }
            ) { resolvedToken ->
                performRequest(
                    path = path,
                    method = method,
                    body = body,
                    accessToken = resolvedToken,
                    preferMergeDuplicates = preferMergeDuplicates,
                    preferReturnRepresentation = preferReturnRepresentation
                )
            }
        } else {
            performRequest(
                path = path,
                method = method,
                body = body,
                accessToken = accessToken,
                preferMergeDuplicates = preferMergeDuplicates,
                preferReturnRepresentation = preferReturnRepresentation
            )
        }

        if (response.code !in 200..299) {
            val message = parseErrorMessage(response.body)
            if (allowSessionRecovery && AuthManager.expireSessionIfNeeded(message, response.code)) {
                throw IllegalStateException("登录已过期，请重新登录")
            }
            throw IllegalStateException(message)
        }
        return response.body
    }

    private fun performRequest(
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String? = null,
        preferMergeDuplicates: Boolean = false,
        preferReturnRepresentation: Boolean = false
    ): RawHttpResponse {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            doInput = true
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (preferMergeDuplicates && preferReturnRepresentation) {
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=representation")
            } else if (preferMergeDuplicates) {
                setRequestProperty("Prefer", "resolution=merge-duplicates")
            } else if (preferReturnRepresentation) {
                setRequestProperty("Prefer", "return=representation")
            }
        }

        if (body != null) {
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }.orEmpty()
        return RawHttpResponse(code = responseCode, body = responseText)
    }

    private fun parseErrorMessage(body: String): String {
        if (body.isBlank()) return "网络请求失败"
        return try {
            val json = JSONObject(body)
            when {
                json.has("msg") -> json.getString("msg")
                json.has("message") -> json.getString("message")
                json.has("error_description") -> json.getString("error_description")
                json.has("error") -> json.getString("error")
                else -> body
            }
        } catch (_: Exception) {
            body
        }
    }
}
