package com.zensee.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.LocaleList
import android.telephony.TelephonyManager
import android.widget.Toast
import com.zensee.android.model.GroupModel
import java.util.Locale
import java.util.TimeZone

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
    private val mainlandChinaTimeZones = setOf("Asia/Shanghai", "Asia/Urumqi")

    fun isMainlandChinaRegion(context: Context): Boolean {
        val resources = context.resources
        val configuration = resources.configuration
        if (configuration.mcc == 460) {
            return true
        }

        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        val telephonyCountries = listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso
        )
            .mapNotNull { country -> country?.trim()?.takeIf { it.isNotEmpty() } }
            .map { country -> country.lowercase(Locale.ROOT) }

        if ("cn" in telephonyCountries) {
            return true
        }

        val telephonyOperators = listOf(
            telephonyManager?.networkOperator,
            telephonyManager?.simOperator
        )
            .mapNotNull { operator -> operator?.trim()?.takeIf { it.isNotEmpty() } }
        if (telephonyOperators.any { it.startsWith("460") }) {
            return true
        }

        if (TimeZone.getDefault().id in mainlandChinaTimeZones) {
            return true
        }

        val locales = linkedSetOf<Locale>()
        val configurationLocales = configuration.locales
        for (index in 0 until configurationLocales.size()) {
            configurationLocales[index]?.let(locales::add)
        }

        val systemLocales = LocaleList.getDefault()
        for (index in 0 until systemLocales.size()) {
            systemLocales[index]?.let(locales::add)
        }

        locales.add(Locale.getDefault())

        if (locales.any(::isMainlandChinaLocale)) {
            return true
        }

        // Final UX fallback: if the device exposes mainland-oriented apps and
        // the current language stack is Chinese (non-Traditional), prefer the
        // dedicated mainland share sheet instead of dropping to system share.
        return locales.any(::isChineseLocale) &&
            (isPackageInstalled(context, WECHAT_PACKAGE) || isPackageInstalled(context, DINGTALK_PACKAGE))
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

    private fun isMainlandChinaLocale(locale: Locale): Boolean {
        val country = locale.country.lowercase(Locale.ROOT)
        if (country == "cn") {
            return true
        }
        if (country in setOf("tw", "hk", "mo")) {
            return false
        }

        val language = locale.language.lowercase(Locale.ROOT)
        val languageTag = locale.toLanguageTag().lowercase(Locale.ROOT)
        return language == "zh" && !languageTag.contains("-hant")
    }

    private fun isChineseLocale(locale: Locale): Boolean {
        val language = locale.language.lowercase(Locale.ROOT)
        val languageTag = locale.toLanguageTag().lowercase(Locale.ROOT)
        return language == "zh" && !languageTag.contains("-hant")
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return context.packageManager.getLaunchIntentForPackage(packageName) != null
    }
}
