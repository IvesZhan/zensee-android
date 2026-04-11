package com.yuzhan.zenseeapp.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.zensee.android.MainlandSocialAuthManager
import com.zensee.android.MainlandSocialProvider

class WXEntryActivity : Activity(), IWXAPIEventHandler {
    private lateinit var weChatApi: IWXAPI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        weChatApi = MainlandSocialAuthManager.createWeChatApi(this)
        dispatchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIntent(intent)
    }

    override fun onReq(req: BaseReq?) = Unit

    override fun onResp(resp: BaseResp?) {
        if (resp is SendAuth.Resp) {
            MainlandSocialAuthManager.handleWeChatResponse(this, resp)
        } else {
            MainlandSocialAuthManager.handleCallbackDispatchFailure(
                context = this,
                provider = MainlandSocialProvider.WECHAT
            )
        }
        MainlandSocialAuthManager.resumeLoginActivity(this)
        finish()
    }

    private fun dispatchIntent(intent: Intent?) {
        if (intent == null || !weChatApi.handleIntent(intent, this)) {
            MainlandSocialAuthManager.handleCallbackDispatchFailure(
                context = this,
                provider = MainlandSocialProvider.WECHAT
            )
            MainlandSocialAuthManager.resumeLoginActivity(this)
            finish()
        }
    }
}
