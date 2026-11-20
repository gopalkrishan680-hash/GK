package com.gk.dotlauncher.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.RemoteViews
import com.gk.dotlauncher.R

/** Ye do widgets kisi bhi launcher ke home screen par lagaye ja sakte hain. */
class DotClockAppWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val rv = RemoteViews(context.packageName, R.layout.appwidget_clock)
        rv.setOnClickPendingIntent(R.id.root, pi(context, Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        mgr.updateAppWidget(ids, rv)
    }
}

class DotDateAppWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val rv = RemoteViews(context.packageName, R.layout.appwidget_date)
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
        rv.setOnClickPendingIntent(R.id.root, pi(context, Intent(Intent.ACTION_VIEW, uri)))
        mgr.updateAppWidget(ids, rv)
    }
}

private fun pi(c: Context, i: Intent): PendingIntent =
    PendingIntent.getActivity(c, i.action.hashCode(), i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
