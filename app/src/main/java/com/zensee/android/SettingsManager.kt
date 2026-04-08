package com.zensee.android

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

enum class AppearanceMode(
    val storageValue: String,
    @StringRes val titleRes: Int,
    val nightMode: Int
) {
    LIGHT("light", R.string.appearance_mode_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", R.string.appearance_mode_dark, AppCompatDelegate.MODE_NIGHT_YES),
    SYSTEM("system", R.string.appearance_mode_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    companion object {
        fun fromStorageValue(value: String?): AppearanceMode {
            return entries.firstOrNull { it.storageValue == value } ?: SYSTEM
        }

        val selectionOrder: List<AppearanceMode>
            get() = listOf(LIGHT, DARK, SYSTEM)
    }
}

object SettingsManager {
    private const val PREFS_NAME = "zensee_settings"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_MEDITATION_SOUND = "meditation_sound"
    private const val KEY_APPEARANCE_MODE = "appearance_mode"

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

    fun appearanceMode(): AppearanceMode {
        return AppearanceMode.fromStorageValue(
            prefs().getString(KEY_APPEARANCE_MODE, AppearanceMode.SYSTEM.storageValue)
        )
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        prefs().edit().putString(KEY_APPEARANCE_MODE, mode.storageValue).apply()
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    fun applyAppearanceMode() {
        AppCompatDelegate.setDefaultNightMode(appearanceMode().nightMode)
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
