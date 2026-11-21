package dev.okumura.orcaremote

internal data class NotificationHistory(val items: Set<String>, val summaryRunAt: Long?)

internal data class NotificationDecision(
    val history: NotificationHistory,
    val newItems: List<PendingItem>,
    val remainingItems: Int,
    val notifySummary: Boolean,
)

internal fun decideNotifications(
    previous: NotificationHistory?,
    items: List<PendingItem>,
    summaryRunAt: Long?,
    canNotifyItems: Boolean = true,
    canNotifySummary: Boolean = true,
): NotificationDecision {
    val pending = items.filter { it.status == "needs_you" }.associateBy { "${it.number}:${it.bot}" }
    val previousRunAt = previous?.summaryRunAt
    val newRunAt = summaryRunAt?.takeIf { previousRunAt == null || it > previousRunAt }
    val newItems = if (previous != null && canNotifyItems) {
        pending.filterKeys { it !in previous.items }.values.toList()
    } else {
        emptyList()
    }
    val notifiedItems = if (previous == null || canNotifyItems) {
        pending.keys
    } else {
        previous.items.intersect(pending.keys)
    }
    val notifiedRunAt = if (previous == null || canNotifySummary) newRunAt ?: previousRunAt else previousRunAt
    return NotificationDecision(
        NotificationHistory(notifiedItems, notifiedRunAt),
        newItems.take(5),
        (newItems.size - 5).coerceAtLeast(0),
        previous != null && canNotifySummary && newRunAt != null,
    )
}
