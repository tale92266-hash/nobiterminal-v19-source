package com.nobidev.nobicode.followup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v11 follow-up persistence. All keys are NEW ("nobi_v11_...") in their
 * own SharedPreferences file, so no existing preference key is touched
 * or renamed. Lists are bounded (recent files: 20, workspaces: 30) and
 * stored as small JSON, never as unbounded text.
 */
object FollowupStore {

    private const val FILE = "nobi_v11_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Recent files (4) ----

    data class RecentFile(val uri: String, val name: String, val time: Long)

    fun recordRecent(context: Context, uri: String?, name: String) {
        if (uri.isNullOrBlank()) return
        val items = recentFiles(context).filter { it.uri != uri }.toMutableList()
        items.add(0, RecentFile(uri, name, System.currentTimeMillis()))
        val arr = JSONArray()
        for (item in items.take(20)) {
            arr.put(
                JSONObject().put("uri", item.uri).put("name", item.name).put("time", item.time)
            )
        }
        prefs(context).edit().putString("nobi_v11_recent_files", arr.toString()).apply()
    }

    fun recentFiles(context: Context): List<RecentFile> {
        val out = ArrayList<RecentFile>()
        try {
            val arr = JSONArray(prefs(context).getString("nobi_v11_recent_files", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(RecentFile(obj.getString("uri"), obj.getString("name"), obj.optLong("time")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    // ---- Workspaces (11) ----

    data class Workspace(val name: String, val uri: String)

    fun workspaces(context: Context): List<Workspace> {
        val out = ArrayList<Workspace>()
        try {
            val arr = JSONArray(prefs(context).getString("nobi_v11_workspaces", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(Workspace(obj.getString("name"), obj.getString("uri")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun addWorkspace(context: Context, name: String, uri: String) {
        val items = workspaces(context).filter { it.uri != uri }.toMutableList()
        items.add(Workspace(name, uri))
        saveWorkspaces(context, items.take(30))
    }

    fun removeWorkspace(context: Context, uri: String) {
        saveWorkspaces(context, workspaces(context).filter { it.uri != uri })
    }

    private fun saveWorkspaces(context: Context, items: List<Workspace>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().put("name", item.name).put("uri", item.uri))
        }
        prefs(context).edit().putString("nobi_v11_workspaces", arr.toString()).apply()
    }

    // ---- Custom toolbar (17): order + disabled set ----

    fun toolbarOrder(context: Context): List<String> {
        val raw = prefs(context).getString("nobi_v11_toolbar_order", null)
        if (raw.isNullOrBlank()) return defaultToolIds()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            val missing = defaultToolIds().filter { it !in out }
            out + missing
        } catch (e: Exception) {
            defaultToolIds()
        }
    }

    fun setToolbarOrder(context: Context, ids: List<String>) {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        prefs(context).edit().putString("nobi_v11_toolbar_order", arr.toString()).apply()
    }

    fun toolbarDisabled(context: Context): Set<String> {
        return try {
            val arr = JSONArray(prefs(context).getString("nobi_v11_toolbar_disabled", "[]") ?: "[]")
            val out = HashSet<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun setToolbarDisabled(context: Context, ids: Set<String>) {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        prefs(context).edit().putString("nobi_v11_toolbar_disabled", arr.toString()).apply()
    }

    fun defaultToolIds(): List<String> = listOf(
        "quick_switch", "recent", "hex", "templates", "image_preview",
        "sort_filter", "workspaces", "cheat_sheets", "diff",
        "find_replace", "split", "terminal_folder", "lint", "encoding",
        "share", "file_info", "send_terminal", "save_clipboard", "tour",
        "custom_toolbar", "fast_mode"
    )

    // ---- Tour (21) ----

    fun isTourShown(context: Context): Boolean {
        return prefs(context).getBoolean("nobi_v11_tour_shown", false)
    }

    fun setTourShown(context: Context) {
        prefs(context).edit().putBoolean("nobi_v11_tour_shown", true).apply()
    }

    // ---- Encoding + line endings (19), per file uri ----

    fun encodingFor(context: Context, uri: String?): String {
        if (uri.isNullOrBlank()) return "UTF-8"
        return prefs(context).getString("nobi_v11_enc_$uri", "UTF-8") ?: "UTF-8"
    }

    fun setEncoding(context: Context, uri: String?, encoding: String) {
        if (uri.isNullOrBlank()) return
        prefs(context).edit().putString("nobi_v11_enc_$uri", encoding).apply()
    }

    fun lineEndingFor(context: Context, uri: String?): String {
        if (uri.isNullOrBlank()) return "LF"
        return prefs(context).getString("nobi_v11_le_$uri", "LF") ?: "LF"
    }

    fun setLineEnding(context: Context, uri: String?, ending: String) {
        if (uri.isNullOrBlank()) return
        prefs(context).edit().putString("nobi_v11_le_$uri", ending).apply()
    }

    // ---- Browser sort + filter (10) ----

    fun browserSort(context: Context): String {
        return prefs(context).getString("nobi_v11_browser_sort", "name") ?: "name"
    }

    fun setBrowserSort(context: Context, sort: String) {
        prefs(context).edit().putString("nobi_v11_browser_sort", sort).apply()
    }

    fun browserTypeFilter(context: Context): String {
        return prefs(context).getString("nobi_v11_browser_filter", "all") ?: "all"
    }

    fun setBrowserTypeFilter(context: Context, filter: String) {
        prefs(context).edit().putString("nobi_v11_browser_filter", filter).apply()
    }

    /** Extension group for the type filter: code / image / doc / other. */
    fun typeGroup(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts", "java", "py", "js", "ts", "html", "htm", "xml",
            "css", "json", "c", "cpp", "h", "sh", "gradle" -> "code"
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> "image"
            "md", "txt", "pdf", "doc", "docx" -> "doc"
            else -> "other"
        }
    }
}
