package dev.okumura.orcaremote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendModelTest {
    private val main = StandardTestDispatcher()
    private val file = LocalAttachment("content://test/file", "file", null, 1)
    private val skill = SkillRef("review", "/skills/review")
    private val state = SavedStateHandle(mapOf(
        "draft" to "確認", "attachments" to apiJson.encodeToString(listOf(file)),
    ))
    private val chat = ChatDraft(state, AttachmentLimits.Conversation).apply { addSkill(skill) }

    @Before fun setMain() = Dispatchers.setMain(main)
    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun uncertainMessageBlocksEvenConfirmedCommandAndStillRequiresMessageResendConfirmation() {
        var messages = 0
        var commands = 0
        val sender = SendModel(state, chat) {
            if (++messages == 1) throw ApiFailure(ApiError.UNKNOWN_RESULT)
        }
        sender.submit(sender.draft)
        main.scheduler.runCurrent()
        assertTrue(sender.deliveryUncertain)
        assertFalse(sender.canStartCommand())
        sender.submit("/clear", resendConfirmed = true) { commands++ }
        sender.submit(sender.draft)
        main.scheduler.runCurrent()
        assertEquals(0, commands)
        assertEquals(1, messages)
        assertTrue(chat.notice.orEmpty().contains("前の送信を確認"))
        assertTrue(sender.deliveryUncertain)
        assertEquals(true, state.get<Boolean>("deliveryUncertain"))
        assertNull(sender.lastCommand)
        assertEquals("確認", sender.draft)
        assertEquals(listOf(file), chat.attachments)
        assertEquals(listOf(skill), chat.skills)

        sender.submit(sender.draft, resendConfirmed = true)
        main.scheduler.runCurrent()
        assertEquals(2, messages)
        assertFalse(sender.deliveryUncertain)
        assertTrue(chat.attachments.isEmpty())
        assertTrue(chat.skills.isEmpty())
    }

    @Test
    fun localAttachmentFailureRestoresPreviousDeliveryState() {
        for (uncertain in listOf(false, true)) {
            state["deliveryUncertain"] = uncertain
            val sender = SendModel<Unit>(state, chat) { throw AttachmentProblem("読み取れません") }
            sender.submit(sender.draft, resendConfirmed = uncertain)
            assertEquals(true, state.get<Boolean>("deliveryUncertain"))
            main.scheduler.runCurrent()
            assertEquals(uncertain, sender.deliveryUncertain)
            assertEquals(uncertain, state.get<Boolean>("deliveryUncertain"))
            assertFalse(sender.sending)
            assertEquals("読み取れません", chat.notice)
            assertEquals(listOf(file), chat.attachments)
            assertEquals("確認", sender.draft)
        }
    }

    @Test
    fun commandSuccessKeepsUnsentAttachmentsAndSkills() {
        val sender = SendModel<String>(state, chat) { error("unexpected message") }
        sender.draft = "/compact"
        sender.submit(sender.draft) { "ok" }
        main.scheduler.runCurrent()
        assertEquals("ok", sender.result)
        assertFalse(sender.deliveryUncertain)
        assertNull(sender.lastCommand)
        assertEquals("", sender.draft)
        assertEquals(listOf(file), chat.attachments)
        assertEquals(listOf(skill), chat.skills)
    }

    @Test
    fun acknowledgingUncertainDeliveryClearsOnlyWhatWasSent() {
        for (wasCommand in listOf(true, false)) {
            state["draft"] = if (wasCommand) "/compact" else "確認"
            state["lastCommand"] = state.get<String>("draft").takeIf { wasCommand }
            state["deliveryUncertain"] = true
            val sender = SendModel<Unit>(state, chat) { error("acknowledgment must not send") }
            sender.markAsSent()
            assertFalse(sender.deliveryUncertain)
            assertEquals(false, state.get<Boolean>("deliveryUncertain"))
            assertNull(sender.lastCommand)
            assertEquals("", sender.draft)
            assertTrue(sender.canStartCommand())
            assertEquals(if (wasCommand) listOf(file) else emptyList(), chat.attachments)
            assertEquals(if (wasCommand) listOf(skill) else emptyList(), chat.skills)
        }
    }

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
