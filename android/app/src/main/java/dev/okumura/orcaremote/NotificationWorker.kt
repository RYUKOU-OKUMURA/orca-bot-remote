package dev.okumura.orcaremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val NotificationsEnabled = "notificationsEnabled"
internal const val NotifiedItems = "notifiedItems"
internal const val NotifiedSummary = "notifiedSummary"
internal const val SummaryInitialized = "summaryInitialized"
internal val notificationSettingsMutex = Mutex()
internal const val NotificationScheme = "orca-notification"
private const val NotificationWork = "orca-notifications"
private const val ItemsChannel = "pending-items"
private const val SummaryChannel = "summaries"

internal fun scheduleNotifications(context: Context, enabled: Boolean) {
    val work = WorkManager.getInstance(context)
    if (enabled) {
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(NotificationWork, ExistingPeriodicWorkPolicy.KEEP, request)
    } else {
        work.cancelUniqueWork(NotificationWork)
    }
}

class NotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = applicationContext.connectionPreferences()
        val connection = notificationSettingsMutex.withLock {
            if (preferences.getBoolean(NotificationsEnabled, true)) preferences.savedConnection() else null
        } ?: return@withContext Result.success()
        val api = OrcaApi(connection)
        val (items, summary) = coroutineScope {
            val itemsRequest = async { try { api.items().items } catch (_: ApiFailure) { null } }
            val summaryRequest = async { try { api.summary() } catch (_: ApiFailure) { null } }
            itemsRequest.await() to summaryRequest.await()
        }
        if (items != null || summary != null) notificationSettingsMutex.withLock {
            ensureActive()
            val current = preferences.savedConnection()
            if (preferences.getBoolean(NotificationsEnabled, true) &&
                current?.url == connection.url && current.token == connection.token
            ) publishChanges(preferences, items, summary)
        }
        Result.success()
    }

    private fun publishChanges(preferences: SharedPreferences, items: List<PendingItem>?, summary: LatestSummary?) {
        val context = applicationContext
        val notifiedItems = preferences.getStringSet(NotifiedItems, null)
        val runAt = preferences.getLong(NotifiedSummary, 0).takeIf { preferences.contains(NotifiedSummary) }
        // Older installations initialized both endpoints together and always stored the item set.
        val previous = NotificationHistory(notifiedItems, runAt, preferences.getBoolean(SummaryInitialized, notifiedItems != null))
        val manager = context.getSystemService(NotificationManager::class.java)
        val canNotify = manager.areNotificationsEnabled()
        val decision = decideNotifications(
            previous, items, summary?.summary?.runAt, summaryUpdated = summary != null,
            canNotifyItems = canNotify && manager.isChannelEnabled(ItemsChannel),
            canNotifySummary = canNotify && manager.isChannelEnabled(SummaryChannel),
        )
        if (decision.history == previous) return
        // If the process stops after saving and before posting, those notifications can be lost.
        if (!preferences.editHistory(decision.history).commit()) {
            // A failed commit still updates the in-memory preferences seen by the next run.
            preferences.editHistory(previous).apply()
            return
        }
        decision.newItems.forEach {
            val target = notificationTarget("item").buildUpon()
                .appendPath(it.bot).appendPath(it.number.toString()).build()
            manager.post(context, ItemsChannel, target, "判断待ち：${it.bot}『${it.title}』")
        }
        if (decision.remainingItems > 0) {
            manager.post(context, ItemsChannel, notificationTarget("items"), "判断待ち：ほか ${decision.remainingItems} 件")
        }
        if (decision.notifySummary) {
            manager.post(context, SummaryChannel, notificationTarget("summary"), "12:00のまとめが届きました")
        }
    }
}

private fun SharedPreferences.editHistory(history: NotificationHistory): SharedPreferences.Editor =
    edit().putStringSet(NotifiedItems, history.items).putBoolean(SummaryInitialized, history.summaryInitialized).apply {
        val runAt = history.summaryRunAt
        if (runAt == null) remove(NotifiedSummary) else putLong(NotifiedSummary, runAt)
    }

private fun NotificationManager.isChannelEnabled(channel: String): Boolean =
    getNotificationChannel(channel)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true

internal fun createNotificationChannels(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannels(
        listOf(
            NotificationChannel(ItemsChannel, "判断待ち", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SummaryChannel, "まとめ", NotificationManager.IMPORTANCE_DEFAULT),
        )
    )
}

private fun notificationTarget(screen: String): Uri =
    Uri.Builder().scheme(NotificationScheme).authority(screen).build()

private fun NotificationManager.post(context: Context, channel: String, target: Uri, text: String) {
    val intent = Intent(context, MainActivity::class.java)
        .setData(target)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    notify(
        target.toString(),
        0,
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.orca_notification)
            .setContentTitle(if (channel == ItemsChannel) "判断待ち" else "12:00のまとめ")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build(),
    )
}
