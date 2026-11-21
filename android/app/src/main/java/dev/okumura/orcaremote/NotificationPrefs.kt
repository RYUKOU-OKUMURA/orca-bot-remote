package dev.okumura.orcaremote

import android.content.SharedPreferences
import kotlinx.serialization.Serializable

internal const val ItemsChannel = "pending-items"
internal const val SummaryChannel = "summaries"
internal const val RepliesChannel = "chat-replies"
internal const val QuietChannel = "quiet-updates"
internal const val RealtimeChannel = "realtime-status"
internal const val ReplyHistory = "replyHistory"

@Serializable
internal data class NotificationPrefs(
    val replies: Boolean = true,
    val quietHours: Boolean = true,
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val realtime: Boolean = true,
) {
    fun quietAt(hour: Int, minute: Int) = quietHours && isQuietHours(hour, minute, startMinute, endMinute)
}

internal fun SharedPreferences.notificationPrefs() =
    getString("notificationDetails", null)?.let { apiJson.decodeFromString<NotificationPrefs>(it) } ?: NotificationPrefs()

internal fun isQuietHours(hour: Int, minute: Int, startMinute: Int, endMinute: Int): Boolean {
    val now = hour * 60 + minute
    return if (startMinute > endMinute) now >= startMinute || now < endMinute else now in startMinute until endMinute
}

internal data class ChatTarget(val bot: String, val chatId: String?)
internal object ActiveChatTracker {
    @Volatile var current: ChatTarget? = null
}

internal fun notificationChannel(channel: String, quiet: Boolean, replies: Boolean = true,
                                 target: ChatTarget? = null, active: ChatTarget? = null): String? = when (channel) {
    ItemsChannel, SummaryChannel -> if (quiet) QuietChannel else channel
    RepliesChannel -> if (quiet || !replies || (active != null && target?.bot == active.bot &&
        (target.chatId == null || target == active))) null else RepliesChannel
    else -> error("Invalid delivery channel")
}

@Serializable
internal data class ReplyStamp(val at: Long, val said: String?)

internal data class ReplyDecision(val history: Map<String, ReplyStamp>, val newReplies: List<Bot>)

internal fun decideReplies(previous: Map<String, ReplyStamp>, bots: List<Bot>): ReplyDecision {
    val history = previous.toMutableMap()
    val replies = bots.filter { bot ->
        val at = bot.lastActivityAt ?: 0L
        val old = previous[bot.id]
        if (old == null || at >= old.at) history[bot.id] = ReplyStamp(at, bot.lastSaid ?: old?.said)
        old != null && at > old.at && !bot.lastSaid.isNullOrBlank() && old.said != bot.lastSaid
    }
    return ReplyDecision(history, replies)
}
