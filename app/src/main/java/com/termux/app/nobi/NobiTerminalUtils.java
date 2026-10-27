package com.termux.app.nobi;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

/**
 * Helpers for the NobiTerminal settings entries added by the
 * nobiterminal-settings-suite plugin patch: running the nobi-tools setup
 * scripts in a terminal session, terminal themes (colors.properties) and
 * the extra keys bar (termux.properties).
 */
public final class NobiTerminalUtils {

    private NobiTerminalUtils() {
    }



    /**
     * Open a new terminal session that runs the given nobi-tools script
     * (nobi-backup, nobi-restore, nobi-dev-setup). This uses the same
     * TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent mechanism that
     * RunCommandService uses to execute commands in a foreground
     * TermuxSession: the service creates the session, switches to it and
     * opens TermuxActivity (the default session action).
     *
     * The script is run through bash so that if the nobi-tools package is
     * not installed yet, the user sees a readable explanation inside the
     * terminal instead of a plugin execution error.
     */
    public static void runSetupScriptInTerminal(@NonNull Context context, @NonNull String scriptName, @NonNull String label) {
        String bashPath = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/bash";
        String command = "if command -v " + scriptName + " >/dev/null 2>&1; then " + scriptName +
            "; else echo \"" + scriptName + " is not installed yet. Install the Nobi tools package first: pkg install nobi-tools\"; fi";

        Uri executableUri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(bashPath).build();
        Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executableUri);
        execIntent.setClass(context, TermuxService.class);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{"-c", command});
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_RUNNER, Runner.TERMINAL_SESSION.getName());
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
            String.valueOf(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY));
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, label);
        context.startService(execIntent);
    }

    /**
     * Ask TermuxActivity to reload the terminal styling (colors and extra
     * keys) from disk. This is the app's own public entry point for the
     * ACTION_RELOAD_STYLE broadcast, also used by Termux:Styling. It takes
     * effect right away while the terminal activity is in the foreground;
     * otherwise the new values are loaded from disk the next time the
     * terminal activity is created.
     */
    public static void requestStylingReload(@NonNull Context context) {
        TermuxActivity.updateTermuxActivityStyling(context, true);
    }



    // region Terminal themes

    public static final String THEME_DEFAULT = "default";

    /**
     * Apply a terminal theme by writing ~/.termux/colors.properties, or
     * delete that file for the default theme (stock look). Returns false
     * if the file could not be written.
     */
    public static boolean applyTheme(@NonNull Context context, @NonNull String themeId) {
        File colorsFile = new File(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE_PATH);
        if (THEME_DEFAULT.equals(themeId)) {
            if (colorsFile.exists() && !colorsFile.delete())
                return false;
        } else {
            String[] colors = getThemeColors(themeId);
            if (colors == null)
                return false;

            StringBuilder content = new StringBuilder();
            content.append("# Terminal theme set from NobiTerminal settings\n");
            content.append("background=").append(colors[0]).append('\n');
            content.append("foreground=").append(colors[1]).append('\n');
            content.append("cursor=").append(colors[2]).append('\n');
            for (int i = 0; i < 16; i++)
                content.append("color").append(i).append('=').append(colors[3 + i]).append('\n');

            if (!writeTextFile(colorsFile, content.toString()))
                return false;
        }

        requestStylingReload(context);
        return true;
    }

    /**
     * Color table for a theme id: {background, foreground, cursor,
     * color0..color15}. Returns null for unknown ids.
     */
    @Nullable
    private static String[] getThemeColors(@NonNull String themeId) {
        switch (themeId) {
            case "nobi_neon":
                // Signature theme: dark navy with cyan and violet accents.
                return new String[]{
                    "#070B1F", "#D7DEFF", "#22D3EE",
                    "#11173A", "#FB7185", "#34D399", "#FBBF24",
                    "#8B5CF6", "#E879F9", "#22D3EE", "#E5E9FF",
                    "#2A336B", "#F43F5E", "#6EE7B7", "#FDE68A",
                    "#A78BFA", "#F0ABFC", "#67E8F9", "#FFFFFF"};
            case "dark":
                // Stock Termux default color scheme.
                return new String[]{
                    "#000000", "#FFFFFF", "#FFFFFF",
                    "#000000", "#CD0000", "#00CD00", "#CDCD00",
                    "#6495ED", "#CD00CD", "#00CDCD", "#E5E5E5",
                    "#7F7F7F", "#FF0000", "#00FF00", "#FFFF00",
                    "#5C5CFF", "#FF00FF", "#00FFFF", "#FFFFFF"};
            case "dracula":
                return new String[]{
                    "#282A36", "#F8F8F2", "#F8F8F2",
                    "#21222C", "#FF5555", "#50FA7B", "#F1FA8C",
                    "#BD93F9", "#FF79C6", "#8BE9FD", "#F8F8F2",
                    "#6272A4", "#FF6E6E", "#69FF94", "#FFFFA5",
                    "#D6ACFF", "#FF92DF", "#A4FFFF", "#FFFFFF"};
            case "matrix":
                return new String[]{
                    "#000000", "#00FF41", "#00FF41",
                    "#0D0D0D", "#007F20", "#00FF41", "#00CC33",
                    "#009926", "#33FF66", "#00E639", "#B3FFCC",
                    "#1A472A", "#005C16", "#66FF8F", "#99FFB3",
                    "#2EB84D", "#8DFFAD", "#4DFF70", "#E6FFE9"};
            case "solarized_dark":
                return new String[]{
                    "#002B36", "#839496", "#93A1A1",
                    "#073642", "#DC322F", "#859900", "#B58900",
                    "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
                    "#002B36", "#CB4B16", "#586E75", "#657B83",
                    "#839496", "#6C71C4", "#93A1A1", "#FDF6E3"};
            case "one_dark":
                return new String[]{
                    "#282C34", "#ABB2BF", "#528BFF",
                    "#282C34", "#E06C75", "#98C379", "#E5C07B",
                    "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF",
                    "#545862", "#E06C75", "#98C379", "#E5C07B",
                    "#61AFEF", "#C678DD", "#56B6C2", "#C8CCD4"};
            case "midnight_purple":
                return new String[]{
                    "#170B26", "#D8CFF0", "#B18CFF",
                    "#241239", "#FF5C7A", "#5CE8A5", "#FFC857",
                    "#7C5CFF", "#C77DFF", "#4DD8E6", "#E4DCFF",
                    "#3D2560", "#FF7A93", "#8CF5C0", "#FFD97A",
                    "#9D85FF", "#DDA8FF", "#8AE6F0", "#FFFFFF"};
            case "nord":
                // v6 themes gallery: arctic, low-contrast blues.
                return new String[]{
                    "#2E3440", "#D8DEE9", "#88C0D0",
                    "#3B4252", "#BF616A", "#A3BE8C", "#EBCB8B",
                    "#81A1C1", "#B48EAD", "#88C0D0", "#ECEFF4",
                    "#4C566A", "#BF616A", "#A3BE8C", "#EBCB8B",
                    "#81A1C1", "#B48EAD", "#8FBCBB", "#ECEFF4"};
            case "monokai":
                // v6 themes gallery: warm dark classic.
                return new String[]{
                    "#272822", "#F8F8F2", "#F8F8F2",
                    "#3E3D32", "#F92672", "#A6E22E", "#F4BF75",
                    "#66D9EF", "#AE81FF", "#A1EFE4", "#F8F8F2",
                    "#75715E", "#F92672", "#A6E22E", "#F4BF75",
                    "#66D9EF", "#AE81FF", "#A1EFE4", "#F9F8F5"};
            case "gruvbox":
                // v6 themes gallery: retro groove, dark warm brown.
                return new String[]{
                    "#282828", "#EBDBB2", "#FABD2F",
                    "#3C3836", "#CC241D", "#98971A", "#D79921",
                    "#458588", "#B16286", "#689D6A", "#EBDBB2",
                    "#665C54", "#FB4934", "#B8BB26", "#FABD2F",
                    "#83A598", "#D3869B", "#8EC07C", "#FBF1C7"};
            case "daylight":
                // v7 (31): light scheme for the time-aware daytime theme.
                return new String[]{
                    "#FFFFFF", "#1F2937", "#2563EB",
                    "#F3F4F6", "#DC2626", "#059669", "#D97706",
                    "#2563EB", "#9333EA", "#0891B2", "#111827",
                    "#E5E7EB", "#EF4444", "#10B981", "#F59E0B",
                    "#3B82F6", "#A855F7", "#06B6D4", "#000000"};
            default:
                return null;
        }
    }

    // endregion



    // region Extra keys bar

    /** Canonical order used when composing the first extra keys row. */
    public static final String[] EXTRA_KEYS_ROW_1_ORDER = {"ESC", "DRAWER", "TAB", "CTRL", "ALT", "|", "-", "/", "_"};

    /** Canonical order used when composing the second extra keys row. */
    public static final String[] EXTRA_KEYS_ROW_2_ORDER = {"HOME", "END", "PGUP", "PGDN", "LEFT", "DOWN", "UP", "RIGHT"};

    /** All keys offered as toggles on the Extra Keys Bar settings screen. */
    public static final String[] ALL_EXTRA_KEYS = {
        "ESC", "DRAWER", "TAB", "CTRL", "ALT", "|", "-", "/", "_",
        "HOME", "END", "PGUP", "PGDN", "LEFT", "DOWN", "UP", "RIGHT"};

    /**
     * v8 (extra-keys cleanup): keys Sam asked to remove from the bar.
     * They are in NEITHER the built-in default value NOR the Nobi Extra
     * Keys settings toggles/presets, so they can only reach the bar
     * through a hand-edited extra-keys line in ~/.termux/termux.properties,
     * which overrides the code default at load time. Matching is exact
     * (after trim) and case-insensitive, so 'keyboard' or 'New Session'
     * spellings are caught too.
     */
    public static final Set<String> REMOVED_EXTRA_KEYS = new HashSet<>(Arrays.asList(
        "KEYBOARD", "NEW SESSION", "NEW WINDOW"));

    /** True if the given extra-key name is one of the removed keys. */
    public static boolean isRemovedExtraKey(@Nullable String key) {
        if (key == null) return false;
        return REMOVED_EXTRA_KEYS.contains(key.trim().toUpperCase(Locale.ROOT));
    }

    private static final Pattern EXTRA_KEY_OBJECT_KEY_PATTERN =
        Pattern.compile("(?:key|macro)\\s*:\\s*'([^']+)'");
    private static final Pattern EXTRA_KEY_OBJECT_POPUP_PATTERN =
        Pattern.compile("popup\\s*:\\s*'([^']+)'");

    /**
     * Strip the removed keys from an extra-keys property value, keeping
     * every other key, its popup object and the row order untouched.
     * A plain removed key ('KEYBOARD') and a whole popup object whose
     * key/macro is removed ({key: 'NEW SESSION', ...}) are dropped; a
     * popup that IS a removed key ({key: '-', popup: 'KEYBOARD'}) loses
     * only the popup clause. Rows left empty are dropped. Returns null
     * for null input and "[]" if nothing is left. A value that does not
     * parse as a matrix of rows is returned unchanged, so the caller
     * falls back to the built-in default exactly as before instead of
     * silently mangling a hand-edited line.
     */
    @Nullable
    public static String sanitizeExtraKeysValue(@Nullable String value) {
        if (value == null) return null;
        String input = value.trim();
        if (input.length() < 2 || input.charAt(0) != '[') return value;

        List<String> rows = new ArrayList<>();
        int i = 1; // past the outer '['
        int n = input.length();
        boolean outerClosed = false;
        while (i < n) {
            char c = input.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') { i++; continue; }
            if (c == ']') { outerClosed = true; break; }
            if (c != '[') return value; // malformed: leave untouched
            i++; // past the row '['
            List<String> elements = new ArrayList<>();
            boolean rowClosed = false;
            while (i < n) {
                char rc = input.charAt(i);
                if (rc == ' ' || rc == '\t' || rc == '\n' || rc == '\r' || rc == ',') { i++; continue; }
                if (rc == ']') { rowClosed = true; i++; break; }
                if (rc == '\'' || rc == '"') {
                    int end = findExtraKeysQuotedEnd(input, i);
                    if (end < 0) return value;
                    String key = input.substring(i + 1, end);
                    if (!isRemovedExtraKey(key))
                        elements.add(input.substring(i, end + 1));
                    i = end + 1;
                } else if (rc == '{') {
                    int end = findExtraKeysObjectEnd(input, i);
                    if (end < 0) return value;
                    String cleaned = sanitizeExtraKeyObject(input.substring(i, end + 1));
                    if (cleaned != null)
                        elements.add(cleaned);
                    i = end + 1;
                } else {
                    return value; // bare/unquoted token: leave untouched
                }
            }
            if (!rowClosed) return value; // unclosed row: leave untouched
            if (!elements.isEmpty()) {
                StringBuilder row = new StringBuilder("[");
                for (int k = 0; k < elements.size(); k++) {
                    if (k > 0) row.append(", ");
                    row.append(elements.get(k));
                }
                rows.add(row.append("]").toString());
            }
        }

        if (!outerClosed) return value; // unclosed matrix: leave untouched
        if (rows.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int k = 0; k < rows.size(); k++) {
            if (k > 0) out.append(", ");
            out.append(rows.get(k));
        }
        return out.append("]").toString();
    }

    /** Index of the closing quote at/after {@code start}, or -1. */
    private static int findExtraKeysQuotedEnd(String input, int start) {
        char quote = input.charAt(start);
        for (int i = start + 1; i < input.length(); i++) {
            if (input.charAt(i) == '\\') { i++; continue; }
            if (input.charAt(i) == quote) return i;
        }
        return -1;
    }

    /** Index of the '}' matching the '{' at {@code start}, or -1. Quote-aware. */
    private static int findExtraKeysObjectEnd(String input, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Clean one popup-object element: null if its key/macro is a removed
     * key (drop the whole button), the object minus its popup clause if
     * only the popup is a removed key, otherwise the object unchanged.
     */
    @Nullable
    private static String sanitizeExtraKeyObject(String object) {
        Matcher keyMatcher = EXTRA_KEY_OBJECT_KEY_PATTERN.matcher(object);
        if (keyMatcher.find() && isRemovedExtraKey(keyMatcher.group(1)))
            return null;
        Matcher popupMatcher = EXTRA_KEY_OBJECT_POPUP_PATTERN.matcher(object);
        if (popupMatcher.find() && isRemovedExtraKey(popupMatcher.group(1))) {
            // Drop the popup clause, keeping whichever comma form it used.
            String withoutPopup = object.replaceFirst(",\\s*popup\\s*:\\s*'[^']+'", "");
            if (withoutPopup.equals(object))
                withoutPopup = object.replaceFirst("popup\\s*:\\s*'[^']+'\\s*,\\s*", "");
            return withoutPopup;
        }
        return object;
    }

    private static final Pattern EXTRA_KEYS_LINE_PATTERN = Pattern.compile("^\\s*extra-keys\\s*=(.*)$");
    private static final Pattern EXTRA_KEY_OBJECT_PATTERN = Pattern.compile("\\{\\s*key\\s*:\\s*'([^']+)'(?:\\s*,\\s*popup\\s*:\\s*'([^']+)')?[^}]*\\}");
    private static final Pattern EXTRA_KEY_QUOTED_PATTERN = Pattern.compile("'([^']+)'");

    /**
     * Parse an extra-keys property value into the set of keys it contains.
     * Handles plain quoted keys ('ESC') and popup objects
     * ({key: '-', popup: '|'}), which contribute both keys.
     */
    @NonNull
    public static Set<String> parseExtraKeysValue(@Nullable String value) {
        Set<String> keys = new LinkedHashSet<>();
        if (value == null)
            return keys;

        Matcher objectMatcher = EXTRA_KEY_OBJECT_PATTERN.matcher(value);
        StringBuffer rest = new StringBuffer();
        while (objectMatcher.find()) {
            keys.add(objectMatcher.group(1));
            if (objectMatcher.group(2) != null)
                keys.add(objectMatcher.group(2));
            objectMatcher.appendReplacement(rest, " ");
        }
        objectMatcher.appendTail(rest);

        Matcher quotedMatcher = EXTRA_KEY_QUOTED_PATTERN.matcher(rest);
        while (quotedMatcher.find())
            keys.add(quotedMatcher.group(1));

        return keys;
    }

    /**
     * The set of keys the extra keys bar currently shows: the extra-keys
     * property of ~/.termux/termux.properties if set, otherwise the keys of
     * the built-in default value.
     */
    @NonNull
    public static Set<String> getCurrentExtraKeys() {
        String value = readExtraKeysPropertyValue();
        Set<String> keys;
        if (value == null)
            keys = parseExtraKeysValue(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
        else
            keys = parseExtraKeysValue(value);
        // v8: never surface the removed keys to the settings screen, so
        // a toggle/reorder save cannot write them back into the file.
        keys.removeIf(NobiTerminalUtils::isRemovedExtraKey);
        return keys;
    }

    /**
     * v6 (4): compose an extra-keys property value from an ORDERED list of
     * keys, preserving the given relative order inside each row. Row 1 holds
     * the editing keys, row 2 the navigation keys (same grouping as the
     * canonical compose); unknown keys go at the end of row 1.
     */
    @NonNull
    public static String composeExtraKeysValueOrdered(@NonNull java.util.List<String> orderedKeys) {
        java.util.List<String> row1Keys = new java.util.ArrayList<>();
        java.util.List<String> row2Keys = new java.util.ArrayList<>();
        java.util.Set<String> row2Set = new java.util.HashSet<>(java.util.Arrays.asList(EXTRA_KEYS_ROW_2_ORDER));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String key : orderedKeys) {
            if (key == null || !seen.add(key)) continue;
            if (isRemovedExtraKey(key)) continue; // v8: never re-compose a removed key
            if (row2Set.contains(key)) row2Keys.add(key);
            else row1Keys.add(key);
        }
        String row1 = row1Keys.isEmpty() ? null : "[" + joinExtraKeys(row1Keys) + "]";
        String row2 = row2Keys.isEmpty() ? null : "[" + joinExtraKeys(row2Keys) + "]";
        if (row1 == null && row2 == null) return "[]";
        if (row1 == null) return "[" + row2 + "]";
        if (row2 == null) return "[" + row1 + "]";
        return "[" + row1 + ", " + row2 + "]";
    }

    private static String joinExtraKeys(@NonNull java.util.List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(keys.get(i)).append('\'');
        }
        return sb.toString();
    }

    /** Compose an extra-keys property value from a set of enabled keys. */
    @NonNull
    public static String composeExtraKeysValue(@NonNull Set<String> enabledKeys) {
        String row1 = composeExtraKeysRow(EXTRA_KEYS_ROW_1_ORDER, enabledKeys);
        String row2 = composeExtraKeysRow(EXTRA_KEYS_ROW_2_ORDER, enabledKeys);
        if (row1 == null && row2 == null)
            return "[]";

        StringBuilder value = new StringBuilder("[");
        if (row1 != null)
            value.append(row1);
        if (row2 != null) {
            if (row1 != null)
                value.append(", ");
            value.append(row2);
        }
        return value.append("]").toString();
    }

    @Nullable
    private static String composeExtraKeysRow(@NonNull String[] order, @NonNull Set<String> enabledKeys) {
        StringBuilder row = null;
        for (String key : order) {
            if (!enabledKeys.contains(key))
                continue;
            if (row == null)
                row = new StringBuilder("[");
            else
                row.append(", ");
            row.append('\'').append(key).append('\'');
        }
        return row == null ? null : row.append("]").toString();
    }

    /**
     * Set the extra-keys property in ~/.termux/termux.properties, or remove
     * it if extraKeysValue is null (the built-in default then applies).
     * All other lines of the file are preserved exactly as they are
     * (read-modify-write). Returns false on an I/O error.
     */
    public static boolean setExtraKeysValue(@Nullable String extraKeysValue) {
        // v8: any save through the settings screen also cleans the file
        // itself, so the removed keys do not linger for a later load.
        if (extraKeysValue != null)
            extraKeysValue = sanitizeExtraKeysValue(extraKeysValue);
        File propertiesFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);

        List<String> lines = new ArrayList<>();
        if (propertiesFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(propertiesFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (EXTRA_KEYS_LINE_PATTERN.matcher(line).find())
                        continue; // The old extra-keys line is dropped; other lines are kept.
                    lines.add(line);
                }
            } catch (IOException e) {
                return false;
            }
        }

        if (extraKeysValue != null)
            lines.add("extra-keys = " + extraKeysValue);

        StringBuilder content = new StringBuilder();
        for (String line : lines)
            content.append(line).append('\n');
        return writeTextFile(propertiesFile, content.toString());
    }

    @Nullable
    private static String readExtraKeysPropertyValue() {
        File propertiesFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        if (!propertiesFile.exists())
            return null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(propertiesFile), StandardCharsets.UTF_8))) {
            String line;
            String value = null;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = EXTRA_KEYS_LINE_PATTERN.matcher(line);
                if (matcher.find())
                    value = matcher.group(1).trim();
            }
            return value;
        } catch (IOException e) {
            return null;
        }
    }

    // endregion



    private static boolean writeTextFile(@NonNull File file, @NonNull String content) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                return false;
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(content);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

}
