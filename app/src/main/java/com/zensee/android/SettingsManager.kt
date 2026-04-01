package com.zensee.android

import android.content.Context

object SettingsManager {
    private const val PREFS_NAME = "zensee_settings"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_MEDITATION_SOUND = "meditation_sound"

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun isSoundEnabled(): Boolean {
        return prefs().getBoolean(KEY_SOUND_ENABLED, true)
    }

    fun setSoundEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    fun selectedMeditationSoundKey(): String {
        return prefs().getString(
            KEY_MEDITATION_SOUND,
            MeditationSoundCatalog.defaultSound.storageKey
        ) ?: MeditationSoundCatalog.defaultSound.storageKey
    }

    fun setSelectedMeditationSoundKey(storageKey: String) {
        prefs().edit().putString(KEY_MEDITATION_SOUND, storageKey).apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
