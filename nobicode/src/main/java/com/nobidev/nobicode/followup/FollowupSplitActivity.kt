package com.nobidev.nobicode.followup

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nobidev.nobicode.R
import com.nobidev.nobicode.editor.CodeEditorView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v11 follow-up (15): split editor panes. Two saved files open side by
 * side in landscape (stacked in portrait, still independently editable),
 * each in its own CodeEditorView. Save writes both files back through
 * the content resolver with their stored encoding and line ending.
 */
class FollowupSplitActivity : AppCompatActivity() {

    private lateinit var editorA: CodeEditorView
    private lateinit var editorB: CodeEditorView
    private var uriA: String? = null
    private var uriB: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_followup_split)

        uriA = intent.getStringExtra("split_a_uri")
        uriB = intent.getStringExtra("split_b_uri")
        val nameA = intent.getStringExtra("split_a_name") ?: "File A"
        val nameB = intent.getStringExtra("split_b_name") ?: "File B"
        if (uriA.isNullOrBlank() || uriB.isNullOrBlank()) {
            Toast.makeText(this, "Split editor needs two saved files", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.splitTitleA).text = nameA
        findViewById<TextView>(R.id.splitTitleB).text = nameB
        editorA = findViewById(R.id.splitEditorA)
        editorB = findViewById(R.id.splitEditorB)

        val panes = findViewById<LinearLayout>(R.id.splitPanes)
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        panes.orientation =
            if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // Weight works on the pane axis only: give each editor a 0dp size
        // on that axis and full size on the other, per orientation.
        for (ed in listOf(editorA, editorB)) {
            ed.layoutParams = LinearLayout.LayoutParams(
                if (landscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                if (landscape) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                1f
            )
        }
        if (!landscape) {
            Toast.makeText(
                this, "Rotate to landscape for side-by-side panes", Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.splitSaveBtn).setOnClickListener { saveBoth() }
        findViewById<Button>(R.id.splitCloseBtn).setOnClickListener { finish() }

        lifecycleScope.launch {
            val textA = withContext(Dispatchers.IO) { readUri(uriA) }
            val textB = withContext(Dispatchers.IO) { readUri(uriB) }
            if (textA == null || textB == null) {
                Toast.makeText(this@FollowupSplitActivity, "Could not read one of the files", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            editorA.setTextCleanly(textA)
            editorB.setTextCleanly(textB)
        }
    }

    private fun readUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return try {
            val uri = android.net.Uri.parse(uriString)
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val enc = FollowupStore.encodingFor(this, uriString)
            String(bytes, FollowupEncoding.charsetFor(enc))
        } catch (e: Exception) {
            null
        }
    }

    private fun saveBoth() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                writeUri(uriA, editorA.text?.toString() ?: "") &&
                    writeUri(uriB, editorB.text?.toString() ?: "")
            }
            Toast.makeText(
                this@FollowupSplitActivity,
                if (ok) "Both files saved" else "Save failed for one of the files",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun writeUri(uriString: String?, text: String): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = android.net.Uri.parse(uriString)
            val ending = FollowupStore.lineEndingFor(this, uriString)
            val converted = FollowupEncoding.convertLineEndings(text, ending == "CRLF")
            val enc = FollowupStore.encodingFor(this, uriString)
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(converted.toByteArray(FollowupEncoding.charsetFor(enc)))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
