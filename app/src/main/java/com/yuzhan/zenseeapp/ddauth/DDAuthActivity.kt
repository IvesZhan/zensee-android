package com.yuzhan.zenseeapp.ddauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.zensee.android.MainlandSocialAuthManager

class DDAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    private fun handleCallback(intent: Intent?) {
        MainlandSocialAuthManager.handleDingTalkCallback(this, intent)
        MainlandSocialAuthManager.resumeLoginActivity(this)
        finish()
    }
}
