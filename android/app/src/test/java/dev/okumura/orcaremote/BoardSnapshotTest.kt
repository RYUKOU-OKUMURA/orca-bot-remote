package dev.okumura.orcaremote

import org.junit.Assert.*
import org.junit.Test

class BoardSnapshotTest {
    @Test fun optionalFieldsAndDamagedCache() {
        assertNull(cachedBoard(null))
        assertNull(cachedBoard("{broken"))
        assertEquals(BoardSnapshot(), cachedBoard("{}"))
        val json = """{"updatedAt":123,"bots":[{"id":"1","name":"Bot","avatar":"mascot:T1"}],
            "sessions":[{"bot":"Bot","title":"Review"}],"extra":42}"""
        val board = requireNotNull(cachedBoard(json))
        assertEquals(123L, board.updatedAt)
        assertFalse(board.bots.single().working)
        assertEquals(R.drawable.mascot_t1, board.rows().single().icon)
        assertEquals(board, cachedBoard(apiJson.encodeToString(board)))
    }

    @Test fun rowsAreGroupedAndRouteToSpecificScreens() {
        val bot = "A/B"
        val board = BoardSnapshot(
            sessions = listOf(BoardSession(bot, "Worker", "worker", "execute"), BoardSession(bot, "Chat", chatId = "a b")),
            tasks = listOf(PendingItem(bot, 1, "Later", "planned"), PendingItem(bot, 2, "Doing", "in_progress", "c"),
                PendingItem(bot, 3, "Ask", "needs_you", "c", avatar = "mascot:T1"), PendingItem(bot, 4, "Done", "done"))
        )
        val rows = board.rows()
        assertEquals(listOf("Worker", "Chat", "Ask", "Doing", "Later"), rows.map { it.title })
        assertEquals(listOf("chats/A%2FB", "chat/A%2FB/a%20b", "item/A%2FB/3", "chat/A%2FB/c", "chats/A%2FB")
            .map { "orca-notification://$it" }, rows.map { it.link })
        assertEquals("A/B · execute", rows.first().subtitle)
        assertEquals(R.drawable.mascot_a1, rows.first().icon)
        assertEquals(R.drawable.mascot_t1, rows[2].icon)
    }

    @Test fun removesOnlyRetiredBoardKeysOrAllOnDisable() {
        val keys = setOf("board:old", "board:current", "token", "url", "boardSettings")
        assertEquals(listOf("board:old"), staleBoardKeys(keys, "board:current"))
        assertEquals(listOf("board:old", "board:current"), staleBoardKeys(keys, null))
    }

    @Test fun cacheKeysSeparateServerAndAccount() {
        fun key(server: String, token: String) = Connection.parse("https://$server", token)!!.boardCacheKey()
        assertNotEquals(key("a.test", "one"), key("b.test", "one"))
        assertNotEquals(key("a.test", "one"), key("a.test", "two"))
    }
}
