package com.zensee.android

import android.app.Application

class ZenSeeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsManager.initialize(this)
        SettingsManager.applyAppearanceMode()
    }
}
