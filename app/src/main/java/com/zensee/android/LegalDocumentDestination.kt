package com.zensee.android

import android.content.Context
import android.content.Intent

enum class LegalDocumentType(
    val titleResId: Int,
    val urlResId: Int
) {
    TERMS(R.string.terms_of_service, R.string.terms_of_service_url),
    PRIVACY_POLICY(R.string.privacy_policy, R.string.privacy_policy_url)
}

data class LegalDocumentPayload(
    val title: String,
    val url: String
)

object LegalDocumentDestination {
    fun payloadFor(
        type: LegalDocumentType,
        resolve: (Int) -> String
    ): LegalDocumentPayload = LegalDocumentPayload(
        title = resolve(type.titleResId),
        url = resolve(type.urlResId)
    )

    fun createIntent(
        context: Context,
        type: LegalDocumentType
    ): Intent {
        val payload = payloadFor(type, context::getString)
        return LegalDocumentActivity.createIntent(context, payload)
    }
}
