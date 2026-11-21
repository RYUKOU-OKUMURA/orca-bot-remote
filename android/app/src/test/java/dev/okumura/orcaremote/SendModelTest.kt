package dev.okumura.orcaremote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendModelTest {
    @Test
    fun commandCompletionMovesCursorAfterArgumentSeparator() {
        val sender = SendModel(SavedStateHandle()) { _: String -> Unit }
        sender.edit(TextFieldValue("/co", TextRange(3)))
        sender.draft = "/compact "
        assertEquals(TextRange(9), sender.draftField.selection)
    }

    @Test
    fun uncertainDeliveryRequiresExplicitResendConfirmation() {
        assertTrue(canSubmit(deliveryUncertain = false, resendConfirmed = false))
        assertFalse(canSubmit(deliveryUncertain = true, resendConfirmed = false))
        assertTrue(canSubmit(deliveryUncertain = true, resendConfirmed = true))
        assertFalse(canSubmit(deliveryUncertain = false, resendConfirmed = true))
    }
}
