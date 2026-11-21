package dev.okumura.orcaremote

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class SecretaryBoardWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = BoardFactory(applicationContext)
}

private class BoardFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows = emptyList<BoardRow>()

    override fun onCreate() = onDataSetChanged()
    override fun onDataSetChanged() {
        rows = context.boardSnapshot(context.connectionPreferences().savedConnection())?.rows().orEmpty()
    }
    override fun onDestroy() { rows = emptyList() }
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)?.toViews(context)
}

internal fun BoardRow.toViews(context: Context) = RemoteViews(context.packageName, R.layout.widget_board_row).apply {
    setImageViewResource(R.id.row_icon, icon)
    setTextViewText(R.id.row_title, title)
    setTextViewText(R.id.row_subtitle, subtitle)
    setTextViewText(R.id.row_status, status.label)
    setInt(R.id.row_status, "setBackgroundResource", status.background)
    setTextColor(R.id.row_status, if (status == BoardStatus.NeedsYou) Color.rgb(37, 22, 3) else Color.WHITE)
    setOnClickFillInIntent(R.id.board_row, Intent().setData(Uri.parse(link)))
}
