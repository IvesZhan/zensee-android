package com.zensee.android

import android.content.Context

interface PrivacyConsentStore {
    var hasAcceptedPrivacyConsent: Boolean
}

class SharedPreferencesPrivacyConsentStore(
    context: Context
) : PrivacyConsentStore {
    private val appContext = context.applicationContext

    override var hasAcceptedPrivacyConsent: Boolean
        get() = prefs().getBoolean(KEY_PRIVACY_CONSENT_ACCEPTED, false)
        set(value) {
            prefs().edit().putBoolean(KEY_PRIVACY_CONSENT_ACCEPTED, value).apply()
        }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "zensee_privacy"
        const val KEY_PRIVACY_CONSENT_ACCEPTED = "privacy_consent_accepted"
    }
}

class PrivacyConsentGate(
    private val store: PrivacyConsentStore
) {
    var shouldPresentPrompt: Boolean = !store.hasAcceptedPrivacyConsent
        private set

    fun accept() {
        store.hasAcceptedPrivacyConsent = true
        shouldPresentPrompt = false
    }
}
