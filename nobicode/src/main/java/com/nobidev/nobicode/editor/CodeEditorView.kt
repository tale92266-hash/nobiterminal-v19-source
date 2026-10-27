package com.nobidev.nobicode.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import com.nobidev.nobicode.R
import com.nobidev.nobicode.git.GitManager.DiffHunk
import com.nobidev.nobicode.theme.EditorTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.Executors

class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onUndoRedoStateChanged: (() -> Unit)? = null
    var onModifiedListener: (() -> Unit)? = null
    var showLineNumbers: Boolean = true
    var onCursorPositionChanged: ((Int, Int) -> Unit)? = null
    var onSelectionStatsChanged: ((Int, Int, Int) -> Unit)? = null
    var onFontSizeChanged: ((Float) -> Unit)? = null
    var onEditorScrollChanged: (() -> Unit)? = null
    var autoCloseBrackets: Boolean = true
    var tabSize: Int = 4
    var foldingEnabled: Boolean = true
    var stickyScrollEnabled: Boolean = true
        set(value) { field = value; stickyDirty = true; invalidate() }
    private var stickyHeaderLine: Int = -1
    private var stickyHeaderText: String = ""
    private var stickyDirty: Boolean = true
    private var lastStickyCursorLine: Int = -1
    private val stickyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var highlightCurrentLine: Boolean = true
    var indentGuidesEnabled: Boolean = true
    var currentFileExtension: String = ""
    var currentFileUri: String = ""

    // v7 (35): vim-style modal editing. Works with hardware keys (onKeyDown)
    // and soft keyboards (InputConnection wrapper): in normal mode every
    // single-char input is a command, never inserted text. Visual mode and
    // count prefixes (e.g. "3j") are NOT implemented.
    var vimMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                vimInsert = false
                vimPendingD = false
                vimPendingG = false
                try { onVimModeChanged?.invoke(false) } catch (e: Exception) { }
            }
        }
    var onVimModeChanged: ((insert: Boolean) -> Unit)? = null
    var onVimCommandPalette: (() -> Unit)? = null
    private var vimInsert = true
    private var vimPendingD = false
    private var vimPendingG = false
    var showWhitespace: Boolean = false
    var rainbowBrackets: Boolean = false
    var fontLigaturesEnabled: Boolean = false
        set(value) {
            field = value
            fontFeatureSettings = if (value) "liga=1,calt=1" else "liga=0,calt=0"
        }
    var lineGuideColumn: Int = 0
    var emmetEnabled: Boolean = false
    var syntaxHighlightEnabled: Boolean = false
        set(value) {
            field = value
            // PERF: toggle par highlight range reset (dobara full visible highlight hoga)
            hlStartLine = -1
            hlEndLine = -1
            if (!value) {
                highlightRunnable?.let { uiHandler.removeCallbacks(it) }
                scrollHlRunnable?.let { uiHandler.removeCallbacks(it) }
                highlightVersion++ // background results discard
                val s = text ?: return
                isHighlighting = true
                try {
                    for (span in s.getSpans(0, s.length, ForegroundColorSpan::class.java)) {
                        s.removeSpan(span)
                    }
                } finally {
                    isHighlighting = false
                }
            } else {
                scheduleHighlight()
            }
        }
    var autoIndentEnabled: Boolean = true
    var pinchZoomEnabled: Boolean = true

    private data class EditAction(val start: Int, val beforeText: CharSequence, val afterText: CharSequence, val groupId: Int = 0)
    private val undoStack = ArrayDeque<EditAction>()
    private val redoStack = ArrayDeque<EditAction>()
    private var isUndoOrRedo = false
    private var isProgrammaticChange = false
    private var beforeText: CharSequence? = null
    private var afterText: CharSequence? = null
    private var startIdx: Int = 0
    private var wrapSelText: String? = null
    private var isHighlighting = false
    private var isSmartTyping = false

    // ===== v6 code suggestions (19) =====
    // Popup anchored under the caret; non-focusable so typing keeps going
    // into the editor. isSuggestionInsert only suppresses the popup refresh
    // (NOT undo capture) so accepting a suggestion stays one Undo step.
    private var suggestionPopup: PopupWindow? = null
    private var isSuggestionInsert = false
    private var suggestionDismissGuard = false

    // ===== v6 error underline (20) =====
    // Custom CharacterStyle (NOT ForegroundColorSpan): SyntaxHighlighter
    // har pass me saare ForegroundColorSpan wipe karta hai, isliye error
    // span us type ka hota to har highlight par gayab ho jata. Ye span
    // sirf underline lagata hai, rang highlighter ka hi rehta hai.
    class BracketErrorSpan(val reason: String) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            tp.isUnderlineText = true
        }
    }

    private data class BracketError(val offset: Int, val reason: String)
    private var bracketScanRunnable: Runnable? = null
    private var largeFileMode: Boolean = false
    private var smartTypingEditEnd: Int = -1
    private var smartTypingNetZero: Boolean = false

    // ===== Multi-cursor editing (VS Code jaisa) =====
    // extraCarets: secondary caret offsets. Primary caret = EditText ka apna selection.
    private val extraCarets = mutableListOf<Int>()
    private var isMultiCursorEdit = false
    private var isUpdatingCarets = false
    private var multiCursorGroupId = 0
    private val multiCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B7CF6")
    }
    // Git diff gutter paints: added = green, modified = violet, deleted = red.
    private val diffAddPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50") }
    private val diffModPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8B7CF6") }
    private val diffDelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F44336") }
    /** HEAD vs current text ki diff hunks (0-based lines) — MainActivity set karta hai. */
    var gitDiffHunks: List<DiffHunk> = emptyList()
    // Inline color swatches: hex color ke baad chhota rang-biranga box, tap = color picker.
    private data class ColorSwatch(val start: Int, val end: Int, val color: Int)
    private var swatchList: List<ColorSwatch> = emptyList()
    private var swatchesDirty: Boolean = true
    private val swatchBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#5A5F7A")
    }
    private val swatchFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Swatch par tap hone par MainActivity color picker kholta hai (range inclusive). */
    var onColorSwatchTap: ((IntRange) -> Unit)? = null
    // Box (column) selection: drag se rectangle, har line par ek caret.
    var boxSelectMode: Boolean = false
    private var boxDragging: Boolean = false
    private var boxAnchorLine: Int = 0
    private var boxAnchorCol: Int = 0
    private var boxCurLine: Int = 0
    private var boxCurCol: Int = 0
    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B7CF6")
        alpha = 40
    }
    private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#8B7CF6")
    }
    // Focus mode: caret wale scope ke siva sab lines dim.
    var focusMode: Boolean = false
    private var focusRange: IntRange? = null
    private var focusCaretLine: Int = -1
    private val focusDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 110
    }

    private val collapsedFolds = mutableMapOf<Int, String>()
    private val foldableCache = mutableMapOf<Int, Int>()
    private var isFoldingOp = false
    private val FOLD_PLACEHOLDER = " \u2026 "
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#8B7CF6")
    }

    private var gutterWidth = 90
    private val gutterPaint = Paint().apply { color = Color.parseColor("#12131B") }
    private val highlightPaint = Paint()
    private val indentGuidePaint = Paint()
    private val whitespacePaint = Paint()
    private val bracketPaint = Paint().apply { color = Color.parseColor("#598B7CF6") }
    private val scopeGuidePaint = Paint().apply { color = Color.parseColor("#808B7CF6") }
    private val rainbowPaints = listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#598B7CF6") },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5960A5FA },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5934D399 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FBBF24 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59F472B6 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FB923C }
    )
    private val tagHlPaint = Paint().apply { color = 0x408B7CF6 }
    private var caretWord: String? = null
    private var lastEditOffset = -1
    private val bookmarks = sortedSetOf<Int>()
    private val bookmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#8B7CF6") }
    private val wordHlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bracketPair: Pair<Int, Int>? = null
    private var scopeGuidePair: Pair<Int, Int>? = null
    private var tagHlPair: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null
    private val bracketPairs = mapOf('(' to ')', '[' to ']', '{' to '}', ')' to '(', ']' to '[', '}' to '{')
    private val openBrackets = setOf('(', '[', '{')
    private val closeBrackets = setOf(')', ']', '}')
    private val gutterLinePaint = Paint().apply {
        color = Color.parseColor("#2A2D3E")
        strokeWidth = 2f
    }
    private val lineNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = 32f
        textAlign = Paint.Align.RIGHT
    }

    private val lineNumActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B7CF6")
        textSize = 32f
        textAlign = Paint.Align.RIGHT
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var highlightRunnable: Runnable? = null
    // PERF: background thread for heavy regex highlighting (single thread = no overlap)
    private val highlightExecutor = Executors.newSingleThreadExecutor()
    // PERF: version tracking — stale background results discard ho jayenge
    @Volatile private var highlightVersion = 0
    // PERF: precompiled regexes (Regex() construction har call par expensive hai)
    private val hlCommentRegex = Regex("//.*")
    private val hlStringRegex = Regex("\"([^\"\\\\]|\\\\.)*\"")
    private val hlNumberRegex = Regex("\\b\\d+(\\.\\d+)?\\b")
    private val hlKeywords = "fun|val|var|class|object|interface|if|else|when|for|while|do|return|import|package|public|private|protected|internal|open|override|abstract|final|const|lateinit|companion|data|sealed|enum|by|in|is|as|try|catch|finally|throw|this|super|null|true|false"
    private val hlKeywordRegex = Regex("\\b($hlKeywords)\\b")
    private val hlFuncRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
    // PERF: last highlighted line range — scroll par sirf naya area highlight hoga
    // v11 follow-up (2): large-file fast mode — tighter visible-range buffer.
    var fastHighlightBuffer: Int = 120
        private set
    fun applyFastMode(large: Boolean) {
        fastHighlightBuffer = if (large) 40 else 120
        hlStartLine = -1
        hlEndLine = -1
    }
    private var hlStartLine = -1
    private var hlEndLine = -1
    // PERF: cached density (onDraw me har bar resources access expensive hai)
    private var cachedDensity = 0f
    // PERF: cached bold typeface (Typeface.create har onDraw par expensive hai)
    private var cachedBoldTypeface: Typeface? = null
    private var cachedBoldFor: Typeface? = null
    private var lastFindStart: Int = 0

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newSize = (textSize * detector.scaleFactor).coerceIn(24f, 120f)
                if (abs(newSize - textSize) > 1f) {
                    val newSp = newSize / resources.displayMetrics.scaledDensity
                    textSize = newSp
                    lineNumPaint.textSize = newSize * 0.85f
                    foldPaint.textSize = newSize * 0.9f
                    updateGutterWidth()
                    onFontSizeChanged?.invoke(newSp)
                }
                return true
            }
        })

    /** BUGFIX: true jab init block poora chal jaye — usse pehle onSelectionChanged jaise
     * overrides me uninitialized properties access karne se NPE aata hai. */
    private var initComplete = false

    init {
        // The JetBrains Mono font files are not part of the integrated
        // module, so the editor uses the system monospace typeface.
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        setBackgroundColor(Color.parseColor("#1A1B26"))
        setTextColor(Color.parseColor("#C0CAF5"))
        setHighlightColor(Color.parseColor("#3B4261"))
        setLineSpacing(14f, 1.4f)
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        lineNumPaint.typeface = Typeface.MONOSPACE
        // PERF: density ek bar cache karo
        cachedDensity = resources.displayMetrics.density

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isUndoOrRedo && !isProgrammaticChange && !isFoldingOp && !isSmartTyping && !isMultiCursorEdit && s != null) {
                    beforeText = s.subSequence(start, start + count).toString()
                    afterText = null
                    startIdx = start
                    wrapSelText = if (after == 1 && selectionStart != selectionEnd)
                        s.subSequence(min(selectionStart, selectionEnd), max(selectionStart, selectionEnd)).toString()
                    else null
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s == null || isUndoOrRedo || isProgrammaticChange || isFoldingOp || isSmartTyping || isMultiCursorEdit) return
                if (beforeText != null) {
                    afterText = if (count > 0) s.subSequence(start, start + count).toString() else ""
                }
                if (count == 1 && before == 0) {
                    smartTypingEditEnd = -1
                    smartTypingNetZero = false
                    handleSmartTyping(s[start].toString(), start)
                    if (smartTypingNetZero) {
                        beforeText = null
                        afterText = null
                    } else if (smartTypingEditEnd > startIdx && beforeText != null) {
                        val cur = text
                        if (cur != null && smartTypingEditEnd <= cur.length) {
                            afterText = cur.subSequence(startIdx, smartTypingEditEnd).toString()
                        }
                    }
                }
                if (count == 1 && before > 0 && wrapSelText != null) {
                    tryWrapSelection(s, start, wrapSelText!!)
                    wrapSelText = null
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (s == null || isHighlighting) return
                if (!isFoldingOp) foldableCache.clear()
                stickyDirty = true
                if (!isUndoOrRedo && !isProgrammaticChange && !isFoldingOp && !isSmartTyping && !isMultiCursorEdit && beforeText != null) {
                    undoStack.addLast(EditAction(startIdx, beforeText!!, afterText ?: ""))
                    if (undoStack.size > 200) undoStack.removeFirst()
                    redoStack.clear()
                    beforeText = null
                    afterText = null
                }
                updateGutterWidth()
                scheduleHighlight()
                // v6 (19): refresh the suggestion popup on real typing only.
                if (!isUndoOrRedo && !isProgrammaticChange && !isFoldingOp && !isSmartTyping && !isMultiCursorEdit && !isSuggestionInsert) {
                    updateSuggestions()
                }
                // v6 (20): bracket scan har text change par (file load bhi);
                // runnable khud highlight pass ke dauran defer ho jata hai.
                scheduleBracketScan()
                // Fold collapse/expand se text badalta hai lekin code nahi badla -
                // isliye dirty/save trigger mat karo (Sam: fold par save button na aaye)
                if (!isFoldingOp) {
                    onModifiedListener?.invoke()
                }
                onUndoRedoStateChanged?.invoke()
            }
        })
        initComplete = true
    }

    /** Text select karke bracket ya quote dabane par selection us pair me lapet jata hai — VS Code jaisa. */
    private fun tryWrapSelection(s: CharSequence?, start: Int, wrapped: String) {
        if (!autoCloseBrackets) return
        val ed = text ?: return
        if (s == null || start < 0 || start >= s.length) return
        val (opener, closer) = when (s[start]) {
            '(', ')' -> "(" to ")"
            '[', ']' -> "[" to "]"
            '{', '}' -> "{" to "}"
            '"' -> "\"" to "\""
            '\'' -> "'" to "'"
            else -> return
        }
        isSmartTyping = true
        try {
            ed.replace(start, start + 1, opener)
            ed.insert(start + 1, wrapped)
            ed.insert(start + 1 + wrapped.length, closer)
            setSelection(start + 1, start + 1 + wrapped.length)
        } finally {
            isSmartTyping = false
        }
    }

    private fun handleSmartTyping(inserted: String, start: Int) {
        val editable = text ?: return
        when (inserted) {
            "\n" -> {
                if (!autoIndentEnabled) return
                isSmartTyping = true
                try {
                    val lineStart = editable.lastIndexOf("\n", start - 1).let { if (it == -1) 0 else it + 1 }
                    val prevLine = editable.substring(lineStart, start)
                    val indent = prevLine.takeWhile { it == ' ' || it == '\t' }
                    val trimmedEnd = prevLine.trimEnd()
                    val extra = if (trimmedEnd.endsWith("{") || trimmedEnd.endsWith("(") || trimmedEnd.endsWith("[")) "    " else ""
                    val nextChar = if (start + 1 < editable.length) editable[start + 1] else null
                    if (trimmedEnd.endsWith("{") && nextChar == '}') {
                        editable.insert(start + 1, "\n$indent}")
                        editable.insert(start + 1, "$indent    ")
                        setSelection(start + 1 + indent.length + 4)
                    } else {
                        editable.insert(start + 1, indent + extra)
                        setSelection(start + 1 + indent.length + extra.length)
                    }
                } finally {
                    isSmartTyping = false
                }
            }
            "{", "(", "[" -> {
                if (!autoCloseBrackets) return
                val closer = mapOf("{" to "}", "(" to ")", "[" to "]")[inserted]!!
                isSmartTyping = true
                try {
                    editable.insert(start + 1, closer)
                    setSelection(start + 1)
                    smartTypingEditEnd = start + 2
                } finally {
                    isSmartTyping = false
                }
            }
            "}", ")", "]" -> {
                if (start + 1 < editable.length && editable[start + 1].toString() == inserted) {
                    isSmartTyping = true
                    try {
                        editable.delete(start, start + 1)
                        setSelection(start + 1)
                        smartTypingNetZero = true
                    } finally {
                        isSmartTyping = false
                    }
                } else if (autoIndentEnabled) {
                    // Naya: khaali-indent wali line par }/) type ho to matching opener
                    // ke indent par dedent karo (VS Code jaisa).
                    val lineStart = editable.lastIndexOf("\n", start - 1).let { if (it == -1) 0 else it + 1 }
                    val beforeText = editable.substring(lineStart, start)
                    if (beforeText.isNotEmpty() && beforeText.all { it == ' ' || it == '\t' }) {
                        val bp = computeBracketPair()
                        if (bp != null) {
                            val openerLineStart = editable.lastIndexOf("\n", bp.first - 1)
                                .let { if (it == -1) 0 else it + 1 }
                            val openerIndent = editable.substring(openerLineStart, bp.first)
                                .takeWhile { it == ' ' || it == '\t' }
                            if (openerIndent.length < beforeText.length) {
                                isSmartTyping = true
                                try {
                                    editable.replace(lineStart, start, openerIndent)
                                    setSelection(lineStart + openerIndent.length + 1)
                                } finally {
                                    isSmartTyping = false
                                }
                            }
                        }
                    }
                }
            }
            "\"", "'" -> {
                if (start + 1 < editable.length && editable[start + 1].toString() == inserted) {
                    isSmartTyping = true
                    try {
                        editable.delete(start, start + 1)
                        setSelection(start + 1)
                        smartTypingNetZero = true
                    } finally {
                        isSmartTyping = false
                    }
                } else if (autoCloseBrackets) {
                    isSmartTyping = true
                    try {
                        editable.insert(start + 1, inserted)
                        setSelection(start + 1)
                    } finally {
                        isSmartTyping = false
                    }
                }
            }
            ">" -> {
                tryAutoCloseTag(editable, start)
            }
        }
    }

    private val voidHtmlElements = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )

    private fun isHtmlLikeFile(): Boolean {
        return currentFileExtension in setOf("html", "htm", "xhtml", "xml", "vue", "svg")
    }

    /**
     * `>` type hone par HTML/XML me tag auto-close karta hai: `<div>` → `<div></div>`
     * (cursor beech me). Void elements, closing tags, comments aur pehle se closed tags skip.
     */
    private fun tryAutoCloseTag(editable: Editable, gtPos: Int) {
        if (!isHtmlLikeFile()) return
        if (gtPos <= 0 || gtPos >= editable.length) return
        if (editable[gtPos - 1] == '/') return // self-closing `<br/>`
        var lt = gtPos - 1
        while (lt >= 0 && editable[lt] != '<' && editable[lt] != '>') lt--
        if (lt < 0 || editable[lt] != '<') return
        val nameStart = lt + 1
        if (nameStart < gtPos) {
            val c0 = editable[nameStart]
            if (c0 == '/' || c0 == '!' || c0 == '?') return // `</div>`, `<!--`, `<?xml`
        }
        // attribute quote ke andar `<` ho to skip (odd quotes = andar)
        var quotes = 0
        var q = nameStart
        while (q < gtPos) {
            if (editable[q] == '"' || editable[q] == '\'') quotes++
            q++
        }
        if (quotes % 2 == 1) return
        var nEnd = nameStart
        while (nEnd < gtPos) {
            val c = editable[nEnd]
            if (!(c.isLetterOrDigit() || c == '-' || c == '_' || c == ':')) break
            nEnd++
        }
        val tagName = editable.substring(nameStart, nEnd)
        if (tagName.isEmpty()) return
        if (tagName.lowercase() in voidHtmlElements) return
        var fwd = gtPos + 1
        while (fwd < editable.length && editable[fwd] == ' ') fwd++
        if (fwd + 1 < editable.length && editable[fwd] == '<' && editable[fwd + 1] == '/') return
        isSmartTyping = true
        try {
            editable.insert(gtPos + 1, "</$tagName>")
            setSelection(gtPos + 1)
        } finally {
            isSmartTyping = false
        }
    }

    /**
     * Selected lines (ya current line) ka indent ek level badhata/ghatata hai.
     * Ek hi edit hota hai, isliye Undo ek baar me wapas laata hai.
     */
    fun indentSelection(increase: Boolean) {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val (rangeStart, rangeEnd) = selectedLineRange(ed)
        val original = ed.substring(rangeStart, rangeEnd)
        val lines = original.split('\n')
        val level = tabSize.coerceAtLeast(1)
        val newLines = lines.map { line ->
            if (increase) {
                if (line.isBlank()) line else " ".repeat(level) + line
            } else {
                var n = 0
                while (n < line.length && n < level) {
                    val c = line[n]
                    if (c == '\t') { n++; break }
                    if (c != ' ') break
                    n++
                }
                line.substring(n)
            }
        }
        val newText = newLines.joinToString("\n")
        if (newText == original) return
        ed.replace(rangeStart, rangeEnd, newText)
        setSelection(rangeStart, (rangeStart + newText.length).coerceAtMost(ed.length))
    }

    // PERF: visible line range + buffer (scroll par naya area highlight hoga)
    private fun getHighlightLineRange(): Pair<Int, Int> {
        val l = layout
        val total = lineCount
        if (l == null || total <= 0) return 0 to 0
        val first = (scrollY / lineHeight).coerceAtLeast(0)
        val last = ((scrollY + height) / lineHeight + 1).coerceAtMost(total - 1)
        val buf = fastHighlightBuffer
        return (first - buf).coerceAtLeast(0) to (last + buf).coerceAtMost(total - 1)
    }

    private fun scheduleHighlight() {
        if (!syntaxHighlightEnabled) return
        highlightRunnable?.let { uiHandler.removeCallbacks(it) }
        highlightRunnable = Runnable {
            val s = text ?: return@Runnable
            if (isHighlighting) return@Runnable
            isHighlighting = true
            highlightVersion++
            val version = highlightVersion
            val (startLine, endLine) = getHighlightLineRange()
            // Text snapshot UI thread par lo (background me Editable change ho sakta hai)
            val fullText = s.toString()
            val l = layout
            val rangeStart: Int
            val rangeEnd: Int
            if (l != null && s.length > 0) {
                rangeStart = l.getLineStart(startLine).coerceIn(0, fullText.length)
                rangeEnd = l.getLineEnd(endLine).coerceIn(0, fullText.length)
            } else {
                rangeStart = 0
                rangeEnd = fullText.length
            }
            val rangeText = if (rangeStart < rangeEnd) fullText.substring(rangeStart, rangeEnd) else ""
            highlightExecutor.execute {
                try {
                    val spans = computeHighlightSpans(rangeText, fullText, rangeStart, rangeEnd)
                    if (version != highlightVersion) return@execute
                    uiHandler.post {
                        if (version != highlightVersion) { isHighlighting = false; return@post }
                        try {
                            applyComputedSpans(s, spans, rangeStart, rangeEnd)
                            hlStartLine = startLine
                            hlEndLine = endLine
                        } finally {
                            isHighlighting = false
                        }
                    }
                } catch (e: Exception) {
                    uiHandler.post { isHighlighting = false }
                }
            }
        }
        uiHandler.postDelayed(highlightRunnable!!, 600)
    }

    // PERF: scroll par agar naya area dikha to highlight karo (debounced)
    private var scrollHlRunnable: Runnable? = null
    private fun scheduleHighlightOnScroll() {
        if (!syntaxHighlightEnabled) return
        val (startLine, endLine) = getHighlightLineRange()
        // Agar visible range pehle se highlighted hai to kuch mat karo
        if (hlStartLine >= 0 && startLine >= hlStartLine && endLine <= hlEndLine) return
        scrollHlRunnable?.let { uiHandler.removeCallbacks(it) }
        scrollHlRunnable = Runnable { scheduleHighlightNow() }
        uiHandler.postDelayed(scrollHlRunnable!!, 250)
    }

    private fun scheduleHighlightNow() {
        highlightRunnable?.let { uiHandler.removeCallbacks(it) }
        scrollHlRunnable?.let { uiHandler.removeCallbacks(it) }
        val s = text ?: return
        if (isHighlighting) return
        isHighlighting = true
        highlightVersion++
        val version = highlightVersion
        val (startLine, endLine) = getHighlightLineRange()
        val fullText = s.toString()
        val l = layout
        val rangeStart: Int
        val rangeEnd: Int
        if (l != null && s.length > 0) {
            rangeStart = l.getLineStart(startLine).coerceIn(0, fullText.length)
            rangeEnd = l.getLineEnd(endLine).coerceIn(0, fullText.length)
        } else {
            rangeStart = 0
            rangeEnd = fullText.length
        }
        val rangeText = if (rangeStart < rangeEnd) fullText.substring(rangeStart, rangeEnd) else ""
        highlightExecutor.execute {
            try {
                val spans = computeHighlightSpans(rangeText, fullText, rangeStart, rangeEnd)
                if (version != highlightVersion) return@execute
                uiHandler.post {
                    if (version != highlightVersion) { isHighlighting = false; return@post }
                    try {
                        applyComputedSpans(s, spans, rangeStart, rangeEnd)
                        hlStartLine = startLine
                        hlEndLine = endLine
                    } finally {
                        isHighlighting = false
                    }
                }
            } catch (e: Exception) {
                uiHandler.post { isHighlighting = false }
            }
        }
    }

    fun clearHighlighting() {
        val s = text ?: return
        for (span in s.getSpans(0, s.length, android.text.style.ForegroundColorSpan::class.java)) {
            s.removeSpan(span)
        }
    }

    // PERF: highlight colors (ek bar parse, har call par nahi)
    private val hlKeywordColor = Color.parseColor("#C678DD")
    private val hlStringColor = Color.parseColor("#98C379")
    private val hlCommentColor = Color.parseColor("#5C6370")
    private val hlNumberColor = Color.parseColor("#D19A66")
    private val hlFuncColor = Color.parseColor("#61AFEF")
    private val hlDepthColors = intArrayOf(
        Color.parseColor("#8B7CF6"),
        Color.parseColor("#61AFEF"),
        Color.parseColor("#98C379"),
        Color.parseColor("#E5C07B"),
        Color.parseColor("#E06C75"),
        Color.parseColor("#C678DD")
    )

    // PERF: background thread par span positions compute karo (koi UI/Editable touch nahi)
    private data class HlSpan(val start: Int, val end: Int, val color: Int)

    private fun computeHighlightSpans(rangeText: String, fullText: String, rangeStart: Int, rangeEnd: Int): List<HlSpan> {
        val out = ArrayList<HlSpan>(512)
        for (m in hlCommentRegex.findAll(rangeText)) {
            out.add(HlSpan(rangeStart + m.range.first, rangeStart + m.range.last + 1, hlCommentColor))
        }
        for (m in hlStringRegex.findAll(rangeText)) {
            out.add(HlSpan(rangeStart + m.range.first, rangeStart + m.range.last + 1, hlStringColor))
        }
        for (m in hlNumberRegex.findAll(rangeText)) {
            out.add(HlSpan(rangeStart + m.range.first, rangeStart + m.range.last + 1, hlNumberColor))
        }
        for (m in hlKeywordRegex.findAll(rangeText)) {
            out.add(HlSpan(rangeStart + m.range.first, rangeStart + m.range.last + 1, hlKeywordColor))
        }
        for (m in hlFuncRegex.findAll(rangeText)) {
            val g = m.groups[1] ?: continue
            out.add(HlSpan(rangeStart + g.range.first, rangeStart + g.range.last + 1, hlFuncColor))
        }
        computeBracketPairSpans(fullText, rangeStart, rangeEnd, out)
        return out
    }

    // PERF: UI thread par sirf computed spans apply karo (range ke bahar ke spans mat chhedo)
    private fun applyComputedSpans(s: Editable, spans: List<HlSpan>, rangeStart: Int, rangeEnd: Int) {
        val rs = rangeStart.coerceIn(0, s.length)
        val re = rangeEnd.coerceIn(0, s.length)
        if (rs < re) {
            for (span in s.getSpans(rs, re, ForegroundColorSpan::class.java)) {
                s.removeSpan(span)
            }
        }
        for (h in spans) {
            val a = h.start.coerceIn(0, s.length)
            val b = h.end.coerceIn(0, s.length)
            if (a < b) {
                s.setSpan(ForegroundColorSpan(h.color), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /**
     * Bracket pair colorization (VS Code jaisa): (), [], {} ko nesting depth ke
     * hisaab se rang do. Strings/comments ke andar wale brackets skip.
     * Background thread par chalta hai — full text scan (depth ke liye zaroori),
     * lekin spans sirf visible range ke liye emit hote hain.
     */
    private fun computeBracketPairSpans(fullText: String, rangeStart: Int, rangeEnd: Int, out: MutableList<HlSpan>) {
        if (fullText.length > 300000) return
        var depth = 0
        var i = 0
        val n = fullText.length
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = 0
        while (i < n) {
            val c = fullText[i]
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment > 0 -> {
                    if (c == '*' && i + 1 < n && fullText[i + 1] == '/') { inBlockComment--; i++ }
                    else if (c == '/' && i + 1 < n && fullText[i + 1] == '*') { inBlockComment++; i++ }
                }
                inString -> {
                    if (c == '\\') i++
                    else if (c == '"') inString = false
                }
                inChar -> {
                    if (c == '\\') i++
                    else if (c == '\'') inChar = false
                }
                c == '/' && i + 1 < n && fullText[i + 1] == '/' -> { inLineComment = true; i++ }
                c == '/' && i + 1 < n && fullText[i + 1] == '*' -> { inBlockComment++; i++ }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' || c == '[' || c == '{' -> {
                    if (i >= rangeStart && i < rangeEnd) {
                        out.add(HlSpan(i, i + 1, hlDepthColors[depth % hlDepthColors.size]))
                    }
                    depth++
                }
                c == ')' || c == ']' || c == '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    if (i >= rangeStart && i < rangeEnd) {
                        out.add(HlSpan(i, i + 1, hlDepthColors[depth % hlDepthColors.size]))
                    }
                }
            }
            i++
        }
    }

    private fun updateGutterWidth() {
        if (!showLineNumbers) return
        val digits = max(2, lineCount.toString().length)
        val charWidth = lineNumPaint.measureText("8")
        gutterWidth = (digits * charWidth + 36f).toInt()
        setPadding(gutterWidth + 28, paddingTop, paddingRight, paddingBottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val stickyH = stickyVisibleHeight()
        if (event.actionMasked == MotionEvent.ACTION_DOWN && stickyH > 0 && event.y < stickyH) {
            jumpToStickyHeader()
            return true
        }
        if (stickyH > 0) event.offsetLocation(0f, -stickyH.toFloat())
        if (boxSelectMode && event.actionMasked == MotionEvent.ACTION_DOWN && event.x > gutterWidth) {
            val bl = layout
            if (bl != null) {
                val bLine = ((event.y + scrollY) / lineHeight).toInt()
                    .coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                val lx = event.x + scrollX - paddingLeft
                val off = bl.getOffsetForHorizontal(bLine, lx)
                    .coerceIn(bl.getLineStart(bLine), bl.getLineEnd(bLine))
                boxAnchorLine = bLine
                boxAnchorCol = off - bl.getLineStart(bLine)
                boxCurLine = bLine
                boxCurCol = boxAnchorCol
                boxDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
        }
        if (boxDragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val bl = layout
            if (bl != null) {
                val bLine = ((event.y + scrollY) / lineHeight).toInt()
                    .coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                val lx = event.x + scrollX - paddingLeft
                val off = bl.getOffsetForHorizontal(bLine, lx)
                    .coerceIn(bl.getLineStart(bLine), bl.getLineEnd(bLine))
                boxCurLine = bLine
                boxCurCol = off - bl.getLineStart(bLine)
                invalidate()
                return true
            }
        }
        if (boxDragging && (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            boxDragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
            if (event.actionMasked == MotionEvent.ACTION_UP) commitBoxSelection()
            boxSelectMode = false
            invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && swatchList.isNotEmpty() && onColorSwatchTap != null) {
            val tapLayout = layout
            if (tapLayout != null) {
                if (swatchesDirty) rebuildSwatches()
                val visualLine = ((event.y + scrollY) / lineHeight).toInt()
                    .coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                val tapLine = if (collapsedFolds.isNotEmpty()) mapVisualToExpanded(visualLine) else visualLine
                val tDensity = resources.displayMetrics.density
                val tTextX = (scrollX + paddingLeft).toFloat()
                val tx = event.x + scrollX
                val ty = event.y + scrollY
                val slopH = 8f * tDensity
                val slopV = 14f * tDensity
                for (sw in swatchList) {
                    if (tapLayout.getLineForOffset(sw.start.coerceIn(0, length())) != tapLine) continue
                    val sx = tTextX + tapLayout.getPrimaryHorizontal(sw.end.coerceIn(0, length()))
                    val midY = (scrollY + (tapLayout.getLineTop(tapLine) + tapLayout.getLineBottom(tapLine)) / 2f).toFloat()
                    val l = sx + 5f * tDensity
                    if (tx >= l - slopH && tx <= l + 15f * tDensity + slopH &&
                        ty >= midY - slopV && ty <= midY + slopV) {
                        onColorSwatchTap?.invoke(sw.start until sw.end)
                        return true
                    }
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && showLineNumbers && !isFoldingOp && foldingEnabled) {
            val visualLine = ((event.y + scrollY) / lineHeight).toInt()
                .coerceIn(0, (lineCount - 1).coerceAtLeast(0))
            if (event.x < gutterWidth) {
                if (collapsedFolds.containsKey(visualLine) || getFoldEnd(visualLine) > visualLine) {
                    toggleFold(visualLine)
                    return true
                }
            } else if (collapsedFolds.isNotEmpty()) {
                val expandedLine = mapVisualToExpanded(visualLine)
                expandAllFolds()
                val layout = layout
                if (layout != null && expandedLine < lineCount) {
                    val ls = layout.getLineStart(expandedLine)
                    val le = layout.getLineEnd(expandedLine)
                    val contentX = event.x - totalPaddingLeft + scrollX
                    val offset = layout.getOffsetForHorizontal(expandedLine, contentX).coerceIn(ls, le)
                    requestFocus()
                    setSelection(offset)
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(this, 0)
                }
                return true
            }
        }
        // v6 (20): underlined bracket error par tap = reason Toast.
        if (event.actionMasked == MotionEvent.ACTION_UP && !boxDragging) {
            val tapLayout = layout
            val ed = text
            if (tapLayout != null && ed != null) {
                val tapLine = ((event.y + scrollY) / lineHeight).toInt()
                    .coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                val tapOff = tapLayout.getOffsetForHorizontal(tapLine, event.x + scrollX - paddingLeft)
                    .coerceIn(tapLayout.getLineStart(tapLine), tapLayout.getLineEnd(tapLine))
                val spans = ed.getSpans(tapOff, tapOff, BracketErrorSpan::class.java)
                val hit = spans.firstOrNull { span ->
                    val s = ed.getSpanStart(span)
                    val e = ed.getSpanEnd(span)
                    tapOff in s until e
                }
                if (hit != null) {
                    Toast.makeText(context, hit.reason, Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (pinchZoomEnabled) scaleDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && hasMultiCursors()) {
            if (event.action == KeyEvent.ACTION_UP) {
                clearMultiCursors()
                Toast.makeText(context, "Multi-cursor off", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (vimMode && event?.action == KeyEvent.ACTION_DOWN) {
            if (!vimInsert) {
                if (vimHandleKeyCode(keyCode, event)) return true
            } else if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                setVimInsert(false)
                return true
            }
        }
        if (hasMultiCursors() && event?.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    deleteAtAllCarets()
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    clearMultiCursors()
                    Toast.makeText(context, "Multi-cursor off", Toast.LENGTH_SHORT).show()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Arrow keys: wapas single-caret navigation.
                    clearMultiCursors()
                }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_TAB && event?.action == KeyEvent.ACTION_DOWN) {
            if (tryTabExpand()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        // Multi-cursor me IME input (type/backspace) saare carets par lagta hai.
        return object : InputConnectionWrapper(ic, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // v7 (35): vim normal mode — single chars are commands, never text.
                if (vimMode && !vimInsert && !text.isNullOrEmpty()) {
                    if (text.length == 1) vimHandleChar(text[0])
                    return true
                }
                if (hasMultiCursors() && !text.isNullOrEmpty()) {
                    insertAtAllCarets(text.toString())
                    return true
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (hasMultiCursors() && !text.isNullOrEmpty()) {
                    insertAtAllCarets(text.toString())
                    return true
                }
                return super.setComposingText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // v7 (35): backspace in vim normal mode moves left (like h).
                if (vimMode && !vimInsert && beforeLength == 1 && afterLength == 0) {
                    vimMoveLeft()
                    return true
                }
                if (hasMultiCursors()) {
                    if (beforeLength == 1 && afterLength == 0) {
                        deleteAtAllCarets()
                    }
                    // Forward-delete multi-cursor me supported nahi - consume karke ignore.
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (hasMultiCursors() && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            deleteAtAllCarets()
                            return true
                        }
                        KeyEvent.KEYCODE_ESCAPE -> {
                            clearMultiCursors()
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    fun tryEmmetExpand(): Boolean {
        val editable = text ?: return false
        val cursor = selectionStart
        if (cursor <= 0 || selectionEnd != cursor) return false
        val layout = layout ?: return false
        val line = layout.getLineForOffset(cursor)
        val lineStart = layout.getLineStart(line)
        val beforeCursor = editable.substring(lineStart, cursor)
        val abbr = extractAbbreviation(beforeCursor)
        if (abbr.isEmpty()) return false
        val lineFull = editable.substring(lineStart, layout.getLineEnd(line).coerceAtMost(editable.length))
        val baseIndent = lineFull.takeWhile { it == ' ' || it == '\t' }
        val expanded = EmmetExpander.expand(abbr, baseIndent) ?: return false
        val abbrStart = cursor - abbr.length
        editable.replace(abbrStart, cursor, expanded)
        val emptyTag = expanded.indexOf("></")
        if (emptyTag != -1) {
            setSelection(abbrStart + emptyTag + 1)
        } else {
            setSelection((abbrStart + expanded.length).coerceAtMost(editable.length))
        }
        return true
    }

    /** Tab dabane par: pehle Emmet (HTML/XML), phir code snippet, warna false. */
    fun tryTabExpand(): Boolean {
        if (emmetEnabled && tryEmmetExpand()) return true
        return trySnippetExpand()
    }

    // Code snippets: keyword + Tab = poora template (VS Code jaisa). `|` = caret position.
    private val kotlinSnippets = mapOf(
        "fun" to "fun name() {\n    |\n}",
        "fori" to "for (i in 0 until n) {\n    |\n}",
        "if" to "if (|) {\n    \n}",
        "when" to "when (|) {\n    \n}",
        "while" to "while (|) {\n    \n}",
        "try" to "try {\n    |\n} catch (e: Exception) {\n    \n}",
        "class" to "class Name {\n    |\n}",
        "logd" to "Log.d(TAG, \"|\")",
        "todo" to "// TODO: |"
    )
    private val javaSnippets = mapOf(
        "psvm" to "public static void main(String[] args) {\n    |\n}",
        "sout" to "System.out.println(|);",
        "fori" to "for (int i = 0; i < n; i++) {\n    |\n}",
        "try" to "try {\n    |\n} catch (Exception e) {\n    \n}",
        "todo" to "// TODO: |"
    )

    /** Caret se pehle wala word snippet keyword ho to expand karo (single undo step). */
    fun trySnippetExpand(): Boolean {
        val editable = text ?: return false
        val cursor = selectionStart
        if (cursor <= 0 || selectionEnd != cursor) return false
        var i = cursor - 1
        while (i >= 0 && (editable[i].isLetterOrDigit() || editable[i] == '_')) i--
        val word = editable.substring(i + 1, cursor)
        if (word.isEmpty()) return false
        val snippets = if (currentFileExtension.lowercase() == "java") javaSnippets else kotlinSnippets
        val template = snippets[word] ?: return false
        val caretMark = template.indexOf('|')
        val body = if (caretMark >= 0) template.removeRange(caretMark, caretMark + 1) else template
        editable.replace(i + 1, cursor, body)
        val newCaret = (i + 1 + if (caretMark >= 0) caretMark else body.length).coerceIn(0, editable.length)
        setSelection(newCaret)
        return true
    }

    private fun extractAbbreviation(beforeCursor: String): String {
        var i = beforeCursor.length - 1
        var braceDepth = 0
        var bracketDepth = 0
        val sb = StringBuilder()
        while (i >= 0) {
            val ch = beforeCursor[i]
            when {
                ch == '}' -> braceDepth++
                ch == '{' -> {
                    if (braceDepth == 0) break
                    braceDepth--
                }
                ch == ']' -> bracketDepth++
                ch == '[' -> {
                    if (bracketDepth == 0) break
                    bracketDepth--
                }
                ch.isWhitespace() && braceDepth == 0 && bracketDepth == 0 -> break
                !isEmmetChar(ch) && braceDepth == 0 && bracketDepth == 0 -> break
            }
            sb.append(ch)
            i--
        }
        return sb.reverse().toString()
    }

    private fun isEmmetChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch in ">+*#.[]{}^$()-_=\"'/:!@|="

    override fun onDraw(canvas: Canvas) {
        if (stickyDirty) {
            recomputeStickyHeader()
            stickyDirty = false
        }
        val stickyH = stickyVisibleHeight()
        canvas.save()
        if (stickyH > 0) canvas.translate(0f, stickyH.toFloat())
        if (showLineNumbers) {
            val left = scrollX.toFloat()
            val right = left + gutterWidth
            canvas.drawRect(left, 0f, right, height.toFloat() + scrollY, gutterPaint)
            canvas.drawLine(right, 0f, right, height.toFloat() + scrollY, gutterLinePaint)
            val layout = layout
            if (layout != null) {
                val totalLines = lineCount
                val baseline = baseline
                val lineSpacing = lineHeight
                val firstVisible = (scrollY / lineSpacing).coerceAtLeast(0)
                val lastVisible = ((scrollY + height) / lineSpacing + 1).coerceAtMost(totalLines - 1)
                lineNumActivePaint.textSize = lineNumPaint.textSize
                // PERF: Typeface.create har onDraw par expensive hai — cache karo
                if (cachedBoldFor !== lineNumPaint.typeface || cachedBoldTypeface == null) {
                    cachedBoldTypeface = Typeface.create(lineNumPaint.typeface, Typeface.BOLD)
                    cachedBoldFor = lineNumPaint.typeface
                }
                lineNumActivePaint.typeface = cachedBoldTypeface
                val caretLine = layout.getLineForOffset(selectionStart.coerceIn(0, length()))
                for (i in firstVisible..lastVisible) {
                    val y = baseline + (i * lineSpacing).toFloat()
                    val lnp = if (i == caretLine) lineNumActivePaint else lineNumPaint
                    canvas.drawText((i + 1).toString(), right - 12f, y, lnp)
                    if (bookmarks.contains(i)) {
                        val bmR = 5f * resources.displayMetrics.density
                        canvas.drawCircle(left + 16f, y - lineSpacing * 0.32f, bmR, bookmarkPaint)
                    }
                    if (gitDiffHunks.isNotEmpty()) {
                        var dPaint: Paint? = null
                        for (h in gitDiffHunks) {
                            if (h.beginB < h.endB && i >= h.beginB && i < h.endB) {
                                dPaint = if (h.type == "INSERT") diffAddPaint else diffModPaint
                                break
                            } else if (h.beginB == h.endB && i == h.beginB) {
                                dPaint = diffDelPaint
                                break
                            }
                        }
                        if (dPaint != null) {
                            val dW = 3f * resources.displayMetrics.density
                            canvas.drawRect(left + 2f, y - lineSpacing + 3f, left + 2f + dW, y - 3f, dPaint)
                        }
                    }
                    if (foldingEnabled && !largeFileMode) {
                        val chev = when {
                            collapsedFolds.containsKey(i) -> "\u25B6"
                            getFoldEnd(i) > i -> "\u25BC"
                            else -> null
                        }
                        if (chev != null) {
                            canvas.drawText(chev, left + 30f, y - 2f, foldPaint)
                        }
                    }
                }
            }
        }
        if (swatchesDirty) rebuildSwatches()
        if (swatchList.isNotEmpty()) {
            val sl = layout
            val sed = text
            if (sl != null && sed != null) {
                val sDensity = resources.displayMetrics.density
                val sTextX = (scrollX + paddingLeft).toFloat()
                val sTotal = lineCount
                val sFirst = (scrollY / lineHeight).coerceAtLeast(0)
                val sLast = ((scrollY + height) / lineHeight + 1).coerceAtMost(sTotal - 1)
                val swW = 15f * sDensity
                val swH = 11f * sDensity
                val swGap = 5f * sDensity
                val swR = 3f * sDensity
                for (sw in swatchList) {
                    val so = sw.start.coerceIn(0, sed.length)
                    val sLine = sl.getLineForOffset(so)
                    if (sLine < sFirst || sLine > sLast) continue
                    val sx = sTextX + sl.getPrimaryHorizontal(sw.end.coerceIn(0, sed.length))
                    val sMidY = (scrollY + (sl.getLineTop(sLine) + sl.getLineBottom(sLine)) / 2f).toFloat()
                    val l = sx + swGap
                    val t = sMidY - swH / 2f
                    swatchFillPaint.color = sw.color
                    canvas.drawRoundRect(l, t, l + swW, t + swH, swR, swR, swatchFillPaint)
                    canvas.drawRoundRect(l, t, l + swW, t + swH, swR, swR, swatchBorderPaint)
                }
            }
        }
        if (boxDragging) {
            val bdl = layout
            if (bdl != null) {
                val top = minOf(boxAnchorLine, boxCurLine).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                val bottom = maxOf(boxAnchorLine, boxCurLine).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                val bTextX = (scrollX + paddingLeft).toFloat()
                fun colX(line: Int, col: Int): Float {
                    val ls = bdl.getLineStart(line)
                    val le = bdl.getLineEnd(line)
                    return bTextX + bdl.getPrimaryHorizontal(ls + col.coerceIn(0, le - ls))
                }
                val x1 = colX(boxAnchorLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0)), boxAnchorCol)
                val x2 = colX(boxCurLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0)), boxCurCol)
                val rt = (scrollY + bdl.getLineTop(top)).toFloat()
                val rb = (scrollY + bdl.getLineBottom(bottom)).toFloat()
                canvas.drawRect(minOf(x1, x2), rt, maxOf(x1, x2), rb, boxFillPaint)
                canvas.drawRect(minOf(x1, x2), rt, maxOf(x1, x2), rb, boxBorderPaint)
            }
        }
        if (focusMode) {
            val fl = layout
            if (fl != null) {
                val caretLine = fl.getLineForOffset(selectionStart.coerceIn(0, length()))
                if (caretLine != focusCaretLine) updateFocusRange(caretLine)
                val fr = focusRange
                if (fr != null) {
                    val totalLines = lineCount
                    val fFirst = (scrollY / lineHeight).coerceAtLeast(0)
                    val fLast = ((scrollY + height) / lineHeight + 1).coerceAtMost(totalLines - 1)
                    val dimLeft = (scrollX + if (showLineNumbers) gutterWidth else 0).toFloat()
                    val dimRight = (scrollX + width).toFloat()
                    for (i in fFirst..fLast) {
                        if (i in fr) continue
                        val top = (scrollY + fl.getLineTop(i)).toFloat()
                        val bottom = (scrollY + fl.getLineBottom(i)).toFloat()
                        canvas.drawRect(dimLeft, top, dimRight, bottom, focusDimPaint)
                    }
                }
            }
        }
        if (highlightCurrentLine) {
            val l = layout
            val caret = selectionStart
            if (l != null && caret == selectionEnd) {
                val line = l.getLineForOffset(caret.coerceIn(0, length()))
                val top = (paddingTop + l.getLineTop(line)).toFloat()
                val bottom = (paddingTop + l.getLineBottom(line)).toFloat()
                val hlLeft = scrollX + if (showLineNumbers) gutterWidth else 0
                canvas.drawRect(hlLeft.toFloat(), top, (scrollX + width).toFloat(), bottom, highlightPaint)
            }
        }
        if (indentGuidesEnabled) {
            val gl = layout
            val ed = text
            if (gl != null && ed != null && tabSize > 0) {
                val spaceW = paint.measureText(" ")
                if (spaceW > 0) {
                    val textX = (scrollX + paddingLeft).toFloat()
                    val totalLines = lineCount
                    val firstVisible = (scrollY / lineHeight).coerceAtLeast(0)
                    val lastVisible = ((scrollY + height) / lineHeight + 1).coerceAtMost(totalLines - 1)
                    for (i in firstVisible..lastVisible) {
                        val lineStart = gl.getLineStart(i)
                        val lineEnd = gl.getLineEnd(i)
                        var col = 0
                        var q = lineStart
                        while (q < lineEnd) {
                            val ch = ed[q]
                            if (ch == ' ') col++
                            else if (ch == '\t') col += tabSize - (col % tabSize)
                            else break
                            q++
                        }
                        val levels = col / tabSize
                        if (levels > 0) {
                            val gTop = (scrollY + gl.getLineTop(i)).toFloat()
                            val gBottom = (scrollY + gl.getLineBottom(i)).toFloat()
                            for (lv in 1..levels) {
                                val gx = textX + lv * tabSize * spaceW
                                if (gx < scrollX + width) {
                                    canvas.drawLine(gx, gTop, gx, gBottom, indentGuidePaint)
                                }
                            }
                        }
                    }
                }
            }
        }
        val sgp = scopeGuidePair
        val sgl = layout
        if (sgp != null && sgl != null && !largeFileMode) {
            val sed = text
            if (sed != null && tabSize > 0) {
                val oLine = sgl.getLineForOffset(sgp.first.coerceIn(0, sed.length))
                val cLine = sgl.getLineForOffset(sgp.second.coerceIn(0, sed.length))
                if (cLine > oLine) {
                    // Opener wali line ke indent se ek level andar — VS Code ke active indent guide jaisa.
                    val oStart = sgl.getLineStart(oLine)
                    val oEnd = sgl.getLineEnd(oLine)
                    var scol = 0
                    var sq = oStart
                    while (sq < oEnd) {
                        val sch = sed[sq]
                        if (sch == ' ') scol++
                        else if (sch == '	') scol += tabSize - (scol % tabSize)
                        else break
                        sq++
                    }
                    val sSpaceW = paint.measureText(" ")
                    if (sSpaceW > 0) {
                        val sgx = scrollX + paddingLeft + (scol + tabSize) * sSpaceW
                        if (sgx < scrollX + width) {
                            val sgTop = (scrollY + sgl.getLineTop(oLine)).toFloat()
                            val sgBottom = (scrollY + sgl.getLineBottom(cLine)).toFloat()
                            canvas.drawLine(sgx, sgTop, sgx, sgBottom, scopeGuidePaint)
                        }
                    }
                }
            }
        }
        val tp = tagHlPair
        val tl = layout
        if (tp != null && tl != null && !largeFileMode) {
            val ted = text
            if (ted != null) {
                val tradius = 4f * resources.displayMetrics.density
                for (tr in listOf(tp.first, tp.second)) {
                    val ts = tr.first.coerceIn(0, ted.length)
                    val te = tr.second.coerceIn(0, ted.length)
                    if (ts < te) {
                        val tfl = tl.getLineForOffset(ts)
                        val tll = tl.getLineForOffset(te)
                        for (tli in tfl..tll) {
                            val tls = tl.getLineStart(tli)
                            val tle = tl.getLineEnd(tli)
                            val trs = maxOf(ts, tls)
                            val tre = minOf(te, tle)
                            if (trs < tre) {
                                val tx0 = scrollX + paddingLeft + tl.getPrimaryHorizontal(trs)
                                val tx1 = scrollX + paddingLeft + tl.getPrimaryHorizontal(tre)
                                val ty0 = (scrollY + tl.getLineTop(tli)).toFloat()
                                val ty1 = (scrollY + tl.getLineBottom(tli)).toFloat()
                                canvas.drawRoundRect(tx0, ty0, tx1, ty1, tradius, tradius, tagHlPaint)
                            }
                        }
                    }
                }
            }
        }
        val bp = bracketPair
        val bl = layout
        if (bp != null && bl != null && !largeFileMode) {
            val bed = text
            if (bed != null) {
                val radius = 4f * resources.displayMetrics.density
                for (off in listOf(bp.first, bp.second)) {
                    val o = off.coerceIn(0, bed.length)
                    val bLine = bl.getLineForOffset(o)
                    val bx0 = scrollX + paddingLeft + bl.getPrimaryHorizontal(o)
                    val cw = paint.measureText(bed, o, (o + 1).coerceAtMost(bed.length))
                    val by0 = (scrollY + bl.getLineTop(bLine)).toFloat()
                    val by1 = (scrollY + bl.getLineBottom(bLine)).toFloat()
                    canvas.drawRoundRect(bx0, by0, bx0 + cw, by1, radius, radius, bracketPaint)
                }
            }
        }
        val cw = caretWord
        val cl2 = layout
        val ced = text
        if (cw != null && cl2 != null && ced != null) {
            val radius2 = 4f * resources.displayMetrics.density
            val total2 = lineCount
            val first2 = (scrollY / lineHeight).coerceAtLeast(0)
            val last2 = ((scrollY + height) / lineHeight + 1).coerceAtMost(total2 - 1)
            var drawn2 = 0
            for (li in first2..last2) {
                if (drawn2 >= 300) break
                val ls2 = cl2.getLineStart(li)
                val le2 = cl2.getLineEnd(li)
                var idx = ced.indexOf(cw, ls2)
                while (idx != -1 && idx + cw.length <= le2 && drawn2 < 300) {
                    val beforeOk = idx == 0 || !isSelWordChar(ced[idx - 1])
                    val afterOk = idx + cw.length >= ced.length || !isSelWordChar(ced[idx + cw.length])
                    if (beforeOk && afterOk) {
                        val x0 = scrollX + paddingLeft + cl2.getPrimaryHorizontal(idx)
                        val x1 = scrollX + paddingLeft + cl2.getPrimaryHorizontal(idx + cw.length)
                        val y0 = (paddingTop + cl2.getLineTop(li)).toFloat()
                        val y1 = (paddingTop + cl2.getLineBottom(li)).toFloat()
                        canvas.drawRoundRect(x0, y0, x1, y1, radius2, radius2, wordHlPaint)
                        drawn2++
                    }
                    idx = ced.indexOf(cw, idx + 1)
                }
            }
        }
        if (showWhitespace) {
            val wl = layout
            val wed = text
            if (wl != null && wed != null) {
                val wSpaceW = paint.measureText(" ")
                if (wSpaceW > 0) {
                    val wTextX = scrollX + paddingLeft
                    val wTotal = lineCount
                    val wFirst = (scrollY / lineHeight).coerceAtLeast(0)
                    val wLast = ((scrollY + height) / lineHeight + 1).coerceAtMost(wTotal - 1)
                    val dotR = (1.4f * resources.displayMetrics.density).coerceAtLeast(1f)
                    val viewRight = scrollX + width
                    for (wi in wFirst..wLast) {
                        val ws = wl.getLineStart(wi)
                        val we = wl.getLineEnd(wi)
                        val wMidY = (scrollY + (wl.getLineTop(wi) + wl.getLineBottom(wi)) / 2).toFloat()
                        var wo = ws
                        while (wo < we) {
                            val wc = wed[wo]
                            if (wc == ' ' || wc == '\t') {
                                val wx = wTextX + wl.getPrimaryHorizontal(wo)
                                if (wx < viewRight) {
                                    if (wc == ' ') {
                                        canvas.drawCircle(wx + wSpaceW / 2f, wMidY, dotR, whitespacePaint)
                                    } else {
                                        val ax = wx + wSpaceW * 0.12f
                                        val len = wSpaceW * 0.76f
                                        canvas.drawLine(ax, wMidY, ax + len, wMidY, whitespacePaint)
                                        canvas.drawLine(ax + len, wMidY, ax + len * 0.55f, wMidY - wSpaceW * 0.28f, whitespacePaint)
                                        canvas.drawLine(ax + len, wMidY, ax + len * 0.55f, wMidY + wSpaceW * 0.28f, whitespacePaint)
                                    }
                                }
                            }
                            wo++
                        }
                    }
                }
            }
        }
        if (rainbowBrackets && !largeFileMode) {
            val rbl = layout
            val rbed = text
            if (rbl != null && rbed != null) {
                val rbRadius = 4f * resources.displayMetrics.density
                val rbTotal = lineCount
                val rbFirst = (scrollY / lineHeight).coerceAtLeast(0)
                val rbLast = ((scrollY + height) / lineHeight + 1).coerceAtMost(rbTotal - 1)
                var rbDepth = 0
                for (rli in rbFirst..rbLast) {
                    val rls = rbl.getLineStart(rli)
                    val rle = rbl.getLineEnd(rli)
                    var ro = rls
                    while (ro < rle) {
                        val rc = rbed[ro]
                        if (rc in openBrackets || rc in closeBrackets) {
                            val rbd = if (rc in openBrackets) rbDepth else (rbDepth - 1).coerceAtLeast(0)
                            val rbx0 = scrollX + paddingLeft + rbl.getPrimaryHorizontal(ro)
                            val rbcw = paint.measureText(rbed, ro, (ro + 1).coerceAtMost(rbed.length))
                            val rby0 = (scrollY + rbl.getLineTop(rli)).toFloat()
                            val rby1 = (scrollY + rbl.getLineBottom(rli)).toFloat()
                            canvas.drawRoundRect(rbx0, rby0, rbx0 + rbcw, rby1, rbRadius, rbRadius, rainbowPaints[rbd % rainbowPaints.size])
                            rbDepth = if (rc in openBrackets) rbDepth + 1 else (rbDepth - 1).coerceAtLeast(0)
                        }
                        ro++
                    }
                }
            }
        }
        if (lineGuideColumn > 0) {
            val rSpaceW = paint.measureText(" ")
            if (rSpaceW > 0) {
                val gx = scrollX + paddingLeft + lineGuideColumn * rSpaceW
                if (gx < scrollX + width) {
                    canvas.drawLine(gx, scrollY.toFloat(), gx, (scrollY + height).toFloat(), indentGuidePaint)
                }
            }
        }
        super.onDraw(canvas)
        if (extraCarets.isNotEmpty()) {
            val mcLayout = layout
            if (mcLayout != null) {
                val mcLen = length()
                val mcW = 2.5f * resources.displayMetrics.density
                for (co in extraCarets) {
                    val o = co.coerceIn(0, mcLen)
                    val mcLine = mcLayout.getLineForOffset(o)
                    val mcX = scrollX + paddingLeft + mcLayout.getPrimaryHorizontal(o)
                    val mcTop = (scrollY + mcLayout.getLineTop(mcLine)).toFloat()
                    val mcBottom = (scrollY + mcLayout.getLineBottom(mcLine)).toFloat()
                    canvas.drawRect(mcX, mcTop, mcX + mcW, mcBottom, multiCursorPaint)
                }
            }
        }
        canvas.restore()
        if (stickyH > 0) drawStickyBar(canvas)
    }

    override fun onTextChanged(t: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(t, start, lengthBefore, lengthAfter)
        swatchesDirty = true
        focusCaretLine = -1
        updateBracketHighlight()
        updateScopeGuide()
        updateCaretWordHighlight()
        updateTagHighlight()
        // v7 (43): tell the LSP client the document changed (background thread).
        noteLspChange()
        if (isFoldingOp || isProgrammaticChange) {
            // Structural rewrite (tab switch, rename, fold) = caret offsets invalid.
            clearMultiCursors()
            if (isProgrammaticChange) {
                lastEditOffset = -1
                if (bookmarks.isNotEmpty()) bookmarks.clear()
            }
        } else {
            lastEditOffset = start.coerceIn(0, length())
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // BUGFIX: superclass (TextView) constructor se onSelectionChanged call ho sakta hai
        // jab subclass properties (extraCarets etc.) abhi init nahi hui — NPE se bacho.
        // initComplete primitive Boolean hai, isliye construction ke dauran bhi safe hai (default false).
        if (!initComplete) return
        if (hasMultiCursors() && !isUpdatingCarets && !isMultiCursorEdit) {
            // Kahin aur tap ya selection change = multi-cursor exit.
            clearMultiCursors()
        }
        if (highlightCurrentLine) invalidate()
        updateBracketHighlight()
        updateScopeGuide()
        updateCaretWordHighlight()
        updateTagHighlight()
        val layout = layout ?: return
        val line = layout.getLineForOffset(selStart.coerceIn(0, length()))
        val col = selStart - layout.getLineStart(line)
        onCursorPositionChanged?.invoke(line + 1, col + 1)
        onSelectionStatsChanged?.invoke(line + 1, col + 1, abs(selEnd - selStart))
        val textLine = mapVisualToExpanded(line)
        if (textLine != lastStickyCursorLine) {
            lastStickyCursorLine = textLine
            stickyDirty = true
        }
        // INV-159: caret nayi line par gaya to usey center me lao.
        if (typewriterMode && selStart == selEnd && line != lastTypewriterLine) {
            recenterCaret()
        }
        // v6 (19): tap / caret move = popup band. Lekin typing ke baad
        // selection change afterTextChanged KE BAAD aata hai, isliye
        // updateSuggestions ek one-shot guard set karta hai jo sirf us
        // turant wale selection change ko dismiss se bachata hai.
        // Suggestion insert apna selection khud set karta hai, isliye
        // us dauran bhi dismiss mat karo.
        if (suggestionDismissGuard) {
            suggestionDismissGuard = false
        } else if (!isSuggestionInsert) {
            dismissSuggestions()
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (!focused) dismissSuggestions()
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        onEditorScrollChanged?.invoke()
        // PERF: scroll par naya dikha area highlight karo (debounced, background)
        if (vert != oldVert) scheduleHighlightOnScroll()
    }

    fun getScrollRange(): Int = computeVerticalScrollRange()
    fun getScrollExtent(): Int = computeVerticalScrollExtent()
    fun getScrollOffset(): Int = computeVerticalScrollOffset()

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (undoStack.isEmpty()) return
        clearFolds()
        clearMultiCursors()
        // Multi-cursor edit ka poora group ek hi undo step me wapas aata hai.
        val group = mutableListOf<EditAction>()
        val first = undoStack.removeLast()
        group.add(first)
        if (first.groupId != 0) {
            while (undoStack.isNotEmpty() && undoStack.last().groupId == first.groupId) {
                group.add(undoStack.removeLast())
            }
        }
        // Descending offsets me apply karo taaki chhote offsets valid rahen.
        val ordered = group.sortedByDescending { it.start }
        isUndoOrRedo = true
        try {
            val editable = text ?: return
            for (action in ordered) {
                editable.replace(action.start, action.start + action.afterText.length, action.beforeText)
                redoStack.addLast(action)
            }
            val a0 = ordered.minByOrNull { it.start } ?: first
            if (a0.beforeText.isNotEmpty()) {
                setSelection(a0.start, a0.start + a0.beforeText.length)
            } else {
                setSelection(a0.start)
            }
        } finally {
            isUndoOrRedo = false
            onUndoRedoStateChanged?.invoke()
            onModifiedListener?.invoke()
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        clearFolds()
        clearMultiCursors()
        val group = mutableListOf<EditAction>()
        val first = redoStack.removeLast()
        group.add(first)
        if (first.groupId != 0) {
            while (redoStack.isNotEmpty() && redoStack.last().groupId == first.groupId) {
                group.add(redoStack.removeLast())
            }
        }
        val ordered = group.sortedByDescending { it.start }
        isUndoOrRedo = true
        try {
            val editable = text ?: return
            for (action in ordered) {
                editable.replace(action.start, action.start + action.beforeText.length, action.afterText)
                undoStack.addLast(action)
            }
            val a0 = ordered.minByOrNull { it.start } ?: first
            if (a0.afterText.isNotEmpty()) {
                setSelection(a0.start, a0.start + a0.afterText.length)
            } else {
                setSelection(a0.start)
            }
        } finally {
            isUndoOrRedo = false
            onUndoRedoStateChanged?.invoke()
            onModifiedListener?.invoke()
        }
    }

    // ===== Multi-cursor core =====

    /** Multi-cursor mode active hai ya nahi. */
    fun hasMultiCursors(): Boolean = extraCarets.isNotEmpty()

    /**
     * Diye gaye offsets par multiple carets lagao.
     * Sabse aakhri offset primary caret banta hai, baaki secondary (violet).
     */
    fun setMultiCursors(offsets: List<Int>) {
        extraCarets.clear()
        val sorted = offsets.distinct().sorted()
        if (sorted.size < 2) {
            invalidate()
            return
        }
        isUpdatingCarets = true
        try {
            setSelection(sorted.last().coerceIn(0, length()))
        } finally {
            isUpdatingCarets = false
        }
        extraCarets.addAll(sorted.dropLast(1))
        invalidate()
    }

    /** Multi-cursor mode band karo - primary caret jahan hai wahin rehta hai. */
    /** Box drag khatm: har line par release-column par caret lagao. */
    private fun commitBoxSelection() {
        val l = layout ?: return
        val top = minOf(boxAnchorLine, boxCurLine).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        val bottom = maxOf(boxAnchorLine, boxCurLine).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        val col = boxCurCol
        val offsets = ArrayList<Int>(bottom - top + 1)
        for (ln in top..bottom) {
            val ls = l.getLineStart(ln)
            val le = l.getLineEnd(ln)
            offsets.add(ls + col.coerceIn(0, le - ls))
        }
        setMultiCursors(offsets)
        if (offsets.distinct().size >= 2) {
            Toast.makeText(context, "${offsets.distinct().size} carets", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearMultiCursors() {
        if (extraCarets.isEmpty()) return
        extraCarets.clear()
        invalidate()
    }

    /** Ek aur caret jodo (sorted order me). Pehle se maujood ho to false. */
    fun addCaretAt(offset: Int): Boolean {
        if (!hasMultiCursors()) return false
        val o = offset.coerceIn(0, length())
        val all = (extraCarets + selectionStart).distinct().toMutableList()
        if (o in all) return false
        all.add(o)
        val sorted = all.sorted()
        isUpdatingCarets = true
        try {
            setSelection(sorted.last())
        } finally {
            isUpdatingCarets = false
        }
        extraCarets.clear()
        extraCarets.addAll(sorted.dropLast(1))
        invalidate()
        return true
    }

    /** Primary caret ka offset (agla occurrence dhoondhne ke liye). */
    fun primaryCaretOffset(): Int = selectionStart.coerceIn(0, length())

    /** Kul kitne carets hain (primary + secondary). */
    fun caretCount(): Int = extraCarets.size + 1

    /**
     * Saare carets par ek saath text insert - ek hi undo step me.
     * Descending order me apply hota hai taaki chhote offsets valid rahen.
     */
    fun insertAtAllCarets(str: String) {
        val ed = text ?: return
        if (str.isEmpty() || !hasMultiCursors()) return
        val primary = selectionStart.coerceIn(0, ed.length)
        val carets = (extraCarets + primary).distinct().sortedDescending()
        multiCursorGroupId++
        val gid = multiCursorGroupId
        isMultiCursorEdit = true
        isUpdatingCarets = true
        try {
            beginBatchEdit()
            try {
                for (c in carets) {
                    val cc = c.coerceIn(0, ed.length)
                    ed.replace(cc, cc, str)
                    undoStack.addLast(EditAction(cc, "", str, gid))
                }
                while (undoStack.size > 200) undoStack.removeFirst()
            } finally {
                endBatchEdit()
            }
            redoStack.clear()
            val asc = carets.sorted()
            val newOffsets = asc.mapIndexed { i, c -> (c + str.length * (i + 1)).coerceAtMost(ed.length) }
            extraCarets.clear()
            extraCarets.addAll(newOffsets.dropLast(1))
            setSelection(newOffsets.last())
        } finally {
            isMultiCursorEdit = false
            isUpdatingCarets = false
        }
        onModifiedListener?.invoke()
        onUndoRedoStateChanged?.invoke()
        invalidate()
    }

    /**
     * Saare carets par ek saath backspace (har caret se pehle ka 1 char) - ek hi undo step me.
     */
    fun deleteAtAllCarets() {
        val ed = text ?: return
        if (!hasMultiCursors()) return
        val primary = selectionStart.coerceIn(0, ed.length)
        val carets = (extraCarets + primary).distinct().sortedDescending()
        multiCursorGroupId++
        val gid = multiCursorGroupId
        val deletedAt = mutableSetOf<Int>()
        isMultiCursorEdit = true
        isUpdatingCarets = true
        try {
            beginBatchEdit()
            try {
                for (c in carets) {
                    val cc = c.coerceIn(0, ed.length)
                    if (cc > 0) {
                        val ch = ed[cc - 1].toString()
                        ed.replace(cc - 1, cc, "")
                        undoStack.addLast(EditAction(cc - 1, ch, "", gid))
                        deletedAt.add(c)
                    }
                }
                while (undoStack.size > 200) undoStack.removeFirst()
            } finally {
                endBatchEdit()
            }
            redoStack.clear()
            val asc = carets.sorted()
            val newOffsets = asc.map { c -> c - deletedAt.count { d -> d <= c } }
            val distinct = newOffsets.distinct()
            if (distinct.size < 2) {
                extraCarets.clear()
                setSelection(distinct.firstOrNull()?.coerceIn(0, ed.length) ?: 0)
            } else {
                extraCarets.clear()
                extraCarets.addAll(distinct.dropLast(1))
                setSelection(distinct.last())
            }
        } finally {
            isMultiCursorEdit = false
            isUpdatingCarets = false
        }
        onModifiedListener?.invoke()
        onUndoRedoStateChanged?.invoke()
        invalidate()
    }

    private fun updateLargeFileMode() {
        val ed = text
        largeFileMode = ed != null && (ed.length > 500 * 1024 || lineCount > 3000)
        if (largeFileMode) { clearFolds() }
    }

    fun setTextCleanly(content: String) {
        isProgrammaticChange = true
        undoStack.clear()
        redoStack.clear()
        clearFolds()
        setText(content)
        updateLargeFileMode()
        isProgrammaticChange = false
        onUndoRedoStateChanged?.invoke()
    }

    fun insertSymbol(symbol: String) {
        val start = max(selectionStart, 0)
        val end = max(selectionEnd, 0)
        text?.replace(min(start, end), max(start, end), symbol)
    }

    fun applyPreferences(textSizeSp: Float) {
        textSize = textSizeSp
        lineNumPaint.textSize = textSizeSp * resources.displayMetrics.scaledDensity * 0.85f
        updateGutterWidth()
    }

    fun setWordWrapEnabled(enabled: Boolean) {
        setHorizontallyScrolling(!enabled)
        requestLayout()
    }

    /** INV-159 Typewriter mode: caret line hamesha vertically centered rehti hai. */
    var typewriterMode: Boolean = false
        set(value) {
            field = value
            lastTypewriterLine = -1
            if (value) recenterCaret()
        }
    private var lastTypewriterLine: Int = -1

    private fun recenterCaret() {
        val l = layout ?: return
        if (selectionStart != selectionEnd) return
        val o = selectionStart.coerceIn(0, length())
        val ln = l.getLineForOffset(o)
        lastTypewriterLine = ln
        val lineH = (l.getLineTop((ln + 1).coerceAtMost(l.lineCount)) - l.getLineTop(ln)).coerceAtLeast(1)
        val targetY = (l.getLineTop(ln) - height / 2).coerceAtLeast(0)
        if (kotlin.math.abs(scrollY - targetY) > lineH) scrollTo(scrollX, targetY)
    }

    fun setLineNumbersVisible(visible: Boolean) {
        showLineNumbers = visible
        updateGutterWidth()
        invalidate()
    }

    fun applyTheme(theme: EditorTheme) {
        setBackgroundColor(theme.background)
        setTextColor(theme.text)
        setHighlightColor(theme.selection)
        gutterPaint.color = theme.gutterBg
        highlightPaint.color = theme.currentLine
        indentGuidePaint.color = (theme.text and 0x00FFFFFF) or 0x26000000
        whitespacePaint.color = (theme.text and 0x00FFFFFF) or 0x30000000
        whitespacePaint.strokeWidth = 1.5f * resources.displayMetrics.density
        scopeGuidePaint.strokeWidth = 2f * resources.displayMetrics.density
        lineNumPaint.color = theme.gutterText
        foldPaint.color = theme.keyword
        wordHlPaint.color = (theme.keyword and 0x00FFFFFF) or 0x2E000000
        bookmarkPaint.color = theme.keyword
        stickyBgPaint.color = theme.surface
        invalidate()
        scheduleHighlight()
    }

    /** Ctrl+D jaisa: pehle tap par cursor wala word select, phir har tap par uska agla occurrence. */
    fun selectNextOccurrence() {
        val ed = text ?: return
        val s = selectionStart
        val e = selectionEnd
        val query: String
        if (s != e) {
            query = ed.substring(min(s, e), max(s, e)).toString()
            lastFindStart = max(s, e)
        } else {
            val cursor = s.coerceIn(0, ed.length)
            var a = cursor
            while (a > 0 && isSelWordChar(ed[a - 1])) a--
            var b = cursor
            while (b < ed.length && isSelWordChar(ed[b])) b++
            if (a == b) {
                Toast.makeText(context, "No word at cursor", Toast.LENGTH_SHORT).show()
                return
            }
            query = ed.substring(a, b).toString()
            lastFindStart = b
        }
        if (query.isEmpty()) return
        findNext(query, false, false)
    }

    /**
     * Selection ko strictly gherne wali sabse najdeeki bracket pair dhoondhta hai
     * (opener offset, closer offset), ya null. Backward single-pass scan me
     * closers ka stack banata hai, pehla unmatched opener = enclosing pair.
     */
    private fun findEnclosingBracket(ed: CharSequence, s: Int, e: Int): Pair<Int, Int>? {
        val closeToOpen = mapOf(')' to '(', ']' to '[', '}' to '{')
        val openers = closeToOpen.values.toSet()
        val pending = ArrayList<Char>()
        var i = (s - 1).coerceAtMost(ed.length - 1)
        val limit = (s - 40000).coerceAtLeast(0)
        while (i >= limit) {
            val ch = ed[i]
            if (closeToOpen.containsKey(ch)) {
                pending.add(closeToOpen[ch]!!)
            } else if (ch in openers) {
                if (pending.isNotEmpty() && pending[pending.size - 1] == ch) {
                    pending.removeAt(pending.size - 1)
                } else {
                    val match = findMatchingBracket(ed, i, true)
                    if (match != null && match >= e) return i to match
                }
            }
            i--
        }
        return null
    }

    /**
     * Expand selection (VS Code jaisa): har tap par selection ek level badhta hai —
     * word -> enclosing bracket ka andar -> enclosing bracket poora -> line -> sab.
     * Sirf strictly-bara candidate chunta hai, isliye har tap par progress pakka.
     */
    fun expandSelection() {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val s0 = minOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        val e0 = maxOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        val candidates = ArrayList<Pair<Int, Int>>(5)
        if (s0 == e0) {
            var a = s0
            while (a > 0 && isSelWordChar(ed[a - 1])) a--
            var b = s0
            while (b < ed.length && isSelWordChar(ed[b])) b++
            if (a != b) candidates.add(a to b)
        }
        val enc = findEnclosingBracket(ed, s0, e0)
        if (enc != null) {
            candidates.add(enc.first + 1 to enc.second)
            candidates.add(enc.first to (enc.second + 1).coerceAtMost(ed.length))
        }
        var ls = s0
        while (ls > 0 && ed[ls - 1] != '\n') ls--
        var le = e0
        while (le < ed.length && ed[le] != '\n') le++
        candidates.add(ls to le)
        candidates.add(0 to ed.length)
        val cur = s0 to e0
        val next = candidates
            .filter { it != cur && it.first <= s0 && it.second >= e0 }
            .minByOrNull { it.second - it.first }
        if (next == null) {
            Toast.makeText(context, "Already fully selected", Toast.LENGTH_SHORT).show()
            return
        }
        setSelection(next.first, next.second)
    }

    private fun isSelWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    /** Whole-word match check: start/len par word boundary hai ya nahi. */
    fun isWholeWordAt(text: String, start: Int, len: Int): Boolean {
        if (start < 0 || len < 0 || start + len > text.length) return false
        val beforeOk = start == 0 || !isSelWordChar(text[start - 1])
        val end = start + len
        val afterOk = end >= text.length || !isSelWordChar(text[end])
        return beforeOk && afterOk
    }

    fun findNext(query: String, caseSensitive: Boolean, useRegex: Boolean, wholeWord: Boolean = false): Boolean {
        if (query.isEmpty()) return false
        val textStr = text?.toString() ?: return false
        val startFrom = if (lastFindStart < textStr.length) lastFindStart else 0
        var found = -1
        var matchLen = query.length
        try {
            if (useRegex) {
                val flags = if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                val pattern = if (wholeWord) "\\b(?:$query)\\b" else query
                val m = Regex(pattern, flags).find(textStr, startFrom)
                if (m != null) {
                    found = m.range.first
                    matchLen = m.range.last - m.range.first + 1
                }
            } else {
                var from = startFrom
                while (true) {
                    val idx = textStr.indexOf(query, from, ignoreCase = !caseSensitive)
                    if (idx < 0) break
                    if (!wholeWord || isWholeWordAt(textStr, idx, query.length)) {
                        found = idx
                        break
                    }
                    from = idx + 1
                }
            }
        } catch (e: Exception) {
            found = -1
        }
        if (found == -1) {
            if (startFrom > 0) {
                lastFindStart = 0
                return findNext(query, caseSensitive, useRegex, wholeWord)
            }
            return false
        }
        setSelection(found, found + matchLen)
        lastFindStart = found + matchLen
        val layout = layout
        if (layout != null) {
            val line = layout.getLineForOffset(found)
            scrollTo(scrollX, (layout.getLineTop(line) - height / 3).coerceAtLeast(0))
        }
        return true
    }

    /** Find dialog me Next par long-press: caret se pehle wala match (wrap-around). */
    fun findPrev(query: String, caseSensitive: Boolean, useRegex: Boolean, wholeWord: Boolean = false): Boolean {
        if (query.isEmpty()) return false
        val textStr = text?.toString() ?: return false
        // Current selection ka start hi boundary hai - us par/andar ka match dobara nahi chahiye.
        val boundary = minOf(selectionStart, selectionEnd).coerceIn(0, textStr.length)
        var found = -1
        var matchLen = query.length
        fun searchBefore(limit: Int): Boolean {
            try {
                if (useRegex) {
                    val flags = if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                    val pattern = if (wholeWord) "\\b(?:$query)\\b" else query
                    var last: MatchResult? = null
                    for (m in Regex(pattern, flags).findAll(textStr)) {
                        if (m.range.first >= limit) break
                        last = m
                    }
                    if (last != null) {
                        found = last.range.first
                        matchLen = last.range.last - last.range.first + 1
                        return true
                    }
                } else {
                    var from = limit - 1
                    while (from >= 0) {
                        val idx = textStr.lastIndexOf(query, from, ignoreCase = !caseSensitive)
                        if (idx < 0) break
                        if (!wholeWord || isWholeWordAt(textStr, idx, query.length)) {
                            found = idx
                            return true
                        }
                        from = idx - 1
                    }
                }
            } catch (e: Exception) {
            }
            return false
        }
        val ok = searchBefore(boundary) || searchBefore(textStr.length)
        if (!ok) return false
        setSelection(found, found + matchLen)
        lastFindStart = found + matchLen
        val layout = layout
        if (layout != null) {
            val line = layout.getLineForOffset(found)
            scrollTo(scrollX, (layout.getLineTop(line) - height / 3).coerceAtLeast(0))
        }
        return true
    }

    fun replaceOne(replaceWith: String) {
        val s = selectionStart
        val e = selectionEnd
        if (s != e && s >= 0) text?.replace(min(s, e), max(s, e), replaceWith)
    }

    fun replaceAll(query: String, replaceWith: String, caseSensitive: Boolean, useRegex: Boolean, wholeWord: Boolean = false): Int {
        if (query.isEmpty()) return 0
        val editable = text ?: return 0
        val textStr = editable.toString()
        var count = 0
        try {
            if (useRegex) {
                val flags = if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                val pattern = if (wholeWord) "\\b(?:$query)\\b" else query
                val regex = Regex(pattern, flags)
                count = regex.findAll(textStr).count()
                if (count > 0) {
                    isProgrammaticChange = true
                    try {
                        editable.replace(0, editable.length, regex.replace(textStr, replaceWith))
                    } finally {
                        isProgrammaticChange = false
                    }
                }
            } else {
                val sb = StringBuilder()
                var lastIdx = 0
                while (true) {
                    val idx = textStr.indexOf(query, lastIdx, ignoreCase = !caseSensitive)
                    if (idx == -1) break
                    if (wholeWord && !isWholeWordAt(textStr, idx, query.length)) {
                        lastIdx = idx + 1
                        continue
                    }
                    count++
                    sb.append(textStr, lastIdx, idx).append(replaceWith)
                    lastIdx = idx + query.length
                }
                if (count > 0) {
                    sb.append(textStr, lastIdx, textStr.length)
                    isProgrammaticChange = true
                    try {
                        editable.replace(0, editable.length, sb.toString())
                    } finally {
                        isProgrammaticChange = false
                    }
                }
            }
        } catch (e: Exception) {
            return 0
        }
        return count
    }

    fun gotoLine(line: Int) {
        if (line < 1 || line > lineCount) return
        val layout = layout ?: return
        val ln = (line - 1).coerceIn(0, lineCount - 1)
        setSelection(layout.getLineStart(ln))
        scrollTo(scrollX, (layout.getLineTop(ln) - height / 3).coerceAtLeast(0))
        requestFocus()
    }

    /**
     * INV-157 Surround With: selection (poori lines tak expand) ko template me
     * lapeto — kind: 0=if, 1=if/else, 2=try/catch, 3=for, 4=while, 5=do/while.
     * Python me colon-style templates. Ek hi edit = ek Undo.
     */
    fun surroundSelection(kind: Int, fileExtension: String): Boolean {
        val ed = text ?: return false
        var s = selectionStart.coerceIn(0, ed.length)
        var e = selectionEnd.coerceIn(0, ed.length)
        if (s == e) return false
        if (s > e) {
            val t = s; s = e; e = t
        }
        var rs = s
        while (rs > 0 && ed[rs - 1] != '\n') rs--
        var re = e
        while (re < ed.length && ed[re] != '\n') re++
        val selected = ed.substring(rs, re)
        val unit = " ".repeat(tabSize.coerceAtLeast(1))
        val firstCodeLine = selected.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val baseIndent = firstCodeLine.takeWhile { it == ' ' || it == '\t' }
        val body = selected.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) line else unit + line
        }
        val isPy = fileExtension.lowercase() in setOf("py", "pyw")
        val newText: String
        val caretOff: Int
        if (isPy) {
            when (kind) {
                1 -> {
                    newText = "${baseIndent}if :\n$body\n${baseIndent}else:\n"
                    caretOff = rs + baseIndent.length + 3
                }
                2 -> {
                    newText = "${baseIndent}try:\n$body\n${baseIndent}except Exception:\n"
                    caretOff = rs + baseIndent.length + 5
                }
                3 -> {
                    newText = "${baseIndent}for :\n$body"
                    caretOff = rs + baseIndent.length + 4
                }
                4 -> {
                    newText = "${baseIndent}while :\n$body"
                    caretOff = rs + baseIndent.length + 6
                }
                5 -> {
                    newText = "${baseIndent}while True:\n$body"
                    caretOff = rs + baseIndent.length + 11
                }
                else -> {
                    newText = "${baseIndent}if :\n$body"
                    caretOff = rs + baseIndent.length + 3
                }
            }
        } else {
            when (kind) {
                1 -> {
                    newText = "${baseIndent}if () {\n$body\n${baseIndent}} else {\n${baseIndent}}"
                    caretOff = rs + baseIndent.length + 4
                }
                2 -> {
                    newText = "${baseIndent}try {\n$body\n${baseIndent}} catch (e: Exception) {\n${baseIndent}}"
                    caretOff = rs + baseIndent.length + 6
                }
                3 -> {
                    newText = "${baseIndent}for () {\n$body\n${baseIndent}}"
                    caretOff = rs + baseIndent.length + 5
                }
                4 -> {
                    newText = "${baseIndent}while () {\n$body\n${baseIndent}}"
                    caretOff = rs + baseIndent.length + 7
                }
                5 -> {
                    newText = "${baseIndent}do {\n$body\n${baseIndent}} while ()"
                    caretOff = rs + newText.length - 1
                }
                else -> {
                    newText = "${baseIndent}if () {\n$body\n${baseIndent}}"
                    caretOff = rs + baseIndent.length + 4
                }
            }
        }
        ed.replace(rs, re, newText)
        setSelection(caretOff.coerceIn(0, ed.length))
        return true
    }

    /**
     * INV-160 Align Assignments: selected lines ke pehle standalone `=` ko ek
     * hi column me lao (string/comment ke `=` aur `==`/`!=`/`<=`/`>=` skip).
     * Ek hi edit = ek Undo. False = kuch align nahi hua.
     */
    fun alignAssignments(): Boolean {
        val ed = text ?: return false
        var s = selectionStart.coerceIn(0, ed.length)
        var e = selectionEnd.coerceIn(0, ed.length)
        if (s == e) return false
        if (s > e) {
            val t = s; s = e; e = t
        }
        var rs = s
        while (rs > 0 && ed[rs - 1] != '\n') rs--
        var re = e
        while (re < ed.length && ed[re] != '\n') re++
        val lines = ed.substring(rs, re).split("\n")
        data class Split(val idx: Int, val eqPos: Int)
        val splits = ArrayList<Split>()
        var maxLeft = 0
        for ((idx, line) in lines.withIndex()) {
            val p = firstAssignmentEq(line)
            if (p >= 0) {
                splits.add(Split(idx, p))
                maxLeft = maxOf(maxLeft, visualWidth(line.substring(0, p).trimEnd()))
            }
        }
        if (splits.size < 2) return false
        val newLines = lines.toMutableList()
        for (sp in splits) {
            val line = lines[sp.idx]
            val left = line.substring(0, sp.eqPos).trimEnd()
            val right = line.substring(sp.eqPos + 1).trimStart()
            val pad = maxLeft - visualWidth(left)
            newLines[sp.idx] = left + " ".repeat(pad) + " = " + right
        }
        ed.replace(rs, re, newLines.joinToString("\n"))
        return true
    }

    /** Line ka pehla standalone `=` (string/comment ke bahar); nahi to -1. */
    private fun firstAssignmentEq(line: String): Int {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(line, i)
                continue
            }
            if (c == '/' && i + 1 < line.length && line[i + 1] == '/') break
            if (c == '=') {
                val prev = if (i > 0) line[i - 1] else ' '
                val next = if (i + 1 < line.length) line[i + 1] else ' '
                if (prev != '=' && prev != '!' && prev != '<' && prev != '>' &&
                    next != '=' && next != '>'
                ) return i
            }
            i++
        }
        return -1
    }

    /** Tab-aware visual width. */
    private fun visualWidth(s: String): Int {
        var w = 0
        val ts = tabSize.coerceAtLeast(1)
        for (c in s) w += if (c == '\t') ts - (w % ts) else 1
        return w
    }

    /** INV-156: caret ko offset par rakho aur us line ko view me lao. */
    fun gotoOffset(offset: Int) {
        val ed = text ?: return
        val o = offset.coerceIn(0, ed.length)
        val l = layout
        setSelection(o)
        if (l != null) {
            val ln = l.getLineForOffset(o)
            scrollTo(scrollX, (l.getLineTop(ln) - height / 3).coerceAtLeast(0))
        }
        requestFocus()
    }

    /**
     * INV-151 Format Document (Sam-requested): poori file ka language-aware format.
     * Brace-family (Kotlin/Java/JS/...) me `{}`/`()` depth se indent — strings,
     * char literals aur comments ke andar ke brackets ignore hote hain.
     * HTML/XML me tag nesting se indent. Baaki files me sirf trailing
     * whitespace trim hota hai. Ek hi Undo step me wapas aata hai, caret apni
     * line par rehta hai, user ka tabSize use hota hai.
     * Returns: 0 = formatted, 1 = koi badlav nahi, 2 = file bahut badi.
     */
    fun formatCurrentCode(fileExtension: String): Int {
        val editable = text ?: return 1
        if (editable.isEmpty()) return 1
        if (editable.length > 2 * 1024 * 1024) return 2
        // Folded text me placeholder hota hai — pehle saare folds kholo
        expandAllFoldsNow()
        clearMultiCursors()
        val oldText = editable.toString()
        val eol = if (oldText.contains("\r\n")) "\r\n" else "\n"
        val lines = oldText.split("\n").map { it.trimEnd('\r') }
        val formatted = when (formatFamilyFor(fileExtension)) {
            1 -> formatBraceLines(lines)
            2 -> formatXmlLines(lines)
            else -> lines.map { it.trimEnd() }
        }
        val newText = formatted.joinToString(eol)
        if (newText == oldText) return 1
        // Caret ki line + column yaad rakho
        val caretOff = selectionStart.coerceIn(0, oldText.length)
        var caretLine = 0
        var caretCol = 0
        var off = 0
        for (i in lines.indices) {
            val next = off + lines[i].length + 1
            if (caretOff < next || i == lines.lastIndex) {
                caretLine = i
                caretCol = (caretOff - off).coerceAtLeast(0)
                break
            }
            off = next
        }
        isProgrammaticChange = true
        try {
            editable.replace(0, editable.length, newText)
        } finally {
            isProgrammaticChange = false
        }
        // Single undo step (programmatic change khud undo me record nahi hota)
        undoStack.addLast(EditAction(0, oldText, newText))
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        // Caret wapas usi line par (column clamp)
        val newLines = newText.split("\n")
        val li = caretLine.coerceIn(0, newLines.lastIndex)
        var newOff = 0
        for (i in 0 until li) newOff += newLines[i].length + 1
        newOff += caretCol.coerceAtMost(newLines[li].length)
        setSelection(newOff.coerceIn(0, newText.length))
        return 0
    }

    /** 1 = brace-based, 2 = XML/tag-based, 0 = safe (sirf trailing whitespace) */
    private fun formatFamilyFor(ext: String): Int {
        return when (ext.lowercase()) {
            "kt", "kts", "java", "js", "mjs", "cjs", "jsx", "ts", "tsx",
            "c", "h", "cpp", "hpp", "cc", "cxx", "cs", "go", "rs",
            "swift", "php", "dart", "scala", "groovy", "json", "json5",
            "css", "scss", "less" -> 1
            "html", "htm", "xhtml", "xml", "vue", "svg", "axml" -> 2
            else -> 0
        }
    }

    private data class BraceScan(
        val leadingBraceClosers: Int,
        val leadingParenClosers: Int,
        val braceNet: Int,
        val parenNet: Int,
        val blockDepth: Int,
        val triple: Char?
    )

    /** Brace-family formatter: brace+paren depth se indent, strings/comments ke brackets skip. */
    private fun formatBraceLines(lines: List<String>): List<String> {
        val unit = " ".repeat(tabSize.coerceAtLeast(1))
        val out = ArrayList<String>(lines.size)
        var braceDepth = 0
        var parenDepth = 0
        var blockDepth = 0
        var triple: Char? = null
        for (raw in lines) {
            // Triple-quoted string ke andar ki lines verbatim — string value na badle
            if (triple != null) {
                val st = scanBraceLine(raw.trim(), blockDepth, triple)
                blockDepth = st.blockDepth
                triple = st.triple
                out.add(raw)
                continue
            }
            val content = raw.trim()
            if (content.isEmpty()) {
                out.add("")
                continue
            }
            val st = scanBraceLine(content, blockDepth, triple)
            blockDepth = st.blockDepth
            triple = st.triple
            val lineIndent = (braceDepth + parenDepth - st.leadingBraceClosers - st.leadingParenClosers).coerceAtLeast(0)
            out.add(unit.repeat(lineIndent) + content)
            braceDepth = (braceDepth + st.braceNet).coerceAtLeast(0)
            parenDepth = (parenDepth + st.parenNet).coerceAtLeast(0)
        }
        return out
    }

    /**
     * Ek line scan karke: shuru ke closers (dedent), braces/parens ka alag-alag
     * net delta, aur agali line ke liye block-comment/triple-string state.
     */
    private fun scanBraceLine(line: String, blockStart: Int, tripleStart: Char?): BraceScan {
        var blockDepth = blockStart
        var triple = tripleStart
        var leadingBraceClosers = 0
        var leadingParenClosers = 0
        var braceNet = 0
        var parenNet = 0
        var seenCode = false
        var i = 0
        val n = line.length
        while (i < n) {
            if (triple != null) {
                if (i + 2 < n && line[i] == triple && line[i + 1] == triple && line[i + 2] == triple) {
                    triple = null
                    i += 3
                    seenCode = true
                } else i++
                continue
            }
            if (blockDepth > 0) {
                if (i + 1 < n && line[i] == '*' && line[i + 1] == '/') {
                    blockDepth--
                    i += 2
                } else if (i + 1 < n && line[i] == '/' && line[i + 1] == '*') {
                    blockDepth++
                    i += 2
                } else i++
                continue
            }
            val c = line[i]
            when {
                c == '/' && i + 1 < n && line[i + 1] == '/' -> break
                c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                    blockDepth++
                    i += 2
                }
                c == '"' || c == '\'' || c == '`' -> {
                    if ((c == '"' || c == '\'') && i + 2 < n && line[i + 1] == c && line[i + 2] == c) {
                        triple = c
                        i += 3
                    } else {
                        i = skipQuoted(line, i)
                    }
                    seenCode = true
                }
                c == '{' -> {
                    braceNet++
                    seenCode = true
                    i++
                }
                c == '}' -> {
                    braceNet--
                    if (!seenCode) leadingBraceClosers++
                    seenCode = true
                    i++
                }
                c == '(' -> {
                    parenNet++
                    seenCode = true
                    i++
                }
                c == ')' -> {
                    parenNet--
                    if (!seenCode) leadingParenClosers++
                    seenCode = true
                    i++
                }
                !c.isWhitespace() -> {
                    seenCode = true
                    i++
                }
                else -> i++
            }
        }
        return BraceScan(leadingBraceClosers, leadingParenClosers, braceNet, parenNet, blockDepth, triple)
    }

    /** line[start] ek quote hai — uske matching close ke baad ka index deta hai. */
    private fun skipQuoted(line: String, start: Int): Int {
        val q = line[start]
        var i = start + 1
        while (i < line.length) {
            val c = line[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == q) return i + 1
            i++
        }
        return i
    }

    /** Tag-family formatter (HTML/XML): nesting depth se indent. */
    private fun formatXmlLines(lines: List<String>): List<String> {
        val unit = " ".repeat(tabSize.coerceAtLeast(1))
        val out = ArrayList<String>(lines.size)
        var depth = 0
        var inComment = false
        for (raw in lines) {
            val content = raw.trim()
            if (content.isEmpty()) {
                out.add("")
                continue
            }
            if (inComment) {
                out.add(unit.repeat(depth) + content)
                if (content.contains("-->")) inComment = false
                continue
            }
            if (content.startsWith("<!--")) {
                out.add(unit.repeat(depth) + content)
                if (!content.contains("-->")) inComment = true
                continue
            }
            if (content.startsWith("<?") || content.startsWith("<!")) {
                out.add(unit.repeat(depth) + content)
                continue
            }
            val startsClosing = content.startsWith("</")
            val opens = countXmlOpens(content)
            val closes = countXmlCloses(content)
            val lineDepth = if (startsClosing) (depth - 1).coerceAtLeast(0) else depth
            out.add(unit.repeat(lineDepth) + content)
            depth = (depth + opens - closes).coerceAtLeast(0)
        }
        return out
    }

    /** `<tag` opens ginta hai — `</`, `<!--`, `<?` aur self-closing skip, quote-aware. */
    private fun countXmlOpens(line: String): Int {
        var count = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'') {
                i = skipQuoted(line, i)
                continue
            }
            if (c == '<' && i + 1 < line.length) {
                val d = line[i + 1]
                if (d == '/' || d == '!' || d == '?') {
                    i += 2
                    continue
                }
                var j = i + 1
                var q: Char? = null
                var selfClose = false
                while (j < line.length) {
                    val e = line[j]
                    if (q != null) {
                        if (e == q) q = null
                    } else when (e) {
                        '"', '\'' -> q = e
                        '>' -> break
                        '/' -> if (j + 1 < line.length && line[j + 1] == '>') selfClose = true
                    }
                    j++
                }
                if (!selfClose) count++
                i = j + 1
                continue
            }
            i++
        }
        return count
    }

    /** `</tag>` closes ginta hai, quote-aware. */
    private fun countXmlCloses(line: String): Int {
        var count = 0
        var i = 0
        while (i + 1 < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'') {
                i = skipQuoted(line, i)
                continue
            }
            if (c == '<' && line[i + 1] == '/') count++
            i++
        }
        return count
    }

    private fun findFoldEnd(startLine: Int): Int {
        val fullText = text?.toString() ?: return -1
        val lines = fullText.split("\n")
        if (startLine < 0 || startLine >= lines.size) return -1
        var depth = 0
        var opened = false
        var i = startLine
        while (i < lines.size) {
            val line = lines[i]
            var j = 0
            var inStr = false
            var strCh = ' '
            while (j < line.length) {
                val ch = line[j]
                if (inStr) {
                    if (ch == strCh && (j == 0 || line[j - 1] != '\\')) inStr = false
                } else if (ch == '"' || ch == '\'') {
                    inStr = true
                    strCh = ch
                } else if (ch == '/' && j + 1 < line.length && line[j + 1] == '/') {
                    break
                } else if (ch == '{') {
                    depth++
                    opened = true
                } else if (ch == '}') {
                    depth--
                }
                j++
            }
            if (opened && depth == 0) {
                return if (i > startLine + 1) i else -1
            }
            if (depth < 0) return -1
            i++
            if (i - startLine > 1500) return -1
        }
        return -1
    }

    private fun getFoldEnd(startLine: Int): Int {
        foldableCache[startLine]?.let { return it }
        val end = findFoldEnd(startLine)
        foldableCache[startLine] = end
        return end
    }

    /** Sticky scroll: cursor wali line ko gherne wale sabse andaruni `{...}` block ka start line. */
    private fun findInnermostFoldStart(targetLine: Int): Int {
        val fullText = text?.toString() ?: return -1
        val lines = fullText.split("\n")
        if (targetLine <= 0 || targetLine >= lines.size) return -1
        val stack = ArrayDeque<Pair<Int, Int>>()
        var depth = 0
        var i = 0
        while (i < targetLine) {
            val line = lines[i]
            var j = 0
            var inStr = false
            var strCh = ' '
            while (j < line.length) {
                val ch = line[j]
                if (inStr) {
                    if (ch == strCh && (j == 0 || line[j - 1] != '\\')) inStr = false
                } else if (ch == '"' || ch == '\'') {
                    inStr = true
                    strCh = ch
                } else if (ch == '/' && j + 1 < line.length && line[j + 1] == '/') {
                    break
                } else if (ch == '{') {
                    depth++
                    stack.addLast(i to depth)
                } else if (ch == '}') {
                    depth--
                    while (stack.isNotEmpty() && stack.last().second > depth) stack.removeLast()
                    if (depth < 0) return -1
                }
                j++
            }
            i++
        }
        return stack.lastOrNull()?.first ?: -1
    }

    private fun recomputeStickyHeader() {
        stickyHeaderLine = -1
        stickyHeaderText = ""
        if (!stickyScrollEnabled) return
        val l = layout ?: return
        if (length() == 0) return
        val cursorLine = mapVisualToExpanded(l.getLineForOffset(selectionStart.coerceIn(0, length())))
        lastStickyCursorLine = cursorLine
        val header = findInnermostFoldStart(cursorLine)
        if (header >= 0) {
            stickyHeaderLine = header
            stickyHeaderText = text?.toString()?.split("\n")?.getOrNull(header)?.trimEnd() ?: ""
        }
    }

    /** Bar ki height (0 = chhupa): header tabhi dikhta hai jab wo scroll karke upar nikal gaya ho. */
    private fun stickyVisibleHeight(): Int {
        if (!stickyScrollEnabled || stickyHeaderLine < 0) return 0
        val firstVisible = (scrollY / lineHeight).coerceAtLeast(0)
        return if (stickyHeaderLine < firstVisible) lineHeight else 0
    }

    private fun drawStickyBar(canvas: Canvas) {
        val top = scrollY.toFloat()
        val left = scrollX.toFloat()
        val right = left + width.toFloat()
        canvas.drawRect(left, top, right, top + lineHeight, stickyBgPaint)
        if (stickyHeaderText.isNotEmpty()) {
            val textX = left + totalPaddingLeft
            canvas.drawText(stickyHeaderText, textX.toFloat(), top + baseline, paint)
        }
        canvas.drawLine(left, top + lineHeight - 1f, right, top + lineHeight - 1f, gutterLinePaint)
    }

    private fun jumpToStickyHeader() {
        val l = layout ?: return
        val header = stickyHeaderLine
        if (header < 0 || header >= lineCount) return
        requestFocus()
        setSelection(l.getLineStart(header).coerceIn(0, length()))
    }

    /**
     * Asli text deta hai - saare collapsed folds ko expand karke.
     * Editor ko touch nahi karta, sirf memory me string banata hai.
     * Dirty check aur save ke liye use karo taaki " … " placeholder
     * kabhi disk ya dirty-comparison me na aaye (nahi to false dirty +
     * save par data loss hoga).
     */
    fun getUnfoldedText(): String {
        val ed = text ?: return ""
        if (collapsedFolds.isEmpty()) return ed.toString()
        val l = layout ?: return ed.toString()
        var result = ed.toString()
        // Ulte order me (neeche se upar) taaki offsets shift na hon
        for (line in collapsedFolds.keys.sortedDescending()) {
            if (line >= lineCount) continue
            val hidden = collapsedFolds[line] ?: continue
            val ls = l.getLineStart(line)
            if (ls >= result.length) continue
            val le = l.getLineEnd(line).coerceAtMost(result.length)
            val lineText = result.substring(ls, le)
            val phIndex = lineText.indexOf(FOLD_PLACEHOLDER)
            if (phIndex == -1) continue
            val phStart = ls + phIndex
            result = result.substring(0, phStart) + hidden + result.substring(phStart + FOLD_PLACEHOLDER.length)
        }
        return result
    }

    /** Koi fold collapsed hai ya nahi */
    fun hasCollapsedFolds(): Boolean = collapsedFolds.isNotEmpty()
    /** Scope-start line ka end line (fold machinery reuse). */
    fun foldEndForLine(startLine: Int): Int {
        return try {
            getFoldEnd(startLine).coerceAtLeast(startLine)
        } catch (e: Exception) {
            startLine
        }
    }

    /** Focus mode ke liye caret wale innermost scope ka range nikalo. */
    private fun updateFocusRange(caretLine: Int) {
        focusCaretLine = caretLine
        val scopes = try { enclosingScopeLines() } catch (e: Exception) { emptyList() }
        val startLine = scopes.lastOrNull()
        focusRange = if (startLine != null) {
            startLine..foldEndForLine(startLine)
        } else {
            caretLine..caretLine
        }
    }
    /**
     * Kotlin/Java imports organize karo: unused hatao, baaki sort karo.
     * Ek hi edit = ek Undo. Returns: hataye gaye imports, 0 = sab clean, -1 = abort.
     */
    fun organizeImports(): Int {
        val ed = text ?: return -1
        val s = ed.toString()
        if (s.isEmpty()) return 0
        val nl = if (s.contains("\r\n")) "\r\n" else "\n"
        val importRegex = Regex("^import\\s+(\\S+?)(\\s+as\\s+(\\w+))?\\s*$")
        data class Imp(val start: Int, val end: Int, val stmt: String, val path: String, val alias: String?)
        val imports = ArrayList<Imp>()
        var firstStart = -1
        var lastEnd = -1
        var pos = 0
        for (rawLine in s.split('\n')) {
            val line = rawLine.trimEnd('\r')
            val m = importRegex.find(line)
            if (m != null) {
                val ls = pos
                val le = pos + rawLine.length
                if (firstStart == -1) firstStart = ls
                lastEnd = le
                val path = m.groupValues[1]
                val alias = m.groupValues[3].ifEmpty { null }
                val stmt = "import $path" + (if (alias != null) " as $alias" else "")
                imports.add(Imp(ls, le, stmt, path, alias))
            }
            pos += rawLine.length + 1
        }
        if (imports.isEmpty()) return 0
        // Guard: first..last ke beech sirf import/blank lines honi chahiye.
        for (bl in s.substring(firstStart, lastEnd).split('\n')) {
            val t = bl.trim()
            if (t.isNotEmpty() && !t.startsWith("import ")) return -1
        }
        // Import lines ko blank karke usage check karo.
        val sb = StringBuilder(s)
        for (imp in imports) for (i in imp.start until imp.end) sb[i] = ' '
        val codeOnly = sb.toString()
        val kept = ArrayList<String>()
        var removed = 0
        for (imp in imports) {
            if (imp.path.endsWith(".*")) { kept.add(imp.stmt); continue } // wildcard: safe rakho
            val name = imp.alias ?: imp.path.substringAfterLast('.')
            if (name.isEmpty()) { kept.add(imp.stmt); continue }
            val used = Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(codeOnly)
            if (used) kept.add(imp.stmt) else removed++
        }
        kept.sort()
        val newBlock = kept.joinToString(nl)
        ed.replace(firstStart, lastEnd, newBlock)
        return removed
    }
    /** Saare collapsed folds khol do (jaise history restore se pehle). */
    fun expandAllFoldsNow() { expandAllFolds() }

    /**
     * INV-161 Fold All: saare top-level foldable regions collapse karo.
     * Layout refresh ke baad (post) taaki line numbers fresh hon; word wrap
     * me layout/text lines alag hoti hain — wahan sirf expand hota hai.
     */
    fun foldAllFolds() {
        if (!foldingEnabled) return
        expandAllFoldsNow()
        post {
            val textLines = (text?.toString() ?: "").split("\n")
            if (lineCount != textLines.size) return@post
            val ranges = ArrayList<Pair<Int, Int>>()
            var line = 0
            while (line < textLines.size) {
                val end = findFoldEnd(line)
                if (end > line) {
                    ranges.add(line to end)
                    line = end + 1
                } else {
                    line++
                }
            }
            for ((s, e) in ranges.sortedByDescending { it.first }) {
                collapseFold(s, e)
            }
        }
    }

    fun isModifiedComparedTo(diskContent: String): Boolean {
        val ed = text ?: return diskContent.isNotEmpty()
        if (collapsedFolds.isEmpty()) {
            if (ed.length != diskContent.length) return true
            return ed.toString() != diskContent
        }
        return getUnfoldedText() != diskContent
    }

    private fun toggleFold(line: Int) {
        if (!foldingEnabled) return
        if (collapsedFolds.containsKey(line)) expandFold(line)
        else {
            val end = getFoldEnd(line)
            if (end > line) collapseFold(line, end)
        }
    }

    private fun collapseFold(startLine: Int, endLine: Int) {
        val editable = text ?: return
        val layout = layout ?: return
        if (startLine < 0 || endLine >= lineCount || endLine <= startLine) return
        val lineEnd = layout.getLineEnd(startLine)
        val foldEndStart = layout.getLineStart(endLine)
        if (lineEnd >= foldEndStart) return
        val hidden = editable.substring(lineEnd, foldEndStart)
        val inner = collapsedFolds.keys.filter { it > startLine && it <= endLine }
        for (k in inner) collapsedFolds.remove(k)
        val cursorInFold = selectionStart in lineEnd until foldEndStart
        isFoldingOp = true
        isProgrammaticChange = true
        try {
            editable.replace(lineEnd, foldEndStart, FOLD_PLACEHOLDER)
        } finally {
            isProgrammaticChange = false
            isFoldingOp = false
        }
        collapsedFolds[startLine] = hidden
        val shift = endLine - startLine
        val adjustments = collapsedFolds.keys.filter { it > startLine }.associateWith { it - shift }
        for ((oldK, newK) in adjustments) {
            val v = collapsedFolds.remove(oldK) ?: continue
            collapsedFolds[newK] = v
        }
        if (cursorInFold) setSelection(lineEnd.coerceIn(0, editable.length))
        foldableCache.clear()
    }

    private fun expandFold(startLine: Int) {
        val editable = text ?: return
        val layout = layout ?: return
        val hidden = collapsedFolds[startLine] ?: return
        if (startLine >= lineCount) {
            collapsedFolds.remove(startLine)
            return
        }
        val ls = layout.getLineStart(startLine)
        val le = layout.getLineEnd(startLine).coerceAtMost(editable.length)
        if (ls >= editable.length) return
        val lineText = editable.substring(ls, le)
        val phIndex = lineText.indexOf(FOLD_PLACEHOLDER)
        if (phIndex == -1) {
            collapsedFolds.remove(startLine)
            return
        }
        val phStart = ls + phIndex
        isFoldingOp = true
        isProgrammaticChange = true
        try {
            editable.replace(phStart, phStart + FOLD_PLACEHOLDER.length, hidden)
        } finally {
            isProgrammaticChange = false
            isFoldingOp = false
        }
        collapsedFolds.remove(startLine)
        val addedLines = hidden.count { it == '\n' }
        val adjustments = collapsedFolds.keys.filter { it > startLine }.associateWith { it + addedLines }
        for ((oldK, newK) in adjustments) {
            val v = collapsedFolds.remove(oldK) ?: continue
            collapsedFolds[newK] = v
        }
        foldableCache.clear()
    }

    private fun expandAllFolds() {
        if (collapsedFolds.isEmpty()) return
        isFoldingOp = true
        try {
            for (line in collapsedFolds.keys.sortedDescending()) {
                val hidden = collapsedFolds.remove(line) ?: continue
                val editable = text ?: continue
                val layout = layout ?: continue
                if (line >= lineCount) continue
                val ls = layout.getLineStart(line)
                val le = layout.getLineEnd(line).coerceAtMost(editable.length)
                if (ls >= editable.length) continue
                val lineText = editable.substring(ls, le)
                val phIndex = lineText.indexOf(FOLD_PLACEHOLDER)
                if (phIndex == -1) continue
                val phStart = ls + phIndex
                isProgrammaticChange = true
                try {
                    editable.replace(phStart, phStart + FOLD_PLACEHOLDER.length, hidden)
                } finally {
                    isProgrammaticChange = false
                }
            }
        } finally {
            isFoldingOp = false
        }
        foldableCache.clear()
    }

    private fun clearFolds() {
        collapsedFolds.clear()
        foldableCache.clear()
    }

    private fun mapVisualToExpanded(visualLine: Int): Int {
        var expanded = 0
        val sorted = collapsedFolds.keys.sorted()
        var fi = 0
        var v = 0
        while (v < visualLine) {
            if (fi < sorted.size && sorted[fi] == v) {
                val hidden = collapsedFolds[v] ?: ""
                expanded += hidden.count { it == '\n' } + 1
                fi++
            } else {
                expanded++
            }
            v++
            if (v > visualLine + 5000) break
        }
        return expanded
    }

    // ---- Inline color picker helpers ----
    private val hexColorRegex = Regex("#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})\\b")

    /** Cursor jis hex color (#RGB / #ARGB / #RRGGBB / #AARRGGBB) par ya uske bilkul paas hai, uska text range — warna null. */
    /** Visible hex colors ke liye swatch list dobara banao (text change par). */
    private fun rebuildSwatches() {
        swatchesDirty = false
        val s = text?.toString() ?: run { swatchList = emptyList(); return }
        if (s.length > 500000) { swatchList = emptyList(); return }
        val out = ArrayList<ColorSwatch>(64)
        for (m in hexColorRegex.findAll(s)) {
            val hex = m.value
            val digits = hex.substring(1)
            val color = try {
                when (digits.length) {
                    3 -> Color.parseColor("#" + digits.map { c -> "$c$c" }.joinToString(""))
                    4 -> {
                        val e = digits.map { c -> "$c$c" }.joinToString("")
                        Color.parseColor("#" + e.substring(6, 8) + e.substring(0, 6))
                    }
                    6 -> Color.parseColor(hex)
                    8 -> Color.parseColor(hex)
                    else -> null
                }
            } catch (e: Exception) { null } ?: continue
            out.add(ColorSwatch(m.range.first, m.range.last + 1, color))
            if (out.size >= 2000) break
        }
        swatchList = out
    }

    /** Caret par R.type.name expression ho to (type, name) do. */
    fun resourceExprAtCaret(): Pair<String, String>? {
        val s = text?.toString() ?: return null
        val cursor = selectionStart.coerceIn(0, s.length)
        var a = cursor
        var b = cursor
        while (a > 0 && (s[a - 1].isLetterOrDigit() || s[a - 1] == '_' || s[a - 1] == '.')) a--
        while (b < s.length && (s[b].isLetterOrDigit() || s[b] == '_' || s[b] == '.')) b++
        if (b <= a) return null
        val m = Regex("^R\\.(\\w+)\\.(\\w+)$").find(s.substring(a, b)) ?: return null
        return Pair(m.groupValues[1], m.groupValues[2])
    }

    fun getHexColorAtCursor(): IntRange? {
        val s = text?.toString() ?: return null
        val cursor = selectionStart.coerceIn(0, s.length)
        for (m in hexColorRegex.findAll(s)) {
            val r = m.range
            if (cursor >= r.first && cursor <= r.last + 1) return r
        }
        return null
    }

    /** Range ke purane hex ko naye hex se replace karke cursor uske end par rakhta hai. */
    fun replaceColorAtRange(range: IntRange, newHex: String) {
        val ed = text ?: return
        val end = (range.last + 1).coerceAtMost(ed.length)
        ed.replace(range.first, end, newHex)
        setSelection((range.first + newHex.length).coerceAtMost(ed.length))
    }

    /** Cursor position par text insert karta hai (undo stack me aata hai). */
    fun insertTextAtCursor(t: String) {
        val ed = text ?: return
        val pos = selectionStart.coerceIn(0, ed.length)
        ed.insert(pos, t)
        setSelection((pos + t.length).coerceAtMost(ed.length))
    }

    /**
     * Selection (ya caret) wali poori lines ka text range deta hai.
     * Agar selection kisi line ke bilkul shuru me khatm ho rahi hai to wo line hissa nahi hoti.
     */
    fun selectedLineRange(ed: Editable): Pair<Int, Int> {
        var s = selectionStart.coerceIn(0, ed.length)
        var e = selectionEnd.coerceIn(0, ed.length)
        if (s > e) { val t = s; s = e; e = t }

        var rangeStart = s
        while (rangeStart > 0 && ed[rangeStart - 1] != '\n') rangeStart--
        var rangeEnd = e
        if (e > s) {
            var probe = e
            while (probe > rangeStart && ed[probe - 1] != '\n') probe--
            if (probe == e) rangeEnd = (e - 1).coerceAtLeast(rangeStart)
        }
        while (rangeEnd < ed.length && ed[rangeEnd] != '\n') rangeEnd++
        return rangeStart to rangeEnd
    }

    // ---- Toggle comment ----

    /** File extension ke hisaab se comment marker; HTML/XML ke liye block pair bhi deta hai. */
    private fun commentMarkerFor(ext: String): Pair<String, Pair<String, String>?> {
        return when (ext.lowercase()) {
            "html", "htm", "xhtml", "xml", "vue", "svg" ->
                "//" to ("<!--" to "-->")
            "py", "pyw", "sh", "bash", "zsh", "rb", "yml", "yaml", "toml",
            "ini", "cfg", "conf", "properties", "r", "pl", "pm" ->
                "#" to null
            "sql", "lua" -> "--" to null
            "lisp", "clj", "cljs", "el", "scm", "ss", "rkt" -> ";" to null
            "vim" -> "\"" to null
            else -> "//" to null
        }
    }

    private fun isLineCommented(line: String, marker: String): Boolean {
        return line.trimStart().startsWith(marker)
    }

    private fun uncommentLine(core: String, marker: String): String {
        var rest = core.substring(marker.length)
        if (rest.startsWith(" ") || rest.startsWith("\t")) rest = rest.substring(1)
        return rest
    }

    private fun isBlockCommented(line: String, open: String, close: String): Boolean {
        val core = line.trim()
        return core.startsWith(open) && core.endsWith(close) &&
            core.length >= open.length + close.length
    }

    private fun unwrapBlock(core: String, open: String, close: String): String {
        var c = core.substring(open.length, core.length - close.length)
        if (c.startsWith(" ") || c.startsWith("\t")) c = c.substring(1)
        if (c.endsWith(" ") || c.endsWith("\t")) c = c.substring(0, c.length - 1)
        return c
    }

    /**
     * Selected lines (ya sirf current line, agar selection nahi hai) ko comment/uncomment
     * karta hai — marker file type se chunta hai. Poora badlav ek hi edit me hota hai,
     * isliye Undo ek baar me wapas laata hai.
     */
    fun toggleComment(fileExtension: String) {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val (lineMarker, blockPair) = commentMarkerFor(fileExtension)

        val (rangeStart, rangeEnd) = selectedLineRange(ed)

        val original = ed.substring(rangeStart, rangeEnd)
        val lines = original.split('\n')
        val targets = lines.filter { it.isNotBlank() }
        if (targets.isEmpty()) return

        val newLines: List<String>
        if (blockPair != null) {
            val (open, close) = blockPair
            val allWrapped = targets.all { isBlockCommented(it, open, close) }
            newLines = lines.map { line ->
                if (line.isBlank()) line
                else {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val core = line.substring(indent.length)
                    if (allWrapped) indent + unwrapBlock(core, open, close)
                    else indent + open + " " + core + " " + close
                }
            }
        } else {
            val allCommented = targets.all { isLineCommented(it, lineMarker) }
            newLines = lines.map { line ->
                if (line.isBlank()) line
                else {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val core = line.substring(indent.length)
                    if (allCommented) indent + uncommentLine(core, lineMarker)
                    else indent + lineMarker + " " + core
                }
            }
        }

        val newText = newLines.joinToString("\n")
        if (newText == original) return
        ed.replace(rangeStart, rangeEnd, newText)
        setSelection(rangeStart, (rangeStart + newText.length).coerceAtMost(ed.length))
    }

    /**
     * Current line (ya selected lines) ki copy theek neeche insert karta hai.
     * Ek hi edit hota hai, isliye Undo ek baar me wapas laata hai. Cursor nayi copy par rehta hai.
     */
    fun duplicateLine() {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val (rangeStart, rangeEnd) = selectedLineRange(ed)
        val original = ed.substring(rangeStart, rangeEnd)
        if (original.isEmpty()) return
        ed.insert(rangeEnd, "\n" + original)
        val dupStart = rangeEnd + 1
        setSelection(dupStart, (dupStart + original.length).coerceAtMost(ed.length))
    }

    /**
     * Current line (ya selected lines) ko ek line upar (direction=-1) ya neeche (direction=1)
     * move karta hai. Ek hi edit hota hai, isliye Undo ek baar me wapas laata hai.
     */
    fun moveLine(direction: Int) {
        val ed = text ?: return
        if (ed.isEmpty() || direction == 0) return
        val (rangeStart, rangeEnd) = selectedLineRange(ed)
        val block = ed.substring(rangeStart, rangeEnd)
        if (block.isEmpty()) return
        if (direction < 0) {
            if (rangeStart == 0) return
            var prevStart = rangeStart - 1
            while (prevStart > 0 && ed[prevStart - 1] != '\n') prevStart--
            val prevLine = ed.substring(prevStart, rangeStart - 1)
            ed.replace(prevStart, rangeEnd, block + "\n" + prevLine)
            setSelection(prevStart, (prevStart + block.length).coerceAtMost(ed.length))
        } else {
            if (rangeEnd >= ed.length) return
            val nextStart = rangeEnd + 1
            var nextEnd = nextStart
            while (nextEnd < ed.length && ed[nextEnd] != '\n') nextEnd++
            val nextLine = ed.substring(nextStart, nextEnd)
            ed.replace(rangeStart, nextEnd, nextLine + "\n" + block)
            val newStart = rangeStart + nextLine.length + 1
            setSelection(newStart, (newStart + block.length).coerceAtMost(ed.length))
        }
    }

    /**
     * Join lines (Ctrl+J jaisa): current line (ya selection ki har line) agli line se
     * judti hai — beech ka newline + agli line ka leading whitespace hat kar ek space
     * judta hai (khaali line se judne par space nahi). Neeche se upar join karta hai
     * taaki offsets valid rahen. Ek hi edit = ek Undo, cursor pehle join point par.
     */
    fun joinLines() {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val s = minOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        val e = maxOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        var rs = s
        while (rs > 0 && ed[rs - 1] != '\n') rs--
        var re = e
        if (re > rs) {
            var tmp = re
            while (tmp > 0 && ed[tmp - 1] != '\n') tmp--
            if (tmp == re) re = (re - 1).coerceAtLeast(rs)
        }
        val starts = ArrayList<Int>()
        starts.add(rs)
        var cur = rs
        while (true) {
            var nl = cur
            while (nl < ed.length && ed[nl] != '\n') nl++
            if (nl >= ed.length) break
            cur = nl + 1
            if (cur > re) break
            starts.add(cur)
        }
        if (s == e) {
            var nl = rs
            while (nl < ed.length && ed[nl] != '\n') nl++
            if (nl >= ed.length) return
            starts.add(nl + 1)
        }
        if (starts.size < 2) return
        var newCaret = -1
        for (i in starts.size - 1 downTo 1) {
            val ls = starts[i]
            var le = ls
            while (le < ed.length && ed[le] != '\n') le++
            var pt = ls - 1
            while (pt > starts[i - 1] && (ed[pt - 1] == ' ' || ed[pt - 1] == '\t')) pt--
            var nt = ls
            while (nt < le && (ed[nt] == ' ' || ed[nt] == '\t')) nt++
            val joinWith = if (pt == starts[i - 1] || nt >= le) "" else " "
            ed.replace(pt, nt, joinWith)
            if (i == 1) newCaret = (pt + joinWith.length).coerceIn(0, ed.length)
        }
        if (newCaret >= 0) setSelection(newCaret, newCaret)
    }

    /**
     * Delete line (Ctrl+Shift+K jaisa): current line (ya selection ki poori lines)
     * delete hoti hain, saath me newline bhi (aakhri line par pichhla newline).
     * Ek hi edit = ek Undo, cursor delete hui jagah par rehta hai.
     */
    fun deleteLines() {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val s = minOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        val e = maxOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        var rs = s
        while (rs > 0 && ed[rs - 1] != '\n') rs--
        var re = e
        if (re > rs) {
            var tmp = re
            while (tmp > 0 && ed[tmp - 1] != '\n') tmp--
            if (tmp == re) re = (re - 1).coerceAtLeast(rs)
        }
        var le = re
        while (le < ed.length && ed[le] != '\n') le++
        var delStart = rs
        var delEnd = le
        if (le < ed.length) delEnd = le + 1 else if (rs > 0) delStart = rs - 1
        if (delStart >= delEnd) return
        ed.replace(delStart, delEnd, "")
        val nc = delStart.coerceIn(0, ed.length)
        setSelection(nc, nc)
    }

    /**
     * Remove duplicate lines: selected lines (ya poori file, agar selection nahi)
     * me duplicate lines hatao, pehli occurrence ka order rakho. Exact-match.
     * Ek hi edit = ek Undo.
     */
    fun removeDuplicateLines() {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val hasSel = selectionStart != selectionEnd
        val s = minOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        val e = maxOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        var rs = 0
        var re = ed.length
        if (hasSel) {
            rs = s
            while (rs > 0 && ed[rs - 1] != '\n') rs--
            var tmp = e
            while (tmp > 0 && ed[tmp - 1] != '\n') tmp--
            re = if (tmp == e) (e - 1).coerceAtLeast(rs) else e
            while (re < ed.length && ed[re] != '\n') re++
        }
        val block = ed.substring(rs, re).toString()
        if (block.isEmpty()) return
        val lines = block.split('\n')
        val seen = LinkedHashSet<String>()
        val kept = lines.filter { seen.add(it) }
        if (kept.size == lines.size) {
            Toast.makeText(context, "No duplicate lines", Toast.LENGTH_SHORT).show()
            return
        }
        ed.replace(rs, re, kept.joinToString("\n"))
        val nc = rs.coerceIn(0, ed.length)
        setSelection(nc, nc)
    }

    /**
     * Reverse lines: selected lines (ya poori file, agar selection nahi) ka order
     * ulta karta hai. Trailing newline preserved. Ek hi edit = ek Undo.
     */
    fun reverseLines() {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val hasSel = selectionStart != selectionEnd
        val s = minOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        val e = maxOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        var rs = 0
        var re = ed.length
        if (hasSel) {
            rs = s
            while (rs > 0 && ed[rs - 1] != '\n') rs--
            var tmp = e
            while (tmp > 0 && ed[tmp - 1] != '\n') tmp--
            re = if (tmp == e) (e - 1).coerceAtLeast(rs) else e
            while (re < ed.length && ed[re] != '\n') re++
        }
        val block = ed.substring(rs, re).toString()
        if (!block.contains('\n')) {
            Toast.makeText(context, "Nothing to reverse", Toast.LENGTH_SHORT).show()
            return
        }
        val trailingNl = block.endsWith('\n')
        val lines = block.split('\n')
        val core = if (trailingNl) lines.dropLast(1) else lines
        val newBlock = core.reversed().joinToString("\n") + if (trailingNl) "\n" else ""
        ed.replace(rs, re, newBlock)
        setSelection(rs, rs)
    }

    /**
     * Har line ka leading indentation convert karta hai: tabs -> spaces
     * (toSpaces=true) ya spaces -> tabs (tabSize spaces = 1 tab, mixed pehle
     * normalize hota hai). Poori file par, ek hi edit = ek Undo.
     */
    fun convertIndentation(toSpaces: Boolean) {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val ts = tabSize.coerceAtLeast(1)
        val sb = StringBuilder(ed.length)
        var i = 0
        var changed = false
        while (i < ed.length) {
            var j = i
            while (j < ed.length && ed[j] != '\n') j++
            val line = ed.substring(i, j).toString()
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            if (indent.isNotEmpty()) {
                val newIndent = if (toSpaces) {
                    indent.replace("\t", " ".repeat(ts))
                } else {
                    val spaces = indent.replace("\t", " ".repeat(ts))
                    "\t".repeat(spaces.length / ts) + " ".repeat(spaces.length % ts)
                }
                if (newIndent != indent) {
                    changed = true
                    sb.append(newIndent).append(line.substring(indent.length))
                } else {
                    sb.append(line)
                }
            } else {
                sb.append(line)
            }
            if (j < ed.length) sb.append('\n')
            i = j + 1
        }
        if (!changed) {
            Toast.makeText(context, "Nothing to convert", Toast.LENGTH_SHORT).show()
            return
        }
        val s = selectionStart.coerceIn(0, ed.length)
        val e = selectionEnd.coerceIn(0, ed.length)
        ed.replace(0, ed.length, sb.toString())
        setSelection(s.coerceAtMost(sb.length), e.coerceAtMost(sb.length))
    }

    /**
     * Saari khaali (blank/whitespace-only) lines hata deta hai.
     * Trailing newline preserved. Ek hi edit = ek Undo.
     */
    /**
     * Line endings convert karta hai (LF <-> CRLF). Caret position preserve
     * hoti hai. Ek edit = ek Undo.
     */
    fun convertLineEndings(toCrlf: Boolean) {
        val ed = text ?: return
        val src = ed.toString()
        if (src.isEmpty()) return
        val caret = selectionStart.coerceIn(0, src.length)
        val before = src.substring(0, caret)
        val normalized = src.replace("\r\n", "\n").replace('\r', '\n')
        val converted = if (toCrlf) normalized.replace("\n", "\r\n") else normalized
        if (converted == src) {
            Toast.makeText(context, if (toCrlf) "Already CRLF" else "Already LF", Toast.LENGTH_SHORT).show()
            return
        }
        val newCaret = if (toCrlf) {
            val normBefore = before.replace("\r\n", "\n").replace('\r', '\n')
            caret + normBefore.count { it == '\n' }
        } else {
            val crlfCount = (before.length - before.replace("\r\n", "").length) / 2
            caret - crlfCount
        }
        ed.replace(0, ed.length, converted)
        setSelection(newCaret.coerceIn(0, converted.length))
    }

    fun removeBlankLines() {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val trailingNl = ed.endsWith("\n")
        val lines = ed.toString().split('\n')
        val kept = lines.filter { it.isNotBlank() }
        if (kept.size == lines.size) {
            Toast.makeText(context, "No blank lines", Toast.LENGTH_SHORT).show()
            return
        }
        ed.replace(0, ed.length, kept.joinToString("\n") + if (trailingNl) "\n" else "")
        setSelection(0, 0)
    }

    /**
     * Toggle case: selection ka case cycle karta hai — lower -> UPPER -> Title -> lower.
     * Ek hi edit = ek Undo, selection naye text par rehti hai.
     */
    /**
     * Selection (ya caret wala word) snake_case <-> camelCase me badalta hai.
     * '_' ho to camelCase, warna snake_case. Ek edit = ek Undo.
     */
    fun convertSnakeCamel() {
        val ed = text ?: return
        val s: Int
        val e: Int
        if (selectionStart != selectionEnd) {
            s = selectionStart; e = selectionEnd
        } else {
            val caret = selectionStart
            var a = caret
            while (a > 0 && (ed[a - 1].isLetterOrDigit() || ed[a - 1] == '_')) a--
            var b = caret
            while (b < ed.length && (ed[b].isLetterOrDigit() || ed[b] == '_')) b++
            if (a == b) {
                Toast.makeText(context, "No word at caret", Toast.LENGTH_SHORT).show()
                return
            }
            s = a; e = b
        }
        val src = ed.substring(s, e)
        val converted = if ('_' in src) snakeToCamel(src) else camelToSnake(src)
        if (converted == src) {
            Toast.makeText(context, "Nothing to convert", Toast.LENGTH_SHORT).show()
            return
        }
        ed.replace(s, e, converted)
        setSelection(s, s + converted.length)
    }

    /** Caret ke aas-paas wale word (letters/digits/_) ki bounds, ya null. */
    private fun wordBoundsAtCaret(): Pair<Int, Int>? {
        val ed = text ?: return null
        val caret = selectionStart.coerceIn(0, ed.length)
        var a = caret
        while (a > 0 && (ed[a - 1].isLetterOrDigit() || ed[a - 1] == '_')) a--
        var b = caret
        while (b < ed.length && (ed[b].isLetterOrDigit() || ed[b] == '_')) b++
        return if (a == b) null else a to b
    }

    private fun snakeToCamel(src: String): String {
        val prefix = if (src.startsWith("_")) "_" else ""
        val parts = src.trimStart('_').split('_')
        if (parts.size < 2) return src
        return prefix + parts[0] + parts.drop(1).joinToString("") { p ->
            if (p.isEmpty()) "" else p[0].uppercaseChar() + p.substring(1)
        }
    }

    private fun camelToSnake(src: String): String {
        val sb = StringBuilder(src.length + 8)
        src.forEachIndexed { i, c ->
            if (c.isUpperCase() && i > 0) sb.append('_')
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * Selection ka case cycle karta hai (lower -> UPPER -> Title).
     * Selection na ho to caret wale word par lagta hai.
     */
    fun toggleCase() {
        val ed = text ?: return
        var s = minOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        var e = maxOf(selectionStart, selectionEnd).coerceIn(0, ed.length)
        if (s == e) {
            val wb = wordBoundsAtCaret()
            if (wb == null) {
                Toast.makeText(context, "No word at caret", Toast.LENGTH_SHORT).show()
                return
            }
            s = wb.first; e = wb.second
        }
        val sel = ed.substring(s, e).toString()
        if (sel.isEmpty()) return
        val hasLower = sel.any { it.isLowerCase() }
        val hasUpper = sel.any { it.isUpperCase() }
        val next = when {
            !hasUpper -> sel.uppercase()
            !hasLower -> sel.lowercase().split(' ').joinToString(" ") { w ->
                w.replaceFirstChar { c -> c.uppercase() }
            }
            else -> sel.lowercase()
        }
        if (next == sel) return
        ed.replace(s, e, next)
        setSelection(s, (s + next.length).coerceAtMost(ed.length))
    }

    private fun isEscapedQuote(ed: CharSequence, pos: Int): Boolean {
        var bs = 0
        var k = pos - 1
        while (k >= 0 && ed[k] == '\\') {
            bs++
            k--
        }
        return bs % 2 == 1
    }

    /**
     * Caret jis string ke andar hai uska content select karta hai (quotes ke bina).
     * Escaped quotes (\\") skip hote hain, sirf single-line strings.
     */
    fun selectStringContent() {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val caret = selectionStart.coerceIn(0, ed.length)
        var open = -1
        var q = '\u0000'
        var i = (caret - 1).coerceAtMost(ed.length - 1)
        val backLimit = (caret - 10000).coerceAtLeast(0)
        while (i >= backLimit) {
            val c = ed[i]
            if ((c == '"' || c == '\'') && !isEscapedQuote(ed, i)) {
                open = i
                q = c
                break
            }
            i--
        }
        if (open < 0) {
            Toast.makeText(context, "No string at cursor", Toast.LENGTH_SHORT).show()
            return
        }
        var close = -1
        var j = open + 1
        val fwdLimit = (open + 10000).coerceAtMost(ed.length - 1)
        while (j <= fwdLimit) {
            val c = ed[j]
            if (c == '\n') break
            if (c == q && !isEscapedQuote(ed, j)) {
                close = j
                break
            }
            j++
        }
        if (close < 0 || caret <= open || caret > close) {
            Toast.makeText(context, "Cursor not inside a string", Toast.LENGTH_SHORT).show()
            return
        }
        setSelection(open + 1, close)
    }

    /** Caret/selection wala word deta hai, ya null. */
    fun wordAtCaret(): String? {
        val ed = text ?: return null
        if (selectionStart != selectionEnd) {
            val w = ed.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)).toString()
            return w.ifEmpty { null }
        }
        val c = selectionStart.coerceIn(0, ed.length)
        var a = c
        while (a > 0 && isSelWordChar(ed[a - 1])) a--
        var b = c
        while (b < ed.length && isSelWordChar(ed[b])) b++
        return if (a == b) null else ed.substring(a, b).toString()
    }

    /**
     * Poori file me word ke saare whole-word occurrences replace karta hai.
     * Ek hi edit = ek Undo. Returns replaced count.
     */
    fun renameSymbol(oldName: String, newName: String): Int {
        val ed = text ?: return 0
        if (oldName.isEmpty() || oldName == newName || newName.isEmpty()) return 0
        val textStr = ed.toString()
        val matches = Regex("\\b" + Regex.escape(oldName) + "\\b").findAll(textStr).toList()
        if (matches.isEmpty()) return 0
        val sb = StringBuilder(textStr.length)
        var last = 0
        for (mt in matches) {
            sb.append(textStr, last, mt.range.first)
            sb.append(newName)
            last = mt.range.last + 1
        }
        sb.append(textStr, last, textStr.length)
        ed.replace(0, ed.length, sb.toString())
        return matches.size
    }

    /**
     * Har line ke end ka trailing whitespace (space/tab) hatata hai.
     * Line breaks (\n / \r\n / \r) waise ke waise rehte hain.
     * Ek hi edit hota hai, isliye Undo ek baar me wapas laata hai.
     * @return true agar kuch badla ho
     */
    fun trimTrailingWhitespace(): Boolean {
        val ed = text ?: return false
        if (ed.isEmpty()) return false
        val original = ed.toString()
        val sb = StringBuilder(original.length)
        var i = 0
        var changed = false
        while (i < original.length) {
            var j = i
            while (j < original.length && original[j] != '\n' && original[j] != '\r') j++
            var k = j
            while (k > i && (original[k - 1] == ' ' || original[k - 1] == '\t')) k--
            if (k < j) changed = true
            sb.append(original, i, k)
            if (j < original.length) {
                if (original[j] == '\r' && j + 1 < original.length && original[j + 1] == '\n') {
                    sb.append("\r\n")
                    j += 2
                } else {
                    sb.append(original[j])
                    j++
                }
            }
            i = j
        }
        if (!changed) return false
        val s = selectionStart.coerceIn(0, original.length)
        val e = selectionEnd.coerceIn(0, original.length)
        val trimmed = sb.toString()
        ed.replace(0, original.length, trimmed)
        setSelection(s.coerceAtMost(trimmed.length), e.coerceAtMost(trimmed.length))
        return true
    }

    /**
     * Caret ko enclose karne wale bracket pair ka (opener, closer) offset pair — ya null.
     * Backward scan me depth gin ke enclosing opener dhoondhta hai (20000 chars cap),
     * phir findMatchingBracket se uska joda milata hai.
     */
    private fun computeEnclosingBracketPair(): Pair<Int, Int>? {
        val ed = text ?: return null
        if (ed.isEmpty()) return null
        val caret = selectionStart
        if (caret != selectionEnd) return null
        var depth = 0
        var opener = -1
        var i = (caret - 1).coerceAtMost(ed.length - 1)
        val limit = (caret - 20000).coerceAtLeast(0)
        while (i >= limit) {
            val c = ed[i]
            if (c in closeBrackets) depth++
            else if (c in openBrackets) {
                if (depth == 0) { opener = i; break }
                depth--
            }
            i--
        }
        if (opener < 0) return null
        val closer = findMatchingBracket(ed, opener, true) ?: return null
        return opener to closer
    }

    /**
     * Caret ko gherne wale `{...}` blocks ki opener LINES (0-based), bahar se andar tak.
     * Breadcrumb navigation ke liye. Upar wali backward scan ka chain version.
     */
    fun enclosingScopeLines(): List<Int> {
        val ed = text ?: return emptyList()
        if (ed.isEmpty() || selectionStart != selectionEnd) return emptyList()
        val lineStarts = ArrayList<Int>(ed.length / 40 + 1)
        lineStarts.add(0)
        for (i in ed.indices) {
            if (ed[i] == '\n') lineStarts.add(i + 1)
        }
        fun lineOf(offset: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lineStarts[mid] <= offset) lo = mid + 1 else hi = mid - 1
            }
            return hi.coerceAtLeast(0)
        }
        val out = mutableListOf<Int>()
        var pos = selectionStart.coerceIn(0, ed.length)
        var guard = 0
        while (guard++ < 20) {
            var depth = 0
            var opener = -1
            var i = (pos - 1).coerceAtMost(ed.length - 1)
            val limit = (pos - 20000).coerceAtLeast(0)
            while (i >= limit) {
                val c = ed[i]
                if (c in closeBrackets) depth++
                else if (c in openBrackets) {
                    if (depth == 0) { opener = i; break }
                    depth--
                }
                i--
            }
            if (opener < 0) break
            out.add(0, lineOf(opener))
            pos = opener
        }
        return out
    }

    // ---- Matching bracket highlight ----

    /**
     * Caret ke just pehle / just baad wale bracket ka matching bracket dhoondhta hai.
     * Dono offsets ka Pair deta hai (chhota offset pehle), ya null.
     */
    private fun computeBracketPair(): Pair<Int, Int>? {
        val ed = text ?: return null
        if (ed.isEmpty()) return null
        val caret = selectionStart
        if (caret != selectionEnd) return null
        var at = -1
        var isOpen = false
        for (off in listOf(caret - 1, caret)) {
            if (off < 0 || off >= ed.length) continue
            val ch = ed[off]
            if (ch in openBrackets) { at = off; isOpen = true; break }
            if (ch in closeBrackets) { at = off; isOpen = false; break }
        }
        if (at < 0) return null
        val match = findMatchingBracket(ed, at, isOpen) ?: return null
        return if (at < match) at to match else match to at
    }

    private fun findMatchingBracket(ed: CharSequence, at: Int, isOpen: Boolean): Int? {
        val openCh = ed[at]
        val closeCh = bracketPairs[openCh] ?: return null
        var depth = 0
        if (isOpen) {
            var i = at
            val limit = (at + 20000).coerceAtMost(ed.length)
            while (i < limit) {
                val c = ed[i]
                if (c == openCh) depth++
                else if (c == closeCh) {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
        } else {
            var i = at
            val limit = (at - 20000).coerceAtLeast(0)
            while (i >= limit) {
                val c = ed[i]
                if (c == openCh) depth++
                else if (c == closeCh) {
                    depth--
                    if (depth == 0) return i
                }
                i--
            }
        }
        return null
    }

    private fun updateBracketHighlight() {
        if (largeFileMode) { if (bracketPair != null) { bracketPair = null; invalidate() }; return }
        val np = computeBracketPair()
        if (np != bracketPair) {
            bracketPair = np
            invalidate()
        }
    }

    /** Caret ke enclosing bracket pair ka scope guide track karta hai (onDraw me violet line). */
    private fun updateScopeGuide() {
        if (largeFileMode) { if (scopeGuidePair != null) { scopeGuidePair = null; invalidate() }; return }
        val np = computeEnclosingBracketPair()
        if (np != scopeGuidePair) {
            scopeGuidePair = np
            invalidate()
        }
    }

    /**
     * Caret wale word (2+ chars, collapsed caret par) ko track karta hai taaki
     * onDraw uske saare visible occurrences highlight kar sake. Badalne par invalidate.
     */
    /**
     * Caret jab kisi HTML/XML tag ke '<' ya '>' ke just pehle/baad ho to uska
     * matching tag pair halke violet rounded-rect se highlight hota hai
     * (bracket highlight jaisa). INV-36 ke tag helpers reuse hote hain.
     */
    private fun updateTagHighlight() {
        if (largeFileMode) { if (tagHlPair != null) { tagHlPair = null; invalidate() }; return }
        val ed = text
        val np = if (ed != null && selectionStart == selectionEnd) computeTagPair() else null
        if (np != tagHlPair) {
            tagHlPair = np
            invalidate()
        }
    }

    /** Caret ke paas wale tag ka (opener, closer) range pair — end exclusive; ya null. */
    private fun computeTagPair(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val ed = text ?: return null
        val s = ed.toString()
        if (s.isEmpty() || s.length > 100000) return null
        val caret = selectionStart.coerceIn(0, s.length)
        var lt = -1
        if (caret > 0 && s[caret - 1] == '<') lt = caret - 1
        else if (caret < s.length && s[caret] == '<') lt = caret
        else {
            var gt = -1
            if (caret > 0 && s[caret - 1] == '>') gt = caret - 1
            else if (caret < s.length && s[caret] == '>') gt = caret
            if (gt >= 0) lt = s.lastIndexOf('<', gt)
        }
        if (lt < 0) return null
        val t = tagAt(s, lt) ?: return null
        if (t.atomic || t.name.isEmpty()) return null
        return if (!t.closer) {
            val ce = matchingCloseEnd(s, lt, t.name)
            if (ce < 0) null else (lt to t.end + 1) to (ce - t.name.length - 3 to ce)
        } else {
            val op = enclosingOpener(s, lt, t.name) ?: return null
            val ot = tagAt(s, op.first) ?: return null
            if (ot.atomic) return null
            (op.first to ot.end + 1) to (lt to t.end + 1)
        }
    }

    private fun updateCaretWordHighlight() {
        val ed = text
        val nw: String? = if (ed != null && selectionStart == selectionEnd) {
            val c = selectionStart.coerceIn(0, ed.length)
            var a = c
            while (a > 0 && isSelWordChar(ed[a - 1])) a--
            var b = c
            while (b < ed.length && isSelWordChar(ed[b])) b++
            if (b - a >= 2) ed.substring(a, b).toString() else null
        } else null
        if (nw != caretWord) {
            caretWord = nw
            invalidate()
        }
    }

    /** Caret ke paas wale bracket se uske matching bracket tak select karta hai. */
    fun selectToMatchingBracket() {
        val ed = text ?: return
        val bp = computeBracketPair() ?: return
        setSelection(bp.first, (bp.second + 1).coerceAtMost(ed.length))
    }

    /**
     * Caret ko matching bracket par jump karata hai (toggle: opener <-> closer).
     * computeBracketPair reuse hota hai; bp.first hamesha opener hota hai.
     */
    private data class TagInfo(val name: String, val closer: Boolean, val atomic: Boolean, val end: Int)

    /**
     * HTML/XML tag ka matching pair select karta hai: caret tag ke andar ho
     * ya uske content me, to poora <tag>...</tag> select hota hai.
     * Self-closing tag par sirf wahi tag; comment/doctype skip hote hain.
     */
    fun selectToMatchingTag() {
        val ed = text ?: return
        val s = ed.toString()
        if (s.isEmpty()) return
        val caret = selectionStart.coerceIn(0, s.length)
        var openLt = -1
        var tagName = ""
        val lt = s.lastIndexOf('<', (caret - 1).coerceAtLeast(0))
        if (lt >= 0) {
            val gtAfter = s.indexOf('>', lt)
            if (gtAfter < 0 || gtAfter >= caret) {
                val t = tagAt(s, lt)
                if (t != null && t.name.isNotEmpty()) {
                    if (t.atomic) {
                        setSelection(lt, t.end + 1)
                        return
                    }
                    if (t.closer) {
                        val op = enclosingOpener(s, lt, t.name)
                        if (op != null) { openLt = op.first; tagName = op.second }
                    } else {
                        openLt = lt; tagName = t.name
                    }
                }
            }
        }
        if (openLt < 0) {
            val op = enclosingOpener(s, caret, null)
            if (op != null) { openLt = op.first; tagName = op.second }
        }
        if (openLt < 0 || tagName.isEmpty()) {
            Toast.makeText(context, "No tag at caret", Toast.LENGTH_SHORT).show()
            return
        }
        val closeEnd = matchingCloseEnd(s, openLt, tagName)
        if (closeEnd < 0) {
            Toast.makeText(context, "No matching tag", Toast.LENGTH_SHORT).show()
            return
        }
        setSelection(openLt, closeEnd)
    }

    /** Position l par '<' hona chahiye; comment/doctype atomic hote hain. */
    private fun tagAt(s: String, l: Int): TagInfo? {
        if (l < 0 || l + 1 >= s.length) return null
        if (s.startsWith("<!--", l)) {
            val e = s.indexOf("-->", l + 4)
            return if (e < 0) null else TagInfo("", false, true, e + 2)
        }
        val c1 = s[l + 1]
        if (c1 == '!' || c1 == '?') {
            val e = s.indexOf('>', l + 2)
            return if (e < 0) null else TagInfo("", false, true, e)
        }
        var j = l + 1
        val closer = j < s.length && s[j] == '/'
        if (closer) j++
        val ns = j
        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '-' || s[j] == '_' || s[j] == ':')) j++
        val nm = s.substring(ns, j)
        if (nm.isEmpty()) return null
        val e = s.indexOf('>', l)
        if (e < 0) return null
        var k = e - 1
        while (k > l && s[k].isWhitespace()) k--
        val selfClosing = !closer && k > l && s[k] == '/'
        return TagInfo(nm, closer, selfClosing, e)
    }

    /** Opener ke '<' se uske matching closer ka end ('>' ke baad); na mile to -1. */
    private fun matchingCloseEnd(s: String, openLt: Int, tagName: String): Int {
        val t0 = tagAt(s, openLt) ?: return -1
        var depth = 1
        var p = t0.end + 1
        while (p < s.length) {
            val l = s.indexOf('<', p)
            if (l < 0) return -1
            val t = tagAt(s, l)
            if (t == null) { p = l + 1; continue }
            if (t.atomic || t.name != tagName) { p = t.end + 1; continue }
            if (t.closer) {
                depth--
                if (depth == 0) return t.end + 1
            } else depth++
            p = t.end + 1
        }
        return -1
    }

    /** pos se peeche scan karke enclosing opener; filterName null = koi bhi tag. */
    private fun enclosingOpener(s: String, pos: Int, filterName: String?): Pair<Int, String>? {
        var depth = 0
        var p = pos - 1
        while (p >= 0) {
            val l = s.lastIndexOf('<', p)
            if (l < 0) return null
            val t = tagAt(s, l)
            if (t == null || t.atomic || t.name.isEmpty()) { p = l - 1; continue }
            if (filterName != null && t.name != filterName) { p = l - 1; continue }
            if (t.closer) depth++
            else {
                if (depth == 0) return l to t.name
                depth--
            }
            p = l - 1
        }
        return null
    }

    fun jumpToMatchingBracket() {
        val ed = text ?: return
        val bp = computeBracketPair()
        if (bp == null) {
            Toast.makeText(context, "No bracket at cursor", Toast.LENGTH_SHORT).show()
            return
        }
        val caret = selectionStart.coerceIn(0, ed.length)
        val nearOpener = (caret - 1 == bp.first || caret == bp.first)
        val target = (if (nearOpener) bp.second else bp.first).coerceIn(0, ed.length)
        setSelection(target, target)
        val layout = layout
        if (layout != null && target <= layout.text.length) {
            val line = layout.getLineForOffset(target)
            scrollTo(scrollX, (layout.getLineTop(line) - height / 3).coerceAtLeast(0))
        }
    }

    /**
     * Aakhri asli edit (typing/undo/bot-action; fold aur file-load nahi) wali jagah
     * par caret le jaata hai. Badi file me ghumne ke baad wapas aane ke kaam ka.
     */
    fun jumpToLastEdit() {
        val ed = text ?: return
        if (lastEditOffset < 0) {
            Toast.makeText(context, "No edits yet", Toast.LENGTH_SHORT).show()
            return
        }
        val target = lastEditOffset.coerceIn(0, ed.length)
        setSelection(target, target)
        val l = layout
        if (l != null && target <= l.text.length) {
            val line = l.getLineForOffset(target)
            scrollTo(scrollX, (l.getLineTop(line) - height / 3).coerceAtLeast(0))
        }
    }

    /**
     * Current line par bookmark lagata/hataata hai. Gutter me violet dot dikhta hai.
     * Bookmarks file-load par clear hote hain (per-file track nahi).
     */
    fun toggleBookmark() {
        val l = layout ?: return
        val line = l.getLineForOffset(selectionStart.coerceIn(0, length()))
        if (bookmarks.contains(line)) {
            bookmarks.remove(line)
            Toast.makeText(context, "Bookmark removed", Toast.LENGTH_SHORT).show()
        } else {
            bookmarks.add(line)
            Toast.makeText(context, "Bookmark added", Toast.LENGTH_SHORT).show()
        }
        invalidate()
    }

    /** INV-162: bookmarked lines ki list — (1-based line number, text preview). */
    fun getBookmarkedLines(): List<Pair<Int, String>> {
        val ed = text ?: return emptyList()
        val l = layout ?: return emptyList()
        return bookmarks.mapNotNull { ln ->
            val cli = ln.coerceIn(0, l.lineCount - 1)
            val s = l.getLineStart(cli).coerceIn(0, ed.length)
            val e = l.getLineEnd(cli).coerceIn(0, ed.length)
            if (s > e) null else (ln + 1) to ed.substring(s, e).trim().take(80)
        }
    }

    /** Agle bookmark par jump karta hai (aakhri ke baad pehle par wrap). */
    fun clearBookmarks() {
        if (bookmarks.isEmpty()) {
            Toast.makeText(context, "No bookmarks", Toast.LENGTH_SHORT).show()
            return
        }
        bookmarks.clear()
        invalidate()
        Toast.makeText(context, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
    }

    fun nextBookmark() {
        if (bookmarks.isEmpty()) {
            Toast.makeText(context, "No bookmarks", Toast.LENGTH_SHORT).show()
            return
        }
        val l = layout ?: return
        val cur = l.getLineForOffset(selectionStart.coerceIn(0, length()))
        val next = bookmarks.firstOrNull { it > cur } ?: bookmarks.first()
        val target = l.getLineStart(next.coerceIn(0, lineCount - 1))
        setSelection(target, target)
        scrollTo(scrollX, (l.getLineTop(next.coerceIn(0, lineCount - 1)) - height / 3).coerceAtLeast(0))
    }

    /**
     * Paragraph jump: agli (forward=true) ya pichhli khaali line par caret le jaata hai.
     * Code blocks ke beech navigate karne ke kaam ka.
     */
    fun jumpToEmptyLine(forward: Boolean) {
        val ed = text ?: return
        val l = layout ?: return
        val total = lineCount
        if (total <= 1) return
        val curLine = l.getLineForOffset(selectionStart.coerceIn(0, ed.length))
        if (forward) {
            for (ln in curLine + 1 until total) {
                if (ed.substring(l.getLineStart(ln), l.getLineEnd(ln)).isBlank()) {
                    val t = l.getLineStart(ln)
                    setSelection(t, t)
                    scrollTo(scrollX, (l.getLineTop(ln) - height / 3).coerceAtLeast(0))
                    return
                }
            }
            Toast.makeText(context, "No next empty line", Toast.LENGTH_SHORT).show()
        } else {
            for (ln in curLine - 1 downTo 0) {
                if (ed.substring(l.getLineStart(ln), l.getLineEnd(ln)).isBlank()) {
                    val t = l.getLineStart(ln)
                    setSelection(t, t)
                    scrollTo(scrollX, (l.getLineTop(ln) - height / 3).coerceAtLeast(0))
                    return
                }
            }
            Toast.makeText(context, "No previous empty line", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Current line ke neeche (below=true) ya upar nayi khali line insert karta hai,
     * current line ka indent lekar. Cursor nayi line par rehta hai.
     */
    fun insertLine(below: Boolean) {
        val ed = text ?: return
        val caret = selectionStart.coerceIn(0, ed.length)
        var lineStart = caret
        while (lineStart > 0 && ed[lineStart - 1] != '\n') lineStart--
        var lineEnd = caret
        while (lineEnd < ed.length && ed[lineEnd] != '\n') lineEnd++
        val indent = ed.substring(lineStart, lineEnd).takeWhile { it == ' ' || it == '\t' }
        if (below) {
            ed.insert(lineEnd, "\n" + indent)
            setSelection((lineEnd + 1 + indent.length).coerceAtMost(ed.length))
        } else {
            ed.insert(lineStart, indent + "\n")
            setSelection((lineStart + indent.length).coerceAtMost(ed.length))
        }
    }

    /**
     * Selected lines (agar multi-line selection hai) ya poori file ki lines ko
     * alphabetically sort karta hai (case-insensitive). Ek hi edit = ek Undo.
     */
    fun sortLines(ascending: Boolean) {
        val ed = text ?: return
        if (ed.isEmpty()) return
        val hasMultiLineSel = selectionStart != selectionEnd
        val rangeStart: Int
        val rangeEnd: Int
        if (hasMultiLineSel) {
            val r = selectedLineRange(ed)
            rangeStart = r.first
            rangeEnd = r.second
        } else {
            rangeStart = 0
            rangeEnd = ed.length
        }
        val original = ed.substring(rangeStart, rangeEnd)
        val lines = original.split('\n')
        if (lines.size < 2) return
        val sorted = if (ascending) lines.sortedWith(String.CASE_INSENSITIVE_ORDER)
        else lines.sortedWith(String.CASE_INSENSITIVE_ORDER.reversed())
        val newText = sorted.joinToString("\n")
        if (newText == original) return
        ed.replace(rangeStart, rangeEnd, newText)
        setSelection(rangeStart, (rangeStart + newText.length).coerceAtMost(ed.length))
    }

    // ===== v6 code suggestions (19) =====
    // Current word (caret ke peeche, 2+ chars) ke liye language keywords +
    // types + isi file ke identifiers se popup. Tap = word replace.

    private fun isSuggestionWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$'

    /** Caret ke peeche wala word, ya null (selection / chhota word). */
    private fun currentWordRange(): Pair<Int, Int>? {
        val ed = text ?: return null
        val caret = selectionStart
        if (caret != selectionEnd || caret == 0) return null
        var start = caret
        while (start > 0 && isSuggestionWordChar(ed[start - 1])) start--
        if (caret - start < 2) return null
        return start to caret
    }

    // ---- v7 (35): vim motions + edits (layout-independent, text-based) ----
    private fun setVimInsert(insert: Boolean) {
        vimInsert = insert
        try { onVimModeChanged?.invoke(insert) } catch (e: Exception) { }
    }

    private fun vimSetCaret(offset: Int) {
        setSelection(offset.coerceIn(0, length()))
    }

    private fun vimMoveLeft() { vimSetCaret(selectionStart - 1) }
    private fun vimMoveRight() { vimSetCaret(selectionStart + 1) }

    private fun vimLineBounds(): Pair<Int, Int> {
        val ed = text ?: return 0 to 0
        val caret = selectionStart.coerceIn(0, ed.length)
        val start = ed.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = ed.indexOf('\n', caret).let { if (it < 0) ed.length else it }
        return start to end
    }

    private fun vimLineStart() { vimSetCaret(vimLineBounds().first) }
    private fun vimLineEnd() { vimSetCaret(vimLineBounds().second) }

    private fun vimMoveVertical(delta: Int) {
        val ed = text ?: return
        val caret = selectionStart.coerceIn(0, ed.length)
        val (start, _) = vimLineBounds()
        val col = caret - start
        var lineIndex = 0
        var idx = -1
        while (true) {
            idx = ed.indexOf('\n', idx + 1)
            if (idx < 0 || idx >= caret) break
            lineIndex++
        }
        val target = lineIndex + delta
        if (target < 0) return
        var pos = 0
        var li = 0
        while (li < target) {
            val n = ed.indexOf('\n', pos)
            if (n < 0) return
            pos = n + 1
            li++
        }
        val targetEnd = ed.indexOf('\n', pos).let { if (it < 0) ed.length else it }
        vimSetCaret((pos + col).coerceAtMost(targetEnd))
    }

    private fun vimWordForward() {
        val ed = text ?: return
        val caret = selectionStart
        for (m in Regex("[A-Za-z0-9_]+").findAll(ed)) {
            if (m.range.first > caret) { vimSetCaret(m.range.first); return }
        }
    }

    private fun vimWordBack() {
        val ed = text ?: return
        val caret = selectionStart
        var last: Int? = null
        for (m in Regex("[A-Za-z0-9_]+").findAll(ed)) {
            if (m.range.first >= caret) break
            last = m.range.first
        }
        if (last != null) vimSetCaret(last)
    }

    private fun vimDeleteChar() {
        val ed = text ?: return
        val caret = selectionStart
        if (caret < ed.length) ed.delete(caret, caret + 1)
    }

    private fun vimDeleteLine() {
        val ed = text ?: return
        val (start, end) = vimLineBounds()
        val delEnd = if (end < ed.length) end + 1 else end
        if (delEnd > start) ed.delete(start, delEnd)
        vimSetCaret(start.coerceAtMost(ed.length))
    }

    private fun vimOpenBelow() {
        val ed = text ?: return
        val (_, end) = vimLineBounds()
        ed.insert(end, "\n")
        vimSetCaret(end + 1)
        setVimInsert(true)
    }

    private fun vimHandleChar(c: Char) {
        when (c) {
            'h' -> vimMoveLeft()
            'j' -> vimMoveVertical(1)
            'k' -> vimMoveVertical(-1)
            'l' -> vimMoveRight()
            'x' -> vimDeleteChar()
            'd' -> if (vimPendingD) { vimDeleteLine(); vimPendingD = false } else vimPendingD = true
            'g' -> if (vimPendingG) { vimSetCaret(0); vimPendingG = false } else vimPendingG = true
            'G' -> vimSetCaret(length())
            'i' -> setVimInsert(true)
            'a' -> { vimMoveRight(); setVimInsert(true) }
            'I' -> { vimLineStart(); setVimInsert(true) }
            'A' -> { vimLineEnd(); setVimInsert(true) }
            'o' -> vimOpenBelow()
            'u' -> undo()
            'w' -> vimWordForward()
            'b' -> vimWordBack()
            '0' -> vimLineStart()
            '$' -> vimLineEnd()
            ':' -> { try { onVimCommandPalette?.invoke() } catch (e: Exception) { } }
            else -> { }
        }
        if (c != 'd') vimPendingD = false
        if (c != 'g') vimPendingG = false
    }

    private fun vimHandleKeyCode(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_DPAD_LEFT -> vimMoveLeft()
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_DPAD_DOWN -> vimMoveVertical(1)
            KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_DPAD_UP -> vimMoveVertical(-1)
            KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_DPAD_RIGHT -> vimMoveRight()
            KeyEvent.KEYCODE_X -> vimDeleteChar()
            KeyEvent.KEYCODE_D -> vimHandleChar('d')
            KeyEvent.KEYCODE_I -> setVimInsert(true)
            KeyEvent.KEYCODE_A -> { vimMoveRight(); setVimInsert(true) }
            KeyEvent.KEYCODE_O -> vimOpenBelow()
            KeyEvent.KEYCODE_U -> undo()
            KeyEvent.KEYCODE_W -> vimWordForward()
            KeyEvent.KEYCODE_B -> vimWordBack()
            KeyEvent.KEYCODE_G -> if (event?.isShiftPressed == true) vimSetCaret(length()) else vimHandleChar('g')
            KeyEvent.KEYCODE_0 -> vimLineStart()
            KeyEvent.KEYCODE_4 -> if (event?.isShiftPressed == true) vimLineEnd() else return false
            KeyEvent.KEYCODE_DEL -> vimMoveLeft()
            KeyEvent.KEYCODE_FORWARD_DEL -> vimDeleteChar()
            KeyEvent.KEYCODE_ENTER -> vimMoveVertical(1)
            KeyEvent.KEYCODE_ESCAPE -> { }
            else -> return false
        }
        return true
    }

    /** v7 (43): feed the LSP client the current document state + word prefix. */
    private fun noteLspChange() {
        try {
            val ed = text ?: return
            val range = currentWordRange()
            val prefix = if (range != null) ed.substring(range.first, range.second) else ""
            val caret = selectionStart.coerceIn(0, ed.length)
            val lay = layout
            val line = lay?.getLineForOffset(caret) ?: 0
            val lineStart = lay?.getLineStart(line) ?: 0
            com.nobidev.nobicode.lsp.LspManager.noteDocumentChanged(
                currentFileExtension, currentFileUri, ed.toString(),
                line, caret - lineStart, prefix)
        } catch (e: Exception) { }
    }

    private fun updateSuggestions() {
        // Typing ke baad aane wale selection change se popup na mare.
        suggestionDismissGuard = true
        val range = currentWordRange()
        if (range == null) {
            dismissSuggestions()
            return
        }
        val ed = text ?: return
        val prefix = ed.substring(range.first, range.second)
        val lang = Languages.byExt(currentFileExtension)
        val seen = LinkedHashSet<String>()
        val candidates = ArrayList<String>()
        fun offer(word: String) {
            if (candidates.size >= 12) return
            if (word.length < prefix.length) return
            if (!word.startsWith(prefix, ignoreCase = true)) return
            if (word.equals(prefix, ignoreCase = true)) return
            if (seen.add(word)) candidates.add(word)
        }
        for (kw in lang.keywords) offer(kw)
        for (t in lang.types) offer(t)
        // Document identifiers (badi file me scan cap: sirf keywords).
        if (ed.length <= 200_000) {
            var scanned = 0
            val idRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
            for (m in idRegex.findAll(ed)) {
                if (scanned++ > 2000 || candidates.size >= 12) break
                offer(m.value)
            }
        }
        // v7 (43): LSP completions from the background cache (never blocks UI).
        try {
            if (com.nobidev.nobicode.lsp.LspManager.cachedExt == currentFileExtension) {
                for (c in com.nobidev.nobicode.lsp.LspManager.cachedCompletions) offer(c)
            }
        } catch (e: Exception) { }
        // v7 (46): plugin snippet prefixes.
        try {
            for (p in com.nobidev.nobicode.plugins.PluginManager.snippetsFor(context, currentFileExtension).keys) offer(p)
        } catch (e: Exception) { }
        if (candidates.isEmpty()) {
            dismissSuggestions()
            return
        }
        showSuggestionPopup(candidates)
    }

    private fun showSuggestionPopup(candidates: List<String>) {
        val lay = layout ?: return
        val caret = selectionStart
        if (caret > length()) return
        val density = resources.displayMetrics.density
        val popupWidth = (280 * density).toInt()
        val rowH = (48 * density).toInt()
        val popupHeight = (candidates.size * rowH).coerceAtMost((240 * density).toInt())
        val listView = ListView(context)
        listView.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, candidates)
        listView.setOnItemClickListener { _, _, pos, _ ->
            acceptSuggestion(candidates[pos])
        }
        val popup = PopupWindow(listView, popupWidth, popupHeight, false)
        popup.setBackgroundDrawable(ColorDrawable(Color.parseColor("#23233A")))
        popup.elevation = 8f
        suggestionPopup?.dismiss()
        suggestionPopup = popup
        val line = lay.getLineForOffset(caret.coerceIn(0, length()))
        val caretX = (lay.getPrimaryHorizontal(caret) + paddingLeft - scrollX).toInt()
        val caretY = lay.getLineBottom(line) - scrollY + paddingTop
        val x = caretX.coerceIn(0, (width - popupWidth).coerceAtLeast(0))
        popup.showAsDropDown(this, x, caretY - height)
    }

    private fun acceptSuggestion(candidate: String) {
        val range = currentWordRange() ?: run {
            dismissSuggestions()
            return
        }
        val ed = text ?: return
        // v7 (46): snippet prefixes expand to their body ($0 = final caret).
        val snippetBody = try {
            com.nobidev.nobicode.plugins.PluginManager.snippetsFor(context, currentFileExtension)[candidate]
        } catch (e: Exception) { null }
        if (snippetBody != null) {
            isSuggestionInsert = true
            try {
                val caretPos = snippetBody.indexOf("\$0")
                val body = snippetBody.replace("\$0", "")
                ed.replace(range.first, range.second, body)
                val targetCaret = if (caretPos >= 0) (range.first + caretPos).coerceAtMost(ed.length)
                    else (range.first + body.length).coerceAtMost(ed.length)
                setSelection(targetCaret)
            } finally {
                isSuggestionInsert = false
            }
            dismissSuggestions()
            return
        }
        isSuggestionInsert = true
        try {
            ed.replace(range.first, range.second, candidate)
            setSelection((range.first + candidate.length).coerceAtMost(ed.length))
        } finally {
            isSuggestionInsert = false
        }
        dismissSuggestions()
    }

    private fun dismissSuggestions() {
        suggestionPopup?.dismiss()
        suggestionPopup = null
    }

    // ===== v6 error underline (20): bracket scanner =====
    // Conservative scanner: strings, char literals, line comments aur
    // C-style block comments ke andar wale brackets ignore hote hain.
    // Kotlin raw strings (""") handle nahi hote — unke andar ka bracket
    // galat flag ho sakta hai (known limitation, STATUS me darj).

    private fun scheduleBracketScan() {
        bracketScanRunnable?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable {
            if (isHighlighting) {
                // Highlight pass khatam hone ke baad dobara try karo.
                uiHandler.postDelayed(bracketScanRunnable!!, 300)
                return@Runnable
            }
            applyBracketErrors()
        }
        bracketScanRunnable = r
        uiHandler.postDelayed(r, 500)
    }

    private fun applyBracketErrors() {
        val ed = text ?: return
        for (span in ed.getSpans(0, ed.length, BracketErrorSpan::class.java)) {
            ed.removeSpan(span)
        }
        val lang = Languages.byExt(currentFileExtension)
        for (err in scanBracketErrors(ed.toString(), lang)) {
            if (err.offset in 0 until ed.length) {
                ed.setSpan(BracketErrorSpan(err.reason), err.offset, err.offset + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun scanBracketErrors(src: String, lang: Language): List<BracketError> {
        val errors = ArrayList<BracketError>()
        val stack = ArrayDeque<Pair<Char, Int>>()
        val lineComment = lang.lineComment
        val cLikeBlock = lineComment == "//"
        var i = 0
        var state = 0 // 0 normal, 1 line comment, 2 block comment, 3 string, 4 char
        var stringDelim = '"'
        while (i < src.length) {
            val c = src[i]
            when (state) {
                1 -> {
                    if (c == '\n') state = 0
                    i++
                    continue
                }
                2 -> {
                    if (c == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        state = 0
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                3 -> {
                    if (c == '\\' && i + 1 < src.length) {
                        i += 2
                        continue
                    }
                    if (c == stringDelim) state = 0
                    i++
                    continue
                }
                4 -> {
                    if (c == '\\' && i + 1 < src.length) {
                        i += 2
                        continue
                    }
                    if (c == '\'') state = 0
                    i++
                    continue
                }
            }
            if (lineComment != null && src.startsWith(lineComment, i)) {
                state = 1
                i += lineComment.length
                continue
            }
            if (cLikeBlock && c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                state = 2
                i += 2
                continue
            }
            if (c == '"' && '"' in lang.stringDelims) {
                state = 3
                stringDelim = '"'
                i++
                continue
            }
            if (c == '\'' && '\'' in lang.stringDelims) {
                state = 4
                i++
                continue
            }
            when (c) {
                '(', '[', '{' -> stack.addLast(c to i)
                ')', ']', '}' -> {
                    val open = when (c) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (stack.isEmpty()) {
                        errors.add(BracketError(i, "Unmatched '$c' — no opening bracket"))
                    } else {
                        val (top, _) = stack.last()
                        if (top == open) {
                            stack.removeLast()
                        } else {
                            errors.add(BracketError(i, "Mismatched '$c' — the open '$top' needs a different closer"))
                            stack.removeLast()
                        }
                    }
                }
            }
            i++
        }
        for ((open, off) in stack) {
            errors.add(BracketError(off, "Unclosed '$open' — missing its closing bracket"))
        }
        return errors
    }
}
