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

        assertEquals(emptySet<String>(), resolved.history.items)
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
    fun remainingCountUsesOnlyNewItemsWhenPollingAgain() {
        val items = (1L..6L).map { item(it) }
        val previous = NotificationHistory(emptySet(), 100)
        val five = decideNotifications(previous, items.take(5), 100)
        assertEquals(items.take(5), five.newItems)
        assertEquals(0, five.remainingItems)

        val six = decideNotifications(previous, items, 100)
        assertEquals(items.take(5), six.newItems)
        assertEquals(1, six.remainingItems)

        val nextSummary = decideNotifications(six.history, items, 101)
        assertTrue(nextSummary.newItems.isEmpty())
        assertEquals(0, nextSummary.remainingItems)
        assertTrue(nextSummary.notifySummary)
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

    @Test
    fun itemsAndSummaryKeepIndependentHistoriesDuringFailures() {
        val old = item(7)
        val new = item(8)
        val initial = decideNotifications(null, listOf(old), 100).history
        val summaryOnly = decideNotifications(initial, null, 101)
        assertTrue(summaryOnly.notifySummary)
        assertTrue(summaryOnly.newItems.isEmpty())
        assertEquals(initial.copy(summaryRunAt = 101), summaryOnly.history)

        val itemsOnly = decideNotifications(summaryOnly.history, listOf(old, new), null, summaryUpdated = false)
        assertEquals(listOf(new), itemsOnly.newItems)
        assertFalse(itemsOnly.notifySummary)
        assertEquals(NotificationHistory(setOf("7:メインBot", "8:メインBot"), 101), itemsOnly.history)
        val bothFailed = decideNotifications(itemsOnly.history, null, null, summaryUpdated = false)
        assertEquals(itemsOnly.history, bothFailed.history)
    }

    @Test
    fun eachEndpointSilentlyRecordsItsOwnFirstSuccessfulCheck() {
        val summaryFirst = decideNotifications(null, null, 100)
        assertEquals(NotificationHistory(null, 100), summaryFirst.history)
        assertFalse(summaryFirst.notifySummary)
        val firstItems = decideNotifications(summaryFirst.history, listOf(item(7)), 101)
        assertTrue(firstItems.newItems.isEmpty())
        assertTrue(firstItems.notifySummary)
        assertEquals(NotificationHistory(setOf("7:メインBot"), 101), firstItems.history)

        val itemsFirst = decideNotifications(null, emptyList(), null, summaryUpdated = false)
        assertEquals(NotificationHistory(emptySet(), null, summaryInitialized = false), itemsFirst.history)
        val firstSummary = decideNotifications(itemsFirst.history, listOf(item(7)), 100)
        assertEquals(listOf(item(7)), firstSummary.newItems)
        assertFalse(firstSummary.notifySummary)
        assertEquals(NotificationHistory(setOf("7:メインBot"), 100), firstSummary.history)

        val emptySummary = decideNotifications(null, null, null, summaryUpdated = true)
        assertTrue(decideNotifications(emptySummary.history, null, 100).notifySummary)
    }
}
