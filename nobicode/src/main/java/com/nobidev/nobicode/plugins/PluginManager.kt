package com.nobidev.nobicode.plugins

import android.content.Context
import com.nobidev.nobicode.theme.EditorTheme
import com.nobidev.nobicode.theme.PluginThemes
import org.json.JSONObject
import java.io.File

/**
 * Plugin system (JSON manifests, no arbitrary code execution — a plugin can
 * never run code, which keeps the editor safe).
 *
 * Supported plugin types:
 * - theme:     { "colors": { "background": "#RRGGBB", "text": ..., ... } }
 *              registers an EditorTheme selectable like built-in themes.
 * - formatter: { "indentSize": 2, "trimTrailing": true, "finalNewline": true,
 *              "languages": ["py", "js"] } — used by format-on-save when the
 *              plugin is enabled and the file language matches.
 * - snippets:  { "snippets": [{ "prefix": "log", "body": "...",
 *              "language": "js" }] } — prefixes join the suggestion popup;
 *              accepting one inserts its body.
 * - ai-provider: { "endpoint": "https://...", "model": "..." } — an
 *              OpenAI-compatible provider for the AI assistant. The API key
 *              is NEVER stored in the manifest; it is entered in AI settings.
 *
 * Plugins live in filesDir/nobi_plugins/<id>/plugin.json. Import accepts a
 * plugin.json file or a .zip containing one at its root. Two sample plugins
 * ship in assets/nobi_plugins/ and are copied in on first use.
 */
object PluginManager {

    data class Plugin(
        val id: String,
        val name: String,
        val version: String,
        val type: String,
        val dir: File,
        val manifest: JSONObject,
        var enabled: Boolean
    )

    data class Snippet(val prefix: String, val body: String, val language: String)

    private const val PREFS = "nobi_plugins"
    private const val DISABLED_KEY = "disabled_ids"

    fun pluginDir(context: Context): File =
        File(context.filesDir, "nobi_plugins").also { if (!it.exists()) it.mkdirs() }

    fun list(context: Context): List<Plugin> {
        val dir = pluginDir(context)
        val disabled = disabledIds(context)
        val out = mutableListOf<Plugin>()
        val children = dir.listFiles() ?: return out
        for (child in children) {
            if (!child.isDirectory) continue
            val manifestFile = File(child, "plugin.json")
            if (!manifestFile.isFile) continue
            try {
                val manifest = JSONObject(manifestFile.readText())
                val id = manifest.optString("id", child.name)
                out.add(
                    Plugin(
                        id = id,
                        name = manifest.optString("name", id),
                        version = manifest.optString("version", ""),
                        type = manifest.optString("type", "unknown"),
                        dir = child,
                        manifest = manifest,
                        enabled = id !in disabled
                    )
                )
            } catch (e: Exception) {
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun disabledIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(DISABLED_KEY, emptySet()) ?: emptySet()
    }

    fun setEnabled(context: Context, plugin: Plugin, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val disabled = disabledIds(context).toMutableSet()
        if (enabled) disabled.remove(plugin.id) else disabled.add(plugin.id)
        prefs.edit().putStringSet(DISABLED_KEY, disabled).apply()
        plugin.enabled = enabled
        if (plugin.type == "theme") {
            if (enabled) registerTheme(plugin) else PluginThemes.unregister(plugin.id)
        }
    }

    /** Install a plugin.json file (or a .zip containing one) into the dir. */
    fun install(context: Context, source: File): Result<String> {
        return try {
            val dir = pluginDir(context)
            val isZip = source.name.endsWith(".zip", ignoreCase = true) || hasZipMagic(source)
            if (isZip) {
                installZip(dir, source)
            } else {
                val manifest = JSONObject(source.readText())
                val id = manifest.optString("id", "").ifBlank { return Result.failure(Exception("plugin.json has no id")) }
                val target = File(dir, sanitize(id))
                target.mkdirs()
                source.copyTo(File(target, "plugin.json"), overwrite = true)
                Result.success(id)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun hasZipMagic(file: File): Boolean {
        return try {
            file.inputStream().use { inp -> inp.read() == 0x50 && inp.read() == 0x4B }
        } catch (e: Exception) {
            false
        }
    }

    private fun installZip(dir: File, zip: File): Result<String> {
        var manifestText: String? = null
        var manifestId = ""
        java.util.zip.ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val entry = entries.firstOrNull { it.name == "plugin.json" }
                ?: entries.firstOrNull { it.name.endsWith("/plugin.json") }
                ?: return Result.failure(Exception("No plugin.json in zip"))
            manifestText = zf.getInputStream(entry).bufferedReader().readText()
            val manifest = JSONObject(manifestText!!)
            manifestId = manifest.optString("id", "")
            if (manifestId.isBlank()) return Result.failure(Exception("plugin.json has no id"))
            val target = File(dir, sanitize(manifestId))
            target.mkdirs()
            for (e in entries) {
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (name.isEmpty()) continue
                val out = File(target, name)
                zf.getInputStream(e).use { inp -> out.outputStream().use { o -> inp.copyTo(o) } }
            }
        }
        return Result.success(manifestId)
    }

    private fun sanitize(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /** Copy bundled sample plugins from assets on first use. */
    fun installSamplesIfEmpty(context: Context) {
        val dir = pluginDir(context)
        val existing = dir.listFiles()?.any { File(it, "plugin.json").isFile } ?: false
        if (existing) return
        try {
            val names = context.assets.list("nobi_plugins") ?: return
            for (name in names) {
                val target = File(dir, name)
                target.mkdirs()
                context.assets.open("nobi_plugins/$name/plugin.json").use { inp ->
                    File(target, "plugin.json").outputStream().use { o -> inp.copyTo(o) }
                }
            }
        } catch (e: Exception) {
        }
    }

    /** Apply all enabled theme plugins to the runtime registry. */
    fun refreshThemes(context: Context) {
        for (plugin in list(context)) {
            if (plugin.enabled && plugin.type == "theme") registerTheme(plugin)
        }
    }

    private fun registerTheme(plugin: Plugin) {
        try {
            val colors = plugin.manifest.optJSONObject("colors") ?: return
            fun color(key: String, fallback: Int): Int {
                val raw = colors.optString(key, "")
                if (raw.isBlank()) return fallback
                return try {
                    android.graphics.Color.parseColor(raw)
                } catch (e: Exception) {
                    fallback
                }
            }
            val base = com.nobidev.nobicode.theme.Themes.DEFAULT
            val theme = EditorTheme(
                id = "plugin:" + plugin.id,
                displayName = plugin.name,
                isDark = plugin.manifest.optBoolean("dark", true),
                background = color("background", base.background),
                surface = color("surface", base.surface),
                text = color("text", base.text),
                caret = color("caret", base.caret),
                selection = color("selection", base.selection),
                currentLine = color("currentLine", base.currentLine),
                gutterBg = color("gutterBg", base.gutterBg),
                gutterText = color("gutterText", base.gutterText),
                gutterActive = color("gutterActive", base.gutterActive),
                keyword = color("keyword", base.keyword),
                string = color("string", base.string),
                comment = color("comment", base.comment),
                number = color("number", base.number),
                function = color("function", base.function),
                type = color("type", base.type),
                annotation = color("annotation", base.annotation)
            )
            PluginThemes.register(theme)
        } catch (e: Exception) {
        }
    }

    /** Enabled formatter plugin matching [ext], if any. */
    fun formatterFor(context: Context, ext: String): JSONObject? {
        for (plugin in list(context)) {
            if (!plugin.enabled || plugin.type != "formatter") continue
            val langs = plugin.manifest.optJSONArray("languages") ?: continue
            for (i in 0 until langs.length()) {
                if (langs.optString(i).equals(ext, ignoreCase = true)) return plugin.manifest
            }
        }
        return null
    }

    /** Enabled snippet prefixes for [ext] (prefix -> body). */
    fun snippetsFor(context: Context, ext: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (plugin in list(context)) {
            if (!plugin.enabled || plugin.type != "snippets") continue
            val arr = plugin.manifest.optJSONArray("snippets") ?: continue
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val lang = s.optString("language", "")
                if (lang.isBlank() || lang.equals(ext, ignoreCase = true)) {
                    val prefix = s.optString("prefix", "")
                    val body = s.optString("body", "")
                    if (prefix.isNotBlank() && body.isNotBlank()) out[prefix] = body
                }
            }
        }
        return out
    }

    /** Enabled ai-provider plugin, if any (endpoint + model, never a key). */
    fun aiProviderManifest(context: Context): JSONObject? {
        for (plugin in list(context)) {
            if (!plugin.enabled || plugin.type != "ai-provider") continue
            return plugin.manifest
        }
        return null
    }

    fun delete(context: Context, plugin: Plugin): Boolean {
        return try {
            plugin.dir.deleteRecursively()
            PluginThemes.unregister(plugin.id)
            true
        } catch (e: Exception) {
            false
        }
    }
}
