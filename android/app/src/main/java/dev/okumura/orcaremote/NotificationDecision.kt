package dev.okumura.orcaremote

internal data class NotificationHistory(val items: Set<String>, val summaryRunAt: Long?)

internal data class NotificationDecision(
    val history: NotificationHistory,
    val newItems: List<PendingItem>,
    val remainingItems: Int,
    val newSummaryRunAt: Long?,
)

internal fun decideNotifications(
    previous: NotificationHistory?,
    items: List<PendingItem>,
    summaryRunAt: Long?,
    canNotify: Boolean = true,
): NotificationDecision {
    val pending = items.filter { it.status == "needs_you" }.associateBy { "${it.number}:${it.bot}" }
    val previousRunAt = previous?.summaryRunAt
    val newRunAt = summaryRunAt?.takeIf { previousRunAt == null || it > previousRunAt }
    val newItems = if (previous != null && canNotify) {
        pending.filterKeys { it !in previous.items }.values.toList()
    } else {
        emptyList()
    }
    val history = if (previous == null || canNotify) {
        NotificationHistory(pending.keys, newRunAt ?: previousRunAt)
    } else {
        NotificationHistory(previous.items.intersect(pending.keys), previousRunAt)
    }
    return NotificationDecision(
        history,
        newItems.take(5),
        (newItems.size - 5).coerceAtLeast(0),
        newRunAt.takeIf { previous != null && canNotify },
    )
}
