package com.termux.app.nobi;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification reader (v10, READ-ONLY).
 *
 * This is not an auto-replier and it sends nothing anywhere: every posted
 * notification is read locally, stored only in memory, and shown inside
 * the app on the Notifications screen. The app's own notifications are
 * never recorded, so the listener cannot feed on itself.
 *
 * Performance: the listener runs on the system's binder thread, does no
 * disk I/O, no network, and no icon loading (rows show text only) — a
 * posted notification costs one small object allocation.
 */
public class NobiNotificationListenerService extends NotificationListenerService {

    /** Broadcast sent when a new record arrives; the screen refreshes on it. */
    public static final String ACTION_UPDATED =
            "com.termux.app.nobi.NOTIFICATIONS_UPDATED";

    /** True when the user has enabled this listener in system settings. */
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null || enabled.isEmpty()) return false;
        String mine = new ComponentName(context, NobiNotificationListenerService.class)
                .flattenToString();
        return enabled.contains(mine);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        CharSequence title = extras != null
                ? extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence text = extras != null
                ? extras.getCharSequence(Notification.EXTRA_TEXT) : null;
        String titleText = title != null ? title.toString() : "";
        String bodyText = text != null ? text.toString() : "";
        if (titleText.isEmpty() && bodyText.isEmpty()) return;

        String appName = pkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) appName = label.toString();
        } catch (Exception ignored) {
            // Fall back to the package name; the record is still useful.
        }

        NobiNotificationStore.add(new NobiNotificationStore.Record(
                appName, pkg, titleText, bodyText, sbn.getPostTime()));
        sendBroadcast(new Intent(ACTION_UPDATED));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Read-only history: a dismissed notification stays visible here
        // until the user clears the list. Nothing is acted on.
    }
}
