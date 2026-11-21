package dev.okumura.orcaremote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        val sender = SendModel<String>(state, chat, showPendingMessage = true) { error("unexpected message") }
        sender.draft = "/compact"
        sender.submit(sender.draft) { "ok" }
        assertNull(sender.pendingMessage)
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
    fun pendingStatesDistinguishSendingFailedUncertainAndSent() {
        assertEquals(PendingState.SENDING, pendingState(true, false, true))
        assertEquals(PendingState.FAILED, pendingState(false, true, false))
        assertEquals(PendingState.UNCERTAIN, pendingState(false, true, true))
        assertEquals(PendingState.SENT, pendingState(false, false, false))
    }

    @Test
    fun sentSnapshotSurvivesFailedReloadAndClearsOnTheNextSuccess() = runBlocking {
        val sender = SendModel(state, chat, showPendingMessage = true) { "ok" }
        val snapshot = PendingMessage("確認", listOf(file.name))
        sender.submit(sender.draft)
        assertTrue(sender.sending)
        assertEquals(snapshot, sender.pendingMessage)
        main.scheduler.runCurrent()
        assertEquals("ok", sender.result)
        assertEquals("", sender.draft)
        assertNull(sender.pendingMessage)
        sender.resultShown()
        assertNull(sender.result)
        assertEquals(listOf(snapshot), sender.sentMessages)

        var fail = true
        val history = ScreenModel {
            sender.loadHistory {
                if (fail) throw ApiFailure(ApiError.CONNECTION)
                "new history"
            }
        }
        history.load()
        assertEquals(ApiError.CONNECTION, history.error)
        assertEquals(listOf(snapshot), sender.sentMessages)
        fail = false
        history.load()
        assertEquals("new history", history.data)
        assertTrue(sender.sentMessages.isEmpty())
    }

    @Test
    fun historyReadDoesNotClearMessagesSentAfterItStarts() = runBlocking {
        val sender = SendModel(state, chat, showPendingMessage = true) { "ok" }
        sender.submit("先の送信")
        main.scheduler.runCurrent()
        sender.resultShown()

        val response = CompletableDeferred<Unit>()
        val read = launch(start = CoroutineStart.UNDISPATCHED) { sender.loadHistory { response.await() } }
        sender.submit("次の送信")
        main.scheduler.runCurrent()
        sender.resultShown()
        response.complete(Unit)
        read.join()
        assertEquals(listOf(PendingMessage("次の送信", emptyList())), sender.sentMessages)
    }

    @Test
    fun interruptedConversationRestoresTheSentSnapshotEvenIfDraftChanges() {
        val sender = SendModel<Unit>(state, chat, showPendingMessage = true) { throw ApiFailure(ApiError.UNKNOWN_RESULT) }
        sender.submit(sender.draft)
        main.scheduler.runCurrent()
        sender.draft = "修正"
        val restored = SendModel<Unit>(state, chat, showPendingMessage = true) { throw ApiFailure(ApiError.CONNECTION) }
        assertTrue(restored.deliveryUncertain)
        assertEquals(PendingMessage("確認", listOf(file.name)), restored.pendingMessage)
        restored.submit(restored.draft, resendConfirmed = true)
        main.scheduler.runCurrent()
        assertEquals("修正", restored.draft)
        assertEquals(PendingMessage("確認", listOf(file.name)), restored.pendingMessage)
        restored.markAsSent()
        assertNull(restored.pendingMessage)
    }

    @Test
    fun ordinaryFailureKeepsDraftAndTextCommandSkipsPendingBubble() {
        val sender = SendModel<Unit>(state, chat, showPendingMessage = true) { throw ApiFailure(ApiError.WRITE_FAILED) }
        sender.submit(sender.draft)
        main.scheduler.runCurrent()
        assertEquals(ApiError.WRITE_FAILED, sender.error)
        assertEquals("確認", sender.draft)
        assertEquals(PendingMessage("確認", listOf(file.name)), sender.pendingMessage)
        sender.submit("/help", showPending = false)
        assertNull(sender.pendingMessage)
        main.scheduler.runCurrent()
    }

    @Test
    fun uncertainDeliveryRequiresExplicitResendConfirmation() {
        assertTrue(canSubmit(deliveryUncertain = false, resendConfirmed = false))
        assertFalse(canSubmit(deliveryUncertain = true, resendConfirmed = false))
        assertTrue(canSubmit(deliveryUncertain = true, resendConfirmed = true))
        assertFalse(canSubmit(deliveryUncertain = false, resendConfirmed = true))
    }
}
