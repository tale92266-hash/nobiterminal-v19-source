package com.nobidev.nobicode.theme

import android.content.Context
import android.net.Uri

class ThemeManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("nobi_settings", Context.MODE_PRIVATE)

    // Appearance
    var currentThemeId: String
        get() = prefs.getString("theme_id", "one_dark") ?: "one_dark"
        set(v) = prefs.edit().putString("theme_id", v).apply()

    var current: EditorTheme
        get() = Themes.byId(currentThemeId)
        set(v) { currentThemeId = v.id }

    var cursorBlink: Boolean
        get() = prefs.getBoolean("cursor_blink", true)
        set(v) = prefs.edit().putBoolean("cursor_blink", v).apply()

    // Editor Mechanics
    var fontSizeSp: Float
        get() = prefs.getFloat("font_size", 14f)
        set(v) = prefs.edit().putFloat("font_size", v).apply()

    var wordWrap: Boolean
        get() = prefs.getBoolean("word_wrap", true)
        set(v) = prefs.edit().putBoolean("word_wrap", v).apply()

    var showLineNumbers: Boolean
        get() = prefs.getBoolean("show_line_numbers", true)
        set(v) = prefs.edit().putBoolean("show_line_numbers", v).apply()

    var tabSize: Int
        get() = prefs.getInt("tab_size", 4)
        set(v) = prefs.edit().putInt("tab_size", v).apply()

    var autoCloseBrackets: Boolean
        get() = prefs.getBoolean("auto_close_brackets", true)
        set(v) = prefs.edit().putBoolean("auto_close_brackets", v).apply()

    var showWhitespace: Boolean
        get() = prefs.getBoolean("show_whitespace", false)
        set(v) = prefs.edit().putBoolean("show_whitespace", v).apply()

    // Files & Storage
    var autoSaveOnExit: Boolean
        get() = prefs.getBoolean("autosave_on_exit", false)
        set(v) = prefs.edit().putBoolean("autosave_on_exit", v).apply()

    var readOnlyMode: Boolean
        get() = prefs.getBoolean("read_only_mode", false)
        set(v) = prefs.edit().putBoolean("read_only_mode", v).apply()

    var treeUri: Uri?
        get() = prefs.getString("tree_uri", null)?.let { Uri.parse(it) }
        set(v) = prefs.edit().putString("tree_uri", v?.toString()).apply()

    var folderId: String?
        get() = prefs.getString("folder_id", null)
        set(v) = prefs.edit().putString("folder_id", v).apply()

    var rootFolderId: String?
        get() = prefs.getString("root_folder_id", null)
        set(v) = prefs.edit().putString("root_folder_id", v).apply()

    var openFilesList: String
        get() = prefs.getString("open_files", "") ?: ""
        set(v) = prefs.edit().putString("open_files", v).apply()

    var activeTabIndex: Int
        get() = prefs.getInt("active_tab", 0)
        set(v) = prefs.edit().putInt("active_tab", v).apply()
}
