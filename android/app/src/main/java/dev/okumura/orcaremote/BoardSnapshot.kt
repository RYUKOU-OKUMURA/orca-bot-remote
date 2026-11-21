package dev.okumura.orcaremote

import java.net.URLEncoder
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class BoardSnapshot(
    val updatedAt: Long? = null,
    val bots: List<Bot> = emptyList(),
    val sessions: List<BoardSession> = emptyList(),
    val tasks: List<PendingItem> = emptyList(),
)

@Serializable
internal data class BoardSession(
    val bot: String,
    val title: String,
    val kind: String = "chat",
    val tool: String? = null,
    val chatId: String? = null,
    val avatar: String? = null,
)

internal enum class BoardStatus(val label: String, val background: Int) {
    Active("進行中", R.drawable.board_active),
    NeedsYou("判断待ち", R.drawable.board_pending),
    Working("作業中", R.drawable.board_working),
    Planned("予定", R.drawable.board_planned),
}

internal data class BoardRow(val title: String, val subtitle: String, val icon: Int, val status: BoardStatus, val link: String)

internal fun boardLink(bot: String, chatId: String? = null, item: Long? = null): String {
    fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    val route = when {
        item != null -> "item/${segment(bot)}/$item"
        !chatId.isNullOrBlank() -> "chat/${segment(bot)}/${segment(chatId)}"
        else -> "chats/${segment(bot)}"
    }
    return "$NotificationScheme://$route"
}

internal fun BoardSnapshot.rows(): List<BoardRow> {
    val avatars = bots.associate { it.name to it.avatar }
    fun icon(bot: String, avatar: String?) = boardIcon(avatar ?: avatars[bot])
    val active = sessions.map {
        val detail = it.tool ?: if (it.kind == "worker") "ワーカー" else "チャット"
        BoardRow(it.title, "${it.bot} · $detail", icon(it.bot, it.avatar), BoardStatus.Active, boardLink(it.bot, it.chatId))
    }
    val taskRows = tasks.mapNotNull {
        val status = when (it.status) {
            "needs_you" -> BoardStatus.NeedsYou
            "in_progress" -> BoardStatus.Working
            "planned" -> BoardStatus.Planned
            else -> return@mapNotNull null
        }
        val subtitle = listOfNotNull(it.bot, relativeTime(it.lastActivityAt)).joinToString(" · ")
        BoardRow(it.title, subtitle, icon(it.bot, it.avatar), status,
            boardLink(it.bot, it.chatId, it.number.takeIf { number -> status == BoardStatus.NeedsYou && number > 0 }))
    }
    return active + taskRows.sortedBy { it.status }
}

internal fun boardIcon(avatar: String?): Int = avatarDrawable(avatar) ?: R.drawable.mascot_a1

private const val BoardKeyPrefix = "board:"

internal fun Connection.boardCacheKey() = BoardKeyPrefix + ("${url}\n$token").encodeUtf8().sha256().hex()

internal fun staleBoardKeys(keys: Set<String>, keep: String?) = keys.filter { it.startsWith(BoardKeyPrefix) && it != keep }

internal fun cachedBoard(json: String?): BoardSnapshot? =
    json?.let { runCatching { apiJson.decodeFromString<BoardSnapshot>(it) }.getOrNull() }
