package com.zensee.android

import android.content.Context

object GroupAutoCheckInManager {
    private const val PREFS_NAME = "zensee_group_auto_check_in"

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun isEnabled(groupId: String, userId: String): Boolean {
        return prefs().getBoolean(preferenceKey(groupId, userId), false)
    }

    fun setEnabled(groupId: String, userId: String, enabled: Boolean) {
        prefs().edit().putBoolean(preferenceKey(groupId, userId), enabled).apply()
    }

    fun clear(groupId: String, userId: String) {
        prefs().edit().remove(preferenceKey(groupId, userId)).apply()
    }

    fun enabledGroupIds(userId: String): Set<String> {
        val prefix = "${userId}_"
        return prefs().all
            .asSequence()
            .filter { (key, value) ->
                key.startsWith(prefix) && value as? Boolean == true
            }
            .map { (key, _) ->
                key.removePrefix(prefix)
            }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun preferenceKey(groupId: String, userId: String): String {
        return "${userId}_${groupId}"
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
