package dev.okumura.orcaremote

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class BoardSnapshot(
    val updatedAt: Long? = null,
    val bots: List<Bot> = emptyList(),
    // null: the server could not read tasks.json.
    val memoTasks: List<MemoTask>? = emptyList(),
)

internal fun boardLink(bot: String): String {
    val segment = URLEncoder.encode(bot, "UTF-8").replace("+", "%20")
    return "$NotificationScheme://chats/$segment"
}

internal const val TasksLink = "orcaremote://tasks"
private const val TaskDonePrefix = "orcaremote://task-done/"

internal fun taskDoneLink(id: String) = TaskDonePrefix + URLEncoder.encode(id, "UTF-8").replace("+", "%20")

internal fun taskDoneId(link: String?): String? = link?.takeIf { it.startsWith(TaskDonePrefix) }
    ?.let { runCatching { URLDecoder.decode(it.removePrefix(TaskDonePrefix).replace("+", "%2B"), "UTF-8") }.getOrNull() }
    ?.takeIf { it.isNotEmpty() }

internal data class TaskRow(val id: String, val title: String, val detail: String, val urgent: Boolean)

internal fun BoardSnapshot.taskRows(today: LocalDate): List<TaskRow> = memoTasks.orEmpty().filter { !it.done }.map { task ->
    val due = dueLabel(task.due, today)
    TaskRow(task.id, task.title, listOfNotNull(due?.first, task.note?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim())
        .joinToString(" · "), due?.second == true)
}

internal val BoardSnapshot.pendingCount: Int get() = bots.sumOf { it.needsYou }

internal fun boardIcon(avatar: String?): Int = avatarDrawable(avatar) ?: R.drawable.mascot_a1

private const val BoardKeyPrefix = "board:"

internal fun Connection.boardCacheKey() = BoardKeyPrefix + ("${url}\n$token").encodeUtf8().sha256().hex()

internal fun staleBoardKeys(keys: Set<String>, keep: String?) = keys.filter { it.startsWith(BoardKeyPrefix) && it != keep }

internal fun cachedBoard(json: String?): BoardSnapshot? =
    json?.let { runCatching { apiJson.decodeFromString<BoardSnapshot>(it) }.getOrNull() }
