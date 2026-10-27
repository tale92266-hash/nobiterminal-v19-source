package com.nobidev.nobicode.lsp

import org.json.JSONObject
import java.io.InputStream

/**
 * Minimal JSON-RPC (LSP) framing: `Content-Length: N\r\n\r\n<body>`.
 * Uses org.json (Android framework) — no new dependency.
 */
object LspProtocol {

    fun encode(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: " + body.size + "\r\n\r\n"
        return header.toByteArray(Charsets.US_ASCII) + body
    }

    fun request(id: Int, method: String, params: JSONObject): String {
        val o = JSONObject()
        o.put("jsonrpc", "2.0")
        o.put("id", id)
        o.put("method", method)
        o.put("params", params)
        return o.toString()
    }

    fun notification(method: String, params: JSONObject): String {
        val o = JSONObject()
        o.put("jsonrpc", "2.0")
        o.put("method", method)
        o.put("params", params)
        return o.toString()
    }

    fun response(id: Int, result: Any?): String {
        val o = JSONObject()
        o.put("jsonrpc", "2.0")
        o.put("id", id)
        o.put("result", result ?: JSONObject.NULL)
        return o.toString()
    }

    fun errorResponse(id: Int, code: Int, message: String): String {
        val o = JSONObject()
        o.put("jsonrpc", "2.0")
        o.put("id", id)
        val e = JSONObject()
        e.put("code", code)
        e.put("message", message)
        o.put("error", e)
        return o.toString()
    }

    /** Blocking reader for one framed message; null on EOF. */
    class Reader(private val input: InputStream) {
        fun readMessage(): String? {
            var contentLength = -1
            val headerBuf = StringBuilder()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b == -1) return null
                headerBuf.append(b.toChar())
                if (prev == '\r'.code && b == '\n'.code) {
                    // End of one header line; check for the blank line.
                    val text = headerBuf.toString()
                    if (text.endsWith("\r\n\r\n")) break
                    // Check whether the last line itself was blank: the
                    // accumulated text ends with two CRLFs only at the end.
                    if (text.endsWith("\n\n")) break
                }
                prev = b
                if (headerBuf.length > 8192) return null
            }
            val headerText = headerBuf.toString()
            for (line in headerText.split("\r\n", "\n")) {
                val t = line.trim()
                if (t.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = t.substringAfter(":").trim().toIntOrNull() ?: -1
                }
            }
            if (contentLength < 0) return null
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n == -1) return null
                read += n
            }
            return String(body, Charsets.UTF_8)
        }
    }
}
