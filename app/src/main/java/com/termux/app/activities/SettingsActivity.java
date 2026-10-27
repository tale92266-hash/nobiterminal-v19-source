package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.file.FileUtils;
import com.termux.shared.models.ReportInfo;
import com.termux.app.models.UserAction;
import com.termux.app.nobi.NobiTerminalUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v16: Settings always follows the terminal's actual colors (see
        // v16: nobiDesiredNightMode). The mode is always determined, so the
        // v16: old upstream fallback (NightMode property/system) is gone: it
        // v16: is exactly what let Settings drift from the terminal.
        getDelegate().setLocalNightMode(nobiDesiredNightMode());

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        // v17: title + back arrow stay white on the navy toolbar in BOTH
        // v17: modes. The DayNight action-bar overlay would otherwise give
        // v17: dark text/icons in day mode, unreadable on navy.
        androidx.appcompat.widget.Toolbar toolbar = findViewById(com.termux.shared.R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitleTextColor(android.graphics.Color.WHITE);
            android.graphics.drawable.Drawable navIcon = toolbar.getNavigationIcon();
            if (navIcon != null) navIcon.mutate().setTint(android.graphics.Color.WHITE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v16: re-apply the terminal theme if it changed while Settings was open.
        int nobiNightMode = nobiDesiredNightMode();
        if (getDelegate().getLocalNightMode() != nobiNightMode) {
            getDelegate().setLocalNightMode(nobiNightMode);
        }
    }

    /**
     * v16: night mode following the terminal's ACTUAL colors, in every case.
     * v16: w15 only followed the time-theme pref, but a manual theme pick in
     * v16: Nobi Settings writes colors.properties and never touches the
     * v16: nobi_v7 prefs, so Settings kept following the stale time-theme
     * v16: value. Signal order: (1) ~/.termux/colors.properties background
     * v16: luminance (the same file the terminal itself reads, so manual
     * v16: picks, time-theme, and hand edits all agree); (2) no colors file
     * v16: means the "default" theme, whose stock terminal is dark; (3) a
     * v16: missing/unparseable background falls back to the last time-theme
     * v16: value, then the hour rule. Never returns -1, so the caller always
     * v16: sets a mode and Settings cannot drift from the terminal.
     * v16: Only a tiny properties file is read here (no heavy work on the
     * v16: UI thread); a broken file never throws, it just falls back.
     */
    private int nobiDesiredNightMode() {
        java.io.File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
        if (!colorsFile.isFile()) {
            // v16: "default" theme deletes colors.properties; stock terminal is dark.
            return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
        }
        String background = nobiTerminalBackground(colorsFile);
        if (background != null) {
            int luminance = nobiColorLuminance(background);
            if (luminance >= 0) {
                return luminance >= 128 ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
            }
        }
        android.content.SharedPreferences prefs = getSharedPreferences("nobi_v7", MODE_PRIVATE);
        String wanted = prefs.getString("time_theme_applied", "");
        if (wanted == null || wanted.isEmpty()) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            wanted = (hour >= 7 && hour < 19) ? "daylight" : "dark";
        }
        if ("daylight".equals(wanted)) return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
        return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
    }

    /**
     * v16: "background=" value from the terminal colors file, or null when
     * v16: the file cannot be read or has no background key. Never throws: a
     * v16: broken colors file must not break Settings, the caller falls back.
     */
    private String nobiTerminalBackground(java.io.File colorsFile) {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = new java.io.FileInputStream(colorsFile)) {
            props.load(in);
        } catch (Exception e) {
            return null;
        }
        String background = props.getProperty("background");
        return (background == null || background.trim().isEmpty()) ? null : background.trim();
    }

    /**
     * v16: perceived luminance of a #RRGGBB or #AARRGGBB color (0-255), or -1
     * v16: when it cannot be parsed. Threshold 128 in the caller: daylight
     * v16: white (#FFFFFF) is 255, every shipped dark theme is below 60.
     */
    private int nobiColorLuminance(String color) {
        String hex = color.startsWith("#") ? color.substring(1) : color;
        try {
            if (hex.length() == 8) hex = hex.substring(2);
            if (hex.length() != 6) return -1;
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return (2126 * r + 7152 * g + 722 * b) / 10000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            new Thread() {
                @Override
                public void run() {
                    configureTermuxAPIPreference(context);
                    configureTermuxFloatPreference(context);
                    configureTermuxTaskerPreference(context);
                    configureTermuxWidgetPreference(context);
                    configureAboutPreference(context);
                    configureDonatePreference(context);
                    configureNobiBackupPreference(context);
                    configureNobiRestorePreference(context);
                    configureNobiDevSetupPreference(context);
                    configureNobiWakeLockPreference(context);
                    configureNobiAppLockPreference(context);
                    configureNobiStartupPickerPreference(context);
                    configureNobiAnimatedIntroPreference(context);
                    configureNobiLauncherIconPreference(context);
                }
            }.start();
        }

        private void configureTermuxAPIPreference(@NonNull Context context) {
            Preference termuxAPIPreference = findPreference("termux_api");
            if (termuxAPIPreference != null) {
                TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxAPIPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxFloatPreference(@NonNull Context context) {
            Preference termuxFloatPreference = findPreference("termux_float");
            if (termuxFloatPreference != null) {
                TermuxFloatAppSharedPreferences preferences = TermuxFloatAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxFloatPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxTaskerPreference(@NonNull Context context) {
            Preference termuxTaskerPreference = findPreference("termux_tasker");
            if (termuxTaskerPreference != null) {
                TermuxTaskerAppSharedPreferences preferences = TermuxTaskerAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxTaskerPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxWidgetPreference(@NonNull Context context) {
            Preference termuxWidgetPreference = findPreference("termux_widget");
            if (termuxWidgetPreference != null) {
                TermuxWidgetAppSharedPreferences preferences = TermuxWidgetAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxWidgetPreference.setVisible(preferences != null);
            }
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = "About";

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));
                            aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context));

                            String userActionName = UserAction.ABOUT.getName();

                            ReportInfo reportInfo = new ReportInfo(userActionName,
                                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title);
                            reportInfo.setReportString(aboutString.toString());
                            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));

                            ReportActivity.startReportActivity(context, reportInfo);
                        }
                    }.start();

                    return true;
                });
            }
        }

        private void configureDonatePreference(@NonNull Context context) {
            Preference donatePreference = findPreference("donate");
            if (donatePreference != null) {
                String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
                if (signingCertificateSHA256Digest != null) {
                    // If APK is a Google Playstore release, then do not show the donation link
                    // since Termux isn't exempted from the playstore policy donation links restriction
                    // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                    String apkRelease = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest);
                    if (apkRelease == null || apkRelease.equals(TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                        donatePreference.setVisible(false);
                        return;
                    } else {
                        donatePreference.setVisible(true);
                    }
                }

                donatePreference.setOnPreferenceClickListener(preference -> {
                    ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL);
                    return true;
                });
            }
        }

        private void configureNobiBackupPreference(@NonNull Context context) {
            Preference backupPreference = findPreference("nobi_backup");
            if (backupPreference != null) {
                backupPreference.setOnPreferenceClickListener(preference -> {
                    NobiTerminalUtils.runSetupScriptInTerminal(context, "nobi-backup", "Backup My Setup");
                    return true;
                });
            }
        }

        private void configureNobiRestorePreference(@NonNull Context context) {
            Preference restorePreference = findPreference("nobi_restore");
            if (restorePreference != null) {
                restorePreference.setOnPreferenceClickListener(preference -> {
                    NobiTerminalUtils.runSetupScriptInTerminal(context, "nobi-restore", "Restore My Setup");
                    return true;
                });
            }
        }

        private void configureNobiDevSetupPreference(@NonNull Context context) {
            Preference devSetupPreference = findPreference("nobi_dev_setup");
            if (devSetupPreference != null) {
                devSetupPreference.setOnPreferenceClickListener(preference -> {
                    NobiTerminalUtils.runSetupScriptInTerminal(context, "nobi-dev-setup", "Dev Ready Setup");
                    return true;
                });
            }
        }

        // v6 (16): persistent wake lock — the checkbox persists itself;
        // the change is forwarded to the service, which already knows how
        // to acquire/release wake locks for its notification actions.
        private void configureNobiWakeLockPreference(@NonNull Context context) {
            CheckBoxPreference wakeLockPreference = findPreference("nobi_wake_lock");
            if (wakeLockPreference != null) {
                wakeLockPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    Intent intent = new Intent(context, TermuxService.class);
                    intent.setAction((Boolean) newValue
                        ? TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_LOCK
                        : TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_UNLOCK);
                    context.startService(intent);
                    return true;
                });
            }
        }

        // v6 (13): app lock PIN setup/change/remove. Only the SHA-256 hash
        // is stored, never the PIN itself.
        private void configureNobiAppLockPreference(@NonNull Context context) {
            Preference appLockPreference = findPreference("nobi_app_lock");
            if (appLockPreference != null) {
                appLockPreference.setOnPreferenceClickListener(preference -> {
                    showNobiAppLockDialog(context);
                    return true;
                });
            }
        }

        // v7 (65/70): these checkboxes live in the settings store, but
        // TermuxActivity reads "nobi_v7" — mirror both ways on configure.
        private void configureNobiStartupPickerPreference(@NonNull Context context) {
            CheckBoxPreference pickerPreference = findPreference("nobi_startup_picker");
            if (pickerPreference != null) {
                SharedPreferences v7 = context.getSharedPreferences("nobi_v7", Context.MODE_PRIVATE);
                pickerPreference.setChecked(v7.getBoolean("startup_picker", true));
                pickerPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    v7.edit().putBoolean("startup_picker", (Boolean) newValue).apply();
                    return true;
                });
            }
        }

        private void configureNobiAnimatedIntroPreference(@NonNull Context context) {
            CheckBoxPreference introPreference = findPreference("nobi_animated_intro");
            if (introPreference != null) {
                SharedPreferences v7 = context.getSharedPreferences("nobi_v7", Context.MODE_PRIVATE);
                introPreference.setChecked(v7.getBoolean("animated_intro", true));
                introPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    v7.edit().putBoolean("animated_intro", (Boolean) newValue).apply();
                    return true;
                });
            }
        }

        // v7 (69): launcher icon switch — enable the chosen alias FIRST,
        // then disable the others, so there is never a no-launcher gap.
        private void configureNobiLauncherIconPreference(@NonNull Context context) {
            ListPreference iconPreference = findPreference("nobi_launcher_icon");
            if (iconPreference == null) return;
            android.content.pm.PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            String[] aliases = new String[]{
                pkg + ".app.TermuxLauncherDefault",
                pkg + ".app.TermuxLauncherViolet",
                pkg + ".app.TermuxLauncherMono"};
            String[] values = new String[]{"default", "violet", "mono"};
            String current = "default";
            for (int i = 1; i < aliases.length; i++) {
                try {
                    android.content.ComponentName cn = new android.content.ComponentName(pkg, aliases[i]);
                    if (pm.getComponentEnabledSetting(cn)
                        == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                        current = values[i];
                    }
                } catch (Exception ignored) {
                }
            }
            iconPreference.setValue(current);
            iconPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String chosen = (String) newValue;
                int chosenIndex = 0;
                for (int i = 0; i < values.length; i++) if (values[i].equals(chosen)) chosenIndex = i;
                try {
                    android.content.ComponentName chosenCn =
                        new android.content.ComponentName(pkg, aliases[chosenIndex]);
                    pm.setComponentEnabledSetting(chosenCn,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
                    for (int i = 0; i < aliases.length; i++) {
                        if (i == chosenIndex) continue;
                        pm.setComponentEnabledSetting(
                            new android.content.ComponentName(pkg, aliases[i]),
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP);
                    }
                    context.getSharedPreferences("nobi_v7", Context.MODE_PRIVATE)
                        .edit().putString("launcher_icon", chosen).apply();
                    Toast.makeText(context, "Launcher icon changed — it may take a moment to appear",
                        Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(context, "Could not change the launcher icon",
                        Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        private void showNobiAppLockDialog(@NonNull Context context) {
            SharedPreferences prefs = context.getSharedPreferences("nobi_applock", Context.MODE_PRIVATE);
            String existing = prefs.getString("pin_hash", null);
            if (existing == null) {
                showNobiAppLockSetDialog(context, prefs);
                return;
            }
            final EditText input = nobiPinInput(context);
            new AlertDialog.Builder(context)
                .setTitle(R.string.nobi_app_lock_current_title)
                .setView(input)
                .setPositiveButton(R.string.nobi_unlock, (dialog, which) -> {
                    if (existing.equals(TermuxActivity.nobiSha256(input.getText().toString()))) {
                        new AlertDialog.Builder(context)
                            .setTitle(R.string.nobi_app_lock_preference_title)
                            .setItems(new String[]{
                                context.getString(R.string.nobi_app_lock_change_pin),
                                context.getString(R.string.nobi_app_lock_remove)}, (d, c) -> {
                                if (c == 0) {
                                    showNobiAppLockSetDialog(context, prefs);
                                } else {
                                    prefs.edit().remove("pin_hash").apply();
                                    Toast.makeText(context, R.string.nobi_app_lock_removed,
                                        Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    } else {
                        Toast.makeText(context, R.string.nobi_wrong_pin,
                            Toast.LENGTH_SHORT).show();
                        showNobiAppLockDialog(context);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }

        private void showNobiAppLockSetDialog(@NonNull Context context, SharedPreferences prefs) {
            final EditText pinInput = nobiPinInput(context);
            pinInput.setHint(R.string.nobi_app_lock_pin_hint);
            final EditText confirmInput = nobiPinInput(context);
            confirmInput.setHint(R.string.nobi_app_lock_confirm_hint);
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
            layout.setPadding(pad, pad, pad, pad);
            layout.addView(pinInput);
            layout.addView(confirmInput);
            new AlertDialog.Builder(context)
                .setTitle(R.string.nobi_app_lock_set_title)
                .setView(layout)
                .setPositiveButton(R.string.nobi_save, (dialog, which) -> {
                    String pin = pinInput.getText().toString();
                    String confirm = confirmInput.getText().toString();
                    if (pin.isEmpty() || !pin.equals(confirm)) {
                        Toast.makeText(context, R.string.nobi_app_lock_pin_mismatch,
                            Toast.LENGTH_SHORT).show();
                        showNobiAppLockSetDialog(context, prefs);
                        return;
                    }
                    prefs.edit().putString("pin_hash", TermuxActivity.nobiSha256(pin)).apply();
                    Toast.makeText(context, R.string.nobi_app_lock_pin_set,
                        Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }

        private EditText nobiPinInput(@NonNull Context context) {
            EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
            input.setPadding(pad, pad, pad, pad);
            return input;
        }
    }

}
