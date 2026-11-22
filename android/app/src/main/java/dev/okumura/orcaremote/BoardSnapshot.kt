package dev.okumura.orcaremote

import java.net.URLDecoder
import java.net.URLEncoder
import android.content.SharedPreferences
import java.time.LocalDate
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class BoardSnapshot(
    val updatedAt: Long? = null,
    val bots: List<Bot> = emptyList(),
    val sessions: List<BoardSession> = emptyList(),
    val tasks: List<BoardTask> = emptyList(),
    // null: the server could not read tasks.json.
    val memoTasks: List<MemoTask>? = emptyList(),
)

/** A running worker or a chat that is answering now. */
@Serializable
internal data class BoardSession(
    val bot: String, val avatar: String? = null, val kind: String = "chat", val title: String = "", val chatId: String? = null,
)

/** An open item of a bot. [status] is needs_you, in_progress or planned. */
@Serializable
internal data class BoardTask(
    val bot: String, val avatar: String? = null, val number: Long = 0, val title: String = "", val status: String = "",
)

private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun boardLink(bot: String) = "$NotificationScheme://chats/${segment(bot)}"

internal fun sessionLink(session: BoardSession) = session.chatId?.takeIf { it.isNotEmpty() }
    ?.let { "$NotificationScheme://chat/${segment(session.bot)}/${segment(it)}" } ?: boardLink(session.bot)

internal fun boardTaskLink(task: BoardTask) =
    if (task.number > 0) "$NotificationScheme://item/${segment(task.bot)}/${task.number}" else boardLink(task.bot)

internal enum class BoardTab { TASKS, PROGRESS }

private const val BoardTabKey = "boardTab"

internal fun boardTab(name: String?) = BoardTab.entries.firstOrNull { it.name == name } ?: BoardTab.TASKS

internal fun SharedPreferences.boardTab() = boardTab(getString(BoardTabKey, null))

internal fun SharedPreferences.saveBoardTab(tab: BoardTab) = edit().putString(BoardTabKey, tab.name).apply()

internal const val TasksLink = "orcaremote://tasks"
private const val TaskDonePrefix = "orcaremote://task-done/"

internal fun taskDoneLink(id: String) = TaskDonePrefix + URLEncoder.encode(id, "UTF-8").replace("+", "%20")

internal fun taskDoneId(link: String?): String? = link?.takeIf { it.startsWith(TaskDonePrefix) }
    ?.let { runCatching { URLDecoder.decode(it.removePrefix(TaskDonePrefix).replace("+", "%2B"), "UTF-8") }.getOrNull() }
    ?.takeIf { it.isNotEmpty() }

/**
 * [bot] is the assigned bot; [avatar] is its mascot when the board knows the bot.
 * A null [id] is a progress row: no check, and a tap opens [link].
 */
internal data class TaskRow(
    val id: String?, val title: String, val detail: String, val urgent: Boolean,
    val bot: String? = null, val avatar: String? = null, val link: String = TasksLink,
)

internal fun BoardSnapshot.taskRows(today: LocalDate): List<TaskRow> = memoTasks.orEmpty().filter { !it.done }.map { task ->
    val due = dueLabel(task.due, today)
    TaskRow(task.id, task.title, listOfNotNull(task.bot, due?.first, task.note?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim())
        .joinToString(" · "), due?.second == true, task.bot, bots.firstOrNull { it.name == task.bot }?.avatar)
}

private fun BoardSession.label() = title.ifBlank { if (kind == "worker") "作業" else "チャット" }

internal fun BoardSnapshot.progressRows(): List<TaskRow> = sessions.map {
    TaskRow(null, it.label(), "${if (it.kind == "worker") "作業中" else "応答中"} · ${it.bot}", false, it.bot, it.avatar, sessionLink(it))
} + tasks.map {
    val status = when (it.status) { "needs_you" -> "判断待ち"; "in_progress" -> "進行中"; "planned" -> "予定"; else -> it.status.ifBlank { "未設定" } }
    TaskRow(null, it.title.ifBlank { "#${it.number}" }, "$status · ${it.bot}", it.status == "needs_you", it.bot, it.avatar, boardTaskLink(it))
}

internal fun BoardSnapshot.rows(tab: BoardTab, today: LocalDate) =
    if (tab == BoardTab.PROGRESS) progressRows() else taskRows(today)

/** One line for the task tab while something runs; null when nothing does. */
internal fun BoardSnapshot.workingBanner(): String? {
    val first = sessions.firstOrNull() ?: return null
    val count = if (sessions.size > 1) " ${sessions.size}件" else ""
    return "● 作業中$count · ${first.bot}: ${first.label()} ›"
}

internal val BoardSnapshot.pendingCount: Int get() = bots.sumOf { it.needsYou }

internal fun boardIcon(avatar: String?): Int = avatarDrawable(avatar) ?: R.drawable.mascot_a1

private const val BoardKeyPrefix = "board:"

internal fun Connection.boardCacheKey() = BoardKeyPrefix + ("${url}\n$token").encodeUtf8().sha256().hex()

internal fun staleBoardKeys(keys: Set<String>, keep: String?) = keys.filter { it.startsWith(BoardKeyPrefix) && it != keep }

internal fun cachedBoard(json: String?): BoardSnapshot? =
    json?.let { runCatching { apiJson.decodeFromString<BoardSnapshot>(it) }.getOrNull() }
