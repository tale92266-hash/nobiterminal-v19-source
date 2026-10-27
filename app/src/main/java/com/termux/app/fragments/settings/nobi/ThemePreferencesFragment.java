package com.termux.app.fragments.settings.nobi;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.nobi.NobiTerminalUtils;

@Keep
public class ThemePreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.nobi_theme_preferences, rootKey);

        ListPreference themePreference = findPreference("nobi_theme_list");
        if (themePreference != null) {
            themePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                Context appContext = context.getApplicationContext();
                boolean applied = NobiTerminalUtils.applyTheme(appContext, (String) newValue);
                if (!applied) {
                    Toast.makeText(context, R.string.nobi_settings_save_failed, Toast.LENGTH_LONG).show();
                    return false;
                }

                // Open the terminal so the new theme is visible. The styling
                // reload broadcast is only handled while TermuxActivity is in
                // the foreground, so it is sent again shortly after the
                // activity has been brought forward.
                startActivity(new Intent(context, TermuxActivity.class));
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> NobiTerminalUtils.requestStylingReload(appContext), 500);
                return true;
            });
        }
    }

}
