package dev.okumura.orcaremote

internal data class NotificationHistory(
    val items: Set<String>?,
    val summaryRunAt: Long?,
    val summaryInitialized: Boolean = true,
)

internal data class NotificationDecision(
    val history: NotificationHistory,
    val newItems: List<PendingItem>,
    val remainingItems: Int,
    val notifySummary: Boolean,
)

internal fun decideNotifications(
    previous: NotificationHistory?,
    items: List<PendingItem>?,
    summaryRunAt: Long?,
    summaryUpdated: Boolean = true,
    canNotifyItems: Boolean = true,
    canNotifySummary: Boolean = true,
): NotificationDecision {
    val pending = items?.filter { it.status == "needs_you" }?.associateBy { "${it.number}:${it.bot}" }
    val previousItems = previous?.items
    val summaryInitialized = previous?.summaryInitialized == true
    val previousRunAt = previous?.summaryRunAt
    val newRunAt = summaryRunAt?.takeIf { summaryUpdated && (previousRunAt == null || it > previousRunAt) }
    val newItems = if (pending != null && previousItems != null && canNotifyItems) {
        pending.filterKeys { it !in previousItems }.values.toList()
    } else {
        emptyList()
    }
    val notifiedItems = when {
        pending == null -> previousItems
        previousItems == null || canNotifyItems -> pending.keys
        else -> previousItems.intersect(pending.keys)
    }
    val notifiedRunAt = if (!summaryInitialized || canNotifySummary) newRunAt ?: previousRunAt else previousRunAt
    return NotificationDecision(
        NotificationHistory(notifiedItems, notifiedRunAt, summaryInitialized || summaryUpdated),
        newItems.take(5),
        (newItems.size - 5).coerceAtLeast(0),
        summaryInitialized && canNotifySummary && newRunAt != null,
    )
}
