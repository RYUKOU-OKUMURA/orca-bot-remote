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
            bots = listOf(Bot("1", "A", needsYou = 2), Bot("2", "B", needsYou = 1, avatar = "mascot:T1"), Bot("3", "C")),
            memoTasks = listOf(
                MemoTask("t1", "期限切れ", note = "\n  補足の1行目\n2行目", due = "2026-11-20"),
                MemoTask("t2", "あした", due = "2026-11-23"),
                MemoTask("t3", "期限なし"),
                MemoTask("t4", "完了", done = true),
                MemoTask("t5", "担当つき", due = "2026-11-25", bot = "B"),
                MemoTask("t6", "消えたBot", bot = "Z"),
            ),
        )
        assertEquals(3, board.pendingCount)
        assertEquals(0, BoardSnapshot(memoTasks = board.memoTasks).pendingCount)
        assertEquals(listOf(
            TaskRow("t1", "期限切れ", "期限切れ 11/20 · 補足の1行目", true),
            TaskRow("t2", "あした", "明日まで", false),
            TaskRow("t3", "期限なし", "", false),
            TaskRow("t5", "担当つき", "B · 11/25まで", false, "B", "mascot:T1"),
            TaskRow("t6", "消えたBot", "Z", false, "Z", null),
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

    @Test fun progressRowsShowSessionsThenItemsWithDeepLinks() {
        val json = """{"sessions":[
            {"bot":"秘書 A/B","avatar":"mascot:T1","kind":"worker","title":"実装","tool":"Bash","chatId":null},
            {"bot":"B","avatar":null,"kind":"chat","title":"","tool":null,"chatId":"c 1/2"}],
            "tasks":[{"bot":"秘書 A/B","avatar":null,"number":7,"title":"確認","status":"needs_you","chatId":null,"lastActivityAt":1},
            {"bot":"B","number":0,"title":"","status":"in_progress"},{"bot":"B","number":3,"title":"次","status":"planned"},
            {"bot":"B","number":4,"title":"謎","status":"odd"}]}"""
        val board = requireNotNull(cachedBoard(json))
        assertEquals(listOf(
            TaskRow(null, "実装", "作業中 · 秘書 A/B", false, "秘書 A/B", "mascot:T1", "orca-notification://chats/%E7%A7%98%E6%9B%B8%20A%2FB"),
            TaskRow(null, "チャット", "応答中 · B", false, "B", null, "orca-notification://chat/B/c%201%2F2"),
            TaskRow(null, "確認", "判断待ち · 秘書 A/B", true, "秘書 A/B", null, "orca-notification://item/%E7%A7%98%E6%9B%B8%20A%2FB/7"),
            TaskRow(null, "#0", "進行中 · B", false, "B", null, "orca-notification://chats/B"),
            TaskRow(null, "次", "予定 · B", false, "B", null, "orca-notification://item/B/3"),
            TaskRow(null, "謎", "odd · B", false, "B", null, "orca-notification://item/B/4"),
        ), board.progressRows())
        assertEquals(board.progressRows(), board.rows(BoardTab.PROGRESS, LocalDate.of(2026, 11, 22)))
        board.progressRows().forEach { assertTrue(appLink(it.link)) }
        // The app reads these with Uri.pathSegments, which decodes each segment like URLDecoder.
        val segments = java.net.URI("orca-notification://chat/%E7%A7%98%E6%9B%B8%20A%2FB/c%201%2F2").rawPath.split('/').drop(1)
            .map { java.net.URLDecoder.decode(it, "UTF-8") }
        assertEquals(listOf("秘書 A/B", "c 1/2"), segments)
        assertEquals("orca-notification://chat/%E7%A7%98%E6%9B%B8%20A%2FB/c%201%2F2",
            sessionLink(BoardSession("秘書 A/B", chatId = "c 1/2")))
        assertEquals(boardLink("B"), sessionLink(BoardSession("B", chatId = "")))
    }

    @Test fun workingBannerSummarisesRunningSessions() {
        assertNull(BoardSnapshot().workingBanner())
        val one = BoardSnapshot(sessions = listOf(BoardSession("A", kind = "worker", title = "実装")))
        assertEquals("● 作業中 · A: 実装 ›", one.workingBanner())
        val two = one.copy(sessions = one.sessions + BoardSession("B"))
        assertEquals("● 作業中 2件 · A: 実装 ›", two.workingBanner())
        assertEquals("● 作業中 · B: チャット ›", BoardSnapshot(sessions = listOf(BoardSession("B"))).workingBanner())
    }

    @Test fun tabDefaultsToTasksAndKeepsTaskRows() {
        assertEquals(BoardTab.TASKS, boardTab(null))
        assertEquals(BoardTab.TASKS, boardTab("bogus"))
        assertEquals(BoardTab.PROGRESS, boardTab(BoardTab.PROGRESS.name))
        val today = LocalDate.of(2026, 11, 22)
        val board = BoardSnapshot(memoTasks = listOf(MemoTask("t1", "a")), sessions = listOf(BoardSession("A")),
            tasks = listOf(BoardTask("A", number = 1)))
        assertEquals(board.taskRows(today), board.rows(BoardTab.TASKS, today))
        assertEquals(TasksLink, board.taskRows(today).single().link)
        // Completing a task from the widget keeps the progress data.
        val done = board.copy(memoTasks = board.memoTasks?.filter { it.id != "t1" })
        assertEquals(board.sessions, done.sessions)
        assertEquals(board.tasks, done.tasks)
        assertEquals(done, cachedBoard(apiJson.encodeToString(done)))
    }
}
