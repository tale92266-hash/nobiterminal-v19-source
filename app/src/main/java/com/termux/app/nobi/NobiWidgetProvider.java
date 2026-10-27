package com.termux.app.nobi;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.termux.app.TermuxActivity;

/**
 * v7 (32): home-screen widget — one tap opens a terminal session, the
 * other opens Nobi Code. No configuration needed; static buttons only.
 */
public class NobiWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(),
                com.termux.R.layout.nobi_widget);

            Intent terminalIntent = new Intent(context, TermuxActivity.class);
            terminalIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent terminalPending = PendingIntent.getActivity(context, 0,
                terminalIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(com.termux.R.id.nobi_widget_terminal, terminalPending);

            Intent editorIntent = new Intent();
            editorIntent.setClassName(context.getPackageName(),
                "com.nobidev.nobicode.MainActivity");
            editorIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent editorPending = PendingIntent.getActivity(context, 1,
                editorIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(com.termux.R.id.nobi_widget_editor, editorPending);

            manager.updateAppWidget(widgetId, views);
        }
    }
}
