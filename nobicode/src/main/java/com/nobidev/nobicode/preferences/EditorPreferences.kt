package com.nobidev.nobicode.preferences

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class EditorPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nobi_ide_prefs", Context.MODE_PRIVATE)

    /** Saari settings default par wapas. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    var fontSize: Float
        get() = prefs.getFloat("font_size", 14f)
        set(value) = prefs.edit().putFloat("font_size", value).apply()

    var isWordWrap: Boolean
        get() = prefs.getBoolean("word_wrap", false)
        set(value) = prefs.edit().putBoolean("word_wrap", value).apply()

    var showLineNumbers: Boolean
        get() = prefs.getBoolean("show_line_numbers", true)
        set(value) = prefs.edit().putBoolean("show_line_numbers", value).apply()

    var hideHiddenFiles: Boolean
        get() = prefs.getBoolean("hide_hidden_files", false)
        set(value) = prefs.edit().putBoolean("hide_hidden_files", value).apply()

    var findCaseSensitive: Boolean
        get() = prefs.getBoolean("find_case", false)
        set(value) = prefs.edit().putBoolean("find_case", value).apply()

    var findUseRegex: Boolean
        get() = prefs.getBoolean("find_regex", false)
        set(value) = prefs.edit().putBoolean("find_regex", value).apply()

    var findWholeWord: Boolean
        get() = prefs.getBoolean("find_word", false)
        set(value) = prefs.edit().putBoolean("find_word", value).apply()

    var findHistory: List<String>
        get() = prefs.getString("find_history", "").orEmpty().split("\u0001").filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("find_history", value.take(8).joinToString("\u0001")).apply()

    var searchFilesHistory: List<String>
        get() = prefs.getString("search_files_history", "").orEmpty().split("\u0001").filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("search_files_history", value.take(8).joinToString("\u0001")).apply()

    var paletteHistory: List<String>
        get() = prefs.getString("palette_history", "").orEmpty().split("\u0001").filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("palette_history", value.take(8).joinToString("\u0001")).apply()

    var recentFolders: List<String>
        get() = prefs.getString("recent_folders", "").orEmpty().split("\u0001").filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("recent_folders", value.take(10).joinToString("\u0001")).apply()

    var autoCloseBrackets: Boolean
        get() = prefs.getBoolean("auto_close_brackets", true)
        set(value) = prefs.edit().putBoolean("auto_close_brackets", value).apply()

    var codeFolding: Boolean
        get() = prefs.getBoolean("code_folding", true)
        set(value) = prefs.edit().putBoolean("code_folding", value).apply()

    var stickyScroll: Boolean
        get() = prefs.getBoolean("sticky_scroll", true)
        set(value) = prefs.edit().putBoolean("sticky_scroll", value).apply()

    var highlightCurrentLine: Boolean
        get() = prefs.getBoolean("highlight_current_line", true)
        set(value) = prefs.edit().putBoolean("highlight_current_line", value).apply()

    var indentGuides: Boolean
        get() = prefs.getBoolean("indent_guides", true)
        set(value) = prefs.edit().putBoolean("indent_guides", value).apply()

    var insertFinalNewline: Boolean
        get() = prefs.getBoolean("insert_final_newline", true)
        set(value) = prefs.edit().putBoolean("insert_final_newline", value).apply()

    var trimTrailingWhitespace: Boolean
        get() = prefs.getBoolean("trim_trailing_whitespace", true)
        set(value) = prefs.edit().putBoolean("trim_trailing_whitespace", value).apply()

    // v6 (12): format on save (default off — opt-in formatting).
    var formatOnSave: Boolean
        get() = prefs.getBoolean("format_on_save", false)
        set(value) = prefs.edit().putBoolean("format_on_save", value).apply()

    var autoIndentOnPaste: Boolean
        get() = prefs.getBoolean("auto_indent_on_paste", true)
        set(value) = prefs.edit().putBoolean("auto_indent_on_paste", value).apply()

    var fontLigatures: Boolean
        get() = prefs.getBoolean("font_ligatures", false)
        set(value) = prefs.edit().putBoolean("font_ligatures", value).apply()

    var lineSpacing: Float
        get() = prefs.getFloat("line_spacing", 1.0f)
        set(value) = prefs.edit().putFloat("line_spacing", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    var typewriterMode: Boolean
        get() = prefs.getBoolean("typewriter_mode", false)
        set(value) = prefs.edit().putBoolean("typewriter_mode", value).apply()

    var symbolsBar: Boolean
        get() = prefs.getBoolean("symbols_bar", true)
        set(value) = prefs.edit().putBoolean("symbols_bar", value).apply()

    var showWhitespace: Boolean
        get() = prefs.getBoolean("show_whitespace", false)
        set(value) = prefs.edit().putBoolean("show_whitespace", value).apply()
    var rainbowBrackets: Boolean
        get() = prefs.getBoolean("rainbow_brackets", false)
        set(value) = prefs.edit().putBoolean("rainbow_brackets", value).apply()

    var lineGuideColumn: Int
        get() = prefs.getInt("line_guide_column", 0)
        set(value) = prefs.edit().putInt("line_guide_column", value).apply()

    var syntaxHighlight: Boolean
        get() = prefs.getBoolean("syntax_highlight_v2", false)
        set(value) = prefs.edit().putBoolean("syntax_highlight_v2", value).apply()

    var minimap: Boolean
        get() = prefs.getBoolean("minimap", false)
        set(value) = prefs.edit().putBoolean("minimap", value).apply()

    var autoIndent: Boolean
        get() = prefs.getBoolean("auto_indent", true)
        set(value) = prefs.edit().putBoolean("auto_indent", value).apply()

    var pinchZoom: Boolean
        get() = prefs.getBoolean("pinch_zoom", true)
        set(value) = prefs.edit().putBoolean("pinch_zoom", value).apply()

    var emmet: Boolean
        get() = prefs.getBoolean("emmet", true)
        set(value) = prefs.edit().putBoolean("emmet", value).apply()

    var tabSize: Int
        get() = prefs.getInt("tab_size", 4)
        set(value) = prefs.edit().putInt("tab_size", value).apply()

    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save", true)
        set(value) = prefs.edit().putBoolean("auto_save", value).apply()

    var readOnly: Boolean
        get() = prefs.getBoolean("read_only", false)
        set(value) = prefs.edit().putBoolean("read_only", value).apply()

    var themeName: String
        get() = prefs.getString("theme_name", "One Dark") ?: "One Dark"
        set(value) = prefs.edit().putString("theme_name", value).apply()

    // Workspace & Session Persistence
    var rootFolderUri: String?
        get() = prefs.getString("root_folder_uri", null)
        set(value) = prefs.edit().putString("root_folder_uri", value).apply()

    var currentFolderUri: String?
        get() = prefs.getString("current_folder_uri", null)
        set(value) = prefs.edit().putString("current_folder_uri", value).apply()

    var folderBackStackJson: String?
        get() = prefs.getString("folder_back_stack_json", null)
        set(value) = prefs.edit().putString("folder_back_stack_json", value).apply()

    var savedTabsJson: String?
        get() = prefs.getString("saved_tabs_json", null)
        set(value) = prefs.edit().putString("saved_tabs_json", value).apply()

    var activeTabIndex: Int
        get() = prefs.getInt("active_tab_index", 0)
        set(value) = prefs.edit().putInt("active_tab_index", value).apply()

    var snippetTipShown: Boolean
        get() = prefs.getBoolean("snippet_tip_shown", false)
        set(value) = prefs.edit().putBoolean("snippet_tip_shown", value).apply()

    // ---- v7 prefs ----
    /** v7 (64): file bookmarks (paths), distinct from line bookmarks. */
    var fileBookmarks: List<String>
        get() = prefs.getString("v7_file_bookmarks", "").orEmpty().split("\u0001").filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("v7_file_bookmarks", value.take(50).joinToString("\u0001")).apply()

    /** v7 (57): per-file run configs as JSON (path -> command string).
     *  Map-backed: the getter parses the stored JSON object, the setter
     *  serializes the map back to the same "v7_run_configs" key, so the
     *  on-disk format is unchanged ("{}" when empty). Malformed JSON
     *  reads as an empty map instead of crashing. */
    var runConfigs: Map<String, String>
        get() = try {
            val obj = JSONObject(prefs.getString("v7_run_configs", "{}") ?: "{}")
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optString(k)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
        set(value) = prefs.edit().putString("v7_run_configs", JSONObject(value).toString()).apply()

    /** v7 (60): follow the terminal's cwd when the hook is installed. */
    var followTerminalFolder: Boolean
        get() = prefs.getBoolean("v7_follow_terminal_folder", true)
        set(value) = prefs.edit().putBoolean("v7_follow_terminal_folder", value).apply()

    /** v7 (35): vim modal editing. */
    var vimMode: Boolean
        get() = prefs.getBoolean("v7_vim_mode", false)
        set(value) = prefs.edit().putBoolean("v7_vim_mode", value).apply()

    /** v7 (86): editor font file path (user-imported .ttf), null = default. */
    var fontPath: String?
        get() = prefs.getString("v7_font_path", null)
        set(value) = prefs.edit().putString("v7_font_path", value).apply()

    /** v7 (87): layout density — "compact" or "comfortable" (via lineSpacing). */
    var density: String
        get() = prefs.getString("v7_density", "comfortable") ?: "comfortable"
        set(value) = prefs.edit().putString("v7_density", value).apply()

    /** v7 (85): glass look (translucent rounded panels) on/off. */
    var glassLook: Boolean
        get() = prefs.getBoolean("v7_glass_look", false)
        set(value) = prefs.edit().putBoolean("v7_glass_look", value).apply()

    /** v7 (31): time-aware editor theme (light by day, dark by night). */
    var timeAwareTheme: Boolean
        get() = prefs.getBoolean("v7_time_aware_theme", false)
        set(value) = prefs.edit().putBoolean("v7_time_aware_theme", value).apply()
}
