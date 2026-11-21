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
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    override suspend fun doWork(): Result {
        NotificationSync.sync(applicationContext)
        return Result.success()
    }
}

private suspend fun <T> readOrNull(read: suspend () -> T): T? = try { read() } catch (_: ApiFailure) { null }

internal object NotificationSync {
    // Serialize SSE and periodic snapshots without blocking settings changes during network I/O.
    private val syncMutex = Mutex()

    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        syncMutex.withLock { checkChanges(context) }
    }

    private suspend fun checkChanges(context: Context) = coroutineScope {
        val prefs = context.connectionPreferences()
        val (connection, connectionId) = notificationSettingsMutex.withLock {
            val saved = prefs.enabledConnection() ?: return@coroutineScope
            saved to prefs.getString(NotificationConnectionId, "")
        }
        createNotificationChannels(context)
        val api = OrcaApi(connection)
        val itemsRequest = async { readOrNull { api.items().items } }
        val summaryRequest = async { readOrNull { api.summary() } }
        val botsRequest = async { readOrNull { api.bots().bots } }
        val items = itemsRequest.await()
        val summary = summaryRequest.await()
        val bots = botsRequest.await()
        val previous = prefs.notificationHistory()
        val oldReplies = readNotificationJson<Map<String, ReplyStamp>> {
            prefs.getString(ReplyHistory, null)
        }.orEmpty()
        val replies = bots?.let { decideReplies(oldReplies, it) }
        val manager = context.getSystemService(NotificationManager::class.java)
        fun delivery(channel: String, target: ChatTarget? = null): String? {
            val details = prefs.notificationPrefs()
            val now = LocalTime.now()
            return notificationChannel(channel, details.quietAt(now.hour, now.minute), details.replies, target,
                ActiveChatTracker.current)?.takeIf {
                manager.areNotificationsEnabled() && (manager.getNotificationChannel(it)?.importance ?: 0) > NotificationManager.IMPORTANCE_NONE
            }
        }
        val newItems = decideNotifications(previous, items, null, summaryUpdated = false,
            canNotifyItems = delivery(ItemsChannel) != null).newItems
        val questionsRequest = async {
            newItems.map { item ->
                async { item to readOrNull { api.item(item.bot, item.number).question } }
            }.awaitAll().toMap()
        }
        val chats = replies?.newReplies.orEmpty().filter { delivery(RepliesChannel) != null }.map { bot ->
            async {
                val chat = readOrNull { api.chats(bot.name).chats }?.singleOrNull {
                    it.lastActivityAt == bot.lastActivityAt && it.lastSaid == bot.lastSaid
                }
                bot to ChatTarget(bot.name, chat?.id)
            }
        }.awaitAll()
        val questions = questionsRequest.await()
        notificationSettingsMutex.withLock {
            ensureActive()
            if (!connection.matches(prefs.enabledConnection()) || connectionId != prefs.getString(NotificationConnectionId, "")) return@withLock
            if (items != null) QuickMemoWidgetProvider.updateCount(context, items.count { it.status == "needs_you" })
            val itemChannel = delivery(ItemsChannel)
            val summaryChannel = delivery(SummaryChannel)
            val decision = decideNotifications(previous, items, summary?.summary?.runAt, summaryUpdated = summary != null,
                canNotifyItems = itemChannel != null, canNotifySummary = summaryChannel != null)
            if (decision.history != previous || (replies != null && oldReplies != replies.history)) {
                val editor = prefs.editHistory(decision.history)
                if (replies != null) editor.putString(ReplyHistory, apiJson.encodeToString(replies.history))
                if (!editor.commit()) {
                    prefs.editHistory(previous).putString(ReplyHistory, apiJson.encodeToString(oldReplies)).apply()
                    return@withLock
                }
            }
            decision.newItems.forEach { item ->
                val target = notificationTarget("item", item.bot, item.number.toString())
                val question = questions[item]
                manager.post(context, itemChannel!!, target, "判断待ち：${item.bot}", question?.text?.ifBlank { item.title } ?: item.title) {
                    addResponseActions(context, target, question?.options.orEmpty())
                }
            }
            if (decision.remainingItems > 0) {
                manager.post(context, itemChannel!!, notificationTarget("items"), "判断待ち", "ほか ${decision.remainingItems} 件")
            }
            if (decision.notifySummary) {
                manager.post(context, summaryChannel!!, notificationTarget("summary"), "まとめ", "12:00のまとめが届きました")
            }
            chats.forEach { (bot, chat) ->
                delivery(RepliesChannel, chat)?.let { channel ->
                    val target = chat.chatId?.let { notificationTarget("chat", bot.name, it) }
                        ?: notificationTarget("chats", bot.name)
                    manager.post(context, channel, target, "ボットの返答：${bot.name}", bot.lastSaid.orEmpty()) {
                        if (chat.chatId != null) addResponseActions(context, target, emptyList(), bot.lastActivityAt ?: 0)
                    }
                }
            }
        }
    }
}

internal fun Connection.matches(other: Connection?) = url == other?.url && token == other.token

internal fun SharedPreferences.enabledConnection() =
    if (getBoolean(NotificationsEnabled, true)) savedConnection() else null

private fun SharedPreferences.notificationHistory(): NotificationHistory {
    val items = getStringSet(NotifiedItems, null)
    val runAt = getLong(NotifiedSummary, 0).takeIf { contains(NotifiedSummary) }
    return NotificationHistory(items, runAt, getBoolean(SummaryInitialized, items != null))
}

private fun SharedPreferences.editHistory(history: NotificationHistory): SharedPreferences.Editor =
    edit().putStringSet(NotifiedItems, history.items).putBoolean(SummaryInitialized, history.summaryInitialized).apply {
        val runAt = history.summaryRunAt
        if (runAt == null) remove(NotifiedSummary) else putLong(NotifiedSummary, runAt)
    }

internal fun createNotificationChannels(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannels(
        listOf(
            NotificationChannel(ItemsChannel, "判断待ち", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(RepliesChannel, "ボットの返答", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SummaryChannel, "まとめ", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(QuietChannel, "おやすみ中の通知", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(RealtimeChannel, "リアルタイム接続", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    )
}

internal fun notificationTarget(screen: String, vararg path: String): Uri =
    Uri.Builder().scheme(NotificationScheme).authority(screen).apply { path.forEach(::appendPath) }.build()

internal fun NotificationManager.post(context: Context, channel: String, target: Uri, title: String, text: String,
                                      configure: Notification.Builder.() -> Unit = {}) {
    val intent = Intent(context, MainActivity::class.java)
        .setData(target)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    notify(target.toString(), 0, Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.orca_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setContentIntent(pending)
        .setAutoCancel(true)
        .apply(configure)
        .build())
}
