package com.nobidev.nobicode.lsp

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Owns at most one LSP client per language extension. Configuration lives in
 * `nobi_lsp` prefs: `<ext>` -> server command line ("" = off).
 * Special value "builtin" selects the in-process server (Python only for
 * now; other languages fall back to off with a clear status).
 *
 * Completion flow (never blocks the UI thread): the editor calls
 * [noteDocumentChanged] on every edit; a debounced background fetch updates
 * [cachedCompletions] and [diagnostics]. The editor merges the cache into
 * its suggestion popup when the prefix still matches.
 */
object LspManager {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nobi-lsp").also { it.isDaemon = true }
    }

    private val clients = ConcurrentHashMap<String, LspClient>()
    private val diagnosticsByUri = ConcurrentHashMap<String, List<LspClient.Diagnostic>>()

    @Volatile
    var cachedPrefix: String = ""
        private set

    @Volatile
    var cachedCompletions: List<String> = emptyList()
        private set

    @Volatile
    var cachedExt: String = ""
        private set

    @Volatile
    var lastStatus: String = "Off"
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("nobi_lsp", Context.MODE_PRIVATE)
    }

    fun serverCommand(ext: String): String {
        val p = prefs ?: return ""
        if (ext == "py" && !p.contains("py")) return "builtin"
        return p.getString(ext, "") ?: ""
    }

    fun setServerCommand(ext: String, command: String) {
        prefs?.edit()?.putString(ext, command)?.apply()
        stop(ext)
    }

    fun isActive(ext: String): Boolean = clients.containsKey(ext)

    fun activeLanguages(): List<String> = clients.keys().toList()

    fun diagnosticsFor(uri: String): List<LspClient.Diagnostic> =
        diagnosticsByUri[uri] ?: emptyList()

    fun start(ext: String, rootUri: String): Boolean {
        if (clients.containsKey(ext)) return true
        val cmd = serverCommand(ext)
        val client: LspClient = when {
            cmd == "builtin" && ext == "py" -> LspClient(loopback = { json -> LspBuiltinPythonServer.handle(json) })
            cmd == "builtin" -> return false
            cmd.isNotBlank() -> {
                val parts = cmd.trim().split(Regex("\\s+"))
                LspClient(command = parts)
            }
            else -> return false
        }
        client.diagnosticsListener = { uri, diags ->
            diagnosticsByUri[uri] = diags
        }
        val ok = try {
            client.start(rootUri)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            lastStatus = "Failed to start server for .$ext"
            return false
        }
        clients[ext] = client
        lastStatus = "Running (.$ext)"
        return true
    }

    fun stop(ext: String) {
        clients.remove(ext)?.stop()
        if (clients.isEmpty()) lastStatus = "Off"
    }

    fun stopAll() {
        clients.values.forEach { it.stop() }
        clients.clear()
        lastStatus = "Off"
    }

    fun openDocument(ext: String, uri: String, languageId: String, text: String) {
        clients[ext]?.didOpen(uri, languageId, text)
    }

    /**
     * Called by the editor on text change. Debounced: the actual LSP round
     * trip runs on the background executor and only refreshes the cache.
     */
    fun noteDocumentChanged(ext: String, uri: String, text: String, line: Int, character: Int, prefix: String) {
        val client = clients[ext] ?: return
        cachedExt = ext
        executor.execute {
            try {
                client.didChange(uri, text)
                // Diagnostics for the builtin server arrive via the
                // notification path above; for external servers the same.
                val items = client.completion(uri, line, character, 800)
                if (prefix.isNotEmpty()) {
                    cachedPrefix = prefix
                    cachedCompletions = items.map { it.label }
                }
            } catch (e: Exception) {
            }
        }
    }

    fun hover(ext: String, uri: String, line: Int, character: Int): String? {
        val client = clients[ext] ?: return null
        return try {
            client.hover(uri, line, character, 800)
        } catch (e: Exception) {
            null
        }
    }

    /** Human-readable status lines for the LSP settings dialog. */
    fun statusLines(): List<String> {
        val out = mutableListOf<String>()
        val exts = listOf("py", "kt", "java", "js", "ts", "c", "cpp", "html", "css", "json", "sh", "go", "rs")
        for (ext in exts) {
            val cmd = serverCommand(ext)
            val state = when {
                clients.containsKey(ext) -> "running"
                cmd == "builtin" && ext == "py" -> "builtin available"
                cmd.isNotBlank() -> "configured: $cmd"
                else -> "off"
            }
            out.add(".$ext — $state")
        }
        return out
    }
}
