package dev.okumura.orcaremote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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

internal class SendModel<T>(
    private val savedState: SavedStateHandle,
    private val onFailure: suspend (ApiError) -> Unit = {},
    private val send: suspend (String) -> T,
) : ViewModel() {
    private var draftText by mutableStateOf(savedState.get<String>("draft").orEmpty())
    var draft: String
        get() = draftText
        set(value) {
            draftText = value
            savedState["draft"] = value
        }
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf(
        ApiError.UNKNOWN_RESULT.takeIf { savedState.get<Boolean>("deliveryUncertain") == true },
    )
        private set
    var result by mutableStateOf<T?>(null)
        private set
    private var chatListRefreshed by mutableStateOf(savedState.get<Boolean>("chatListRefreshed") == true)
    val needsChatListRefresh: Boolean
        get() = error == ApiError.UNKNOWN_RESULT && !chatListRefreshed

    fun markChatListRefreshed() {
        chatListRefreshed = true
        savedState["chatListRefreshed"] = true
    }

    fun submit(text: String) {
        if (sending || result != null) return
        if (text.isBlank()) return
        sending = true
        error = null
        // A restored screen cannot know whether an interrupted POST reached the server.
        savedState["deliveryUncertain"] = true
        chatListRefreshed = false
        savedState["chatListRefreshed"] = false
        viewModelScope.launch {
            try {
                result = send(text)
                draft = ""
                savedState["deliveryUncertain"] = false
            } catch (failure: ApiFailure) {
                error = failure.error
                savedState["deliveryUncertain"] = failure.error == ApiError.UNKNOWN_RESULT
                onFailure(failure.error)
            } finally {
                sending = false
            }
        }
    }

    fun resultShown() {
        result = null
    }
}

internal fun shouldPoll(state: String?, error: ApiError?): Boolean = state == "working" && when (error) {
    null, ApiError.ORCA, ApiError.TIMEOUT, ApiError.CONNECTION -> true
    else -> false
}

internal fun relativeTime(timestamp: Long?, now: Long = System.currentTimeMillis()): String? {
    if (timestamp == null) return null
    val minutes = ((now - timestamp) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "たった今"
        minutes < 60 -> "${minutes}分前"
        minutes < 1_440 -> "${minutes / 60}時間前"
        else -> "${minutes / 1_440}日前"
    }
}
