package dev.okumura.orcaremote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8

private const val RefreshBoard = "dev.okumura.orcaremote.REFRESH_BOARD"

private fun Context.boardIds() = AppWidgetManager.getInstance(this)
    .getAppWidgetIds(ComponentName(this, SecretaryBoardWidgetProvider::class.java))

internal fun Connection.boardCacheKey() = "board:" + ("${url}\n$token").encodeUtf8().sha256().hex()

internal fun Context.boardSnapshot(connection: Connection?): BoardSnapshot? =
    connection?.let { cachedBoard(connectionPreferences().getString(it.boardCacheKey(), null)) }

class SecretaryBoardWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == RefreshBoard) update(context) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    private fun update(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                renderCached(context)
                // Broadcasts have a short deadline. WorkManager can finish a slow Mac request.
                if (withTimeoutOrNull(7_000) { refresh(context) } == null) {
                    WorkManager.getInstance(context).enqueueUniqueWork("refresh-secretary-board", ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<SecretaryBoardWorker>().build())
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val refreshMutex = Mutex()

        internal fun requestRefresh(context: Context) {
            if (context.boardIds().isNotEmpty()) {
                context.sendBroadcast(Intent(context, SecretaryBoardWidgetProvider::class.java).setAction(RefreshBoard))
            }
        }

        internal suspend fun refresh(context: Context) = refreshMutex.withLock {
            if (context.boardIds().isEmpty()) return@withLock
            val prefs = context.connectionPreferences()
            val connection = prefs.savedConnection()
            if (connection != null) {
                val key = connection.boardCacheKey()
                try {
                    val snapshot = OrcaApi(connection).board()
                    prefs.edit().putString(key, snapshot?.let { apiJson.encodeToString(it) }).apply()
                } catch (failure: ApiFailure) {
                    if (failure.error == ApiError.UNAUTHORIZED) prefs.edit().remove(key).apply()
                }
            }
            renderCached(context)
        }

        @Suppress("DEPRECATION")
        private fun renderCached(context: Context) {
            val ids = context.boardIds()
            if (ids.isEmpty()) return
            val connection = context.connectionPreferences().savedConnection()
            val board = context.boardSnapshot(connection)
            val views = RemoteViews(context.packageName, R.layout.widget_secretary_board)
            views.setTextViewText(R.id.board_time, board?.updatedAt?.let { localClock(it) }.orEmpty())
            views.setOnClickPendingIntent(R.id.board_memo, open(context, "orcaremote://quick-memo?inbox=1"))
            views.setOnClickPendingIntent(R.id.board_voice, open(context, "orcaremote://quick-memo?inbox=1&voice=1"))
            val refresh = Intent(context, SecretaryBoardWidgetProvider::class.java).setAction(RefreshBoard)
            views.setOnClickPendingIntent(R.id.board_refresh, PendingIntent.getBroadcast(context, 0, refresh, PendingIntent.FLAG_IMMUTABLE))
            views.removeAllViews(R.id.board_bots)
            board?.bots?.take(6)?.forEach { bot ->
                val tile = RemoteViews(context.packageName, R.layout.widget_board_bot)
                tile.setImageViewResource(R.id.bot_icon, boardIcon(bot.avatar))
                tile.setTextViewText(R.id.bot_name, bot.name)
                tile.setViewVisibility(R.id.bot_working, if (bot.working) View.VISIBLE else View.GONE)
                tile.setViewVisibility(R.id.bot_badge, if (bot.needsYou > 0) View.VISIBLE else View.GONE)
                tile.setTextViewText(R.id.bot_badge, bot.needsYou.toString())
                tile.setContentDescription(R.id.bot_badge, "判断待ち ${bot.needsYou} 件")
                tile.setOnClickPendingIntent(R.id.board_bot, open(context, boardLink(bot.name)))
                views.addView(R.id.board_bots, tile)
            }
            val empty = when {
                connection == null -> "アプリで Mac mini に接続してください"
                board == null -> "ボードを取得できません。接続先を確認してください"
                else -> "進行中の作業・タスクはありません"
            }
            views.setTextViewText(R.id.board_empty, empty)
            views.setOnClickPendingIntent(R.id.board_empty, open(context))
            views.setEmptyView(R.id.board_list, R.id.board_empty)
            if (Build.VERSION.SDK_INT >= 35) {
                // Publish rows and header together. A separate legacy invalidation can republish an older header.
                val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
                board?.rows()?.forEachIndexed { index, row -> items.addItem(index.toLong(), row.toViews(context)) }
                views.setRemoteAdapter(R.id.board_list, items.build())
            } else {
                views.setRemoteAdapter(R.id.board_list, Intent(context, SecretaryBoardWidgetService::class.java))
            }
            // Only the URI is filled in by the launcher; the activity component stays fixed.
            val template = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setPendingIntentTemplate(R.id.board_list, PendingIntent.getActivity(context, 1, template,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(ids, views)
            if (Build.VERSION.SDK_INT < 35) manager.notifyAppWidgetViewDataChanged(ids, R.id.board_list)
        }

        private fun open(context: Context, link: String? = null): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).setData(link?.let(Uri::parse))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

class SecretaryBoardWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        SecretaryBoardWidgetProvider.refresh(applicationContext)
        return Result.success()
    }
}
