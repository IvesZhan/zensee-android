package com.zensee.android

import org.junit.Assert.assertEquals
import org.junit.Test

class LegalDocumentDestinationTest {

    @Test
    fun `builds payload for terms document`() {
        val payload = LegalDocumentDestination.payloadFor(LegalDocumentType.TERMS) { resId ->
            when (resId) {
                R.string.terms_of_service -> "用户协议"
                R.string.terms_of_service_url -> "https://example.com/terms"
                else -> error("Unexpected resId: $resId")
            }
        }

        assertEquals("用户协议", payload.title)
        assertEquals("https://example.com/terms", payload.url)
    }

    @Test
    fun `builds payload for privacy document`() {
        val payload = LegalDocumentDestination.payloadFor(LegalDocumentType.PRIVACY_POLICY) { resId ->
            when (resId) {
                R.string.privacy_policy -> "隐私政策"
                R.string.privacy_policy_url -> "https://example.com/privacy"
                else -> error("Unexpected resId: $resId")
            }
        }

        assertEquals("隐私政策", payload.title)
        assertEquals("https://example.com/privacy", payload.url)
    }
}
