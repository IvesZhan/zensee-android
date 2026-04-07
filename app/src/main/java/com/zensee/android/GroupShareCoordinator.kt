package com.zensee.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.widget.Toast
import com.zensee.android.model.GroupModel
import java.util.Locale

enum class MainlandShareDestination {
    WECHAT,
    DINGTALK,
    MORE
}

data class GroupSharePayload(
    val subject: String,
    val messageWithLink: String,
    val clipboardText: String
)

object GroupShareCoordinator {
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"

    fun isMainlandChinaRegion(resources: Resources): Boolean {
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        return locale.country.equals("CN", ignoreCase = true)
    }

    fun payload(context: Context, group: GroupModel): GroupSharePayload {
        val shareUrl = GroupShareLinkBuilder.shareUrl(group.id)
        val messageWithLink = context.getString(R.string.group_share_message_with_link, group.name, shareUrl)
        return GroupSharePayload(
            subject = group.name + " · " + context.getString(R.string.group_discover_title),
            messageWithLink = messageWithLink,
            clipboardText = messageWithLink
        )
    }

    fun copyToClipboard(context: Context, label: String, payload: GroupSharePayload) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, payload.clipboardText))
    }

    fun presentSystemShare(activity: Activity, payload: GroupSharePayload) {
        activity.startActivity(
            Intent.createChooser(
                buildShareIntent(payload),
                activity.getString(R.string.group_share_link_action)
            )
        )
    }

    fun presentThirdPartyShare(
        activity: Activity,
        destination: MainlandShareDestination,
        payload: GroupSharePayload
    ) {
        when (destination) {
            MainlandShareDestination.WECHAT -> {
                launchPackageShare(
                    activity = activity,
                    payload = payload,
                    packageName = WECHAT_PACKAGE,
                    packageMissingMessage = activity.getString(R.string.group_share_wechat_not_installed),
                    packageOpenFailedMessage = activity.getString(R.string.group_share_wechat_open_failed)
                )
            }

            MainlandShareDestination.DINGTALK -> {
                launchPackageShare(
                    activity = activity,
                    payload = payload,
                    packageName = DINGTALK_PACKAGE,
                    packageMissingMessage = activity.getString(R.string.group_share_dingtalk_not_installed),
                    packageOpenFailedMessage = activity.getString(R.string.group_share_dingtalk_open_failed)
                )
            }

            MainlandShareDestination.MORE -> presentSystemShare(activity, payload)
        }
    }

    private fun launchPackageShare(
        activity: Activity,
        payload: GroupSharePayload,
        packageName: String,
        packageMissingMessage: String,
        packageOpenFailedMessage: String
    ) {
        val shareIntent = buildShareIntent(payload).apply {
            `package` = packageName
        }

        if (shareIntent.resolveActivity(activity.packageManager) == null) {
            Toast.makeText(activity, packageMissingMessage, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            activity.startActivity(shareIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, packageOpenFailedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildShareIntent(payload: GroupSharePayload): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, payload.subject)
            putExtra(Intent.EXTRA_TEXT, payload.messageWithLink)
        }
    }
}
