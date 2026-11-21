package dev.okumura.orcaremote

import org.junit.Assert.*
import org.junit.Test

class ChatMetaStoreTest {
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
        assertEquals(listOf(newer,a), meta.sorted("B", listOf(a,newer)))
        meta.togglePin("B","a")
        assertEquals(listOf(a,newer), meta.sorted("B", listOf(newer,a)))
        meta.markRead("B","b",20)
        meta.markRead("B","b",5)
        assertFalse(meta.unread(bot.copy(lastActivityAt = 20)))
        val restored = ChatMetaStore(saved) { _,_ -> }
        assertTrue(restored.pinned("B","a"))
        assertFalse(restored.unread("B",newer))
        restored.togglePin("B","a")
        assertFalse(restored.pinned("B","a"))
        assertTrue(restored.unread(bot.copy(lastActivityAt = 21)))
    }
}
