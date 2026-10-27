package com.termux.app.nobi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.termux.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Notifications screen (v10, read-only).
 *
 * Shows what the notification listener captured, newest first. If access
 * is off, the screen explains why it is empty and offers the system
 * settings page — it never pretends to show data it cannot read.
 *
 * Performance: rows are text only (no per-app icon loading, no images),
 * at most MAX_SHOWN rows are inflated, and only while this screen is
 * open. Nothing is re-read from disk on refresh.
 */
public class NobiNotificationsActivity extends AppCompatActivity {

    private LinearLayout emptyState;
    private TextView emptyText;
    private Button grantButton;
    private LinearLayout listContainer;
    private LinearLayout footer;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nobi_notifications);

        MaterialToolbar toolbar = findViewById(R.id.nobiNotificationsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        emptyState = findViewById(R.id.nobiNotificationsEmpty);
        emptyText = findViewById(R.id.nobiNotificationsEmptyText);
        grantButton = findViewById(R.id.nobiNotificationsGrantButton);
        listContainer = findViewById(R.id.nobiNotificationsList);
        footer = findViewById(R.id.nobiNotificationsFooter);

        grantButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, R.string.nobi_perm_open_settings_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });

        Button clearButton = findViewById(R.id.nobiNotificationsClearButton);
        clearButton.setOnClickListener(v -> {
            NobiNotificationStore.clear();
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, updateReceiver,
                new IntentFilter(NobiNotificationListenerService.ACTION_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        refresh();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    private void refresh() {
        if (!NobiNotificationListenerService.isEnabled(this)) {
            emptyState.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.nobi_notifications_empty_no_access);
            grantButton.setVisibility(View.VISIBLE);
            listContainer.setVisibility(View.GONE);
            footer.setVisibility(View.GONE);
            return;
        }
        List<NobiNotificationStore.Record> records = NobiNotificationStore.snapshot();
        if (records.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.nobi_notifications_empty_none);
            grantButton.setVisibility(View.GONE);
            listContainer.setVisibility(View.GONE);
            footer.setVisibility(View.GONE);
            return;
        }
        emptyState.setVisibility(View.GONE);
        listContainer.setVisibility(View.VISIBLE);
        footer.setVisibility(View.VISIBLE);
        listContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);
        int shown = 0;
        for (NobiNotificationStore.Record record : records) {
            if (shown >= NobiNotificationStore.MAX_SHOWN) break;
            View row = inflater.inflate(R.layout.item_nobi_notification,
                    listContainer, false);
            ((TextView) row.findViewById(R.id.nobiNotificationApp))
                    .setText(record.appName);
            ((TextView) row.findViewById(R.id.nobiNotificationTime))
                    .setText(timeFormat.format(new Date(record.timeMillis)));
            TextView titleView = row.findViewById(R.id.nobiNotificationTitle);
            if (record.title.isEmpty()) {
                titleView.setVisibility(View.GONE);
            } else {
                titleView.setText(record.title);
            }
            TextView textView = row.findViewById(R.id.nobiNotificationText);
            if (record.text.isEmpty()) {
                textView.setVisibility(View.GONE);
            } else {
                textView.setText(record.text);
            }
            listContainer.addView(row);
            shown++;
        }
    }
}
