package dev.okumura.orcaremote

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BoardSnapshotTest {
    @Test fun optionalFieldsAndDamagedCache() {
        assertNull(cachedBoard(null))
        assertNull(cachedBoard("{broken"))
        assertEquals(BoardSnapshot(), cachedBoard("{}"))
        val json = """{"updatedAt":123,"bots":[{"id":"1","name":"Bot","avatar":"mascot:T1","needsYou":2}],
            "sessions":[{"bot":"Bot","title":"Review"}],"tasks":[],"memoTasks":null,"extra":42}"""
        val board = requireNotNull(cachedBoard(json))
        assertEquals(123L, board.updatedAt)
        assertFalse(board.bots.single().working)
        assertNull(board.memoTasks)
        assertEquals(emptyList<TaskRow>(), board.taskRows(LocalDate.of(2026, 11, 22)))
        assertEquals(board, cachedBoard(apiJson.encodeToString(board)))
    }

    @Test fun widgetListsOpenMemoTasksSeparatelyFromPendingItems() {
        val today = LocalDate.of(2026, 11, 22)
        val board = BoardSnapshot(
            bots = listOf(Bot("1", "A", needsYou = 2), Bot("2", "B", needsYou = 1), Bot("3", "C")),
            memoTasks = listOf(
                MemoTask("t1", "期限切れ", note = "\n  補足の1行目\n2行目", due = "2026-11-20"),
                MemoTask("t2", "あした", due = "2026-11-23"),
                MemoTask("t3", "期限なし"),
                MemoTask("t4", "完了", done = true),
            ),
        )
        assertEquals(3, board.pendingCount)
        assertEquals(0, BoardSnapshot(memoTasks = board.memoTasks).pendingCount)
        assertEquals(listOf(
            TaskRow("t1", "期限切れ", "期限切れ 11/20 · 補足の1行目", true),
            TaskRow("t2", "あした", "明日まで", false),
            TaskRow("t3", "期限なし", "", false),
        ), board.taskRows(today))
    }

    @Test fun widgetLinksRoundTripTaskIds() {
        for (id in listOf("abc", "a b/c+d?e", "日本語")) assertEquals(id, taskDoneId(taskDoneLink(id)))
        assertEquals("orcaremote://task-done/a%20b%2Fc%2Bd", taskDoneLink("a b/c+d"))
        assertNull(taskDoneId(TasksLink))
        assertNull(taskDoneId("orcaremote://task-done/"))
        assertNull(taskDoneId(null))
        assertEquals("orca-notification://chats/A%2FB%20C", boardLink("A/B C"))
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
