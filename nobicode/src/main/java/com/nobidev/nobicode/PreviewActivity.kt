package com.nobidev.nobicode

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.StringReader
import java.lang.ref.WeakReference

class PreviewActivity : AppCompatActivity() {
    companion object {
        private var instance: WeakReference<PreviewActivity>? = null
        private var latestContent = ""
        private var latestName = ""

        fun open(context: android.content.Context, name: String, content: String) {
            latestName = name
            latestContent = content
            context.startActivity(android.content.Intent(context, PreviewActivity::class.java))
        }

        fun update(name: String, content: String) {
            latestName = name
            latestContent = content
            instance?.get()?.render(name, content)
        }
    }

    private lateinit var preview: FrameLayout
    private lateinit var error: TextView
    private var web: WebView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        instance = WeakReference(this)
        buildUi()
        render(latestName, latestContent)
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        instance = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18,18,18))
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(30,30,30))
        }

        val title = TextView(this).apply {
            text = "Live Preview"
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        bar.addView(title)
        bar.addView(close)
        root.addView(bar)

        error = TextView(this).apply {
            setTextColor(Color.rgb(255,150,150))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        root.addView(error)

        preview = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(preview)
        setContentView(root)
    }

    private fun render(name: String, content: String) {
        if (!::preview.isInitialized) return
        error.visibility = View.GONE
        preview.removeAllViews()
        web?.destroy()
        web = null
        when (name.substringAfterLast('.', "").lowercase()) {
            "xml" -> renderXml(content)
            "html", "htm" -> renderWeb(content)
            else -> showError("Live Preview supports .xml and .html files.")
        }
    }

    private fun renderWeb(content: String) {
        val v = WebView(this)
        v.settings.javaScriptEnabled = true
        v.settings.domStorageEnabled = true
        v.settings.allowFileAccess = true
        v.webViewClient = WebViewClient()
        v.webChromeClient = WebChromeClient()
        v.loadDataWithBaseURL("https://nobicode.local/", content, "text/html", "UTF-8", null)
        web = v
        preview.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    private fun renderXml(source: String) {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(source))
            val stack = ArrayDeque<View>()
            var rootView: View? = null

            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        val v = createView(parser.name)
                        applyAttrs(v, parser)
                        if (stack.isNotEmpty()) addChild(stack.last(), v) else rootView = v
                        if (v is ViewGroup) stack.addLast(v)
                    }
                    XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeLast()
                    XmlPullParser.END_DOCUMENT -> break
                }
            }

            if (rootView == null) throw IllegalArgumentException("No root view")
            preview.addView(rootView, FrameLayout.LayoutParams(-1, -1))
        } catch (e: Throwable) {
            showError("XML Preview Error: " + (e.message ?: "Invalid XML"))
        }
    }

    private fun createView(name: String): View = when {
        name.endsWith("LinearLayout") -> LinearLayout(this)
        name.endsWith("FrameLayout") -> FrameLayout(this)
        name.endsWith("ScrollView") -> ScrollView(this)
        name.endsWith("HorizontalScrollView") -> HorizontalScrollView(this)
        name.endsWith("RelativeLayout") -> RelativeLayout(this)
        name.endsWith("TextView") -> TextView(this)
        name.endsWith("Button") -> Button(this)
        name.endsWith("EditText") -> EditText(this)
        name.endsWith("CheckBox") -> CheckBox(this)
        name.endsWith("RadioButton") -> RadioButton(this)
        name.endsWith("Switch") -> Switch(this)
        name.endsWith("ImageView") -> ImageView(this)
        name.endsWith("ProgressBar") -> ProgressBar(this)
        name.endsWith("Space") -> Space(this)
        else -> LinearLayout(this)
    }

    private fun addChild(parent: View, child: View) {
        if (parent is ViewGroup) parent.addView(child)
    }

    private fun applyAttrs(v: View, p: XmlPullParser) {
        var width = -2
        var height = -2
        var weight = 0f

        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            val value = p.getAttributeValue(i)
            when (n) {
                "layout_width" -> width = size(value)
                "layout_height" -> height = size(value)
                "layout_weight" -> weight = value.toFloatOrNull() ?: 0f
                "orientation" -> if (v is LinearLayout) v.orientation =
                    if (value == "horizontal") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                "text" -> if (v is TextView) v.text = textValue(value)
                "hint" -> if (v is EditText) v.hint = textValue(value)
                "textSize" -> if (v is TextView) v.textSize = value.removeSuffix("sp").toFloatOrNull() ?: 14f
                "textColor" -> if (v is TextView) parseColor(value)?.let { v.setTextColor(it) }
                "gravity" -> if (v is TextView) v.gravity = gravity(value)
                "padding" -> v.setPadding(dpVal(value), dpVal(value), dpVal(value), dpVal(value))
                "enabled" -> v.isEnabled = value != "false"
                "visibility" -> v.visibility = when (value) {
                    "gone" -> View.GONE
                    "invisible" -> View.INVISIBLE
                    else -> View.VISIBLE
                }
                "checked" -> if (v is CompoundButton) v.isChecked = value == "true"
            }
        }

        if (v is EditText) v.inputType = InputType.TYPE_CLASS_TEXT
        v.layoutParams = LinearLayout.LayoutParams(width, height, weight)
    }

    private fun size(s: String): Int {
        val v = s.trim()
        return when {
            v == "match_parent" -> -1
            v == "wrap_content" -> -2
            v.endsWith("dp") -> dpVal(v)
            else -> v.toIntOrNull() ?: -2
        }
    }

    private fun dpVal(s: String): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            s.replace("dp", "").replace("sp", "").toFloatOrNull() ?: 0f,
            resources.displayMetrics
        ).toInt()

    private fun textValue(s: String): String =
        s.removePrefix("@string/").replace("\\n", "\n")

    private fun parseColor(s: String): Int? = try {
        if (s.startsWith("#")) Color.parseColor(s) else null
    } catch (_: Throwable) { null }

    private fun gravity(s: String): Int {
        var g = 0
        s.split("|").forEach {
            g = g or when (it.trim()) {
                "center" -> Gravity.CENTER
                "center_vertical" -> Gravity.CENTER_VERTICAL
                "center_horizontal" -> Gravity.CENTER_HORIZONTAL
                "top" -> Gravity.TOP
                "bottom" -> Gravity.BOTTOM
                "start", "left" -> Gravity.START
                "end", "right" -> Gravity.END
                else -> 0
            }
        }
        return if (g == 0) Gravity.START else g
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}