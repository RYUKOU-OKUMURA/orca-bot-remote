package dev.okumura.orcaremote

import org.junit.Assert.*
import org.junit.Test

class ChatMetaStoreTest {
    private val bot = Bot("1", "B", paused = false, needsYou = 0, lastActivityAt = 10)
    private val a = Chat("a", home = true, lastActivityAt = 10)
    private val b = Chat("b", home = false, lastActivityAt = 20)

    @Test fun firstChatListPreservesNewActivitySinceHomeBaseline() {
        val meta = ChatMetaStore(emptyMap()) { _, _ -> }
        meta.observeBots(listOf(bot))
        meta.observeChats("B", listOf(a, b))
        assertFalse(meta.unread("B", a))
        assertTrue(meta.unread("B", b))
        assertTrue(meta.unread(bot.copy(lastActivityAt = 20)))
    }

    @Test fun quickMemoDoesNotAcknowledgeActivityInAnUnseenChat() {
        val meta = ChatMetaStore(emptyMap()) { _, _ -> }
        meta.observeBots(listOf(bot))
        val updated = bot.copy(lastActivityAt = 20)
        assertTrue(meta.unread(updated))
        meta.markRead("B", a.id, 30)
        assertTrue(meta.unread(updated))
        meta.observeChats("B", listOf(a.copy(lastActivityAt = 30), b))
        assertTrue(meta.unread("B", b))
        assertTrue(meta.unread(updated.copy(lastActivityAt = 30)))
        meta.markRead("B", b.id, 31)
        assertFalse(meta.unread(updated.copy(lastActivityAt = 30)))
    }

    @Test fun directConversationBeforeFirstHomeStillBaselinesOtherBots() {
        val meta = ChatMetaStore(emptyMap()) { _, _ -> }
        meta.markRead("opened", "chat", 5)
        val bot = Bot("1", "other", paused = false, needsYou = 0, lastActivityAt = 20)
        meta.observeBots(listOf(bot))
        assertFalse(meta.unread(bot))
        meta.observeBots(listOf(bot.copy(lastActivityAt = 30)))
        assertTrue(meta.unread(bot.copy(lastActivityAt = 30)))
    }

    @Test fun newBotAfterAnEmptyBaselineIsUnread() {
        val meta = ChatMetaStore(emptyMap()) { _, _ -> }
        meta.observeBots(emptyList())
        val bot = Bot("1", "B", paused = false, needsYou = 0, lastActivityAt = 10)
        meta.observeBots(listOf(bot))
        assertTrue(meta.unread(bot))
    }

    @Test fun chatListBeforeHomeSetsBaselineAndOnlyKeepsExistingReadEntries() {
        val saved = mutableMapOf<String, BotMeta>()
        val meta = ChatMetaStore(saved) { key, value -> saved[key] = value }
        meta.observeChats("B", listOf(a, b))
        assertFalse(meta.unread("B", b))
        val updated = bot.copy(lastActivityAt = 40)
        meta.observeBots(listOf(updated))
        assertTrue(meta.unread(updated))
        meta.observeChats("B", listOf(b))
        assertEquals(setOf("b"), saved.getValue("B").read.keys)
    }

    @Test fun corruptMetadataFallsBackPerBot() {
        val good = BotMeta(readAt = 10, pins = setOf("a"))
        assertEquals(good, restoreBotMeta(apiJson.encodeToString(good)))
        assertEquals(BotMeta(), restoreBotMeta("{broken"))
        assertEquals(BotMeta(), restoreBotMeta(false))
    }

    @Test fun pinsByActivityThenUnpinnedHomeThenOtherChatsByActivity() {
        val meta = ChatMetaStore(emptyMap()) { _, _ -> }
        val chats = (1..5).map { Chat(it.toString(), home = it == 2, lastActivityAt = it.toLong()) }
        meta.togglePin("B", "1")
        meta.togglePin("B", "3")
        assertEquals(listOf("3", "1", "2", "5", "4"), meta.sorted("B", chats).map { it.id })
    }

    @Test fun baselineUpdatesPinsAndRestoration() {
        val saved = mutableMapOf<String, BotMeta>()
        val meta = ChatMetaStore(saved) { k, v -> saved[k] = v }
        val bot = Bot("b", "B", paused = false, needsYou = 0, lastActivityAt = 10)
        val a = Chat("a", home = true, lastActivityAt = 10)
        val b = a.copy(id = "b", home = false, lastActivityAt = 6)
        meta.observeBots(listOf(bot))
        assertFalse(meta.unread(bot))
        meta.observeChats("B", listOf(a,b))
        assertFalse(meta.unread("B", a))
        val newer = b.copy(lastActivityAt = 20)
        meta.observeChats("B", listOf(a,newer))
        assertTrue(meta.unread(bot))
        assertTrue(meta.unread("B", newer))
        assertEquals(listOf(a,newer), meta.sorted("B", listOf(newer,a)))
        meta.togglePin("B","b")
        assertEquals(listOf(newer,a), meta.sorted("B", listOf(a,newer)))
        meta.markRead("B","b",20)
        meta.markRead("B","b",5)
        assertFalse(meta.unread(bot.copy(lastActivityAt = 20)))
        val restored = ChatMetaStore(saved) { _,_ -> }
        assertTrue(restored.pinned("B","b"))
        assertFalse(restored.unread("B",newer))
        restored.togglePin("B","b")
        assertFalse(restored.pinned("B","b"))
        assertTrue(restored.unread(bot.copy(lastActivityAt = 21)))
    }
}
