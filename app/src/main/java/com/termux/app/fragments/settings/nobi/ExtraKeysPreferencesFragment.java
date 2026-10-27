package com.termux.app.fragments.settings.nobi;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.nobi.NobiTerminalUtils;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Keep
public class ExtraKeysPreferencesFragment extends PreferenceFragmentCompat {

    private static final String PRESET_CUSTOM = "custom";

    /** True while checkbox states are being set from code, to avoid save loops. */
    private boolean mUpdatingCheckBoxes = false;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.nobi_extra_keys_preferences, rootKey);

        ListPreference presetPreference = findPreference("nobi_extra_keys_preset");
        if (presetPreference != null) {
            presetPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                applyPreset(context, (String) newValue);
                return true;
            });
        }

        // v6 (4): reorder the bar's keys (add/remove stays on the toggles).
        Preference reorderPreference = findPreference("nobi_extra_keys_reorder");
        if (reorderPreference != null) {
            reorderPreference.setOnPreferenceClickListener(preference -> {
                showReorderDialog(context);
                return true;
            });
        }

        // Show the keys that are currently in effect and save every change
        // straight to ~/.termux/termux.properties.
        Set<String> currentKeys = NobiTerminalUtils.getCurrentExtraKeys();
        mUpdatingCheckBoxes = true;
        for (String key : NobiTerminalUtils.ALL_EXTRA_KEYS) {
            CheckBoxPreference checkBox = findPreference(getCheckBoxKey(key));
            if (checkBox != null)
                checkBox.setChecked(currentKeys.contains(key));
        }
        mUpdatingCheckBoxes = false;

        for (String key : NobiTerminalUtils.ALL_EXTRA_KEYS) {
            CheckBoxPreference checkBox = findPreference(getCheckBoxKey(key));
            if (checkBox != null) {
                checkBox.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (mUpdatingCheckBoxes)
                        return true;
                    // v6 (4): keep the file's key order (incl. any custom
                    // reorder) instead of recomposing canonically, so a
                    // toggle does not silently undo a reorder.
                    List<String> ordered = new ArrayList<>(NobiTerminalUtils.getCurrentExtraKeys());
                    if ((Boolean) newValue) {
                        if (!ordered.contains(key)) ordered.add(key);
                    } else {
                        ordered.remove(key);
                    }
                    if (!saveExtraKeys(context, NobiTerminalUtils.composeExtraKeysValueOrdered(ordered)))
                        return false;
                    setPresetValue(PRESET_CUSTOM);
                    return true;
                });
            }
        }
    }

    /** v6 (4): reorder dialog — up/down per key, saved in file order. */
    private void showReorderDialog(Context context) {
        final List<String> order = new ArrayList<>(NobiTerminalUtils.getCurrentExtraKeys());
        if (order.isEmpty()) {
            Toast.makeText(context, R.string.nobi_extra_keys_reorder_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(context,
            android.R.layout.simple_list_item_1, order) {
            @Override
            public View getView(final int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                TextView label;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                    label = (TextView) row.getChildAt(0);
                } else {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    label = new TextView(context);
                    LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    label.setLayoutParams(labelParams);
                    Button up = new Button(context);
                    up.setText("▲");
                    Button down = new Button(context);
                    down.setText("▼");
                    row.addView(label);
                    row.addView(up);
                    row.addView(down);
                }
                label.setText(order.get(position));
                Button up = (Button) row.getChildAt(1);
                Button down = (Button) row.getChildAt(2);
                up.setOnClickListener(v -> {
                    if (position > 0) {
                        String key = order.remove(position);
                        order.add(position - 1, key);
                        notifyDataSetChanged();
                    }
                });
                down.setOnClickListener(v -> {
                    if (position < order.size() - 1) {
                        String key = order.remove(position);
                        order.add(position + 1, key);
                        notifyDataSetChanged();
                    }
                });
                return row;
            }
        };
        ListView listView = new ListView(context);
        listView.setAdapter(adapter);
        float density = context.getResources().getDisplayMetrics().density;
        int height = (int) (Math.min(order.size(), 8) * 56 * density);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height));
        new AlertDialog.Builder(context)
            .setTitle(R.string.nobi_extra_keys_reorder_title)
            .setView(listView)
            .setPositiveButton(R.string.nobi_save, (dialog, which) -> {
                if (saveExtraKeys(context, NobiTerminalUtils.composeExtraKeysValueOrdered(order)))
                    setPresetValue(PRESET_CUSTOM);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void applyPreset(Context context, String preset) {
        switch (preset) {
            case "default":
                // Removing the property restores the built-in default bar.
                if (saveExtraKeys(context, null))
                    refreshCheckBoxes(NobiTerminalUtils.parseExtraKeysValue(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS));
                break;
            case "developer":
                saveComposedPreset(context, new HashSet<>(Arrays.asList(
                    "ESC", "TAB", "CTRL", "ALT", "HOME", "END", "PGUP", "PGDN", "LEFT", "DOWN", "UP", "RIGHT")));
                break;
            case "minimal":
                saveComposedPreset(context, new HashSet<>(Arrays.asList(
                    "ESC", "TAB", "CTRL", "ALT", "-", "/")));
                break;
            case "off":
                if (saveExtraKeys(context, "[]"))
                    refreshCheckBoxes(new HashSet<>());
                break;
            default:
                // "custom" (or anything unknown): the toggles below define the bar.
                break;
        }
    }

    private void saveComposedPreset(Context context, Set<String> keys) {
        if (saveExtraKeys(context, NobiTerminalUtils.composeExtraKeysValue(keys)))
            refreshCheckBoxes(keys);
    }

    /** Write the value to termux.properties (null removes it) and reload styling. */
    private boolean saveExtraKeys(Context context, String value) {
        boolean saved = NobiTerminalUtils.setExtraKeysValue(value);
        if (saved)
            NobiTerminalUtils.requestStylingReload(context);
        else
            Toast.makeText(context, R.string.nobi_settings_save_failed, Toast.LENGTH_LONG).show();
        return saved;
    }

    private void refreshCheckBoxes(Set<String> enabledKeys) {
        mUpdatingCheckBoxes = true;
        for (String key : NobiTerminalUtils.ALL_EXTRA_KEYS) {
            CheckBoxPreference checkBox = findPreference(getCheckBoxKey(key));
            if (checkBox != null)
                checkBox.setChecked(enabledKeys.contains(key));
        }
        mUpdatingCheckBoxes = false;
    }

    private void setPresetValue(String value) {
        ListPreference presetPreference = findPreference("nobi_extra_keys_preset");
        if (presetPreference != null)
            presetPreference.setValue(value);
    }

    private static String getCheckBoxKey(String key) {
        return "nobi_key_" + key;
    }

}
