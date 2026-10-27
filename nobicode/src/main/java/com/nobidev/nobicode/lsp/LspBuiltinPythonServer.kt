package com.nobidev.nobicode.lsp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in mini language server for Python, speaking real LSP JSON-RPC.
 * It runs in-process (no binary needed) and covers: completion from
 * document words + Python keywords/builtins, hover docs for common
 * builtins, and bracket-balance diagnostics. It is intentionally small:
 * full language servers (pylsp etc.) can be configured per language in
 * LspManager and replace this server whenever the user installs one.
 */
object LspBuiltinPythonServer {

    private val keywords = listOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue",
        "def", "del", "elif", "else", "except", "False", "finally", "for",
        "from", "global", "if", "import", "in", "is", "lambda", "None",
        "nonlocal", "not", "or", "pass", "raise", "return", "True", "try",
        "while", "with", "yield"
    )

    private val builtins = mapOf(
        "print" to "print(*values, sep=' ', end='\\n') — print values to stdout.",
        "len" to "len(obj) — return the number of items of a container.",
        "range" to "range(stop) / range(start, stop[, step]) — immutable number sequence.",
        "open" to "open(file, mode='r') — open a file and return a file object.",
        "str" to "str(obj) — return a string version of an object.",
        "int" to "int(x) — convert a number or string to an integer.",
        "list" to "list(iterable) — build a list from an iterable.",
        "dict" to "dict() — build a dictionary.",
        "append" to "list.append(x) — append an item to the end of a list.",
        "split" to "str.split(sep) — split a string into a list of words.",
        "join" to "str.join(iterable) — join strings with a separator.",
        "read" to "file.read() — read file content.",
        "write" to "file.write(s) — write a string to a file.",
        "enumerate" to "enumerate(iterable) — iterate with (index, value) pairs.",
        "zip" to "zip(*iterables) — iterate several iterables in parallel.",
        "sorted" to "sorted(iterable) — return a new sorted list.",
        "sum" to "sum(iterable) — sum the items of an iterable.",
        "min" to "min(iterable) — smallest item.",
        "max" to "max(iterable) — largest item.",
        "type" to "type(obj) — return the type of an object.",
        "isinstance" to "isinstance(obj, class) — check an object's type."
    )

    private var docText: String = ""
    private var docUri: String = ""

    /** Handle one client message; returns a response and/or notification JSON. */
    fun handle(messageJson: String): List<String> {
        val out = mutableListOf<String>()
        val msg: JSONObject = try {
            JSONObject(messageJson)
        } catch (e: Exception) {
            return out
        }
        val method = msg.optString("method", "")
        val hasId = msg.has("id")
        val id = msg.optInt("id", -1)
        when (method) {
            "initialize" -> {
                if (hasId) {
                    val caps = JSONObject()
                    caps.put("completionProvider", JSONObject().put("triggerCharacters", JSONArray(listOf(".", "_"))))
                    caps.put("hoverProvider", true)
                    caps.put("textDocumentSync", 1)
                    val result = JSONObject()
                    result.put("capabilities", caps)
                    out.add(LspProtocol.response(id, result))
                }
            }
            "initialized", "$/cancelRequest" -> {
                // No-op.
            }
            "textDocument/didOpen", "textDocument/didChange" -> {
                val params = msg.optJSONObject("params") ?: return out
                val doc = params.optJSONObject("textDocument") ?: return out
                docUri = doc.optString("uri", docUri)
                if (method.endsWith("didOpen")) {
                    docText = doc.optString("text", "")
                } else {
                    val changes = params.optJSONArray("contentChanges")
                    if (changes != null && changes.length() > 0) {
                        val last = changes.getJSONObject(changes.length() - 1)
                        if (last.has("text")) docText = last.optString("text", docText)
                    }
                }
                out.add(diagnosticsNotification())
            }
            "textDocument/completion" -> {
                if (!hasId) return out
                val params = msg.optJSONObject("params") ?: return out
                val pos = params.optJSONObject("position") ?: return out
                val line = pos.optInt("line", 0)
                val ch = pos.optInt("character", 0)
                val prefix = currentWord(line, ch)
                val items = JSONArray()
                val seen = LinkedHashSet<String>()
                val words = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(docText)
                    .map { it.value }.toSet()
                for (w in keywords + builtins.keys + words) {
                    if (w.startsWith(prefix) && w != prefix && seen.add(w)) {
                        val item = JSONObject()
                        item.put("label", w)
                        item.put("detail", builtins[w] ?: "keyword")
                        items.put(item)
                    }
                }
                val result = JSONObject()
                result.put("isIncomplete", false)
                result.put("items", items)
                out.add(LspProtocol.response(id, result))
            }
            "textDocument/hover" -> {
                if (!hasId) return out
                val params = msg.optJSONObject("params") ?: return out
                val pos = params.optJSONObject("position") ?: return out
                val line = pos.optInt("line", 0)
                val ch = pos.optInt("character", 0)
                val word = currentWord(line, ch)
                val doc = builtins[word]
                if (doc != null) {
                    val result = JSONObject()
                    val contents = JSONObject()
                    contents.put("kind", "markdown")
                    contents.put("value", "**" + word + "** — " + doc)
                    result.put("contents", contents)
                    out.add(LspProtocol.response(id, result))
                } else {
                    out.add(LspProtocol.response(id, JSONObject.NULL))
                }
            }
            "shutdown" -> {
                if (hasId) out.add(LspProtocol.response(id, JSONObject.NULL))
            }
            "exit" -> {
                // No-op.
            }
            else -> {
                if (hasId) out.add(LspProtocol.errorResponse(id, -32601, "Method not supported by built-in server"))
            }
        }
        return out
    }

    private fun currentWord(line: Int, ch: Int): String {
        val lines = docText.split("\n")
        if (line !in lines.indices) return ""
        val text = lines[line]
        val upto = ch.coerceIn(0, text.length)
        var start = upto
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return text.substring(start, upto)
    }

    private fun diagnosticsNotification(): String {
        val params = JSONObject()
        params.put("uri", docUri)
        val diags = JSONArray()
        // Bracket-balance scan, strings/comments aware (same conservative
        // approach as the editor's own bracket diagnostics).
        val stack = ArrayDeque<Pair<Char, Int>>()
        var inString = false
        var stringDelim = '"'
        var inLineComment = false
        var inBlockComment = false
        val lines = docText.split("\n")
        for ((lineNo, raw) in lines.withIndex()) {
            var i = 0
            inLineComment = false
            while (i < raw.length) {
                val c = raw[i]
                if (inBlockComment) {
                    if (c == '*' && i + 1 < raw.length && raw[i + 1] == '/') {
                        inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (inString) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == stringDelim) inString = false
                    i++
                    continue
                }
                if (inLineComment) break
                if (c == '#') {
                    inLineComment = true
                    break
                }
                if (c == '"' || c == '\'') {
                    inString = true
                    stringDelim = c
                    i++
                    continue
                }
                if (c == '(' || c == '[' || c == '{') {
                    stack.addLast(c to lineNo)
                    i++
                    continue
                }
                if (c == ')' || c == ']' || c == '}') {
                    val open = when (c) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (stack.isEmpty() || stack.last().first != open) {
                        diags.put(makeDiag(lineNo, i, "Unmatched closing bracket '" + c + "'"))
                    } else {
                        stack.removeLast()
                    }
                    i++
                    continue
                }
                i++
            }
        }
        while (stack.isNotEmpty()) {
            val (open, lineNo) = stack.removeLast()
            diags.put(makeDiag(lineNo, 0, "Unclosed bracket '" + open + "'"))
        }
        params.put("diagnostics", diags)
        return LspProtocol.notification("textDocument/publishDiagnostics", params)
    }

    private fun makeDiag(line: Int, ch: Int, message: String): JSONObject {
        val d = JSONObject()
        val range = JSONObject()
        val start = JSONObject()
        start.put("line", line)
        start.put("character", ch)
        val end = JSONObject()
        end.put("line", line)
        end.put("character", ch + 1)
        range.put("start", start)
        range.put("end", end)
        d.put("range", range)
        d.put("severity", 1)
        d.put("message", message)
        d.put("source", "nobi-lsp")
        return d
    }
}
