package dev.okumura.orcaremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val NotificationsEnabled = "notificationsEnabled"
internal const val NotifiedItems = "notifiedItems"
internal const val NotifiedSummary = "notifiedSummary"
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
    // The network calls suspend. Keep history and publication on the same thread as settings changes.
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val context = applicationContext
        val preferences = context.connectionPreferences()
        if (!preferences.getBoolean(NotificationsEnabled, true)) return@withContext Result.success()
        val connection = preferences.savedConnection() ?: return@withContext Result.success()
        val api = OrcaApi(connection)
        val items: List<PendingItem>
        val summaryRunAt: Long?
        try {
            items = api.items().items
            summaryRunAt = api.summary().summary?.runAt
        } catch (_: ApiFailure) {
            return@withContext Result.success()
        }
        ensureActive()
        val currentConnection = preferences.savedConnection()
        if (!preferences.getBoolean(NotificationsEnabled, true) ||
            currentConnection?.url != connection.url || currentConnection.token != connection.token
        ) return@withContext Result.success()

        val previous = preferences.getStringSet(NotifiedItems, null)?.let { notifiedItems ->
            val runAt = preferences.getLong(NotifiedSummary, 0).takeIf { preferences.contains(NotifiedSummary) }
            NotificationHistory(notifiedItems, runAt)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val decision = decideNotifications(previous, items, summaryRunAt, manager.areNotificationsEnabled())
        decision.newItems.forEach {
            val target = notificationTarget("item").buildUpon()
                .appendPath(it.bot).appendPath(it.number.toString()).build()
            manager.post(context, ItemsChannel, target, "判断待ち：${it.bot}『${it.title}』")
        }
        if (decision.remainingItems > 0) {
            manager.post(context, ItemsChannel, notificationTarget("items"), "判断待ち：ほか ${decision.remainingItems} 件")
        }
        decision.newSummaryRunAt?.let {
            val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(it))
            manager.post(context, SummaryChannel, notificationTarget("summary"), "${time}のまとめが届きました")
        }
        val editor = preferences.edit().putStringSet(NotifiedItems, decision.history.items)
        decision.history.summaryRunAt?.let { editor.putLong(NotifiedSummary, it) }
        editor.apply()
        Result.success()
    }
}

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

private fun NotificationManager.post(context: Context, channel: String, target: Uri, title: String) {
    val intent = Intent(context, MainActivity::class.java)
        .setData(target)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    notify(
        target.toString(),
        0,
        Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentText(title)
            .setStyle(Notification.BigTextStyle().bigText(title))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build(),
    )
}
