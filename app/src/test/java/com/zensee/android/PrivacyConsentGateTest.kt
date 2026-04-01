package com.zensee.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyConsentGateTest {

    @Test
    fun `shows prompt when consent has not been accepted`() {
        val store = FakePrivacyConsentStore(hasAcceptedPrivacyConsent = false)

        val gate = PrivacyConsentGate(store)

        assertTrue(gate.shouldPresentPrompt)
    }

    @Test
    fun `accept persists consent and hides prompt`() {
        val store = FakePrivacyConsentStore(hasAcceptedPrivacyConsent = false)
        val gate = PrivacyConsentGate(store)

        gate.accept()

        assertTrue(store.hasAcceptedPrivacyConsent)
        assertFalse(gate.shouldPresentPrompt)
    }

    @Test
    fun `does not show prompt after consent was already accepted`() {
        val store = FakePrivacyConsentStore(hasAcceptedPrivacyConsent = true)

        val gate = PrivacyConsentGate(store)

        assertFalse(gate.shouldPresentPrompt)
    }
}

private class FakePrivacyConsentStore(
    override var hasAcceptedPrivacyConsent: Boolean
) : PrivacyConsentStore
