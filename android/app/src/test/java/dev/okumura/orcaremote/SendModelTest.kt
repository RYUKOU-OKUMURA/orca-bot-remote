package dev.okumura.orcaremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendModelTest {
    @Test
    fun uncertainDeliveryRequiresExplicitResendConfirmation() {
        assertTrue(canSubmit(deliveryUncertain = false, resendConfirmed = false))
        assertFalse(canSubmit(deliveryUncertain = true, resendConfirmed = false))
        assertTrue(canSubmit(deliveryUncertain = true, resendConfirmed = true))
        assertFalse(canSubmit(deliveryUncertain = false, resendConfirmed = true))
    }
}
