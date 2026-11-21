package dev.okumura.orcaremote

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val ReplyKey = "reply"
private const val ChoiceKey = "choice"
private const val NoticeKey = "noticeId"
internal const val NotificationConnectionId = "notificationConnectionId"

internal data class ResponseAction(val label: String, val inline: Boolean)
internal fun responseActions(options: List<String>) =
    options.take(2).filter(String::isNotBlank).map { ResponseAction(it, false) } + ResponseAction("返信", true)

internal fun responseText(inline: Boolean, choice: String?, remoteResult: CharSequence?): String? =
    (if (inline) remoteResult?.toString()?.trim() else choice)?.takeIf { it.isNotBlank() }

internal fun Notification.Builder.addResponseActions(context: Context, target: Uri, options: List<String>) {
    val noticeId = UUID.randomUUID().toString()
    addExtras(Bundle().apply { putString(NoticeKey, noticeId) })
    responseActions(options).forEachIndexed { index, action ->
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(if (action.inline) ReplyKey else "choice/$index")
            .setData(target.buildUpon().fragment(noticeId).build())
            .putExtra(NotificationConnectionId, context.connectionPreferences().getString(NotificationConnectionId, ""))
        if (!action.inline) intent.putExtra(ChoiceKey, action.label)
        val mutability = if (action.inline) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
        val builder = Notification.Action.Builder(null, action.label, pending)
        if (action.inline) builder.addRemoteInput(RemoteInput.Builder(ReplyKey).setLabel("返信").build())
        if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true)
        addAction(builder.build())
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val noticeId = uri.fragment ?: return
        val target = uri.buildUpon().fragment(null).build()
        if (uri.scheme != NotificationScheme || uri.authority !in setOf("chat", "item") || uri.pathSegments.size != 2) return
        val (bot, id) = uri.pathSegments
        val item = uri.authority == "item"
        val number = id.toLongOrNull()
        if (bot.isBlank() || id.isBlank() || (item && (number == null || number <= 0))) return
        val inline = intent.action == ReplyKey
        val text = responseText(inline, intent.getStringExtra(ChoiceKey),
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ReplyKey)) ?: return
        if (!processing.add(noticeId)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.connectionPreferences()
                val manager = context.getSystemService(NotificationManager::class.java)
                fun current() = manager.activeNotifications.firstOrNull { it.tag == target.toString() &&
                    it.notification.extras.getString(NoticeKey) == noticeId }?.notification
                fun update(notification: Notification, message: String) {
                    manager.post(context, notification.channelId, target,
                        notification.extras.getString(Notification.EXTRA_TITLE).orEmpty(), message) {
                        addExtras(Bundle().apply { putString(NoticeKey, noticeId) })
                        setOnlyAlertOnce(true)
                    }
                }
                val connection = notificationSettingsMutex.withLock {
                    val saved = prefs.enabledConnection() ?: return@launch
                    if (prefs.getString(NotificationConnectionId, "") != intent.getStringExtra(NotificationConnectionId)) return@launch
                    val notification = current()?.takeUnless { it.actions.isNullOrEmpty() } ?: return@launch
                    update(notification, "送信中…")
                    saved
                }
                val api = OrcaApi(connection)
                // Broadcasts have a short execution budget. A timed-out write must never be replayed.
                val error = try {
                    val sent = withTimeoutOrNull(8_000) {
                        if (item) api.answer(bot, number!!, text) else api.sendMessage(bot, id, text)
                        true
                    }
                    if (sent == true) null else ApiError.UNKNOWN_RESULT
                } catch (failure: ApiFailure) { failure.error }
                notificationSettingsMutex.withLock {
                    if (!connection.matches(prefs.enabledConnection())) return@launch
                    if (error == null) {
                        if (item) WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<NotificationWorker>().build())
                        else withContext(Dispatchers.Main) { context.chatMetaStore(connection.url.toString()).markRead(bot, id) }
                    }
                    val result = error?.let { "${it.text} タップして確認してください。" }
                        ?: if (item) "回答を送りました：$text" else "送信しました：$text"
                    current()?.let { update(it, result) }
                }
            } finally {
                processing.remove(noticeId)
                pending.finish()
            }
        }
    }

    private companion object {
        val processing = ConcurrentHashMap.newKeySet<String>()
    }
}
