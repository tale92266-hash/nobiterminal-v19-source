package com.nobidev.nobicode.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.EditText

class LineNumberGutter @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bgColor: Int = Color.TRANSPARENT
        set(v) { field = v; invalidate() }

    var textColor: Int = Color.GRAY
        set(v) { field = v; invalidate() }

    var activeTextColor: Int = Color.WHITE
        set(v) { field = v; invalidate() }

    var textSizePx: Float = 12f * resources.displayMetrics.scaledDensity
        set(v) {
            field = v
            paint.textSize = v
            invalidate()
        }

    private var editor: EditText? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.MONOSPACE
        textSize = textSizePx
    }

    fun attachTo(ed: EditText) {
        editor = ed
        ed.setOnScrollChangeListener { _, _, _, _, _ -> invalidate() }
        invalidate()
    }

    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (bgColor != Color.TRANSPARENT) canvas.drawColor(bgColor)

        val ed = editor ?: return
        val layout = ed.layout ?: return

        val scrollY = ed.scrollY
        val topPad = ed.compoundPaddingTop
        val rightPad = dp(8)
        val active = layout.getLineForOffset(ed.selectionStart.coerceAtLeast(0))
        val ts = paint.textSize

        for (i in 0 until layout.lineCount) {
            val baseline = layout.getLineBaseline(i) + topPad - scrollY
            if (baseline < -ts || baseline > height + ts) continue
            paint.color = if (i == active) activeTextColor else textColor
            canvas.drawText((i + 1).toString(), width - rightPad, baseline.toFloat(), paint)
        }
    }

    private fun dp(v: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    )
}
