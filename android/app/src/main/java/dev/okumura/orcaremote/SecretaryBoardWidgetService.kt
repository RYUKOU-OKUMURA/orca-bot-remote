package dev.okumura.orcaremote

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate

class SecretaryBoardWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = BoardFactory(applicationContext)
}

private class BoardFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows = emptyList<TaskRow>()

    override fun onCreate() = onDataSetChanged()
    override fun onDataSetChanged() {
        val prefs = context.connectionPreferences()
        rows = context.boardSnapshot(prefs.savedConnection())?.rows(prefs.boardTab(), LocalDate.now()).orEmpty()
    }
    override fun onDestroy() { rows = emptyList() }
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)?.toViews(context)
}

internal fun TaskRow.toViews(context: Context) = RemoteViews(context.packageName, R.layout.widget_board_row).apply {
    setTextViewText(R.id.row_title, title)
    setViewVisibility(R.id.row_icon, if (bot == null) View.GONE else View.VISIBLE)
    if (bot != null) setImageViewResource(R.id.row_icon, boardIcon(avatar))
    setTextViewText(R.id.row_subtitle, if (urgent) SpannableString("⚠ $detail").apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
    } else detail)
    setViewVisibility(R.id.row_subtitle, if (detail.isEmpty()) View.GONE else View.VISIBLE)
    // Launchers reuse row views, so set the check both ways.
    setViewVisibility(R.id.row_check, if (id == null) View.GONE else View.VISIBLE)
    if (id != null) {
        setContentDescription(R.id.row_check, "「$title」を完了にする")
        setOnClickFillInIntent(R.id.row_check, Intent().setData(Uri.parse(taskDoneLink(id))))
    }
    setOnClickFillInIntent(R.id.row_body, Intent().setData(Uri.parse(link)))
}
