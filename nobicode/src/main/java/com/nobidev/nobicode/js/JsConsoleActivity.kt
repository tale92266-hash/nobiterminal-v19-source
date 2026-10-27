package com.nobidev.nobicode.js

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nobidev.nobicode.R

/**
 * Interactive JavaScript console inside the editor. The engine is the
 * platform WebView's JavaScript engine (no new dependency): code runs in a
 * hidden WebView via evaluateJavascript, console.log/error output is
 * captured through WebChromeClient, and the expression value is shown.
 * Previous/next buttons walk the in-memory history.
 */
class JsConsoleActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var runBtn: Button
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var clearBtn: Button

    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private val log = StringBuilder()
    private var webReady = false
    private val pending = ArrayDeque<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_js_console)
        title = "JavaScript Console"
        webView = findViewById(R.id.jsWebView)
        output = findViewById(R.id.jsOutput)
        input = findViewById(R.id.jsInput)
        runBtn = findViewById(R.id.jsRunBtn)
        prevBtn = findViewById(R.id.jsPrevBtn)
        nextBtn = findViewById(R.id.jsNextBtn)
        clearBtn = findViewById(R.id.jsClearBtn)

        webView.visibility = View.GONE
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                appendLog("[" + message.messageLevel().name.lowercase() + "] " + message.message())
                return true
            }
        }
        webView.loadDataWithBaseURL(
            null,
            "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body></body></html>",
            "text/html", "utf-8", null
        )
        // The page is blank; treat the WebView as ready shortly after load.
        webView.postDelayed({
            webReady = true
            while (pending.isNotEmpty()) evaluate(pending.removeFirst())
        }, 500)

        runBtn.setOnClickListener {
            val code = input.text.toString()
            if (code.isBlank()) return@setOnClickListener
            history.add(code)
            historyIndex = history.size
            appendLog("> " + code)
            if (webReady) evaluate(code) else pending.addLast(code)
            input.setText("")
        }
        prevBtn.setOnClickListener {
            if (history.isEmpty()) return@setOnClickListener
            if (historyIndex > 0) historyIndex--
            input.setText(history[historyIndex])
        }
        nextBtn.setOnClickListener {
            if (history.isEmpty()) return@setOnClickListener
            if (historyIndex < history.size - 1) {
                historyIndex++
                input.setText(history[historyIndex])
            } else {
                historyIndex = history.size
                input.setText("")
            }
        }
        clearBtn.setOnClickListener {
            log.clear()
            output.text = ""
        }
        appendLog("JavaScript console ready. Type an expression and press Run.")
    }

    private fun evaluate(code: String) {
        // Wrap in an IIFE-free try/catch so errors surface as text instead
        // of a silent null; the raw value comes back JSON-encoded.
        val wrapped = "(function(){ try { var __r = eval(" + jsString(code) + "); " +
            "return (typeof __r === 'undefined') ? 'undefined' : JSON.stringify(__r); } " +
            "catch (e) { return 'Error: ' + e.message; } })()"
        webView.evaluateJavascript(wrapped) { value ->
            if (value != null && value != "null") {
                appendLog(unquote(value))
            }
        }
    }

    private fun jsString(code: String): String {
        val escaped = code.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        return "'" + escaped + "'"
    }

    private fun unquote(value: String): String {
        var v = value
        // evaluateJavascript returns a JSON string for string results.
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length - 1)
            v = v.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return v
    }

    private fun appendLog(line: String) {
        if (log.isNotEmpty()) log.append("\n")
        log.append(line)
        // Keep the log bounded so a runaway loop cannot grow it forever.
        if (log.length > 100000) {
            log.delete(0, log.length - 100000)
        }
        output.text = log.toString()
    }

    override fun onDestroy() {
        try {
            webView.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
