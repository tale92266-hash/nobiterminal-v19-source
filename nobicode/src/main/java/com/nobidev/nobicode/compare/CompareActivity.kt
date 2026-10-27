package com.nobidev.nobicode.compare

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nobidev.nobicode.R

/**
 * v7 (36): side-by-side diff of two files. Lines are compared with a
 * classic LCS table; files over 1000 lines are shown side-by-side
 * without highlights (the table would be too big). Scrolling one pane
 * scrolls the other. Files are read through the content resolver, so
 * SAF documents work the same as plain files.
 */
class CompareActivity : AppCompatActivity() {

    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare)

        val uriA = intent.getStringExtra("compare_a_uri")?.let { Uri.parse(it) }
        val uriB = intent.getStringExtra("compare_b_uri")?.let { Uri.parse(it) }
        val nameA = intent.getStringExtra("compare_a_name") ?: "File A"
        val nameB = intent.getStringExtra("compare_b_name") ?: "File B"
        if (uriA == null || uriB == null) {
            Toast.makeText(this, "Compare needs two saved files", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.compare_title_a).text = nameA
        findViewById<TextView>(R.id.compare_title_b).text = nameB

        val linesA = readLines(uriA)
        val linesB = readLines(uriB)
        if (linesA == null || linesB == null) {
            Toast.makeText(this, "Could not read one of the files", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val note = findViewById<TextView>(R.id.compare_note)
        val diffA: Set<Int>?
        val diffB: Set<Int>?
        if (linesA.size > 1000 || linesB.size > 1000) {
            note.text = "Files too large for line highlights — plain side-by-side view"
            note.visibility = View.VISIBLE
            diffA = null
            diffB = null
        } else {
            val (da, db) = lcsDiff(linesA, linesB)
            diffA = da
            diffB = db
            val count = da.size.coerceAtLeast(db.size)
            note.text = if (count == 0) "Files are identical" else "$count differing line(s)"
            note.visibility = View.VISIBLE
        }

        findViewById<TextView>(R.id.compare_text_a).text =
            buildSpanned(linesA, diffA, Color.parseColor("#44FF5252"))
        findViewById<TextView>(R.id.compare_text_b).text =
            buildSpanned(linesB, diffB, Color.parseColor("#444CAF50"))

        val scrollA = findViewById<ScrollView>(R.id.compare_scroll_a)
        val scrollB = findViewById<ScrollView>(R.id.compare_scroll_b)
        scrollA.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!syncing) {
                syncing = true
                scrollB.scrollTo(0, scrollY)
                syncing = false
            }
        }
        scrollB.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!syncing) {
                syncing = true
                scrollA.scrollTo(0, scrollY)
                syncing = false
            }
        }
    }

    private fun readLines(uri: Uri): List<String>? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.readLines()
        } catch (e: Exception) {
            null
        }
    }

    private fun buildSpanned(lines: List<String>, diff: Set<Int>?, color: Int): CharSequence {
        val sb = SpannableStringBuilder()
        for ((i, line) in lines.withIndex()) {
            val start = sb.length
            sb.append(line).append("\n")
            if (diff != null && i in diff) {
                sb.setSpan(BackgroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** Returns (differing line indices in A, differing line indices in B). */
    private fun lcsDiff(a: List<String>, b: List<String>): Pair<Set<Int>, Set<Int>> {
        val n = a.size
        val m = b.size
        val w = m + 1
        val table = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i * w + j] = if (a[i] == b[j]) table[(i + 1) * w + j + 1] + 1
                    else maxOf(table[(i + 1) * w + j], table[i * w + j + 1])
            }
        }
        val diffA = HashSet<Int>()
        val diffB = HashSet<Int>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                i++
                j++
            } else if (table[(i + 1) * w + j] >= table[i * w + j + 1]) {
                diffA.add(i)
                i++
            } else {
                diffB.add(j)
                j++
            }
        }
        while (i < n) diffA.add(i++)
        while (j < m) diffB.add(j++)
        return diffA to diffB
    }
}
