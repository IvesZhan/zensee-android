package com.zensee.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import com.android.dingtalk.share.ddsharemodule.DDShareApiFactory
import com.android.dingtalk.share.ddsharemodule.message.DDMediaMessage
import com.android.dingtalk.share.ddsharemodule.message.DDWebpageMessage
import com.android.dingtalk.share.ddsharemodule.message.SendMessageToDD
import java.io.ByteArrayOutputStream

object DingTalkShareCoordinator {
    private const val TAG = "DingTalkShareCoord"
    private const val MAX_THUMB_BYTES = 32 * 1024
    private val thumbSizes = intArrayOf(96, 84, 72, 64, 56)

    fun shareWebPage(activity: Activity, payload: GroupSharePayload) {
        val appId = BuildConfig.DINGTALK_CLIENT_ID.trim()
        if (appId.isEmpty()) {
            Toast.makeText(activity, R.string.group_share_dingtalk_app_id_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val api = DDShareApiFactory.createDDShareApi(activity, appId, true)
        if (!api.isDDAppInstalled) {
            Toast.makeText(activity, R.string.group_share_dingtalk_not_installed, Toast.LENGTH_SHORT).show()
            return
        }

        if (!api.isDDSupportAPI) {
            Toast.makeText(activity, R.string.group_share_dingtalk_version_too_low, Toast.LENGTH_SHORT).show()
            return
        }

        if (!api.registerApp(appId)) {
            Toast.makeText(activity, R.string.group_share_dingtalk_register_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val request = SendMessageToDD.Req().apply {
            mMediaMessage = DDMediaMessage(
                DDWebpageMessage().apply {
                    mUrl = payload.shareUrl
                }
            ).apply {
                mTitle = payload.subject
                mContent = payload.message
                mUrl = payload.shareUrl
                loadAppThumbData(activity)?.let { mThumbData = it }
            }
        }

        if (!api.sendReq(request)) {
            Log.w(TAG, "sendReq returned false for url=${payload.shareUrl}")
            Toast.makeText(activity, R.string.group_share_dingtalk_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAppThumbData(activity: Activity): ByteArray? {
        val icon = runCatching {
            activity.applicationInfo.loadIcon(activity.packageManager)
        }.getOrNull() ?: return null

        return icon.toDingTalkThumbData()
    }

    private fun Drawable.toDingTalkThumbData(): ByteArray? {
        for (size in thumbSizes) {
            val bitmap = runCatching {
                toBitmap(width = size, height = size, config = Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: continue
            val bytes = bitmap.toPngData() ?: continue
            if (bytes.size <= MAX_THUMB_BYTES) {
                return bytes
            }
        }

        return null
    }

    private fun Bitmap.toPngData(): ByteArray? {
        return runCatching {
            ByteArrayOutputStream().use { stream ->
                if (compress(CompressFormat.PNG, 100, stream)) stream.toByteArray() else null
            }
        }.getOrNull()
    }
}
