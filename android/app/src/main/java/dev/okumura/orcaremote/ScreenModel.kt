package dev.okumura.orcaremote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable

internal class ScreenModel<T>(private val fetch: suspend () -> T) : ViewModel() {
    private val loadMutex = Mutex()
    var data by mutableStateOf<T?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<ApiError?>(null)
        private set

    fun refresh(afterCurrent: Boolean = false) {
        viewModelScope.launch { load(afterCurrent) }
    }

    suspend fun load(afterCurrent: Boolean = false) {
        if (afterCurrent) loadMutex.lock() else if (!loadMutex.tryLock()) return
        loading = true
        try {
            data = fetch()
            error = null
        } catch (failure: ApiFailure) {
            error = failure.error
        } finally {
            loading = false
            loadMutex.unlock()
        }
    }
}

@Serializable
internal data class PendingMessage(val text: String, val attachments: List<String>)

internal enum class PendingState { SENDING, FAILED, UNCERTAIN }

internal fun pendingState(sending: Boolean, error: Boolean, deliveryUncertain: Boolean, awaitingReload: Boolean): PendingState? = when {
    sending || awaitingReload -> PendingState.SENDING
    deliveryUncertain -> PendingState.UNCERTAIN
    error -> PendingState.FAILED
    else -> null
}

internal class SendModel<T>(
    private val savedState: SavedStateHandle,
    val chat: ChatDraft? = null,
    private val onFailure: suspend (ApiError) -> Unit = {},
    private val showPendingMessage: Boolean = false,
    private val send: suspend (String) -> T,
) : ViewModel() {
    var draftField by mutableStateOf(savedState.get<String>("draft").orEmpty().let { TextFieldValue(it, TextRange(it.length)) })
        private set
    var draft: String
        get() = draftField.text
        set(value) { edit(TextFieldValue(value, TextRange(value.length))) }

    fun edit(value: TextFieldValue) {
        if (draftField.text != value.text) savedState["draft"] = value.text
        draftField = value
    }

    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<ApiError?>(null)
        private set
    var result by mutableStateOf<T?>(null)
        private set
    var deliveryUncertain by mutableStateOf(savedState.get<Boolean>("deliveryUncertain") == true)
        private set

    var pendingMessage by mutableStateOf(
        savedState.get<String>("pendingMessage")?.takeIf { showPendingMessage && deliveryUncertain }
            ?.let { apiJson.decodeFromString<PendingMessage>(it) }
    )
        private set
    var pendingAttachmentError by mutableStateOf<String?>(null)
        private set

    val lastCommand: String? get() = savedState["lastCommand"]

    fun canStartCommand(): Boolean {
        if (!deliveryUncertain || lastCommand != null) return true
        chat?.notice = "前の送信を確認してください。「送信済みとして閉じる」か、再送確認で「もう一度送る」を選んでからコマンドを使えます"
        return false
    }

    fun submit(
        text: String,
        resendConfirmed: Boolean = false,
        showPending: Boolean = true,
        command: (suspend (String) -> T)? = null,
    ) {
        if (sending || result != null || chat?.reading == true || (text.isBlank() && chat?.attachments.isNullOrEmpty())) return
        if (command != null && !canStartCommand()) return
        if (!canSubmit(deliveryUncertain, resendConfirmed)) return
        savedState["lastCommand"] = text.takeIf { command != null }
        val previousPending = pendingMessage
        pendingMessage = if (showPendingMessage && showPending && command == null) {
            PendingMessage(text, chat?.attachments.orEmpty().map { it.name })
        } else null
        savePendingMessage()
        pendingAttachmentError = null
        sending = true
        chat?.notice = null
        error = null
        // A restored screen cannot know whether an interrupted POST reached the server.
        savedState["deliveryUncertain"] = true
        viewModelScope.launch {
            try {
                result = (command ?: send)(text)
                clearDraft()
            } catch (failure: AttachmentProblem) {
                chat?.notice = failure.message
                if (pendingMessage != null) pendingAttachmentError = failure.message
                if (deliveryUncertain) restorePendingMessage(previousPending)
                savedState["deliveryUncertain"] = deliveryUncertain
            } catch (failure: ApiFailure) {
                if (failure.error == ApiError.UNKNOWN_RESULT) {
                    deliveryUncertain = true
                } else {
                    if (deliveryUncertain) restorePendingMessage(previousPending)
                    error = failure.error
                }
                savedState["deliveryUncertain"] = deliveryUncertain
                onFailure(failure.error)
            } finally {
                sending = false
            }
        }
    }

    private fun savePendingMessage() {
        savedState["pendingMessage"] = pendingMessage?.let { apiJson.encodeToString(it) }
    }

    private fun restorePendingMessage(previous: PendingMessage?) {
        pendingMessage = previous
        savePendingMessage()
    }

    fun markAsSent() {
        if (!sending && deliveryUncertain) {
            pendingMessage = null
            clearDraft()
        }
    }

    private fun clearDraft() {
        if (lastCommand == null) chat?.clear()
        savedState.remove<String>("lastCommand")
        draft = ""
        error = null
        deliveryUncertain = false
        savedState["deliveryUncertain"] = false
        savedState.remove<String>("pendingMessage")
    }

    fun resultShown() {
        pendingMessage = null
        result = null
    }
}

internal fun canSubmit(deliveryUncertain: Boolean, resendConfirmed: Boolean): Boolean =
    deliveryUncertain == resendConfirmed

internal fun shouldPoll(state: String?, error: ApiError?): Boolean = state == "working" && when (error) {
    null, ApiError.ORCA, ApiError.TIMEOUT, ApiError.CONNECTION -> true
    else -> false
}

internal fun relativeTime(
    timestamp: Long?,
    now: Long = System.currentTimeMillis(),
    compact: Boolean = false,
): String? {
    if (timestamp == null) return null
    val minutes = ((now - timestamp) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "たった今"
        minutes < 60 -> if (compact) "${minutes}m" else "${minutes}分前"
        minutes < 1_440 -> if (compact) "${minutes / 60}h" else "${minutes / 60}時間前"
        else -> if (compact) "${minutes / 1_440}d" else "${minutes / 1_440}日前"
    }
}
