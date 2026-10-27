package com.nobidev.nobicode.editor

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import com.nobidev.nobicode.theme.EditorTheme
import java.util.regex.Pattern

class SyntaxHighlighter {

    private var KEYWORD_COLOR = Color.parseColor("#BB9AF7")   // Soft Purple
    private var STRING_COLOR = Color.parseColor("#9ECE6A")    // Mint Green
    private var COMMENT_COLOR = Color.parseColor("#565F89")   // Grayish Blue
    private var NUMBER_COLOR = Color.parseColor("#FF9E64")    // Orange
    private var TYPE_COLOR = Color.parseColor("#2AC3DE")      // Cyan
    private var ANNOTATION_COLOR = Color.parseColor("#E0AF68")// Golden Yellow

    /** Re-tint syntax colors from an [EditorTheme]. */
    fun updateColors(theme: EditorTheme) {
        KEYWORD_COLOR = theme.keyword
        STRING_COLOR = theme.string
        COMMENT_COLOR = theme.comment
        NUMBER_COLOR = theme.number
        TYPE_COLOR = theme.type
        ANNOTATION_COLOR = theme.annotation
    }

    private val keywordsPattern = Pattern.compile(
        "\\b(fun|val|var|class|interface|object|enum|if|else|when|for|while|return|package|import|public|private|protected|internal|abstract|override|open|sealed|data|companion|def|self|async|await|try|catch|finally|throw|function|const|let|new|typeof|instanceof|import|from|export|default|struct|namespace|using|static|void|int|float|double|boolean|char|true|false|null|nil)\\b"
    )
    private val stringPattern = Pattern.compile("\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'")
    private val commentPattern = Pattern.compile("//.*|/\\*(?:.|[\\n\\r])*?\\*/|#.*")
    private val numberPattern = Pattern.compile("\\b(\\d+(\\.\\d+)?|0x[0-9a-fA-F]+)\\b")
    private val typePattern = Pattern.compile("\\b[A-Z][a-zA-Z0-9_]*\\b")
    private val annotationPattern = Pattern.compile("@[a-zA-Z0-9_]+")

    fun highlight(editable: Editable) {
        // Clear previous highlight spans
        val existingSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        for (span in existingSpans) {
            editable.removeSpan(span)
        }

        applyPattern(editable, keywordsPattern, KEYWORD_COLOR)
        applyPattern(editable, numberPattern, NUMBER_COLOR)
        applyPattern(editable, typePattern, TYPE_COLOR)
        applyPattern(editable, annotationPattern, ANNOTATION_COLOR)
        applyPattern(editable, stringPattern, STRING_COLOR)
        applyPattern(editable, commentPattern, COMMENT_COLOR)
    }

    private fun applyPattern(editable: Editable, pattern: Pattern, color: Int) {
        val matcher = pattern.matcher(editable)
        while (matcher.find()) {
            editable.setSpan(
                ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
