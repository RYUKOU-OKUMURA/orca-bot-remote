package dev.okumura.orcaremote

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TasksTest {
    @Test fun memoTaskSerializesWithServerShape() {
        val json = """{"id":"a","title":"牛乳","note":null,"due":"2026-11-30","done":true,"createdAt":1,"updatedAt":2,"doneAt":3,"extra":1}"""
        assertEquals(MemoTask("a", "牛乳", due = "2026-11-30", done = true, createdAt = 1, updatedAt = 2, doneAt = 3),
            apiJson.decodeFromString<MemoTask>(json))
        assertEquals(MemoTask("b", "最小"), apiJson.decodeFromString<MemoTask>("""{"id":"b","title":"最小"}"""))
        // Creation omits empty fields; edits send null so the server clears them.
        assertEquals("""{"title":"t"}""", apiJson.encodeToString(NewTask("t")))
        assertEquals("""{"title":"t","note":null,"due":null,"bot":null}""", apiJson.encodeToString(TaskEdit("t", null, null, null)))
        assertEquals("""{"title":"t","bot":"整体院発信"}""", apiJson.encodeToString(NewTask("t", bot = "整体院発信")))
        assertEquals("B", apiJson.decodeFromString<MemoTask>("""{"id":"c","title":"担当","bot":"B"}""").bot)
    }

    @Test fun askingABotUsesTheTaskAsDraft() {
        assertEquals("見積もり\n金額を確認", taskRequest(" 見積もり ", "\n金額を確認 "))
        assertEquals("見積もり", taskRequest("見積もり", "  "))
        assertEquals("見積もり", taskRequest("見積もり", null))
        val bots = listOf(Bot("1", "整体院発信"), Bot("2", "メインBot"))
        assertEquals("整体院発信", taskAskTarget("整体院発信", bots))
        assertEquals("消えたBot", taskAskTarget("消えたBot", bots))
        assertEquals("メインBot", taskAskTarget(null, bots))
        assertEquals("整体院発信", taskAskTarget(null, bots.take(1)))
        assertNull(taskAskTarget(null, emptyList()))
    }

    @Test fun quickMemoSplitsTitleAndNote() {
        assertNull(memoTask(" \n\t\n"))
        assertEquals(NewTask("牛乳を買う"), memoTask("\n  牛乳を買う  \n\n"))
        assertEquals(NewTask("見積もり", "金額を確認\n先方に返信", "2026-12-01"),
            memoTask("見積もり\n金額を確認\n先方に返信\n", "2026-12-01"))
        assertEquals(NewTask("頼む", bot = "B"), memoTask("頼む", bot = "B"))
        val long = "あ".repeat(199) + "😀😀"
        val split = requireNotNull(memoTask("$long\n続き"))
        assertEquals(MaxTaskTitle, split.title.codePointCount(0, split.title.length))
        assertEquals("$long\n続き", split.note)
        assertTrue(taskInputValid(" 題 ", "x".repeat(MaxTaskNote)))
        assertFalse(taskInputValid("  ", ""))
        assertFalse(taskInputValid("題", "x".repeat(MaxTaskNote + 1)))
        assertFalse(taskInputValid("x".repeat(MaxTaskTitle + 1), ""))
    }

    @Test fun dueDatesAreStrictAndLabelled() {
        assertEquals(LocalDate.of(2028, 2, 29), parseDue("2028-02-29"))
        for (bad in listOf("2027-02-29", "2026-1-01", "+2026-01-01", "2026/01/01", "", null)) assertNull(bad, parseDue(bad))
        val today = LocalDate.of(2026, 11, 22)
        assertEquals("期限切れ 11/21" to true, dueLabel("2026-11-21", today))
        assertEquals("今日まで" to true, dueLabel("2026-11-22", today))
        assertEquals("明日まで" to false, dueLabel("2026-11-23", today))
        assertEquals("12/5まで" to false, dueLabel("2026-12-05", today))
        assertEquals("2027/1/2まで" to false, dueLabel("2027-01-02", today))
        assertNull(dueLabel("bad", today))
    }

    @Test fun sortingMatchesServerAndReplacesUpdatedTask() {
        val tasks = listOf(
            MemoTask("old", "古い", createdAt = 1),
            MemoTask("done1", "完了1", done = true, updatedAt = 5, doneAt = 10),
            MemoTask("late", "遅い期限", due = "2026-12-01"),
            MemoTask("new", "新しい", createdAt = 2),
            MemoTask("done2", "完了2", done = true, updatedAt = 20),
            MemoTask("soon", "近い期限", due = "2026-11-25"),
        )
        assertEquals(listOf("soon", "late", "new", "old", "done2", "done1"), sortTasks(tasks).map { it.id })
        val reopened = replaceTask(tasks, MemoTask("done2", "完了2", due = "2026-11-24"))
        assertEquals(listOf("done2", "soon", "late", "new", "old", "done1"), reopened.map { it.id })
        assertEquals(6, reopened.size)
    }

    @Test fun dueUrgencyAndOneTapToggle() {
        val today = LocalDate.of(2026, 12, 31)
        assertEquals(DueUrgency.OVERDUE, dueUrgency("2026-12-30", today))
        assertEquals(DueUrgency.TODAY, dueUrgency("2026-12-31", today))
        assertEquals(DueUrgency.TOMORROW, dueUrgency("2027-01-01", today))
        assertEquals(DueUrgency.UPCOMING, dueUrgency("2027-01-02", today))
        assertNull(dueUrgency(null, today))
        assertNull(dueUrgency("2026-02-30", today))
        assertEquals("2026-12-31", toggleDue(null, "2026-12-31"))
        assertNull(toggleDue("2026-12-31", "2026-12-31"))
        assertEquals("2027-01-01", toggleDue("2026-12-31", "2027-01-01"))
    }

    @Test fun sortOrdersBreakTiesLikeTheServerAndFallBackToDue() {
        val tasks = listOf(
            MemoTask("sameOld", "同日・古い", due = "2026-12-01", createdAt = 1),
            MemoTask("undated", "期限なし", createdAt = 5),
            MemoTask("sameNew", "同日・新しい", due = "2026-12-01", createdAt = 3),
            MemoTask("bad", "不正な期限", due = "2026-02-30", createdAt = 4),
            MemoTask("done", "完了", done = true, updatedAt = 9, doneAt = 2),
            MemoTask("soon", "近い", due = "2026-11-26", createdAt = 2),
        )
        assertEquals(listOf("soon", "sameNew", "sameOld", "undated", "bad", "done"), sortTasks(tasks).map { it.id })
        assertEquals(sortTasks(tasks), sortTasks(tasks, TaskSortOrder.DUE))
        assertEquals(listOf("undated", "bad", "sameNew", "soon", "sameOld", "done"), sortTasks(tasks, TaskSortOrder.CREATED).map { it.id })
        val moved = MemoTask("sameOld", "同日・古い", due = "2026-11-20", createdAt = 1)
        assertEquals("sameOld", replaceTask(tasks, moved).first().id)
        assertEquals(listOf("undated", "bad", "sameNew", "soon", "sameOld", "done"),
            replaceTask(tasks, moved, TaskSortOrder.CREATED).map { it.id })
        assertEquals(TaskSortOrder.DUE, taskSortOrder(null))
        assertEquals(TaskSortOrder.DUE, taskSortOrder("bogus"))
        assertEquals(TaskSortOrder.CREATED, taskSortOrder(TaskSortOrder.CREATED.name))
        assertEquals(listOf("期限順", "新しい順"), TaskSortOrder.entries.map { it.label })
    }

    @Test fun quickDueCountsFromTheTapDayAndMidnightWaitIsLocal() {
        val newYear = LocalDate.of(2027, 1, 1)
        // A chip drawn on 12/31 but tapped after midnight sets the new day.
        assertEquals("2027-01-01", quickDue("2026-12-31", 0, newYear))
        assertEquals("2027-01-02", quickDue(null, 1, newYear))
        assertNull(quickDue("2027-01-02", 1, newYear))
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(500L, millisUntilNextDay(ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 500_000_000, tokyo)))
        assertEquals(86_400_000L, millisUntilNextDay(ZonedDateTime.of(2026, 11, 25, 0, 0, 0, 0, tokyo)))
        // A 23-hour day: New York skips 2:00-3:00 on 2026-03-08.
        val newYork = ZoneId.of("America/New_York")
        assertEquals(23 * 3_600_000L, millisUntilNextDay(ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork)))
    }

    @Test fun sortOrderIsSavedAndReadBack() {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { values[args[0] as String] = args[1]; editor }
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
        assertEquals(TaskSortOrder.DUE, prefs.taskSortOrder())
        prefs.saveTaskSortOrder(TaskSortOrder.CREATED)
        assertEquals(mapOf("taskSortOrder" to "CREATED"), values)
        assertEquals(TaskSortOrder.CREATED, prefs.taskSortOrder())
        values["taskSortOrder"] = "removed"
        assertEquals(TaskSortOrder.DUE, prefs.taskSortOrder())
    }

    @Test fun aTaskTakesOneChangeAtATime() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            val mutations = TaskMutations()
            val gate = CompletableDeferred<Unit>()
            var runs = 0
            assertTrue(mutations.run("a") { runs++; gate.await() })
            main.scheduler.runCurrent()
            assertEquals(setOf("a"), mutations.busy)
            assertFalse(mutations.run("a") { runs++ })
            assertTrue(mutations.run("b") { runs++ })
            main.scheduler.runCurrent()
            assertEquals(setOf("a"), mutations.busy)
            gate.complete(Unit)
            main.scheduler.runCurrent()
            assertEquals(emptySet<String>(), mutations.busy)
            assertEquals(2, runs)
            // A failed change also frees the task.
            assertTrue(mutations.run("a") { throw IllegalStateException() }.also { runCatching { main.scheduler.runCurrent() } })
            assertEquals(emptySet<String>(), mutations.busy)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
