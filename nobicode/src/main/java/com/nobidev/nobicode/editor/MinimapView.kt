package com.nobidev.nobicode.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil

/**
 * Code ka zoomed-out overview (VS Code jaisa minimap).
 * Har line ek patli bar hai; viewport violet rectangle; touch/drag se scroll.
 * Performance: content bitmap me cache hota hai, sirf text change par re-render.
 */
class MinimapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var editor: CodeEditorView? = null
        set(value) {
            field = value
            value?.let { ed ->
                // Existing listeners ko overwrite mat karo — chain karo.
                val prevScroll = ed.onEditorScrollChanged
                ed.onEditorScrollChanged = {
                    prevScroll?.invoke()
                    updateViewport()
                }
                val prevMod = ed.onModifiedListener
                ed.onModifiedListener = {
                    prevMod?.invoke()
                    scheduleRerender()
                }
            }
            requestRerender()
        }

    var barColor: Int = 0xFF8B8B9E.toInt()
    var viewportColor: Int = 0x4D8B7CF6.toInt()
    // @JvmField: a generated setBackgroundColor(I)V would clash with
    // View.setBackgroundColor(int) (accidental override, Kotlin 2.x error).
    @JvmField
    var backgroundColor: Int = 0xFF14151D.toInt()

    private val bgPaint = Paint()
    private val barPaint = Paint().apply { isAntiAlias = false }
    private val viewportPaint = Paint().apply { isAntiAlias = true }

    private var contentBitmap: Bitmap? = null
    private var lastTextHash: Int = 0
    private var rerenderPending = false

    // Viewport (minimap coordinates me).
    private var vpTop: Float = 0f
    private var vpBottom: Float = 0f

    private val tmpRect = RectF()
    private var dragging = false

    private fun scheduleRerender() {
        if (rerenderPending) return
        rerenderPending = true
        post {
            rerenderPending = false
            requestRerender()
        }
    }

    fun requestRerender() {
        lastTextHash = 0 // force re-render
        invalidate()
    }

    private fun updateViewport() {
        computeViewport()
        invalidate()
    }

    private fun computeViewport() {
        val ed = editor ?: return
        val h = height.toFloat()
        if (h <= 0f) return
        val totalLines = ed.lineCount.coerceAtLeast(1)
        val pxPerLine = h / totalLines
        val layout = ed.layout
        if (layout != null) {
            val firstLine = layout.getLineForVertical(ed.scrollY)
            val lastLine = layout.getLineForVertical(ed.scrollY + ed.height)
            vpTop = (firstLine * pxPerLine).coerceIn(0f, h)
            vpBottom = ((lastLine + 1) * pxPerLine).coerceIn(0f, h)
        } else {
            // Fallback: scroll fraction se.
            val maxScroll = (ed.lineCount * 20 - ed.height).coerceAtLeast(1)
            val frac = ed.scrollY.toFloat() / maxScroll
            val vpH = h * ed.height.toFloat() / (ed.lineCount * 20).coerceAtLeast(1)
            vpTop = (frac * (h - vpH)).coerceIn(0f, h)
            vpBottom = (vpTop + vpH).coerceIn(0f, h)
        }
    }

    private fun renderContent(w: Int, h: Int) {
        val ed = editor
        if (ed == null || w <= 0 || h <= 0) {
            contentBitmap = null
            return
        }
        val text = ed.text?.toString() ?: ""
        val hash = text.hashCode()
        if (hash == lastTextHash && contentBitmap != null) return
        lastTextHash = hash

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(backgroundColor)

        val lines = text.split('\n')
        val totalLines = lines.size.coerceAtLeast(1)
        val pxPerLine = h.toFloat() / totalLines
        val barH = pxPerLine.coerceAtLeast(1f).coerceAtMost(3f)

        barPaint.color = barColor
        // Har line ki chaudai uski length ke hisaab se (scaled).
        var maxLen = 1
        for (ln in lines) if (ln.length > maxLen) maxLen = ln.length

        val barMaxW = w.toFloat() * 0.92f
        val barLeft = w.toFloat() * 0.04f
        for ((idx, ln) in lines.withIndex()) {
            if (ln.isBlank()) continue
            val y = idx * pxPerLine
            val barW = (ln.trim().length.toFloat() / maxLen * barMaxW)
                .coerceAtLeast(2f)
            tmpRect.set(barLeft, y, barLeft + barW, y + barH)
            // Halki alpha variation taaki texture dikhe.
            barPaint.alpha = 90 + (ln.length % 60)
            c.drawRect(tmpRect, barPaint)
        }
        contentBitmap?.recycle()
        contentBitmap = bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        renderContent(w, h)
        contentBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            ?: run {
                bgPaint.color = backgroundColor
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
            }

        // Viewport rectangle.
        computeViewport()
        viewportPaint.color = viewportColor
        tmpRect.set(0f, vpTop, w.toFloat(), vpBottom)
        canvas.drawRoundRect(tmpRect, 4f, 4f, viewportPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val ed = editor ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                scrollToY(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) scrollToY(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scrollToY(touchY: Float) {
        val ed = editor ?: return
        val h = height.toFloat()
        if (h <= 0f) return
        val frac = (touchY / h).coerceIn(0f, 1f)
        val layout = ed.layout
        val maxScroll = if (layout != null) {
            (layout.height - ed.height).coerceAtLeast(0)
        } else {
            (ed.lineCount * 20 - ed.height).coerceAtLeast(0)
        }
        // Touch point ko viewport ke center me lao.
        val target = (frac * maxScroll - ed.height / 2).coerceIn(0f, maxScroll.toFloat())
        ed.scrollTo(ed.scrollX, target.toInt())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentBitmap?.recycle()
        contentBitmap = null
    }
}
