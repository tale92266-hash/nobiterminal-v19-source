package com.nobidev.nobicode.lsp

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LSP client: talks JSON-RPC to either an external server process
 * (command line, e.g. `pylsp` installed via pkg) or the built-in
 * in-process server (loopback). Requests block up to [REQUEST_TIMEOUT_MS];
 * diagnostics notifications arrive via [diagnosticsListener].
 */
class LspClient(
    private val loopback: ((String) -> List<String>)? = null,
    private val command: List<String>? = null
) {

    data class CompletionItem(val label: String, val detail: String)
    data class Diagnostic(val line: Int, val character: Int, val message: String, val severity: Int)

    var diagnosticsListener: ((String, List<Diagnostic>) -> Unit)? = null

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CountDownLatch>()
    private val results = ConcurrentHashMap<Int, JSONObject?>()
    private var process: Process? = null
    private var writer: BufferedOutputStream? = null
    private var readerThread: Thread? = null
    @Volatile
    var running: Boolean = false
        private set

    fun start(rootUri: String): Boolean {
        if (running) return true
        try {
            if (loopback != null) {
                running = true
            } else if (command != null && command.isNotEmpty()) {
                val proc = ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
                process = proc
                writer = BufferedOutputStream(proc.outputStream)
                val reader = LspProtocol.Reader(proc.inputStream)
                readerThread = Thread({
                    while (running) {
                        val msg = try {
                            reader.readMessage()
                        } catch (e: Exception) {
                            null
                        } ?: break
                        handleIncoming(msg)
                    }
                }, "nobi-lsp-reader").also { it.isDaemon = true; it.start() }
                running = true
            } else {
                return false
            }
            // initialize handshake (best effort; builtin always answers).
            val params = JSONObject()
            params.put("processId", JSONObject.NULL)
            params.put("rootUri", rootUri)
            val caps = JSONObject()
            params.put("capabilities", caps)
            request("initialize", params, 3000)
            sendNotification("initialized", JSONObject())
            return true
        } catch (e: Exception) {
            stop()
            return false
        }
    }

    fun stop() {
        try {
            if (loopback == null && running) {
                sendNotification("shutdown", JSONObject())
                sendNotification("exit", JSONObject())
            }
        } catch (e: Exception) {
        }
        running = false
        try {
            writer?.close()
        } catch (e: Exception) {
        }
        try {
            process?.destroy()
        } catch (e: Exception) {
        }
        process = null
        writer = null
        pending.values.forEach { it.countDown() }
        pending.clear()
    }

    private fun sendRaw(json: String) {
        if (loopback != null) {
            val replies = try {
                loopback.invoke(json)
            } catch (e: Exception) {
                emptyList()
            }
            for (reply in replies) handleIncoming(reply)
            return
        }
        val w = writer ?: return
        w.write(LspProtocol.encode(json))
        w.flush()
    }

    private fun sendNotification(method: String, params: JSONObject) {
        if (!running) return
        sendRaw(LspProtocol.notification(method, params))
    }

    private fun request(method: String, params: JSONObject, timeoutMs: Long): JSONObject? {
        if (!running) return null
        val id = nextId.getAndIncrement()
        val latch = CountDownLatch(1)
        pending[id] = latch
        sendRaw(LspProtocol.request(id, method, params))
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending.remove(id)
        return results.remove(id)
    }

    private fun handleIncoming(messageJson: String) {
        val msg: JSONObject = try {
            JSONObject(messageJson)
        } catch (e: Exception) {
            return
        }
        if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
            val id = msg.optInt("id", -1)
            if (msg.has("result") && !msg.isNull("result")) {
                val r = msg.optJSONObject("result")
                results[id] = r
            } else {
                results[id] = null
            }
            pending[id]?.countDown()
            return
        }
        if (msg.optString("method", "") == "textDocument/publishDiagnostics") {
            val params = msg.optJSONObject("params") ?: return
            val uri = params.optString("uri", "")
            val arr = params.optJSONArray("diagnostics") ?: return
            val diags = mutableListOf<Diagnostic>()
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val range = d.optJSONObject("range") ?: continue
                val start = range.optJSONObject("start") ?: continue
                diags.add(
                    Diagnostic(
                        start.optInt("line", 0),
                        start.optInt("character", 0),
                        d.optString("message", ""),
                        d.optInt("severity", 1)
                    )
                )
            }
            diagnosticsListener?.invoke(uri, diags)
        }
    }

    fun didOpen(uri: String, languageId: String, text: String) {
        if (!running) return
        val params = JSONObject()
        val doc = JSONObject()
        doc.put("uri", uri)
        doc.put("languageId", languageId)
        doc.put("version", 1)
        doc.put("text", text)
        params.put("textDocument", doc)
        sendNotification("textDocument/didOpen", params)
    }

    fun didChange(uri: String, text: String) {
        if (!running) return
        val params = JSONObject()
        val doc = JSONObject()
        doc.put("uri", uri)
        doc.put("version", 2)
        params.put("textDocument", doc)
        val changes = org.json.JSONArray()
        val change = JSONObject()
        change.put("text", text)
        changes.put(change)
        params.put("contentChanges", changes)
        sendNotification("textDocument/didChange", params)
    }

    fun completion(uri: String, line: Int, character: Int, timeoutMs: Long = 800): List<CompletionItem> {
        if (!running) return emptyList()
        val params = JSONObject()
        val doc = JSONObject()
        doc.put("uri", uri)
        params.put("textDocument", doc)
        val pos = JSONObject()
        pos.put("line", line)
        pos.put("character", character)
        params.put("position", pos)
        val res = request("textDocument/completion", params, timeoutMs) ?: return emptyList()
        val out = mutableListOf<CompletionItem>()
        // Result may be a list or an object with "items".
        if (res.has("items")) {
            val items = res.optJSONArray("items") ?: return out
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                out.add(CompletionItem(it.optString("label", ""), it.optString("detail", "")))
            }
        }
        return out
    }

    fun hover(uri: String, line: Int, character: Int, timeoutMs: Long = 800): String? {
        if (!running) return null
        val params = JSONObject()
        val doc = JSONObject()
        doc.put("uri", uri)
        params.put("textDocument", doc)
        val pos = JSONObject()
        pos.put("line", line)
        pos.put("character", character)
        params.put("position", pos)
        val res = request("textDocument/hover", params, timeoutMs) ?: return null
        val contents = res.optJSONObject("contents") ?: return null
        return contents.optString("value", "")
    }

    companion object {
        const val REQUEST_TIMEOUT_MS: Long = 3000
    }
}
