package dev.okumura.orcaremote

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val RefreshBoard = "dev.okumura.orcaremote.REFRESH_BOARD"
private const val SelectTab = "dev.okumura.orcaremote.SELECT_BOARD_TAB"
private const val TabExtra = "tab"

private fun Context.boardIds() = AppWidgetManager.getInstance(this)
    .getAppWidgetIds(ComponentName(this, SecretaryBoardWidgetProvider::class.java))

// Widget removal can arrive while a fetch is in flight. Check placement again at the write.
@Synchronized
private fun Context.replaceBoardCache(source: Connection?, json: String?) {
    val prefs = connectionPreferences()
    if (source != null && !source.matches(prefs.savedConnection())) return
    val keep = if (json != null && boardIds().isNotEmpty()) source?.boardCacheKey() else null
    val editor = prefs.edit()
    staleBoardKeys(prefs.all.keys, keep).forEach(editor::remove)
    if (keep != null) editor.putString(keep, json)
    editor.apply()
}

internal fun Context.boardSnapshot(connection: Connection?): BoardSnapshot? =
    connection?.let { cachedBoard(connectionPreferences().getString(it.boardCacheKey(), null)) }

class SecretaryBoardWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RefreshBoard -> update(context)
            SelectTab -> {
                context.connectionPreferences().saveBoardTab(boardTab(intent.getStringExtra(TabExtra)))
                renderCached(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    override fun onDisabled(context: Context) = context.replaceBoardCache(null, null)

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
            val connection = context.connectionPreferences().savedConnection()
            if (connection != null) {
                try {
                    val snapshot = OrcaApi(connection).board()
                    context.replaceBoardCache(connection, snapshot?.let { apiJson.encodeToString(it) })
                } catch (failure: ApiFailure) {
                    if (failure.error == ApiError.UNAUTHORIZED) context.replaceBoardCache(connection, null)
                }
            }
            renderCached(context)
        }

        @Suppress("DEPRECATION")
        internal fun renderCached(context: Context) {
            val ids = context.boardIds()
            if (ids.isEmpty()) return
            val connection = context.connectionPreferences().savedConnection()
            val board = context.boardSnapshot(connection)
            val views = RemoteViews(context.packageName, R.layout.widget_secretary_board)
            views.setTextViewText(R.id.board_time, board?.updatedAt?.let { localClock(it) }.orEmpty())
            views.setOnClickPendingIntent(R.id.board_header, open(context, HomeLink))
            views.setOnClickPendingIntent(R.id.board_memo, open(context, "orcaremote://quick-memo?inbox=1"))
            views.setOnClickPendingIntent(R.id.board_voice, open(context, "orcaremote://quick-memo?inbox=1&voice=1"))
            val refresh = Intent(context, SecretaryBoardWidgetProvider::class.java).setAction(RefreshBoard)
            views.setOnClickPendingIntent(R.id.board_refresh, PendingIntent.getBroadcast(context, 0, refresh, PendingIntent.FLAG_IMMUTABLE))
            val tab = context.connectionPreferences().boardTab()
            val rows = board?.rows(tab, LocalDate.now()).orEmpty()
            for ((target, id, label, count) in listOf(
                TabButton(BoardTab.TASKS, R.id.board_tab_tasks, "タスク", board?.taskRows(LocalDate.now())?.size ?: 0),
                TabButton(BoardTab.PROGRESS, R.id.board_tab_progress, "進捗", board?.progressRows()?.size ?: 0),
            )) {
                val text = if (count > 0) "$label $count" else label
                val selected = target == tab
                views.setTextViewText(id, if (selected) SpannableString(text).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
                } else text)
                views.setInt(id, "setBackgroundResource", if (selected) R.drawable.board_tab_selected else R.drawable.board_tab)
                views.setContentDescription(id, "$text${if (selected) "（表示中）" else "を表示"}")
                views.setOnClickPendingIntent(id, selectTab(context, target))
            }
            val banner = board?.workingBanner()?.takeIf { tab == BoardTab.TASKS }
            views.setViewVisibility(R.id.board_working, if (banner != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.board_working, banner.orEmpty())
            views.setOnClickPendingIntent(R.id.board_working, selectTab(context, BoardTab.PROGRESS))
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
            val pending = board?.pendingCount ?: 0
            // Shown only when needed so the task list keeps the space.
            views.setViewVisibility(R.id.board_pending, if (pending > 0) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.board_pending, "判断待ち ${pending}件 ›")
            views.setOnClickPendingIntent(R.id.board_pending, open(context, "orcaremote://items"))
            val empty = when {
                connection == null -> "アプリで Mac mini に接続してください"
                board == null -> "ボードを取得できません。接続先を確認してください"
                tab == BoardTab.PROGRESS -> "稼働中のセッションや進行中の項目はありません"
                board.memoTasks == null -> "タスクリストを読めませんでした"
                else -> "タスクリストは空です（上の「メモ」から追加）"
            }
            views.setTextViewText(R.id.board_empty, empty)
            views.setOnClickPendingIntent(R.id.board_empty, open(context, if (tab == BoardTab.PROGRESS) HomeLink else TasksLink))
            views.setEmptyView(R.id.board_list, R.id.board_empty)
            if (Build.VERSION.SDK_INT >= 35) {
                // Publish rows and header together. A separate legacy invalidation can republish an older header.
                val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
                rows.forEachIndexed { index, row -> items.addItem(index.toLong(), row.toViews(context)) }
                views.setRemoteAdapter(R.id.board_list, items.build())
            } else {
                views.setRemoteAdapter(R.id.board_list, Intent(context, SecretaryBoardWidgetService::class.java))
            }
            // Collection items may only fill in one template, so a check (background write) and a body tap
            // (open the app) both go to WidgetTaskActivity, which needs no broadcast-started activity.
            views.setPendingIntentTemplate(R.id.board_list, PendingIntent.getActivity(context, 1,
                Intent(context, WidgetTaskActivity::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(ids, views)
            if (Build.VERSION.SDK_INT < 35) manager.notifyAppWidgetViewDataChanged(ids, R.id.board_list)
        }

        // Explicit, and a request code per tab, so the two tabs keep separate extras.
        private fun selectTab(context: Context, tab: BoardTab): PendingIntent = PendingIntent.getBroadcast(context, 10 + tab.ordinal,
            Intent(context, SecretaryBoardWidgetProvider::class.java).setAction(SelectTab).putExtra(TabExtra, tab.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Request code 0 is shared; each button's own data URI keeps its PendingIntent distinct.
        private fun open(context: Context, link: String? = null): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).setData(link?.let(Uri::parse))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

private data class TabButton(val tab: BoardTab, val id: Int, val label: String, val count: Int)

/** Applies the check at once in the widget, then completes the task on the Mac in the background. */
internal fun Context.completeTaskFromWidget(id: String) {
    val connection = connectionPreferences().savedConnection() ?: return
    boardSnapshot(connection)?.let { board ->
        replaceBoardCache(connection, apiJson.encodeToString(board.copy(memoTasks = board.memoTasks?.filter { it.id != id })))
        SecretaryBoardWidgetProvider.renderCached(this)
    }
    WorkManager.getInstance(this).enqueueUniqueWork("task-done:$id", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<TaskDoneWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build())
}

class TaskDoneWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val connection = applicationContext.connectionPreferences().savedConnection()
        if (connection != null) {
            try {
                OrcaApi(connection).setTaskDone(id, true)
            } catch (failure: ApiFailure) {
                // Marking done is idempotent, so an uncertain write can be repeated.
                val retry = failure.error in setOf(ApiError.CONNECTION, ApiError.TIMEOUT, ApiError.UNKNOWN_RESULT)
                if (retry && runAttemptCount < 3) return Result.retry()
            }
        }
        // Brings the row back if the write failed for good.
        SecretaryBoardWidgetProvider.refresh(applicationContext)
        return Result.success()
    }
}

/** Invisible target of widget rows. Activity starts from a widget tap are allowed; from a broadcast they are not. */
class WidgetTaskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = taskDoneId(intent.dataString)
        if (id != null) applicationContext.completeTaskFromWidget(id)
        else startActivity(Intent(this, MainActivity::class.java).setData(Uri.parse(intent.dataString?.takeIf(::appLink) ?: TasksLink))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

class SecretaryBoardWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        SecretaryBoardWidgetProvider.refresh(applicationContext)
        return Result.success()
    }
}
