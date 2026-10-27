package com.termux.app.nobi;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.termux.R;

/**
 * Permissions hub (v10).
 *
 * One screen for every permission the app may need, each with what it is
 * for and its live status. Nothing is requested in a batch at first run:
 * tapping a row requests only that permission, and a denied permission
 * simply leaves its feature degraded — the screen stays usable and the
 * row stays tappable for a later grant.
 *
 * Deliberately NOT here (v10 scope): Device Admin and Accessibility.
 * Neither was asked for, and both are high-risk grants.
 *
 * Performance: status checks are cheap system calls made only in
 * onResume and after a request returns — there is no polling.
 */
public class NobiPermissionsActivity extends AppCompatActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 101;
    private static final int REQUEST_STORAGE = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nobi_permissions);

        MaterialToolbar toolbar = findViewById(R.id.nobiPermissionsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        findViewById(R.id.nobiPermissionsViewNotifications).setOnClickListener(v ->
                startActivity(new Intent(this, NobiNotificationsActivity.class)));

        findViewById(R.id.nobiPermRowNotifAccess).setOnClickListener(v -> {
            if (NobiNotificationListenerService.isEnabled(this)) {
                startActivity(new Intent(this, NobiNotificationsActivity.class));
            } else {
                openSettingsSafely(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        findViewById(R.id.nobiPermRowOverlay).setOnClickListener(v -> {
            if (Settings.canDrawOverlays(this)) {
                showAlreadyGranted();
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                openSettingsSafely(intent);
            }
        });
        findViewById(R.id.nobiPermRowAllFiles).setOnClickListener(v -> {
            if (isAllFilesGranted()) {
                showAlreadyGranted();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                openSettingsSafely(intent);
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE);
            }
        });
        findViewById(R.id.nobiPermRowInstall).setOnClickListener(v -> {
            if (getPackageManager().canRequestPackageInstalls()) {
                showAlreadyGranted();
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                openSettingsSafely(intent);
            }
        });
        findViewById(R.id.nobiPermRowBattery).setOnClickListener(v -> {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                showAlreadyGranted();
            } else {
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                openSettingsSafely(intent);
            }
        });
        findViewById(R.id.nobiPermRowPostNotif).setOnClickListener(v -> {
            if (isPostNotificationsGranted()) {
                showAlreadyGranted();
            } else if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
            } else {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                openSettingsSafely(intent);
            }
        });
        findViewById(R.id.nobiPermRowStorage).setOnClickListener(v -> {
            if (isStorageGranted()) {
                showAlreadyGranted();
            } else if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO}, REQUEST_STORAGE);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatuses();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Denied = degraded feature only; the row stays tappable for retry.
        refreshStatuses();
    }

    private void refreshStatuses() {
        setStatus(R.id.nobiPermStatusNotifAccess,
                NobiNotificationListenerService.isEnabled(this));
        setStatus(R.id.nobiPermStatusOverlay, Settings.canDrawOverlays(this));
        setStatus(R.id.nobiPermStatusAllFiles, isAllFilesGranted());
        setStatus(R.id.nobiPermStatusInstall,
                getPackageManager().canRequestPackageInstalls());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        setStatus(R.id.nobiPermStatusBattery,
                pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
        setStatus(R.id.nobiPermStatusPostNotif, isPostNotificationsGranted());
        setStatus(R.id.nobiPermStatusStorage, isStorageGranted());
    }

    private void setStatus(int viewId, boolean granted) {
        TextView status = findViewById(viewId);
        status.setText(granted ? R.string.nobi_perm_granted
                : R.string.nobi_perm_not_granted);
    }

    private boolean isAllFilesGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isPostNotificationsGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private boolean isStorageGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openSettingsSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.nobi_perm_open_settings_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showAlreadyGranted() {
        Toast.makeText(this, R.string.nobi_perm_already_granted,
                Toast.LENGTH_SHORT).show();
    }
}
