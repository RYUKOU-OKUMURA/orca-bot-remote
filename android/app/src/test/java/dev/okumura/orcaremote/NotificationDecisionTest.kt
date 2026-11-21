package dev.okumura.orcaremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDecisionTest {
    private fun item(number: Long, bot: String = "メインBot", status: String = "needs_you") =
        PendingItem(bot, number, "タイトル", status)

    @Test
    fun firstCheckRecordsExistingItemsAndSummaryWithoutNotifying() {
        val result = decideNotifications(null, listOf(item(7)), 100)

        assertTrue(result.newItems.isEmpty())
        assertFalse(result.notifySummary)
        assertEquals(NotificationHistory(setOf("7:メインBot"), 100), result.history)
    }

    @Test
    fun onlyNewBotAndNumberPairsAreNotifiedOnce() {
        val old = item(7)
        val differentBot = item(7, bot = "別のBot")
        val differentNumber = item(8)
        val previous = decideNotifications(null, listOf(old), null).history
        val result = decideNotifications(previous, listOf(old, differentBot, differentNumber, differentBot), null)

        assertEquals(listOf(differentBot, differentNumber), result.newItems)
        assertEquals(0, result.remainingItems)
    }

    @Test
    fun resolvedItemsAreRemovedAndCanNotifyAgain() {
        val pending = item(7)
        val previous = decideNotifications(null, listOf(pending, item(8)), null).history
        val resolved = decideNotifications(previous, listOf(item(7, status = "done")), null)

        assertTrue(resolved.history.items.isEmpty())
        assertTrue(resolved.newItems.isEmpty())
        assertEquals(listOf(pending), decideNotifications(resolved.history, listOf(pending), null).newItems)
    }

    @Test
    fun onlyNewerSummariesNotifyAndMissingOrOlderSummariesPreserveTheTimestamp() {
        val previous = NotificationHistory(emptySet(), 100)

        for (runAt in listOf(null, 99L, 100L)) {
            val result = decideNotifications(previous, emptyList(), runAt)
            assertFalse(result.notifySummary)
            assertEquals(previous, result.history)
        }
        val newer = decideNotifications(previous, emptyList(), 101)
        assertTrue(newer.notifySummary)
        assertEquals(101L, newer.history.summaryRunAt)
        val firstSummary = decideNotifications(NotificationHistory(emptySet(), null), emptyList(), 100)
        assertTrue(firstSummary.notifySummary)
    }

    @Test
    fun firstFiveNewItemsAreIndividualAndTheRestAreCountedOnce() {
        val items = (1L..8L).map { item(it) }
        val previous = NotificationHistory(emptySet(), null)
        val result = decideNotifications(previous, items, null)

        assertEquals(items.take(5), result.newItems)
        assertEquals(3, result.remainingItems)
        assertTrue(decideNotifications(result.history, items, null).newItems.isEmpty())
    }

    @Test
    fun deniedPermissionPrunesResolvedItemsWithoutMarkingNewArrivalsAsNotified() {
        val previous = NotificationHistory(setOf("7:メインBot"), 100)
        val result = decideNotifications(previous, listOf(item(8)), 101, canNotifyItems = false, canNotifySummary = false)

        assertTrue(result.newItems.isEmpty())
        assertFalse(result.notifySummary)
        assertEquals(NotificationHistory(emptySet(), 100), result.history)
        assertEquals(listOf(item(8)), decideNotifications(result.history, listOf(item(8)), 101).newItems)
    }

    @Test
    fun disabledItemsChannelPreservesNewItemsForWhenItIsReenabled() {
        val previous = NotificationHistory(setOf("7:メインBot"), 100)
        val items = listOf(item(8))
        val result = decideNotifications(previous, items, 101, canNotifyItems = false)

        assertTrue(result.newItems.isEmpty())
        assertTrue(result.notifySummary)
        assertEquals(NotificationHistory(emptySet(), 101), result.history)
        val reenabled = decideNotifications(result.history, items, 101)
        assertEquals(items, reenabled.newItems)
        assertFalse(reenabled.notifySummary)
    }

    @Test
    fun disabledSummaryChannelPreservesNewSummaryForWhenItIsReenabled() {
        val previous = NotificationHistory(emptySet(), 100)
        val items = listOf(item(8))
        val result = decideNotifications(previous, items, 101, canNotifySummary = false)

        assertEquals(items, result.newItems)
        assertFalse(result.notifySummary)
        assertEquals(NotificationHistory(setOf("8:メインBot"), 100), result.history)
        val reenabled = decideNotifications(result.history, items, 101)
        assertTrue(reenabled.newItems.isEmpty())
        assertTrue(reenabled.notifySummary)
    }
}
