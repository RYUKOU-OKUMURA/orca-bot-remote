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
            save(bot.name, old.copy(readAt = maxOf(old.readAt, bot.lastActivityAt ?: 0)))
        }
        homeInitialized = true
        finishHomeBaseline()
    }

    fun observeChats(bot: String, chats: List<Chat>) {
        val old = meta(bot)
        val activity = chats.associate { it.id to (it.lastActivityAt ?: 0) }
        val baseline = if (old.initialized) old.read else old.read + activity.mapValues { (id, at) ->
            maxOf(at, old.read[id] ?: 0)
        }
        save(bot, old.copy(initialized = true, read = baseline, activity = activity,
            readAt = if (old.initialized) old.readAt else maxOf(old.readAt, activity.values.maxOrNull() ?: 0)))
    }

    fun markRead(bot: String, id: String, at: Long = System.currentTimeMillis()) {
        val old = meta(bot)
        val time = maxOf(at, old.activity[id] ?: 0, old.read[id] ?: 0)
        save(bot, old.copy(readAt = maxOf(old.readAt, time), read = old.read + (id to time)))
    }

    fun unread(bot: String, chat: Chat) = (chat.lastActivityAt ?: 0) > (meta(bot).read[chat.id] ?: 0)
    fun unread(bot: Bot): Boolean {
        val m = meta(bot.name)
        return (bot.lastActivityAt ?: 0) > m.readAt || m.activity.any { (id, at) -> at > (m.read[id] ?: 0) }
    }

    fun pinned(bot: String, id: String) = id in meta(bot).pins
    fun togglePin(bot: String, id: String) {
        val old = meta(bot)
        save(bot, old.copy(pins = if (id in old.pins) old.pins - id else old.pins + id))
    }

    fun sorted(bot: String, chats: List<Chat>) = chats.sortedWith(
        compareByDescending<Chat> { pinned(bot, it.id) }.thenByDescending { it.lastActivityAt }
    )
}

internal fun Context.chatMetaStore(server: String): ChatMetaStore {
    val prefs = getSharedPreferences("chat-meta-" + server.encodeUtf8().sha256().hex(), Context.MODE_PRIVATE)
    val initial = prefs.all.filterKeys { it.startsWith("bot/") }.mapKeys { it.key.removePrefix("bot/") }
        .mapValues { apiJson.decodeFromString<BotMeta>(it.value as String) }
    return ChatMetaStore(initial, prefs.getBoolean("initialized", false), {
        prefs.edit().putBoolean("initialized", true).apply()
    }) { bot, value ->
        prefs.edit().putString("bot/$bot", apiJson.encodeToString(value)).apply()
    }
}
