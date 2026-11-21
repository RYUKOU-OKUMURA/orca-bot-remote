package dev.okumura.orcaremote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class QuickMemoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_memo)
        fun link(id: Int, uri: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(id, PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        }
        link(R.id.memo, "orcaremote://quick-memo")
        link(R.id.voice, "orcaremote://quick-memo?voice=1")
        link(R.id.pending, "orcaremote://items")
        val count = context.connectionPreferences().getInt("widgetPending", -1)
        views.setTextViewText(R.id.pending, if (count >= 0) "判断待ち\n${count}件" else "判断待ち")
        manager.updateAppWidget(ids, views)
    }

    companion object {
        fun updateCount(context: Context, count: Int) {
            val prefs = context.connectionPreferences()
            if (prefs.getInt("widgetPending", -1) == count) return
            prefs.edit().putInt("widgetPending", count).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickMemoWidgetProvider::class.java))
            if (ids.isNotEmpty()) QuickMemoWidgetProvider().onUpdate(context, manager, ids)
        }
    }
}
