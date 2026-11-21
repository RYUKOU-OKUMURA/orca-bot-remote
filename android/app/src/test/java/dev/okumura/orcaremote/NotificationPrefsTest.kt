package dev.okumura.orcaremote

import org.junit.Assert.*
import org.junit.Test

class NotificationPrefsTest {
    @Test fun overnightBoundariesAndDisabledHours() {
        val prefs = NotificationPrefs()
        assertTrue(prefs.quietAt(22, 0))
        assertTrue(prefs.quietAt(6, 59))
        assertFalse(prefs.quietAt(7, 0))
        assertFalse(prefs.quietAt(21, 59))
        assertFalse(prefs.copy(quietHours = false).quietAt(23, 0))
    }

    @Test fun sameDayAndEqualTimes() {
        val prefs = NotificationPrefs(startMinute = 780, endMinute = 840)
        assertTrue(prefs.quietAt(13, 0))
        assertTrue(prefs.quietAt(13, 59))
        assertFalse(prefs.quietAt(14, 0))
        assertFalse(prefs.copy(startMinute = 840).quietAt(14, 0))
    }

    @Test fun deliveryUsesPreferencesAndExactOpenChat() {
        for (channel in listOf(ItemsChannel, SummaryChannel)) {
            assertEquals(channel, notificationChannel(channel, false))
            assertEquals(QuietChannel, notificationChannel(channel, true))
        }
        val chat = ChatTarget("Bot", "1")
        assertEquals(RepliesChannel, notificationChannel(RepliesChannel, false))
        assertNull(notificationChannel(RepliesChannel, true))
        assertNull(notificationChannel(RepliesChannel, false, replies = false))
        assertNull(notificationChannel(RepliesChannel, false, target = chat, active = chat))
        assertEquals(RepliesChannel, notificationChannel(RepliesChannel, false, target = chat, active = chat.copy(chatId = "2")))
    }

    @Test fun repliesRequireBothNewActivityAndChangedTextAfterBaseline() {
        val bot = Bot("id", "bot", paused = false, needsYou = 0, lastActivityAt = 10, lastSaid = "one")
        val baseline = decideReplies(emptyMap(), listOf(bot))
        assertTrue(baseline.newReplies.isEmpty())
        for (b in listOf(bot.copy(lastActivityAt = 11), bot.copy(lastSaid = "two"), bot.copy(lastActivityAt = 9, lastSaid = "two"))) {
            assertTrue(decideReplies(baseline.history, listOf(b)).newReplies.isEmpty())
        }
        val newer = bot.copy(lastActivityAt = 11, lastSaid = "two")
        val next = decideReplies(baseline.history, listOf(newer))
        assertEquals(listOf(newer), next.newReplies)
        assertTrue(decideReplies(next.history, listOf(newer)).newReplies.isEmpty())
        val idle = decideReplies(emptyMap(), listOf(bot.copy(lastActivityAt = null, lastSaid = null)))
        assertEquals(listOf(bot), decideReplies(idle.history, listOf(bot)).newReplies)
    }
}
