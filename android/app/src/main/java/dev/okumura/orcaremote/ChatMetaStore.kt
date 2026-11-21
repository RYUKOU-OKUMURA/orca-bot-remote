package dev.okumura.orcaremote

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class BotMeta(
    val readAt: Long = 0,
    val initialized: Boolean = false,
    val read: Map<String, Long> = emptyMap(),
    val activity: Map<String, Long> = emptyMap(),
    val pins: Set<String> = emptySet(),
)

internal class ChatMetaStore(
    initial: Map<String, BotMeta>,
    homeInitialized: Boolean = false,
    private val finishHomeBaseline: () -> Unit = {},
    private val persist: (String, BotMeta) -> Unit,
) {
    private var homeInitialized = homeInitialized
    private val bots = mutableStateMapOf<String, BotMeta>().apply { putAll(initial) }
    private fun meta(bot: String) = bots[bot] ?: BotMeta()
    private fun save(bot: String, value: BotMeta) {
        if (bots[bot] != value) {
            bots[bot] = value
            persist(bot, value)
        }
    }

    fun observeBots(list: List<Bot>) {
        if (homeInitialized) return
        list.forEach { bot ->
            val old = meta(bot.name)
            if (!old.initialized) save(bot.name, old.copy(readAt = maxOf(old.readAt, bot.lastActivityAt ?: 0)))
        }
        homeInitialized = true
        finishHomeBaseline()
    }

    fun observeChats(bot: String, chats: List<Chat>) {
        val old = meta(bot)
        val activity = chats.associate { it.id to (it.lastActivityAt ?: 0) }
        val firstListBeforeHome = !old.initialized && !homeInitialized && old.readAt == 0L
        val baseline = if (firstListBeforeHome) activity.values.maxOrNull() ?: 0 else old.readAt
        val read = if (old.initialized) old.read.filterKeys(activity::containsKey) else activity.mapValues { (id, at) ->
            maxOf(old.read[id] ?: 0, minOf(at, baseline))
        }
        save(bot, old.copy(initialized = true, readAt = baseline, read = read, activity = activity))
    }

    fun markRead(bot: String, id: String, at: Long = System.currentTimeMillis()) {
        val old = meta(bot)
        val time = maxOf(at, old.activity[id] ?: 0, old.read[id] ?: 0)
        save(bot, old.copy(read = old.read + (id to time)))
    }

    fun markNotificationRead(bot: String, id: String, activityAt: Long) {
        val old = meta(bot)
        save(bot, old.copy(
            read = old.read + (id to maxOf(old.read[id] ?: 0, activityAt)),
            activity = old.activity + (id to maxOf(old.activity[id] ?: 0, activityAt)),
        ))
    }

    fun unread(bot: String, chat: Chat) = (chat.lastActivityAt ?: 0) > (meta(bot).read[chat.id] ?: 0)
    fun unread(bot: Bot): Boolean {
        val m = meta(bot.name)
        val knownActivity = maxOf(m.readAt, m.activity.values.maxOrNull() ?: 0)
        return (bot.lastActivityAt ?: 0) > knownActivity || m.activity.any { (id, at) -> at > (m.read[id] ?: 0) }
    }

    fun pinned(bot: String, id: String) = id in meta(bot).pins
    fun togglePin(bot: String, id: String) {
        val old = meta(bot)
        save(bot, old.copy(pins = if (id in old.pins) old.pins - id else old.pins + id))
    }

    fun sorted(bot: String, chats: List<Chat>) = chats.sortedWith(
        compareByDescending<Chat> { pinned(bot, it.id) }
            .thenByDescending { it.home && !pinned(bot, it.id) }
            .thenByDescending { it.lastActivityAt }
    )
}

internal fun restoreBotMeta(value: Any?): BotMeta =
    (value as? String)?.let { runCatching { apiJson.decodeFromString<BotMeta>(it) }.getOrNull() } ?: BotMeta()

// UI and notification replies mutate the same instance on the main thread.
private var currentStore: Pair<String, ChatMetaStore>? = null

@androidx.annotation.MainThread
internal fun Context.chatMetaStore(server: String): ChatMetaStore {
    currentStore?.let { if (it.first == server) return it.second }
    val prefs = getSharedPreferences("chat-meta-" + server.encodeUtf8().sha256().hex(), Context.MODE_PRIVATE)
    val values = prefs.all
    val initial = values.filterKeys { it.startsWith("bot/") }.mapKeys { it.key.removePrefix("bot/") }
        .mapValues { restoreBotMeta(it.value) }
    return ChatMetaStore(initial, values["initialized"] == true, {
        prefs.edit().putBoolean("initialized", true).apply()
    }) { bot, value ->
        prefs.edit().putString("bot/$bot", apiJson.encodeToString(value)).apply()
    }.also { currentStore = server to it }
}
