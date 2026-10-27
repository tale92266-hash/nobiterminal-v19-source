package com.nobidev.nobicode

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.util.LruCache
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doOnTextChanged
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nobidev.nobicode.databinding.ActivityMainBinding
import com.nobidev.nobicode.databinding.DialogFindReplaceBinding
import com.nobidev.nobicode.databinding.DialogColorPickerBinding
import com.nobidev.nobicode.databinding.DialogGotoBinding
import com.nobidev.nobicode.icons.FileIconProvider
import com.nobidev.nobicode.model.EditorTab
import com.nobidev.nobicode.model.FileItem
import com.nobidev.nobicode.model.MenuActionAdapter
import com.nobidev.nobicode.model.MenuItemData
import com.nobidev.nobicode.preferences.EditorPreferences
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.nobidev.nobicode.followup.FollowupCheatSheets
import com.nobidev.nobicode.followup.FollowupDiff
import com.nobidev.nobicode.followup.FollowupEncoding
import com.nobidev.nobicode.followup.FollowupFuzzy
import com.nobidev.nobicode.followup.FollowupHex
import com.nobidev.nobicode.followup.FollowupLint
import com.nobidev.nobicode.followup.FollowupSplitActivity
import com.nobidev.nobicode.followup.FollowupStore
import com.nobidev.nobicode.followup.FollowupSymbols
import com.nobidev.nobicode.followup.FollowupTemplates
import com.nobidev.nobicode.followup.FollowupTour
import com.nobidev.nobicode.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Stack
import kotlin.math.abs
import android.view.Gravity
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.OnBackPressedCallback
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nobidev.nobicode.git.GitManager
import com.nobidev.nobicode.git.GitSheet
import com.nobidev.nobicode.history.LocalHistory
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.nobidev.nobicode.remote.RemoteSheet

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: EditorPreferences

    private val openTabs = mutableListOf<EditorTab>()
    // Quick Switcher: MRU list of recently accessed files (max 20)
    private val recentFiles = ArrayList<QuickSwitchItem>()
    private var activeTabIndex = -1
    // Current tab ka file Uri (derived — openTabs/activeTabIndex se, kabhi out-of-sync nahi hota)
    private val currentFileUri: android.net.Uri?
        get() = openTabs.getOrNull(activeTabIndex)?.uri
    // Tab reorder: long-press se drag mode (tabDragView != null = dragging)
    private var tabDragView: View? = null
    private val closedTabs = ArrayDeque<EditorTab>()
    private val recentFileUris = ArrayDeque<android.net.Uri>()
    // Custom draggable scrollbar state
    private lateinit var editorScrollThumb: View
    private val scrollThumbHandler = Handler(Looper.getMainLooper())
    private var scrollThumbExpandRunnable: Runnable? = null
    private var isThumbDragging = false

    private var rootDocumentFile: DocumentFile? = null
    private var currentFolder: DocumentFile? = null
    private val folderBackStack = Stack<DocumentFile>()
    private val folderCache = LruCache<String, List<FileItem>>(60)
    private lateinit var fileAdapter: FileListAdapter
    // v6 tree sidebar: folders expanded inline in the drawer (uri strings),
    // the current folder's flat base list, and in-flight expansion fetches.
    private val expandedFolderUris = mutableSetOf<String>()
    private var lastDrawerBase: List<FileItem> = emptyList()
    private val fetchingExpansion = mutableSetOf<String>()

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.rootFolderUri = uri.toString()
            initFileManager(uri)
            saveSessionState()
        }
    }

    private val openSingleFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val doc = DocumentFile.fromSingleUri(this, uri)
            if (doc != null) {
                openFileInTab(doc)
            }
        }
    }

    /** Settings JSON import ke liye file picker. */
    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) doImportSettings(uri)
    }

    /** v7 (86): import a .ttf font for the editor (copied into app storage). */
    private val importFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val dir = java.io.File(filesDir, "fonts").also { it.mkdirs() }
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
            } ?: "imported.ttf"
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = java.io.File(dir, safe)
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            prefs.fontPath = out.absolutePath
            applyPreferences()
            Toast.makeText(this, "Font installed: $safe", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Font import failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NobiCrashLogger.install(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupQuickSwitcherSwipe()

        prefs = EditorPreferences(this)

        setupFullScreenAndInsets()
        setupToolbar()
        setupV7ToolbarGestures()
        setupV7OutputStrip()
        setupV7EditorGestures()
        setupDrawer()
        setupFooterToolbar()
        setupEditor()
        setupZenMode()
        setupFollowup()

        restoreSessionState()

        // v6 (15) + v7 (49/56/58/61/62/64): files/commands arriving from
        // another app, a share, or the `nobi` terminal script.
        handleNobiOpenIntent(intent)

        binding.toolbar.post {
            updateRunButtonVisibility()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // v7: singleTask — a running editor receives new open requests here
        // instead of a second instance (nobi edit / diff / find / mark).
        setIntent(intent)
        handleNobiOpenIntent(intent)
    }

    /**
     * v7: single entry point for every editor-open intent extra.
     * - nobi_open_file_path (+ nobi_open_line): open a plain file, jump to line.
     * - nobi_source_session: terminal session this editor was opened from (55).
     * - nobi_bookmark_path: add a file bookmark (64).
     * - nobi_git_diff_file: open the file and show its git diff (61).
     * - nobi_search_text: open project-wide search with this query (62).
     * - nobi_show_output: show the last-run output strip (63).
     */
    private fun handleNobiOpenIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra("nobi_source_session")?.let { sourceSessionName = it }
        intent.getStringExtra("nobi_open_file_path")?.let { sharedPath ->
            val line = intent.getIntExtra("nobi_open_line", -1)
            openSharedFileInTab(sharedPath, line)
        }
        intent.getStringExtra("nobi_bookmark_path")?.let { path ->
            addFileBookmark(path)
            Toast.makeText(this, "Bookmarked: " + path.substringAfterLast('/'), Toast.LENGTH_SHORT).show()
        }
        intent.getStringExtra("nobi_git_diff_file")?.let { path ->
            openFileAndShowDiff(path)
        }
        intent.getStringExtra("nobi_search_text")?.let { query ->
            binding.root.postDelayed({ showSearchInFilesDialog(query) }, 600)
        }
        if (intent.getBooleanExtra("nobi_show_output", false)) {
            binding.root.postDelayed({ refreshOutputStrip() }, 600)
        }
    }

    /** v7 (55): terminal session this editor was opened from, if any. */
    private var sourceSessionName: String? = null

    /** v7 (63): mtime of the last output file already shown in the strip. */
    private var lastShownOutputMtime: Long = 0L

    // v6 (15): open a plain filesystem file (shared from another app) in a
    // tab. Files outside the SAF project tree open via DocumentFile.fromFile;
    // save goes through ContentResolver, which handles file:// URIs.
    // v7: optional gotoLine for `nobi edit file:line` (56).
    private fun openSharedFileInTab(sharedPath: String, gotoLine: Int = -1) {
        try {
            val file = java.io.File(sharedPath)
            if (file.isDirectory) {
                Toast.makeText(this, "That is a folder, not a file", Toast.LENGTH_SHORT).show()
                return
            }
            // v7 (49): `nobi edit newfile.py` creates the file on first open.
            if (!file.exists()) {
                val parent = file.parentFile
                if (parent == null || !parent.isDirectory) {
                    Toast.makeText(this, "Shared file not found", Toast.LENGTH_SHORT).show()
                    return
                }
                file.createNewFile()
            }
            if (!file.isFile) {
                Toast.makeText(this, "Shared file not found", Toast.LENGTH_SHORT).show()
                return
            }
            openFileInTab(DocumentFile.fromFile(file), gotoLine)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open shared file", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= v7: terminal<->editor switching + new features =====

    /** v7 (41): open a remote (FTP/SFTP) file for editing. Downloads to a
     * cache file, opens it, and tags the tab so save uploads it back. */
    private fun openRemoteFile(remotePath: String) {
        val conn = remoteConn ?: return
        lifecycleScope.launch {
            try {
                val safe = remotePath.trimStart('/').replace("/", "_").ifBlank { "remote" }
                val cache = java.io.File(cacheDir, "nobi_remote/" + safe)
                val result = com.nobidev.nobicode.remote.RemoteManager.download(remotePath, cache)
                result.onSuccess {
                    openFileInTab(
                        DocumentFile.fromFile(cache),
                        remotePath = remotePath,
                        remoteConnId = conn.id
                    )
                }.onFailure { e ->
                    Toast.makeText(this@MainActivity, "Remote open failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Remote open failed: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** v7 (45): open a freshly cloned GitHub folder as the editor project. */
    fun openGithubFolder(doc: DocumentFile) {
        try {
            currentFolder = doc
            rootDocumentFile = doc
            folderBackStack.clear()
            loadCurrentFolderContentsFast()
            prefs.rootFolderUri = doc.uri.toString()
            binding.drawer.closeDrawer(GravityCompat.START)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open folder", Toast.LENGTH_SHORT).show()
        }
    }

    /** v7 (45): the current folder as a java.io.File, if it is a git repo dir. */
    fun currentGithubDir(): java.io.File? {
        val folder = currentFolder ?: return null
        return try {
            val file = com.nobidev.nobicode.git.GitManager.resolveToFile(folder.uri) ?: return null
            if (com.nobidev.nobicode.git.GitManager.isRepo(file)) file else null
        } catch (e: Exception) {
            null
        }
    }

    fun refreshCurrentFolder() {
        currentFolder?.uri?.let { folderCache.remove(it.toString()) }
        loadCurrentFolderContentsFast()
    }

    /** v7 (61): open a file and show its git diff vs HEAD in a dialog. */
    private fun openFileAndShowDiff(path: String) {
        try {
            val file = java.io.File(path)
            if (!file.isFile) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            openFileInTab(DocumentFile.fromFile(file))
            lifecycleScope.launch {
                val repoDir = findGitRoot(file.parentFile)
                if (repoDir == null) {
                    Toast.makeText(this@MainActivity, "Not in a git repo", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val rel = repoDir.toURI().relativize(file.toURI()).path
                val text = try {
                    file.readText()
                } catch (e: Exception) {
                    ""
                }
                val diff = com.nobidev.nobicode.git.GitManager.unifiedDiff(repoDir, rel, text)
                diff.onSuccess { d ->
                    val scroll = android.widget.ScrollView(this@MainActivity)
                    val tv = android.widget.TextView(this@MainActivity)
                    tv.text = if (d.isBlank()) "No changes vs HEAD" else d
                    tv.setTextIsSelectable(true)
                    tv.typeface = android.graphics.Typeface.MONOSPACE
                    tv.setPadding(24, 24, 24, 24)
                    scroll.addView(tv)
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Diff: " + file.name)
                        .setView(scroll)
                        .setPositiveButton("Close", null)
                        .show()
                }.onFailure { e ->
                    Toast.makeText(this@MainActivity, "Diff failed: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open diff", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findGitRoot(dir: java.io.File?): java.io.File? {
        var d = dir
        while (d != null) {
            if (java.io.File(d, ".git").exists()) return d
            d = d.parentFile
        }
        return null
    }

    // ---- v7 (64): file bookmarks (distinct from line bookmarks) ----

    private fun fileBookmarks(): MutableList<String> {
        return prefs.fileBookmarks.toMutableList()
    }

    private fun addFileBookmark(path: String) {
        val list = fileBookmarks()
        list.remove(path)
        list.add(0, path)
        while (list.size > 50) list.removeAt(list.size - 1)
        prefs.fileBookmarks = list
    }

    private fun showFileBookmarksDialog() {
        val items = fileBookmarks()
        if (items.isEmpty()) {
            Toast.makeText(this, "No file bookmarks yet (use: nobi mark <file>)", Toast.LENGTH_SHORT).show()
            return
        }
        val names = items.map { it.substringAfterLast('/') + " — " + it }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("File Bookmarks (${items.size})")
            .setItems(names) { _, which ->
                val file = java.io.File(items[which])
                if (file.isFile) {
                    openFileInTab(DocumentFile.fromFile(file))
                } else {
                    Toast.makeText(this, "File gone: " + items[which], Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Clear all") { _, _ ->
                prefs.fileBookmarks = emptyList()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    // ---- v7 (57): per-file run configs ----

    /** Run command for a tab: saved config wins, else a detected default. */
    private fun buildRunCommand(tab: EditorTab): String? {
        val path = tab.path ?: tab.name
        val configs = prefs.runConfigs
        val saved = configs[path] ?: configs[tab.name]
        if (saved != null) return saved
        val ext = tab.name.substringAfterLast('.', "").lowercase()
        val quoted = "'" + path + "'"
        return when (ext) {
            "py" -> "python3 $quoted"
            "js" -> "node $quoted"
            "sh" -> "bash $quoted"
            "rb" -> "ruby $quoted"
            "pl" -> "perl $quoted"
            else -> null
        }
    }

    private fun showRunConfigDialog() {
        if (activeTabIndex !in openTabs.indices) return
        val tab = openTabs[activeTabIndex]
        val key = tab.path ?: tab.name
        val existing = prefs.runConfigs[key] ?: prefs.runConfigs[tab.name] ?: buildRunCommand(tab) ?: ""
        val input = android.widget.EditText(this)
        input.setText(existing)
        input.hint = "Command to run this file (e.g. python3 file.py --flag)"
        MaterialAlertDialogBuilder(this)
            .setTitle("Run Configuration: " + tab.name)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val cmd = input.text.toString().trim()
                val map = prefs.runConfigs.toMutableMap()
                if (cmd.isEmpty()) {
                    map.remove(key)
                    map.remove(tab.name)
                } else {
                    map[key] = cmd
                }
                prefs.runConfigs = map
                Toast.makeText(this, "Run configuration saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Delete") { _, _ ->
                val map = prefs.runConfigs.toMutableMap()
                map.remove(key)
                map.remove(tab.name)
                prefs.runConfigs = map
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** v7 (52/57): run the current file in the terminal and return when done. */
    private fun runInTerminalAndReturn() {
        if (activeTabIndex !in openTabs.indices) return
        val tab = openTabs[activeTabIndex]
        if (tab.isModified) saveActiveTabContent(isManualSave = false, showToast = false)
        val cmd = buildRunCommand(tab)
        if (cmd.isNullOrBlank()) {
            Toast.makeText(this, "No run command for this file — set one in Run Configuration", Toast.LENGTH_LONG).show()
            showRunConfigDialog()
            return
        }
        val path = tab.path ?: tab.name
        val dir = try {
            java.io.File(path).parent ?: ""
        } catch (e: Exception) {
            ""
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, "Terminal not available", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        intent.putExtra("nobi_run_and_return", cmd)
        intent.putExtra("nobi_run_cwd", dir)
        intent.putExtra("nobi_return_file", path)
        intent.putExtra("nobi_return_output", true)
        startActivity(intent)
    }

    /** v7 (59): send the selection (or current line) to the terminal. */
    private fun sendSelectionToTerminal() {
        val ed = binding.editor.text ?: return
        val s0 = minOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, ed.length)
        val e0 = maxOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, ed.length)
        val text = if (e0 > s0) {
            ed.substring(s0, e0)
        } else {
            val line = binding.editor.text.toString().split("\n").getOrNull(currentEditorLine()) ?: ""
            line
        }
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) return
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        intent.putExtra("nobi_share_path", text)
        startActivity(intent)
    }

    private fun currentEditorLine(): Int {
        return try {
            val lay = binding.editor.layout ?: return 0
            lay.getLineForOffset(binding.editor.selectionStart.coerceIn(0, binding.editor.length()))
        } catch (e: Exception) {
            0
        }
    }

    // ---- v7 (63): last-run output strip ----

    private fun lastOutputFile(): java.io.File =
        java.io.File(filesDir, "nobi_last_output.txt")

    private fun refreshOutputStrip() {
        val strip = binding.root.findViewById<View>(R.id.nobi_output_strip) ?: return
        val textView = binding.root.findViewById<android.widget.TextView>(R.id.nobi_output_text) ?: return
        val file = lastOutputFile()
        if (!file.isFile) {
            strip.visibility = View.GONE
            return
        }
        if (file.lastModified() <= lastShownOutputMtime && strip.visibility == View.VISIBLE) return
        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            emptyList()
        }
        if (lines.isEmpty()) {
            strip.visibility = View.GONE
            return
        }
        lastShownOutputMtime = file.lastModified()
        // w23: error lines get the existing error token (accent_dot) plus a short
        // recovery hint; detection is a plain-substring heuristic, not a parser.
        val shown = lines.takeLast(40)
        val errorColor = android.graphics.Color.parseColor("#FF6B8A")
        val hintColor = android.graphics.Color.parseColor("#22D3EE")
        val sb = SpannableStringBuilder()
        var hasError = false
        for ((idx, ln) in shown.withIndex()) {
            if (idx > 0) sb.append("\n")
            val start = sb.length
            sb.append(ln)
            val lower = ln.lowercase()
            if (lower.contains("traceback") || lower.contains("error") || lower.contains("exception") || lower.contains("failed") || lower.contains("fatal")) {
                hasError = true
                sb.setSpan(ForegroundColorSpan(errorColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (hasError) {
            val hintStart = sb.length
            sb.append("\nErrors detected — open Terminal for full output")
            sb.setSpan(ForegroundColorSpan(hintColor), hintStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = sb
        // v7 (85): glass look — translucent rounded strip instead of flat fill.
        if (prefs.glassLook) {
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = 16f * resources.displayMetrics.density
            bg.setColor(android.graphics.Color.parseColor("#991A1A2E"))
            strip.background = bg
        }
        strip.visibility = View.VISIBLE
    }

    private fun hideOutputStrip() {
        binding.root.findViewById<View>(R.id.nobi_output_strip)?.visibility = View.GONE
    }

    // ---- v7 (60): folder sync with the terminal ----

    /** Write the editor's current filesystem folder for `nobi sync`. */
    private fun writeEditorFolderFile() {
        try {
            val folder = currentFolder ?: return
            val file = com.nobidev.nobicode.git.GitManager.resolveToFile(folder.uri) ?: return
            if (!file.isDirectory) return
            val prefix = filesDir.parentFile?.parentFile?.absolutePath ?: return
            // The terminal PREFIX lives under the same app files dir.
            val usr = java.io.File(filesDir, "usr")
            val out = java.io.File(usr, "tmp/nobi-editor-folder")
            out.parentFile?.mkdirs()
            out.writeText(file.absolutePath)
        } catch (e: Exception) {
        }
    }

    /** v7 (60): on resume, follow the terminal's cwd (PROMPT_COMMAND hook). */
    private fun syncFolderFromTerminal() {
        if (!prefs.followTerminalFolder) return
        try {
            val usr = java.io.File(filesDir, "usr")
            val cwdFile = java.io.File(usr, "tmp/nobi-cwd")
            if (!cwdFile.isFile) return
            val path = cwdFile.readText().trim()
            if (path.isBlank()) return
            val dir = java.io.File(path)
            if (!dir.isDirectory) return
            val current = currentFolder?.let {
                com.nobidev.nobicode.git.GitManager.resolveToFile(it.uri)
            }
            if (current != null && current.absolutePath == dir.absolutePath) return
            currentFolder = DocumentFile.fromFile(dir)
            rootDocumentFile = currentFolder
            folderBackStack.clear()
            loadCurrentFolderContentsFast()
        } catch (e: Exception) {
        }
    }

    /** v7 (36): pick the second file for side-by-side compare. */
    private fun showComparePicker() {
        val current = if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex] else null
        val currentUri = current?.uri?.toString()
        if (currentUri == null) {
            Toast.makeText(this, "Save the current file first", Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = LinkedHashMap<String, String>()
        for ((i, tab) in openTabs.withIndex()) {
            if (i == activeTabIndex) continue
            val u = tab.uri?.toString() ?: continue
            candidates[u] = tab.name
        }
        try {
            for (item in lastDrawerBase) {
                if (item.isDirectory || item.isParent) continue
                val u = item.uri.toString()
                if (u != currentUri) candidates.putIfAbsent(u, item.name)
            }
        } catch (e: Exception) { }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Open a second file first", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = candidates.keys.toList()
        val names = candidates.values.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Compare with")
            .setItems(names) { _, which ->
                val intent = Intent(this, com.nobidev.nobicode.compare.CompareActivity::class.java)
                intent.putExtra("compare_a_uri", currentUri)
                intent.putExtra("compare_a_name", current?.name ?: "Current file")
                intent.putExtra("compare_b_uri", uris[which])
                intent.putExtra("compare_b_name", names[which])
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** v7 (86): editor font picker — default, imported .ttf files, or import. */
    private fun showV7FontPicker() {
        val dir = java.io.File(filesDir, "fonts")
        val imported = if (dir.isDirectory) dir.listFiles()?.filter { it.extension.equals("ttf", true) }?.sortedBy { it.name } ?: emptyList() else emptyList()
        val labels = mutableListOf("Default")
        val paths = mutableListOf<String?>(null)
        for (f in imported) {
            labels.add(f.name)
            paths.add(f.absolutePath)
        }
        labels.add("Import .ttf…")
        AlertDialog.Builder(this)
            .setTitle("Editor Font")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.size - 1) {
                    importFontLauncher.launch(arrayOf("font/ttf", "application/octet-stream", "*/*"))
                } else {
                    prefs.fontPath = paths[which]
                    if (paths[which] == null) {
                        binding.editor.typeface = android.graphics.Typeface.MONOSPACE
                    }
                    applyPreferences()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** v7 (28): paste an older clipboard item — shared with the terminal's history. */
    private fun showV7ClipboardHistory() {
        val raw = getSharedPreferences("nobi_clip_history", MODE_PRIVATE)
            .getString("items", "") ?: ""
        val items = raw.split("\u0001").filter { it.isNotEmpty() }
        if (items.isEmpty()) {
            Toast.makeText(this, "Clipboard history is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { it.replace("\n", " ").take(60) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Clipboard History")
            .setItems(labels) { _, which ->
                val ed = binding.editor.text ?: return@setItems
                val caret = binding.editor.selectionStart.coerceIn(0, ed.length)
                ed.insert(caret, items[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** v7 (47): open the AI chat panel with the current file as context. */
    private fun openAiChat() {
        val tab = if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex] else null
        val intent = Intent(this, com.nobidev.nobicode.ai.AiChatActivity::class.java)
        intent.putExtra("ai_file_name", tab?.name ?: "")
        val sel = try {
            val ed = binding.editor.text
            if (ed == null) null
            else {
                val s0 = minOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, ed.length)
                val e0 = maxOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, ed.length)
                if (e0 > s0) ed.substring(s0, e0) else null
            }
        } catch (e: Exception) { null }
        val ctx = sel?.takeIf { it.isNotBlank() } ?: binding.editor.text.toString().split("\n").getOrNull(currentEditorLine()) ?: ""
        intent.putExtra("ai_context", ctx.take(4000))
        startActivity(intent)
    }

    /** v7 (54): double-tap the editor toolbar to jump back to the terminal. */
    private fun setupV7ToolbarGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                backToTerminal()
                return true
            }
        })
        binding.toolbar.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            false
        }
    }

    /** v7 (86/87): editor font from an imported .ttf + density as a scale
     * factor on top of the user's own line-spacing setting (never clobbers it). */
    private fun applyV7EditorFontAndDensity() {
        try {
            val fp = prefs.fontPath
            if (!fp.isNullOrBlank()) {
                val f = java.io.File(fp)
                if (f.exists()) binding.editor.typeface = android.graphics.Typeface.createFromFile(f)
            }
        } catch (e: Exception) { }
        val factor = if (prefs.density == "compact") 0.85f else 1.0f
        binding.editor.setLineSpacing(0f, prefs.lineSpacing * factor)
    }

    /** v7 (82): fling the editor tab bar to switch tabs / open the drawer. */
    private fun setupV7EditorGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(velocityX) > 1000) {
                    if (dx < 0) switchTab(activeTabIndex + 1) else switchTab(activeTabIndex - 1)
                    return true
                }
                if (dy > 100 && kotlin.math.abs(velocityY) > 1000 && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    binding.drawer.openDrawer(GravityCompat.START)
                    return true
                }
                return false
            }
        })
        binding.tabScroll.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            false
        }
    }

    /** v7 (63): wire the output strip buttons (close + jump to terminal). */
    private fun setupV7OutputStrip() {
        try {
            binding.root.findViewById<View>(R.id.nobi_output_close)?.setOnClickListener { hideOutputStrip() }
            binding.root.findViewById<View>(R.id.nobi_output_open_terminal)?.setOnClickListener { backToTerminal() }
        } catch (e: Exception) { }
    }

    // ---- v7 (50/55): terminal switching ----

    /** v7 (50): open a terminal in the folder of the current file. */
    /** v7 (50): filesystem folder of the current file (or folder), if any. */
    private fun currentFileDir(): String? {
        val tab = if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex] else null
        return try {
            tab?.path?.let { java.io.File(it).parent }
                ?: tab?.uri?.let { com.nobidev.nobicode.git.GitManager.resolveToFile(it)?.parent }
                ?: currentFolder?.let { com.nobidev.nobicode.git.GitManager.resolveToFile(it.uri)?.absolutePath }
        } catch (e: Exception) {
            null
        }
    }

    private fun openTerminalHere() {
        val tab = if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex] else null
        val dir = try {
            tab?.path?.let { java.io.File(it).parent }
                ?: currentFolder?.let { com.nobidev.nobicode.git.GitManager.resolveToFile(it.uri)?.absolutePath }
                ?: ""
        } catch (e: Exception) {
            ""
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (dir.isNotBlank()) intent.putExtra("nobi_terminal_cwd", dir)
        startActivity(intent)
    }

    /** v7 (55): back to the terminal session this editor was opened from. */
    private fun backToTerminal() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        sourceSessionName?.let { intent.putExtra("nobi_focus_session", it) }
        startActivity(intent)
    }

    /** v7 (51): split screen — editor + terminal side by side (platform). */
    private fun openSplitScreen() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        intent.putExtra("nobi_split_screen", true)
        startActivity(intent)
        Toast.makeText(this, "Split screen: drag the divider to resize", Toast.LENGTH_SHORT).show()
    }

    // ---- v7 (43): LSP dialogs ----

    private fun showLspDialog() {
        val lines = com.nobidev.nobicode.lsp.LspManager.statusLines()
        val items = lines.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("LSP Servers — " + com.nobidev.nobicode.lsp.LspManager.lastStatus)
            .setItems(items) { _, which ->
                val ext = lines[which].substringAfter(".").substringBefore(" —")
                showLspServerEditDialog(ext)
            }
            .setNeutralButton("Problems") { _, _ -> showProblemsDialog() }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showLspServerEditDialog(ext: String) {
        val current = com.nobidev.nobicode.lsp.LspManager.serverCommand(ext)
        val input = android.widget.EditText(this)
        input.setText(current)
        input.hint = "Server command (empty = off, 'builtin' = built-in Python server)"
        MaterialAlertDialogBuilder(this)
            .setTitle("LSP server for .$ext")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                com.nobidev.nobicode.lsp.LspManager.setServerCommand(ext, input.text.toString().trim())
                Toast.makeText(this, "LSP server saved for .$ext", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProblemsDialog() {
        val tab = if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex] else null
        val uri = tab?.uri?.toString() ?: ""
        val diags = com.nobidev.nobicode.lsp.LspManager.diagnosticsFor(uri)
        if (diags.isEmpty()) {
            Toast.makeText(this, "No problems reported for this file", Toast.LENGTH_SHORT).show()
            return
        }
        val names = diags.map { "Line " + (it.line + 1) + ": " + it.message }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Problems (${diags.size})")
            .setItems(names) { _, which ->
                binding.editor.gotoLine(diags[which].line + 1)
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun setupFullScreenAndInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerContent) { view, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topPad = maxOf(statusBarInset.top, displayCutout.top)
            view.setPadding(0, topPad, 0, 0)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val isKeyboardOpen = imeInsets.bottom > 0
            val bottomInset = if (isKeyboardOpen) imeInsets.bottom else systemBars.bottom

            binding.statusBar.visibility = if (isKeyboardOpen) View.GONE else View.VISIBLE

            binding.mainRoot.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                bottomInset
            )
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        recordClipboard()
        // v7 (31): time-aware theme wins before preferences are applied.
        if (prefs.timeAwareTheme) {
            try {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val targetId = if (hour in 7 until 19) "solarized_light" else "tokyo_night"
                val tm = ThemeManager(this)
                if (tm.currentThemeId != targetId) {
                    tm.current = com.nobidev.nobicode.theme.Themes.byId(targetId)
                    prefs.themeName = tm.current.displayName
                }
            } catch (e: Exception) { }
        }
        applyPreferences()
        updateFooterButtonsState()
        updateRunButtonVisibility()
        // v7 (60): follow the terminal's cwd when the hook is installed.
        syncFolderFromTerminal()
        // Folder reflects real filesystem state (invalidate cache + reload)
        currentFolder?.uri?.let { folderCache.remove(it.toString()) }
        if (isRemoteMode) loadRemoteFiles() else loadLocalFiles()
        // v7 (63): show the last-run output strip when new output arrived.
        refreshOutputStrip()
        // Open tabs sync with disk: external changes + deletions
        syncOpenTabsWithDisk()
    }

    /**
     * Makes open tabs reflect reality: detects files changed or deleted
     * outside NobiIDE and updates dirty state / shows indicators.
     */
    private fun syncOpenTabsWithDisk() {
        if (openTabs.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            data class SyncInfo(
                val tab: EditorTab,
                val deleted: Boolean,
                val diskText: String?
            )
            val infos = openTabs.map { tab ->
                val uri = tab.uri
                if (uri == null) {
                    SyncInfo(tab, false, null)
                } else {
                    val doc = try { DocumentFile.fromSingleUri(this@MainActivity, uri) } catch (e: Exception) { null }
                    if (doc == null || !doc.exists()) {
                        SyncInfo(tab, true, null)
                    } else {
                        val diskText = try {
                            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        } catch (e: Exception) { null }
                        SyncInfo(tab, false, diskText)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                var uiChanged = false
                for (info in infos) {
                    val tab = info.tab
                    if (info.deleted) {
                        if (!tab.isDeleted) {
                            tab.isDeleted = true
                            uiChanged = true
                        }
                        continue
                    }
                    if (tab.isDeleted) {
                        tab.isDeleted = false
                        uiChanged = true
                    }
                    val diskText = info.diskText
                    if (diskText != null && diskText != tab.diskContent) {
                        if (!tab.isModified) {
                            // No unsaved edits: reflect reality
                            tab.diskContent = diskText
                            tab.content = diskText
                            if (openTabs.indexOf(tab) == activeTabIndex) {
                                binding.editor.setTextCleanly(diskText)
                            }
                            uiChanged = true
                        } else {
                            showExternalChangeConflictDialog(tab, diskText)
                        }
                    }
                }
                // Recompute dirty for active tab against real disk state
                if (activeTabIndex in openTabs.indices) {
                    val tab = openTabs[activeTabIndex]
                    val dirty = binding.editor.isModifiedComparedTo(tab.diskContent)
                    if (tab.isModified != dirty) {
                        tab.isModified = dirty
                        uiChanged = true
                    }
                    binding.statusModifiedDot.visibility = if (tab.isModified) View.VISIBLE else View.GONE
                }
                updateToolbarForTab()
                if (uiChanged) {
                    renderTabs()
                    updateFooterButtonsState()
                }
            }
        }
    }

    private fun showExternalChangeConflictDialog(tab: EditorTab, diskText: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("File changed outside")
            .setMessage("${tab.name} was modified outside NobiIDE. Reload it or keep your unsaved changes?")
            .setPositiveButton("Reload") { _, _ ->
                tab.diskContent = diskText
                tab.content = diskText
                tab.isModified = false
                if (openTabs.indexOf(tab) == activeTabIndex) {
                    binding.editor.setTextCleanly(diskText)
                    binding.statusModifiedDot.visibility = View.GONE
                }
                renderTabs()
                updateToolbarForTab()
                updateFooterButtonsState()
            }
            .setNegativeButton("Keep mine") { _, _ ->
                // Remember this disk state so we don't ask again
                tab.diskContent = diskText
                renderTabs()
                updateFooterButtonsState()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (prefs.autoSave) {
            saveAllTabs(quiet = true)
        }
        saveSessionState()
    }

    override fun onStop() {
        super.onStop()
        saveSessionState()
    }

    private fun hideSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val focusView = currentFocus ?: binding.root
        imm?.hideSoftInputFromWindow(focusView.windowToken, 0)
        binding.editor.clearFocus()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            hideSoftKeyboard()
            binding.drawer.openDrawer(GravityCompat.START)
        }

        if (binding.toolbar.menu.size() == 0) {
            binding.toolbar.inflateMenu(R.menu.main_menu)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_run -> {
                    triggerRunPreview()
                    true
                }
                R.id.action_open_file -> {
                    startActivity(Intent(this, ProjectManagerActivity::class.java))
                    true
                }
                R.id.action_save -> {
                    saveActiveTabContent(isManualSave = true, showToast = true)
                    true
                }
                R.id.action_save_as -> {
                    showSaveAsDialog()
                    true
                }
                R.id.action_new -> {
                    openNewUntitledTab()
                    true
                }
                R.id.action_goto -> {
                    showGotoLineDialog()
                    true
                }
                R.id.action_outline -> {
                    showCodeOutline()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_command_palette -> {
                    showCommandPalette()
                    true
                }
                                R.id.action_followup -> {
                    showFollowupMenu()
                    true
                }
                R.id.action_quick_switch -> {
                    showQuickSwitcher()
                    true
                }
R.id.action_markdown_preview -> {
                    toggleMarkdownPreview()
                    true
                }
                R.id.action_terminal -> {
                    // v7 (50/55): pass the file's folder + session link instead of a bare launch.
                    val launchIntent = Intent()
                    launchIntent.setClassName(this, "com.nobiterminal.app.TermuxActivity")
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    val cwd = currentFileDir()
                    if (cwd != null) launchIntent.putExtra("nobi_terminal_cwd", cwd)
                    if (sourceSessionName != null) launchIntent.putExtra("nobi_focus_session", sourceSessionName)
                    startActivity(launchIntent)
                    true
                }
                R.id.action_git -> {
                    GitSheet(this).show()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDrawer() {
        binding.remoteBtn.setOnClickListener { RemoteSheet(this).show() }
        binding.quickOpenBtn.setOnClickListener { showQuickOpenDialog() }
        binding.fileRecyclerView.layoutManager = LinearLayoutManager(this)
        fileAdapter = FileListAdapter(
            onArrowClick = { item -> toggleFolderExpansion(item) },
            onItemClick = { item ->
                // v7 (41): remote mode has its own navigation (no SAF tree).
                if (isRemoteMode) {
                    if (item.isParent) {
                        if (remotePathStack.isNotEmpty()) {
                            remotePath = remotePathStack.removeLast()
                            loadRemoteFiles()
                        } else {
                            exitRemoteMode()
                        }
                    } else if (item.isDirectory) {
                        remotePathStack.addLast(remotePath)
                        remotePath = item.uri.toString().removePrefix("nobiremote://")
                        loadRemoteFiles()
                    } else {
                        openRemoteFile(item.uri.toString().removePrefix("nobiremote://"))
                    }
                } else if (item.isParent) {
                    if (folderBackStack.isNotEmpty()) {
                        currentFolder = folderBackStack.pop()
                        loadCurrentFolderContentsFast()
                        saveSessionState()
                    }
                } else if (item.isDirectory) {
                    folderBackStack.push(currentFolder)
                    val nextDoc = DocumentFile.fromTreeUri(this, item.uri)
                    currentFolder = nextDoc
                    loadCurrentFolderContentsFast()
                    saveSessionState()
                } else {
                    val doc = DocumentFile.fromSingleUri(this, item.uri)
                    if (doc != null) {
                        openFileInTab(doc)
                    }
                }
            },
            onItemLongClick = { item ->
                val doc = if (item.isDirectory) {
                    DocumentFile.fromTreeUri(this, item.uri)
                } else {
                    DocumentFile.fromSingleUri(this, item.uri)
                }
                if (doc != null) {
                    if (item.isDirectory) showFolderContextMenu(doc) else showFileContextMenu(doc)
                }
            }
        )
        binding.fileRecyclerView.adapter = fileAdapter

        binding.drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (slideOffset > 0.02f) {
                    hideSoftKeyboard()
                }
            }
            override fun onDrawerOpened(drawerView: View) {
                hideSoftKeyboard()
            }
        })

        binding.pickFolderBtn.setOnClickListener {
            openFolderLauncher.launch(null)
        }

        binding.refreshBtn.setOnClickListener {
            if (isRemoteMode) {
                loadRemoteFiles()
            } else {
                currentFolder?.uri?.let { folderCache.remove(it.toString()) }
                loadCurrentFolderContentsFast()
            }
        }

        binding.newEntryBtn.setOnClickListener {
            val target = currentFolder ?: rootDocumentFile
            if (target != null) {
                showCreateChoiceDialog(target)
            } else {
                Toast.makeText(this, "Please choose a folder first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreateChoiceDialog(parent: DocumentFile) {
        val items = listOf(
            MenuItemData("New File", R.drawable.ic_file),
            MenuItemData("New Folder", R.drawable.ic_new_folder)
        )
        val adapter = MenuActionAdapter(this, items)

        MaterialAlertDialogBuilder(this)
            .setTitle("Create in ${parent.name ?: "Folder"}")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> showCreateNewFileDialog(parent)
                    1 -> showCreateNewFolderDialog(parent)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupFooterToolbar() {
        binding.btnUndo.setOnClickListener {
            binding.editor.undo()
            updateFooterButtonsState()
        }

        // Undo long-press: saare unsaved changes wapas (revert to saved).
        binding.btnUndo.setOnLongClickListener {
            val tab = openTabs.getOrNull(activeTabIndex)
            if (tab != null && tab.isModified) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Revert to saved?")
                    .setMessage("Discard all unsaved changes in \"${tab.name}\"?")
                    .setPositiveButton("Revert") { _, _ ->
                        binding.editor.setTextCleanly(tab.diskContent)
                        tab.content = tab.diskContent
                        tab.isModified = false
                        binding.statusModifiedDot.visibility = View.GONE
                        renderTabs()
                        updateFooterButtonsState()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "No unsaved changes", Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.btnRedo.setOnClickListener {
            binding.editor.redo()
            updateFooterButtonsState()
        }

        binding.btnSave.setOnClickListener {
            saveActiveTabContent(isManualSave = true, showToast = true)
        }

        // Save button long-press: saare tabs save.
        binding.btnSave.setOnLongClickListener {
            saveAllTabs()
            true
        }

        binding.btnFind.setOnClickListener {
            showFindReplaceDialog()
        }

        // Find button long-press: poore folder me search.
        binding.btnFind.setOnLongClickListener {
            showSearchInFilesDialog()
            true
        }

        // Status position tap: go to line dialog.
        binding.statusPos.setOnClickListener {
            showGotoLineDialog()
        }

        // Status bar long-press: tab size badlo.
        binding.statusEncoding.setOnLongClickListener {
            val sizes = arrayOf("2 spaces", "4 spaces", "8 spaces")
            val checked = when (prefs.tabSize) { 2 -> 0; 8 -> 2; else -> 1 }
            MaterialAlertDialogBuilder(this)
                .setTitle("Tab size")
                .setSingleChoiceItems(sizes, checked) { d, which ->
                    prefs.tabSize = arrayOf(2, 4, 8)[which]
                    applyPreferences()
                    updateEncodingStatus()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        // Toolbar long-press: current file ka full path copy.
        binding.toolbar.setOnLongClickListener {
            val tab = openTabs.getOrNull(activeTabIndex)
            val fullPath = if (tab != null) fullPathForTab(tab) else ""
            if (fullPath.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("file_path", fullPath))
                Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.btnSelectAll.setOnClickListener {
            binding.editor.selectAll()
            updateFooterButtonsState()
        }

        // Select All long-press: current line (ya selected lines) select.
        binding.btnSelectAll.setOnLongClickListener {
            val ed = binding.editor.text
            if (ed != null) {
                val (s, e) = binding.editor.selectedLineRange(ed)
                binding.editor.setSelection(s, e)
            }
            updateFooterButtonsState()
            true
        }

        binding.btnPaste.setOnClickListener {
            pasteFromClipboard()
        }
        // Paste long-press: clipboard history (pichhle 15 clips).
        binding.btnPaste.setOnLongClickListener {
            recordClipboard()
            showClipboardHistory()
            true
        }

        binding.btnClearAll.setOnClickListener {
            clearEditorWithConfirm()
        }

        // Clear All long-press: saara text clipboard me cut (undo se wapas).
        binding.btnClearAll.setOnLongClickListener {
            val text = binding.editor.getUnfoldedText()
            if (text.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cut_all", text))
                binding.editor.setText("")
                updateFooterButtonsState()
                Toast.makeText(this, "Cut all to clipboard", Toast.LENGTH_SHORT).show()
            }
            true
        }

        updateFooterButtonsState()
    }

    /**
     * Pasted text ka indent caret wali line se match karta hai: pehli line as-is,
     * baaki lines ka relative indent rakhthe hue target indent par shift.
     */
    private fun reindentPastedText(pasteText: String, caretOffset: Int): String {
        val ed = binding.editor.text ?: return pasteText
        if (!pasteText.contains('\n')) return pasteText
        val caret = caretOffset.coerceIn(0, ed.length)
        var ls = caret
        while (ls > 0 && ed[ls - 1] != '\n') ls--
        val targetIndent = ed.substring(ls, caret).takeWhile { it == ' ' || it == '\t' }.toString()
        val lines = pasteText.split('\n')
        if (lines.size < 2) return pasteText
        val base = lines.drop(1).filter { it.isNotBlank() }
            .minOfOrNull { it.takeWhile { c -> c == ' ' || c == '\t' }.length } ?: 0
        val out = ArrayList<String>(lines.size)
        out.add(lines[0])
        for (i in 1 until lines.size) {
            val ln = lines[i]
            if (ln.isBlank()) {
                out.add(ln)
            } else {
                out.add(targetIndent + ln.drop(base.coerceAtMost(ln.length)))
            }
        }
        return out.joinToString("\n")
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val pasteText = if ((clip?.itemCount ?: 0) > 0) clip?.getItemAt(0)?.text?.toString() else null
        if (!pasteText.isNullOrEmpty()) {
            recordClipboard()
            insertPasteText(pasteText)
        }
    }

    /** Paste text insert karo - multi-cursor me saare carets par. */
    private fun insertPasteText(pasteText: String) {
        val ed = binding.editor
        if (ed.hasMultiCursors()) {
            ed.insertAtAllCarets(pasteText)
        } else {
            val start = ed.selectionStart.coerceAtLeast(0)
            val end = ed.selectionEnd.coerceAtLeast(0)
            val finalText = if (prefs.autoIndentOnPaste) reindentPastedText(pasteText, start) else pasteText
            ed.text?.replace(start, end, finalText)
        }
        updateFooterButtonsState()
    }

    /** System clipboard ka naya text history me record karo (max 15, session-only). */
    private fun recordClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val t = if ((clip?.itemCount ?: 0) > 0) clip?.getItemAt(0)?.text?.toString()?.take(50000) else null
        if (t.isNullOrEmpty() || t == lastSeenClip) return
        lastSeenClip = t
        clipboardHistory.remove(t)
        clipboardHistory.addFirst(t)
        while (clipboardHistory.size > 15) clipboardHistory.removeLast()
    }

    /** Clipboard history bottom sheet - tap karne par paste. */
    private fun showClipboardHistory() {
        if (clipboardHistory.isEmpty()) {
            Toast.makeText(this, "Clipboard history empty", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_clipboard, null)
        dialog.setContentView(view)
        val list: RecyclerView = view.findViewById(R.id.clipboardList)
        val emptyText: TextView = view.findViewById(R.id.clipboardEmpty)
        val clearBtn: TextView = view.findViewById(R.id.clipboardClear)
        list.layoutManager = LinearLayoutManager(this)
        val items = clipboardHistory.toList()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_quick_open, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val clipText = items[position]
                holder.itemView.findViewById<TextView>(R.id.qoName).text = clipText.replace("\n", " / ").take(120)
                holder.itemView.findViewById<TextView>(R.id.qoPath).text = "${clipText.length} chars"
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    insertPasteText(clipText)
                }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        emptyText.visibility = View.GONE
        clearBtn.setOnClickListener {
            clipboardHistory.clear()
            lastSeenClip = null
            dialog.dismiss()
            Toast.makeText(this, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private fun clearEditorWithConfirm() {
        val currentText = binding.editor.getUnfoldedText()
        if (currentText.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Clear All")
            .setMessage("Are you sure you want to clear the entire editor?")
            .setPositiveButton("Clear") { _, _ ->
                binding.editor.setText("")
                updateFooterButtonsState()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFooterButtonsState() {
        val canUndo = binding.editor.canUndo()
        binding.btnUndo.isEnabled = canUndo
        binding.btnUndo.alpha = if (canUndo) 1.0f else 0.35f

        val canRedo = binding.editor.canRedo()
        binding.btnRedo.isEnabled = canRedo
        binding.btnRedo.alpha = if (canRedo) 1.0f else 0.35f

        val isModified = if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex].isModified else false
        binding.btnSave.isEnabled = isModified
        binding.btnSave.alpha = if (isModified) 1.0f else 0.35f
    }

    private fun setupEditor() {
        binding.editor.onColorSwatchTap = { range ->
            binding.editor.requestFocus()
            binding.editor.setSelection(range.first.coerceIn(0, binding.editor.length()))
            showColorPickerDialog()
        }
        setupSymbolsBar()
        binding.editor.onCursorPositionChanged = { line, col ->
            lastCursorLine = line
            binding.statusPos.text = "Ln $line/${totalLines()}, Col $col" + vimStatusSuffix()
            if (line != lastBreadcrumbLine || activeTabIndex != lastBreadcrumbTab) {
                lastBreadcrumbLine = line
                lastBreadcrumbTab = activeTabIndex
                refreshBreadcrumbs()
            }
        }
        binding.editor.onSelectionStatsChanged = { line, col, selLen ->
            binding.statusPos.text =
                if (selLen > 0) "Ln $line/${totalLines()}, Col $col • $selLen sel" + vimStatusSuffix()
                else "Ln $line/${totalLines()}, Col $col" + vimStatusSuffix()
        }
        // v7 (35): vim mode indicator in the status line.
        binding.editor.onVimModeChanged = { insert ->
            vimIndicator = if (insert) " · INSERT" else " · NORMAL"
            binding.statusPos.text = "Ln $lastCursorLine/${totalLines()}, Col 1" + vimStatusSuffix()
        }
        binding.editor.onVimCommandPalette = { showCommandPalette() }
        binding.editor.onFontSizeChanged = { newSp ->
            prefs.fontSize = newSp
        }

        binding.editor.onModifiedListener = {
            breadcrumbSymbolsDirty = true
            scheduleGitDiff()
            if (activeTabIndex in openTabs.indices) {
                val tab = openTabs[activeTabIndex]
                // Real dirty tracking: dot/save only when text actually differs from disk
                val dirty = binding.editor.isModifiedComparedTo(tab.diskContent)
                updateEncodingStatus()
                if (tab.isModified != dirty) {
                    tab.isModified = dirty
                    binding.statusModifiedDot.visibility = if (dirty) View.VISIBLE else View.GONE
                    renderTabs()
                    updateFooterButtonsState()
                }
            }
        }

        binding.editor.onUndoRedoStateChanged = {
            updateFooterButtonsState()
        }

        binding.welcomeOpenFolder.setOnClickListener {
            openFolderLauncher.launch(null)
        }
        binding.welcomeOpenFile.setOnClickListener {
            openSingleFileLauncher.launch(arrayOf("*/*"))
        }
        setupEditorScrollbar()
    }

    // ---- Custom draggable editor scrollbar ----
    private fun setupEditorScrollbar() {
        editorScrollThumb = binding.editorScrollThumb
        val editor = binding.editor

        editor.onEditorScrollChanged = {
            if (!isThumbDragging) {
                expandScrollThumb()
                updateScrollThumb()
            }
        }

        editorScrollThumb.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isThumbDragging = true
                    editorScrollThumb.parent?.requestDisallowInterceptTouchEvent(true)
                    expandScrollThumb()
                    dragThumbTo(event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    dragThumbTo(event.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isThumbDragging = false
                    scheduleThumbShrink()
                    true
                }
                else -> false
            }
        }

        // Minimap ko editor se jodo (listeners chain honge, overwrite nahi).
        binding.minimap.editor = binding.editor
    }

    private fun expandScrollThumb() {
        val density = resources.displayMetrics.density
        val lp = editorScrollThumb.layoutParams
        val targetW = (20 * density).toInt()
        if (lp.width != targetW) {
            lp.width = targetW
            editorScrollThumb.layoutParams = lp
        }
        editorScrollThumb.alpha = 1f
        scrollThumbExpandRunnable?.let { scrollThumbHandler.removeCallbacks(it) }
        scrollThumbExpandRunnable = null
    }

    private fun scheduleThumbShrink() {
        scrollThumbExpandRunnable?.let { scrollThumbHandler.removeCallbacks(it) }
        scrollThumbExpandRunnable = Runnable {
            val density = resources.displayMetrics.density
            val lp = editorScrollThumb.layoutParams
            lp.width = (8 * density).toInt()
            editorScrollThumb.layoutParams = lp
            editorScrollThumb.alpha = 0.55f
        }
        scrollThumbHandler.postDelayed(scrollThumbExpandRunnable!!, 1200)
    }

    private fun updateScrollThumb() {
        val editor = binding.editor
        val range = editor.getScrollRange()
        val extent = editor.getScrollExtent()
        if (range <= extent || extent <= 0) {
            editorScrollThumb.visibility = View.GONE
            return
        }
        if (editorScrollThumb.visibility != View.VISIBLE) {
            editorScrollThumb.visibility = View.VISIBLE
        }
        val container = editorScrollThumb.parent as? View ?: return
        val trackHeight = container.height
        if (trackHeight <= 0) return
        val density = resources.displayMetrics.density
        val minThumb = (56 * density).toInt()
        val thumbH = (extent.toFloat() / range * trackHeight).toInt()
            .coerceIn(minThumb, trackHeight)
        val lp = editorScrollThumb.layoutParams
        if (lp.height != thumbH) {
            lp.height = thumbH
            editorScrollThumb.layoutParams = lp
        }
        positionScrollThumb()
        scheduleThumbShrink()
    }

    private fun positionScrollThumb() {
        val editor = binding.editor
        val range = editor.getScrollRange()
        val extent = editor.getScrollExtent()
        if (range <= extent || extent <= 0) return
        val container = editorScrollThumb.parent as? View ?: return
        val trackHeight = container.height
        val thumbH = editorScrollThumb.height.takeIf { it > 0 }
            ?: (56 * resources.displayMetrics.density).toInt()
        val maxOffset = (range - extent).coerceAtLeast(1)
        val fraction = (editor.getScrollOffset().toFloat() / maxOffset).coerceIn(0f, 1f)
        val maxTop = (trackHeight - thumbH).coerceAtLeast(0)
        editorScrollThumb.translationY = fraction * maxTop
    }

    private fun dragThumbTo(rawY: Float) {
        val container = editorScrollThumb.parent as? View ?: return
        val editor = binding.editor
        val range = editor.getScrollRange()
        val extent = editor.getScrollExtent()
        if (range <= extent || extent <= 0) return
        val loc = IntArray(2)
        container.getLocationOnScreen(loc)
        val relativeY = rawY - loc[1]
        val trackHeight = container.height
        val thumbH = editorScrollThumb.height.takeIf { it > 0 } ?: 1
        val maxTop = (trackHeight - thumbH).coerceAtLeast(1)
        val fraction = ((relativeY - thumbH / 2f) / maxTop).coerceIn(0f, 1f)
        val maxOffset = (range - extent).coerceAtLeast(1)
        editor.scrollTo(0, (fraction * maxOffset).toInt())
        positionScrollThumb()
    }

    // ---- Toolbar: current file name + short parent path ----
    private fun updateToolbarForTab() {
        if (activeTabIndex in openTabs.indices) {
            val tab = openTabs[activeTabIndex]
            binding.toolbar.title = tab.name
            val path = shortPathForTab(tab)
            binding.toolbar.subtitle = if (tab.isDeleted) {
                if (path.isEmpty()) "deleted" else "$path • deleted"
            } else {
                path
            }
        } else {
            binding.toolbar.title = getString(R.string.app_name)
            binding.toolbar.subtitle = null
        }
    }

    /** Tab ka poora path (toolbar subtitle wala short version nahi). */
    private fun fullPathForTab(tab: EditorTab): String {
        val uriStr = tab.uri?.toString() ?: return tab.path ?: ""
        val decoded = try {
            java.net.URLDecoder.decode(uriStr, "UTF-8")
        } catch (e: Exception) {
            uriStr
        }
        val marker = "primary:"
        val idx = decoded.lastIndexOf(marker)
        return if (idx != -1) decoded.substring(idx + marker.length) else (tab.path ?: "")
    }

    private fun shortPathForTab(tab: EditorTab): String {
        val uriStr = tab.uri?.toString() ?: return ""
        val decoded = try {
            java.net.URLDecoder.decode(uriStr, "UTF-8")
        } catch (e: Exception) {
            uriStr
        }
        val marker = "primary:"
        val idx = decoded.lastIndexOf(marker)
        val full = if (idx != -1) decoded.substring(idx + marker.length) else (tab.path ?: "")
        val dirParts = full.split("/").filter { it.isNotEmpty() }.dropLast(1)
        if (dirParts.isEmpty()) return ""
        return if (dirParts.size > 2) "\u2026/" + dirParts.takeLast(2).joinToString("/")
        else dirParts.joinToString("/")
    }

    /** Show the welcome overlay when only an empty untitled tab is open. */
    /**
     * Symbols bar ke buttons banata hai. Har button dabane par symbol caret par
     * insert hota hai (smart typing — auto-close — bhi lagta hai).
     */
    private fun setupSymbolsBar() {
        val symbols = FollowupSymbols.symbolsFor(currentFileExtension())
        // Long-press par milne wale operator variants.
        val longPressVariants = mapOf(
            "=" to listOf("==", "=>", "!="),
            "-" to listOf("->", "--"),
            "+" to listOf("++", "+="),
            "<" to listOf("<=", "<-"),
            ">" to listOf(">=", "->"),
            "!" to listOf("!=", "!!"),
            "&" to listOf("&&"),
            "|" to listOf("||"),
            "*" to listOf("**", "*="),
            "/" to listOf("//", "/="),
            "." to listOf("..", "..."),
            ":" to listOf("::", ":="),
            "#" to listOf("#!"),
            "\"" to listOf("\"".repeat(3)),
            "'" to listOf("'".repeat(3))
        )
        val container = binding.symbolsContainer
        container.removeAllViews()
        val density = resources.displayMetrics.density
        for (sym in symbols) {
            val btn = android.widget.Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = sym
                textSize = 17f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, 0, 0)
                minimumWidth = (44 * density).toInt()
            }
            btn.setOnClickListener {
                if (sym == "Tab") {
                    val ed = binding.editor
                    val expanded = !ed.hasMultiCursors() && ed.tryTabExpand()
                    if (!expanded) insertSymbol(" ".repeat(prefs.tabSize))
                    else if (!prefs.snippetTipShown) {
                        prefs.snippetTipShown = true
                        Toast.makeText(this, "Snippet expanded (try: fun fori if when try class logd todo)", Toast.LENGTH_LONG).show()
                    }
                } else insertSymbol(sym)
            }
            btn.setOnLongClickListener {
                val variants = longPressVariants[sym] ?: return@setOnLongClickListener false
                val popup = PopupMenu(this, btn)
                variants.forEachIndexed { i, v -> popup.menu.add(0, i, i, v) }
                popup.setOnMenuItemClickListener { item ->
                    insertSymbol(variants[item.itemId])
                    true
                }
                popup.show()
                true
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
            container.addView(btn, lp)
        }
    }

    private fun insertSymbol(sym: String) {
        val ed = binding.editor
        if (!ed.isFocused) ed.requestFocus()
        if (ed.hasMultiCursors()) {
            ed.insertAtAllCarets(sym)
            return
        }
        val t = ed.text ?: return
        val s = ed.selectionStart.coerceIn(0, t.length)
        val e = ed.selectionEnd.coerceIn(0, t.length)
        if (s != e) t.replace(s, e, sym) else t.insert(s, sym)
        ed.setSelection((s + sym.length).coerceAtMost(t.length))
    }

    private fun updateSymbolsBarVisibility() {
        binding.symbolsBar.visibility =
            if (!prefs.symbolsBar || binding.welcomeView.visibility == android.view.View.VISIBLE)
                android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Status bar me current file ke line endings dikhata hai (UTF-8 • LF / CRLF). */
    /** Drawer me dotfiles chhupata hai (agar setting on hai). */
    private fun filterHiddenFiles(items: List<FileItem>): List<FileItem> {
        return if (prefs.hideHiddenFiles) items.filter { !it.name.startsWith(".") } else items
    }

    private var lastCursorLine: Int = 1
    // v7 (35): vim mode label appended to the status line ("" when vim off).
    private var vimIndicator: String = ""
    private fun vimStatusSuffix(): String =
        if (binding.editor.vimMode) vimIndicator else ""
    private var cachedBreadcrumbSymbols: List<Symbol> = emptyList()
    private var breadcrumbSymbolsDirty: Boolean = true
    private var lastBreadcrumbLine: Int = -1
    private var lastBreadcrumbTab: Int = -2
    private var gitDiffJob: Job? = null
    private val clipboardHistory = ArrayDeque<String>()
    private var lastSeenClip: String? = null

    /** Editor me kul kitni lines hain. */
    private fun totalLines(): Int {
        val t = binding.editor.text ?: return 1
        return if (t.isEmpty()) 1 else t.count { it == '\n' } + 1
    }

    private fun updateEncodingStatus() {
        val t = binding.editor.text
        val le = if (t != null && t.contains("\r\n")) "CRLF" else "LF"
        val indentInfo = "Spaces: ${prefs.tabSize}"
        val roInfo = if (prefs.readOnly) " \u2022 Read-only" else ""
        val followupEnc = FollowupStore.encodingFor(this, openTabs.getOrNull(activeTabIndex)?.uri?.toString())
        binding.statusEncoding.text = "$followupEnc \u2022 $le \u2022 $indentInfo$roInfo"
        refreshFileBreadcrumb()
    }

    private fun updateEmptyState() {
        val showWelcome = openTabs.size == 1 &&
            openTabs[0].name.startsWith("Untitled") &&
            binding.editor.text.isNullOrEmpty()
        binding.welcomeView.visibility = if (showWelcome) View.VISIBLE else View.GONE
        updateSymbolsBarVisibility()
    }

    private fun applyPreferences() {
        binding.editor.textSize = prefs.fontSize
        binding.editor.setWordWrapEnabled(prefs.isWordWrap)
        binding.editor.typewriterMode = prefs.typewriterMode
        binding.editor.setLineNumbersVisible(prefs.showLineNumbers)
        binding.editor.autoCloseBrackets = prefs.autoCloseBrackets
        binding.editor.tabSize = prefs.tabSize
        binding.editor.foldingEnabled = prefs.codeFolding
        binding.editor.highlightCurrentLine = prefs.highlightCurrentLine
        binding.editor.indentGuidesEnabled = prefs.indentGuides
        binding.editor.showWhitespace = prefs.showWhitespace
        binding.editor.rainbowBrackets = prefs.rainbowBrackets
        // v7 (35): vim modal editing (starts in normal mode when enabled).
        binding.editor.vimMode = prefs.vimMode
        binding.editor.lineGuideColumn = prefs.lineGuideColumn
        binding.editor.stickyScrollEnabled = prefs.stickyScroll
        binding.minimap.visibility = if (prefs.minimap) View.VISIBLE else View.GONE
        if (prefs.minimap) binding.minimap.requestRerender()
        binding.editor.syntaxHighlightEnabled = prefs.syntaxHighlight
        if (!prefs.syntaxHighlight) binding.editor.clearHighlighting()
        binding.editor.autoIndentEnabled = prefs.autoIndent
        binding.editor.fontLigaturesEnabled = prefs.fontLigatures
        binding.editor.setLineSpacing(0f, prefs.lineSpacing)
        // v7 (86/87): imported .ttf font + density scaling on top of lineSpacing.
        applyV7EditorFontAndDensity()
        if (prefs.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.editor.pinchZoomEnabled = prefs.pinchZoom
        binding.editor.isFocusable = !prefs.readOnly
        binding.editor.isFocusableInTouchMode = !prefs.readOnly
        // Apply the selected editor theme (incl. AMOLED Black)
        updateSymbolsBarVisibility()
        binding.editor.applyTheme(ThemeManager(this).current)
        updateEncodingStatus()
    }

    private fun saveSessionState() {
        if (activeTabIndex in openTabs.indices) {
            val curr = openTabs[activeTabIndex]
            curr.content = binding.editor.getUnfoldedText()
            curr.cursorPosition = binding.editor.selectionStart
        }

        try {
            val jsonArray = JSONArray()
            for (tab in openTabs) {
                val obj = JSONObject()
                obj.put("uri", tab.uri?.toString() ?: "")
                obj.put("name", tab.name)
                obj.put("path", tab.path ?: "")
                obj.put("cursorPosition", tab.cursorPosition)
                obj.put("isModified", tab.isModified)
                obj.put("isPinned", tab.isPinned)
                obj.put("content", tab.content)
  obj.put("diskContent", tab.diskContent)
                jsonArray.put(obj)
            }
            prefs.savedTabsJson = jsonArray.toString()
            prefs.activeTabIndex = activeTabIndex
        } catch (e: Exception) {}

        try {
            prefs.currentFolderUri = currentFolder?.uri?.toString()
            val stackArray = JSONArray()
            for (doc in folderBackStack) {
                stackArray.put(doc.uri.toString())
            }
            prefs.folderBackStackJson = stackArray.toString()
        } catch (e: Exception) {}
    }

    private fun restoreSessionState() {
        // Project Manager: check if a specific project URI was passed
        val projectUri = intent.getStringExtra("project_uri")
        if (projectUri != null) {
            prefs.rootFolderUri = projectUri
            ProjectManager.updateLastOpened(this, projectUri)
        }
        // Project Manager file browser: open specific file after folder loads
        val openFileUri = intent.getStringExtra("open_file_uri")
        if (openFileUri != null) {
            // Delay slightly to let folder init complete
            binding.root.postDelayed({
                try {
                    val fileUri = Uri.parse(openFileUri)
                    openFileFromUri(fileUri)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 500)
        }
        val savedRoot = prefs.rootFolderUri
        if (savedRoot != null) {
            try {
                val rootUri = Uri.parse(savedRoot)
                val root = DocumentFile.fromTreeUri(this, rootUri)
                if (root != null && root.exists()) {
                    rootDocumentFile = root

                    folderBackStack.clear()
                    val stackJson = prefs.folderBackStackJson
                    if (!stackJson.isNullOrEmpty()) {
                        val arr = JSONArray(stackJson)
                        for (i in 0 until arr.length()) {
                            val uriText = arr.getString(i)
                            val sDoc = DocumentFile.fromTreeUri(this, Uri.parse(uriText))
                            if (sDoc != null && sDoc.exists()) {
                                folderBackStack.push(sDoc)
                            }
                        }
                    }

                    val currUriStr = prefs.currentFolderUri
                    val targetCurr = if (!currUriStr.isNullOrEmpty()) {
                        DocumentFile.fromTreeUri(this, Uri.parse(currUriStr))
                    } else null

                    currentFolder = if (targetCurr != null && targetCurr.exists()) targetCurr else root
                    loadCurrentFolderContentsFast()
                } else {
                    updateDrawerNavHeader()
                }
            } catch (e: Exception) {
                updateDrawerNavHeader()
            }
        } else {
            updateDrawerNavHeader()
        }

        openTabs.clear()
        val tabsJson = prefs.savedTabsJson
        if (!tabsJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(tabsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val uriStr = obj.optString("uri", "")
                    val name = obj.optString("name", "Untitled")
                    val path = if (obj.has("path") && !obj.isNull("path")) obj.getString("path") else null
                    val cursorPos = obj.optInt("cursorPosition", 0)
                    val isModified = obj.optBoolean("isModified", false)
                    var content = obj.optString("content", "")
                    val diskContent = obj.optString("diskContent", content)

                    val tabUri = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null

                    if (tabUri != null && content.isEmpty()) {
                        try {
                            contentResolver.openInputStream(tabUri)?.use { stream ->
                                content = stream.bufferedReader().readText()
                            }
                        } catch (e: Exception) {
                            content = ""
                        }
                    }

                    openTabs.add(
                        EditorTab(
                            uri = tabUri,
                            name = name,
                            path = path,
                            content = content,
                            diskContent = diskContent,
                            isModified = isModified,
                            cursorPosition = cursorPos,
                            isPinned = obj.optBoolean("isPinned", false)
                        )
                    )
                }
            } catch (e: Exception) {}
        }

        if (openTabs.isEmpty()) {
            openNewUntitledTab()
        } else {
            val targetIdx = prefs.activeTabIndex.coerceIn(0, openTabs.size - 1)
            renderTabs()
            switchTab(targetIdx)
        }
    }

    private fun updateRunButtonVisibility() {
        val runItem: MenuItem? = binding.toolbar.menu.findItem(R.id.action_run)
        if (runItem == null) return

        if (activeTabIndex !in openTabs.indices) {
            runItem.isVisible = false
            return
        }

        val fileName = openTabs[activeTabIndex].name.lowercase()
        val isHtmlOrXml = fileName.endsWith(".html") || 
                          fileName.endsWith(".htm") || 
                          fileName.endsWith(".hfml") || 
                          fileName.endsWith(".xml")
        
        runItem.isVisible = isHtmlOrXml
        // Markdown preview menu item
        binding.toolbar.menu.findItem(R.id.action_markdown_preview)?.isVisible =
            fileName.endsWith(".md") || fileName.endsWith(".markdown")
    }

    // ---------- Live HTML preview: 50/50 split, auto-reloads as you type ----------
    private var isHtmlPreviewing = false
    private var htmlPreviewWatcherAdded = false
    private val htmlPreviewHandler = Handler(Looper.getMainLooper())
    private val htmlReloadRunnable = Runnable {
        if (isHtmlPreviewing && activeTabIndex in openTabs.indices) {
            val html = binding.editor.text.toString()
            openTabs[activeTabIndex].content = html
            binding.htmlPreview.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }

    private fun toggleHtmlPreview() {
        if (activeTabIndex !in openTabs.indices) return
        if (isHtmlPreviewing) hideHtmlPreview() else showHtmlPreview()
    }

    private fun showHtmlPreview() {
        if (activeTabIndex !in openTabs.indices) return
        val tab = openTabs[activeTabIndex]
        val n = tab.name.lowercase()
        if (!n.endsWith(".html") && !n.endsWith(".htm")) {
            Toast.makeText(this, "Open an .html file for live preview", Toast.LENGTH_SHORT).show()
            return
        }
        if (isMarkdownPreviewing) hideMarkdownPreview()
        ensureHtmlPreviewWatcher()
        tab.content = binding.editor.getUnfoldedText()
        val parentH = (binding.editor.parent as View).height
        val half = if (parentH > 0) parentH / 2 else FrameLayout.LayoutParams.WRAP_CONTENT
        val ep = binding.editor.layoutParams as FrameLayout.LayoutParams
        ep.height = half
        ep.gravity = Gravity.TOP
        binding.editor.layoutParams = ep
        binding.htmlPreview.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, half, Gravity.BOTTOM
        )
        binding.htmlPreview.settings.javaScriptEnabled = true
        binding.htmlPreview.loadDataWithBaseURL(null, tab.content, "text/html", "utf-8", null)
        binding.htmlPreview.visibility = View.VISIBLE
        isHtmlPreviewing = true
    }

    private fun hideHtmlPreview() {
        val ep = binding.editor.layoutParams as FrameLayout.LayoutParams
        ep.height = FrameLayout.LayoutParams.MATCH_PARENT
        ep.gravity = Gravity.NO_GRAVITY
        binding.editor.layoutParams = ep
        binding.htmlPreview.visibility = View.GONE
        isHtmlPreviewing = false
    }

    private fun ensureHtmlPreviewWatcher() {
        if (htmlPreviewWatcherAdded) return
        htmlPreviewWatcherAdded = true
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isHtmlPreviewing) return
                htmlPreviewHandler.removeCallbacks(htmlReloadRunnable)
                htmlPreviewHandler.postDelayed(htmlReloadRunnable, 600)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // ---------- Quick Open: file-name search across the open folder ----------
    private fun showQuickOpenDialog() {
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        data class QOEntry(val name: String, val relPath: String, val doc: DocumentFile, val line: Int = -1)
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_quick_open, null)
        dialog.setContentView(view)
        val searchInput: EditText = view.findViewById(R.id.quickOpenSearch)
        searchInput.post { searchInput.requestFocus() }
        val list: RecyclerView = view.findViewById(R.id.quickOpenList)
        val emptyText: TextView = view.findViewById(R.id.quickOpenEmpty)
        list.layoutManager = LinearLayoutManager(this)
        var allFiles: List<QOEntry> = emptyList()
        var quickOpenGotoLine = -1
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<QOEntry> = emptyList()
            fun submit(newItems: List<QOEntry>) {
                items = newItems
                notifyDataSetChanged()
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_quick_open, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = items[position]
                holder.itemView.findViewById<TextView>(R.id.qoName).text = e.name
                holder.itemView.findViewById<TextView>(R.id.qoPath).text = e.relPath
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    binding.drawer.closeDrawer(GravityCompat.START)
                    openFileInTab(e.doc, if (e.line > 0) e.line else quickOpenGotoLine)
                }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter

        // Enter dabane par pehla result kholo.
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val first = adapter.items.firstOrNull()
                if (first != null) {
                    dialog.dismiss()
                    binding.drawer.closeDrawer(GravityCompat.START)
                    openFileInTab(first.doc, if (first.line > 0) first.line else quickOpenGotoLine)
                }
                true
            } else false
        }

        // INV-118: "@" prefix se current file ke symbols me jump (VS Code Ctrl+P jaisa).
        val qoSymbols: List<Symbol> = collectSymbols()
        val qoCurDoc: DocumentFile? = if (activeTabIndex in openTabs.indices) {
            openTabs[activeTabIndex].uri?.let { u -> try { DocumentFile.fromSingleUri(this, u) } catch (e: Exception) { null } }
        } else null

        fun refreshFilter() {
            val rawQ = searchInput.text.toString().trim()
            if (rawQ.startsWith("@")) {
                val sq = rawQ.drop(1).trim().lowercase()
                val qdoc = qoCurDoc
                val symItems = if (qdoc == null) emptyList() else qoSymbols
                    .filter { sq.isEmpty() || it.name.lowercase().contains(sq) }
                    .take(50)
                    .map { QOEntry(it.name, it.kind + " \u00b7 L" + it.line, qdoc, it.line) }
                adapter.submit(symItems)
                emptyText.text = if (qdoc == null) "No file open" else if (symItems.isEmpty()) "No symbols found" else "Symbols in " + openTabs[activeTabIndex].name
                emptyText.visibility = if (symItems.isEmpty()) View.VISIBLE else View.GONE
                return
            }
            val lineMatch = Regex(":(\\d+)$").find(rawQ)
            quickOpenGotoLine = lineMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val q = (if (lineMatch != null) rawQ.substring(0, lineMatch.range.first) else rawQ).trim().lowercase()
             val filtered = if (q.isEmpty()) {
                val recents = recentFileUris.mapNotNull { uri -> allFiles.find { it.doc.uri == uri } }
                (recents + allFiles.filter { e -> recents.none { it.doc.uri == e.doc.uri } }).take(50)
            }
            else allFiles.filter { it.name.lowercase().contains(q) }.take(50)
            adapter.submit(filtered)
            emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val found = mutableListOf<QOEntry>()
            fun walk(dir: DocumentFile, prefix: String) {
                if (found.size >= 800) return
                val children = try { dir.listFiles() } catch (_: Exception) { return }
                for (child in children) {
                    if (found.size >= 800) return
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    try {
                        if (child.isDirectory) walk(child, "$prefix$name/")
                        else if (child.isFile) found.add(QOEntry(name, "$prefix$name", child))
                    } catch (_: Exception) { }
                }
            }
            try { walk(folder, "") } catch (_: Exception) { }
            found.sortBy { it.name.lowercase() }
            withContext(Dispatchers.Main) {
                allFiles = found
                emptyText.text = if (found.isEmpty()) "No files found" else "Type to search ${found.size} files"
                refreshFilter()
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshFilter()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        searchInput.requestFocus()
    }

    private var isMarkdownPreviewing = false

    private fun toggleMarkdownPreview() {
        if (activeTabIndex !in openTabs.indices) return
        if (isMarkdownPreviewing) {
            hideMarkdownPreview()
        } else {
            showMarkdownPreview()
        }
    }

    private fun showMarkdownPreview() {
        val currentTab = openTabs[activeTabIndex]
        if (!currentTab.name.lowercase().endsWith(".md")) {
            Toast.makeText(this, "Open a .md file to preview", Toast.LENGTH_SHORT).show()
            return
        }
        // Save current editor content to tab
        currentTab.content = binding.editor.getUnfoldedText()
        val html = com.nobidev.nobicode.editor.MarkdownRenderer.wrapHtml(
            com.nobidev.nobicode.editor.MarkdownRenderer.toHtml(currentTab.content)
        )
        binding.markdownPreview.settings.javaScriptEnabled = false
        binding.markdownPreview.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        binding.markdownPreview.visibility = View.VISIBLE
        binding.editor.visibility = View.GONE
        isMarkdownPreviewing = true
        invalidateOptionsMenu()
    }

    private fun hideMarkdownPreview() {
        binding.markdownPreview.visibility = View.GONE
        binding.editor.visibility = View.VISIBLE
        isMarkdownPreviewing = false
        invalidateOptionsMenu()
    }

    /** Resolve the open folder to a java.io.File for JGit (primary storage only). */
    fun getRepoDirForGit(): java.io.File? {
        val folder = currentFolder ?: return null
        return com.nobidev.nobicode.git.GitManager.resolveToFile(folder.uri)
    }

    // ── Remote (FTP/SFTP) mode ───────────────────────────────────
    private var remoteConn: com.nobidev.nobicode.model.RemoteConnection? = null
    private var remotePath: String = "/"
    private val remotePathStack = ArrayDeque<String>()
    var isRemoteMode: Boolean = false
        private set

    fun enterRemoteMode(conn: com.nobidev.nobicode.model.RemoteConnection) {
        remoteConn = conn
        remotePath = "/"
        remotePathStack.clear()
        isRemoteMode = true
        Toast.makeText(this, "Remote: " + conn.name, Toast.LENGTH_SHORT).show()
        loadRemoteFiles()
    }

    fun exitRemoteMode() {
        isRemoteMode = false
        remoteConn = null
        remotePathStack.clear()
        // Reload local files
        loadLocalFiles()
    }

    private fun loadLocalFiles() {
        loadCurrentFolderContentsFast()
    }

    private fun loadRemoteFiles() {
        if (remoteConn == null) return
        lifecycleScope.launch {
            val result = com.nobidev.nobicode.remote.RemoteManager.listDir(remotePath)
            result.onSuccess { files: List<com.nobidev.nobicode.model.RemoteFile> ->
                val items = mutableListOf<com.nobidev.nobicode.model.FileItem>()
                if (remotePath != "/") {
                    items.add(com.nobidev.nobicode.model.FileItem(
                        "..", android.net.Uri.parse("nobiremote://parent"),
                        isDirectory = true, isParent = true
                    ))
                }
                for (f: com.nobidev.nobicode.model.RemoteFile in files) {
                    items.add(com.nobidev.nobicode.model.FileItem(
                        f.name,
                        android.net.Uri.parse("nobiremote://" + f.path),
                        isDirectory = f.isDir
                    ))
                }
                fileAdapter.submitList(items)
            }.onFailure { e: Throwable ->
                Toast.makeText(this@MainActivity, "List failed: " + e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerRunPreview() {
        if (activeTabIndex !in openTabs.indices) return
        val currentTab = openTabs[activeTabIndex]
        val fileName = currentTab.name.lowercase()

        if (fileName.endsWith(".html") || fileName.endsWith(".htm") || fileName.endsWith(".hfml")) {
            showWebBrowserScreen(currentTab)
        } else if (fileName.endsWith(".xml")) {
            showXmlPreviewScreen(currentTab)
        }
    }

    private fun resolveRelativeAsset(relativePath: String): Pair<String, InputStream>? {
        val cleanPath = relativePath.trim().trimStart('/').replace("\\", "/")
        if (cleanPath.isEmpty()) return null

        val fileName = cleanPath.substringAfterLast('/')
        val mimeType = getMimeTypeForAsset(fileName)

        val matchingTab = openTabs.firstOrNull { 
            it.name.equals(fileName, ignoreCase = true) || 
            (it.path != null && it.path!!.endsWith(cleanPath, ignoreCase = true))
        }
        if (matchingTab != null) {
            val stream = ByteArrayInputStream(matchingTab.content.toByteArray(StandardCharsets.UTF_8))
            return Pair(mimeType, stream)
        }

        val baseFolders = listOfNotNull(currentFolder, rootDocumentFile)
        for (folder in baseFolders) {
            val doc = findDocumentByRelativePath(folder, cleanPath)
            if (doc != null) {
                try {
                    val stream = contentResolver.openInputStream(doc.uri)
                    if (stream != null) {
                        return Pair(mimeType, stream)
                    }
                } catch (e: Exception) {}
            }
        }

        val cacheFile = File(cacheDir, cleanPath)
        if (cacheFile.exists() && cacheFile.isFile) {
            try {
                return Pair(mimeType, cacheFile.inputStream())
            } catch (e: Exception) {}
        }

        return null
    }

    private fun findDocumentByRelativePath(parent: DocumentFile?, relativePath: String): DocumentFile? {
        if (parent == null || relativePath.isEmpty()) return null
        val parts = relativePath.split("/").filter { it.isNotEmpty() && it != "." }
        var current: DocumentFile = parent

        for (i in parts.indices) {
            val part = parts[i]
            if (part == "..") {
                current = current.parentFile ?: current
                continue
            }
            val isLast = (i == parts.size - 1)
            val child = current.findFile(part) ?: run {
                current.listFiles().firstOrNull { it.name.equals(part, ignoreCase = true) }
            } ?: return null

            if (isLast) {
                return child
            } else if (child.isDirectory) {
                current = child
            } else {
                return null
            }
        }
        return null
    }

    private fun getMimeTypeForAsset(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "html", "htm", "hfml" -> "text/html"
            "txt" -> "text/plain"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    enum class WebProtocolMode { CONTENT_URI, LOCALHOST, FILE_PATH }

    private fun showWebBrowserScreen(tab: EditorTab) {
        val dialog = Dialog(this, R.style.Theme_NobiCode)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_web_preview, null)
        dialog.setContentView(view)

        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#1A1B26")))
            statusBarColor = Color.parseColor("#16161E")
            navigationBarColor = Color.parseColor("#16161E")
        }

        val headerView = view.findViewById<View>(R.id.browserHeader)
        val webView = view.findViewById<WebView>(R.id.previewWebView)
        val urlText = view.findViewById<TextView>(R.id.previewUrlText)
        val reloadBtn = view.findViewById<ImageView>(R.id.previewReload)
        val menuBtn = view.findViewById<ImageView>(R.id.previewMenuBtn)
        val closeBtn = view.findViewById<ImageView>(R.id.previewClose)

        ViewCompat.setOnApplyWindowInsetsListener(headerView) { v, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topP = maxOf(statusBarInset.top, cutout.top)
            v.setPadding(v.paddingLeft, topP + 4, v.paddingRight, v.paddingBottom)
            insets
        }

        var currentProtocol = if (tab.uri != null) WebProtocolMode.CONTENT_URI else WebProtocolMode.LOCALHOST

        webView.setBackgroundColor(Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            displayZoomControls = false
            builtInZoomControls = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("JavaScript Alert")
                    .setMessage(message ?: "")
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("JavaScript Confirm")
                    .setMessage(message ?: "")
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUri = request?.url ?: return null
                val path = reqUri.path ?: ""

                if (reqUri.host.equals("localhost", ignoreCase = true) || reqUri.scheme == "http" || reqUri.scheme == "https") {
                    val relative = path.trimStart('/')
                    if (relative.isNotEmpty() && !relative.equals(tab.name, ignoreCase = true)) {
                        val asset = resolveRelativeAsset(relative)
                        if (asset != null) {
                            return WebResourceResponse(asset.first, "UTF-8", asset.second)
                        }
                    }
                }

                if (reqUri.scheme == "content") {
                    try {
                        val stream = contentResolver.openInputStream(reqUri)
                        if (stream != null) {
                            val mime = contentResolver.getType(reqUri) ?: getMimeTypeForAsset(reqUri.lastPathSegment ?: "")
                            return WebResourceResponse(mime, "UTF-8", stream)
                        }
                    } catch (e: Exception) {
                        val asset = resolveRelativeAsset(reqUri.lastPathSegment ?: "")
                        if (asset != null) {
                            return WebResourceResponse(asset.first, "UTF-8", asset.second)
                        }
                    }
                }

                if (reqUri.scheme == "file") {
                    val asset = resolveRelativeAsset(reqUri.lastPathSegment ?: "")
                    if (asset != null) {
                        return WebResourceResponse(asset.first, "UTF-8", asset.second)
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        fun getLatestHtmlCode(): String {
            val code = binding.editor.text?.toString() ?: tab.content
            return if (code.trim().isEmpty()) {
                "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head><body style='font-family:sans-serif;padding:30px;color:#333;background:#fff;'><h2>Empty File</h2><p>Write some HTML code in the editor to preview it.</p></body></html>"
            } else {
                code
            }
        }

        fun loadPreviewContent(protocol: WebProtocolMode) {
            currentProtocol = protocol
            val codeToRender = getLatestHtmlCode()

            when (protocol) {
                WebProtocolMode.CONTENT_URI -> {
                    val uriString = tab.uri?.toString() ?: "content://com.nobidev.nobicode/documents/${tab.name}"
                    urlText.text = uriString
                    webView.loadDataWithBaseURL(uriString, codeToRender, "text/html", "UTF-8", null)
                }
                WebProtocolMode.LOCALHOST -> {
                    val localUrl = "http://localhost:8080/${tab.name}"
                    urlText.text = localUrl
                    webView.loadDataWithBaseURL("http://localhost:8080/", codeToRender, "text/html", "UTF-8", null)
                }
                WebProtocolMode.FILE_PATH -> {
                    try {
                        val publicDir = getExternalFilesDir(null) ?: cacheDir
                        val targetFile = File(publicDir, tab.name)
                        targetFile.writeText(codeToRender)
                        val fileUrl = "file://${targetFile.absolutePath}"
                        urlText.text = fileUrl
                        webView.loadDataWithBaseURL(fileUrl, codeToRender, "text/html", "UTF-8", null)
                    } catch (e: Exception) {
                        val fallbackUrl = "file:///${tab.name}"
                        urlText.text = fallbackUrl
                        webView.loadDataWithBaseURL(fallbackUrl, codeToRender, "text/html", "UTF-8", null)
                    }
                }
            }
        }

        loadPreviewContent(currentProtocol)

        reloadBtn.setOnClickListener {
            loadPreviewContent(currentProtocol)
            Toast.makeText(this, "Reloaded", Toast.LENGTH_SHORT).show()
        }

        menuBtn.setOnClickListener {
            val options = arrayOf(
                "Run as Content URI (Chrome format)",
                "Run as Localhost (http://localhost:8080/)",
                "Run as File Path (file://)",
                "Open in External Chrome",
                "Copy Preview URL"
            )
            val selectedIndex = when (currentProtocol) {
                WebProtocolMode.CONTENT_URI -> 0
                WebProtocolMode.LOCALHOST -> 1
                WebProtocolMode.FILE_PATH -> 2
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Browser Protocol & Options")
                .setSingleChoiceItems(options, selectedIndex) { d, which ->
                    when (which) {
                        0 -> {
                            loadPreviewContent(WebProtocolMode.CONTENT_URI)
                            Toast.makeText(this, "Switched to Content URI", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            loadPreviewContent(WebProtocolMode.LOCALHOST)
                            Toast.makeText(this, "Switched to Localhost", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            loadPreviewContent(WebProtocolMode.FILE_PATH)
                            Toast.makeText(this, "Switched to File Path", Toast.LENGTH_SHORT).show()
                        }
                        3 -> {
                            try {
                                if (tab.uri != null) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(tab.uri, "text/html")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(Intent.createChooser(intent, "Open in Browser"))
                                } else {
                                    Toast.makeText(this, "Save the file first to open in Chrome", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this, "Could not open external browser: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        4 -> {
                            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clip?.setPrimaryClip(ClipData.newPlainText("Preview URL", urlText.text))
                            Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showXmlPreviewScreen(tab: EditorTab) {
        val dialog = Dialog(this, R.style.Theme_NobiCode)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_xml_preview, null)
        dialog.setContentView(view)

        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val previewTitle = view.findViewById<TextView>(R.id.xmlPreviewTitle)
        val vectorImage = view.findViewById<ImageView>(R.id.xmlVectorImageView)
        val previewInfo = view.findViewById<TextView>(R.id.xmlPreviewInfo)
        val closeBtn = view.findViewById<ImageView>(R.id.xmlPreviewClose)

        previewTitle.text = tab.name
        val xmlContent = binding.editor.text?.toString() ?: tab.content

        if (xmlContent.contains("<vector", ignoreCase = true)) {
            try {
                val tempFile = File(cacheDir, "temp_preview_vector.xml")
                tempFile.writeText(xmlContent)
                val d = Drawable.createFromPath(tempFile.absolutePath)
                if (d != null) {
                    vectorImage.setImageDrawable(d)
                    previewInfo.text = "Vector Drawable Parsed Successfully"
                } else {
                    vectorImage.setImageResource(R.drawable.file_type_android)
                    previewInfo.text = "Android Vector Format • Ready to Compile"
                }
            } catch (e: Exception) {
                vectorImage.setImageResource(R.drawable.file_type_android)
                previewInfo.text = "Vector XML Preview Ready"
            }
        } else {
            vectorImage.setImageResource(R.drawable.file_type_android)
            previewInfo.text = "Android Layout / Values XML Document"
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun initFileManager(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.exists()) {
            binding.drawerCurrentFolder.text = getString(R.string.app_name)
            binding.drawerPath.text = getString(R.string.drawer_empty)
            return
        }

        rootDocumentFile = root
        currentFolder = root
        pushRecentFolder(treeUri.toString())
        folderBackStack.clear()
        folderCache.evictAll()
        loadCurrentFolderContentsFast()
    }

    private fun loadCurrentFolderContentsFast() {
        val folder = currentFolder ?: return
        val treeUri = rootDocumentFile?.uri ?: folder.uri
        val folderKey = folder.uri.toString()
        val isNotAtRoot = folderBackStack.isNotEmpty()

        updateDrawerNavHeader()

        val cached = folderCache.get(folderKey)
        if (cached != null) {
            lastDrawerBase = filterHiddenFiles(cached)
            lastDrawerBase = applyFollowupBrowserSortFilter(lastDrawerBase)
            submitDrawerList(isNotAtRoot)
            return
        }

        binding.drawerLoadingProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val resultItems = withContext(Dispatchers.IO) {
                fetchFolderItemsOnly(folder, treeUri)
            }

            folderCache.put(folderKey, resultItems)
            lastDrawerBase = filterHiddenFiles(resultItems)
            lastDrawerBase = applyFollowupBrowserSortFilter(lastDrawerBase)
            submitDrawerList(isNotAtRoot)
            // v7 (60): tell the terminal where the editor is looking.
            writeEditorFolderFile()
        }
    }

    /** v6 tree sidebar: submit the drawer list with inline expansion applied.
     * The ".." parent row (when drilled in) always stays first, unexpanded. */
    private fun submitDrawerList(isNotAtRoot: Boolean) {
        val displayList = mutableListOf<FileItem>()
        if (isNotAtRoot) {
            val folder = currentFolder
            if (folder != null) {
                displayList.add(FileItem(getString(R.string.parent_folder), folder.uri, isDirectory = true, isParent = true))
            }
        }
        displayList.addAll(flattenDrawerTree(lastDrawerBase, 0))
        fileAdapter.submitList(displayList)
        binding.emptyFolderText.visibility = if (displayList.isEmpty()) View.VISIBLE else View.GONE
        binding.drawerLoadingProgress.visibility = View.GONE
    }

    /** v6: flatten a folder list into tree rows. Expanded directories
     * contribute their (cached, hidden-filtered) children right below them,
     * indented one level deeper; uncached expansions are fetched async. */
    private fun flattenDrawerTree(items: List<FileItem>, depth: Int): List<FileItem> {
        val out = mutableListOf<FileItem>()
        for (item in items) {
            val expanded = !item.isParent && item.isDirectory &&
                expandedFolderUris.contains(item.uri.toString())
            out.add(item.copy(depth = depth, isExpanded = expanded))
            if (expanded) {
                val children = folderCache.get(item.uri.toString())
                if (children != null) {
                    out.addAll(flattenDrawerTree(filterHiddenFiles(children), depth + 1))
                } else {
                    fetchExpansionChildren(item)
                }
            }
        }
        return out
    }

    /** v6: fetch one expanded folder's children (cache-first callers already
     * handled the cached case), then re-submit the drawer list. */
    private fun fetchExpansionChildren(item: FileItem) {
        val key = item.uri.toString()
        if (!fetchingExpansion.add(key)) return
        val treeUri = rootDocumentFile?.uri ?: currentFolder?.uri
        val doc = DocumentFile.fromTreeUri(this, item.uri)
        if (treeUri == null || doc == null) {
            fetchingExpansion.remove(key)
            return
        }
        lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) {
                fetchFolderItemsOnly(doc, treeUri)
            }
            folderCache.put(key, children)
            fetchingExpansion.remove(key)
            submitDrawerList(folderBackStack.isNotEmpty())
        }
    }

    /** v6: drawer folder arrow toggles inline expansion. Row tap still
     * navigates into the folder, as before. */
    private fun toggleFolderExpansion(item: FileItem) {
        val key = item.uri.toString()
        if (expandedFolderUris.contains(key)) {
            expandedFolderUris.remove(key)
            submitDrawerList(folderBackStack.isNotEmpty())
            return
        }
        expandedFolderUris.add(key)
        if (folderCache.get(key) != null) {
            submitDrawerList(folderBackStack.isNotEmpty())
        } else {
            fetchExpansionChildren(item)
        }
    }

    private fun fetchFolderItemsOnly(folder: DocumentFile, treeUri: Uri): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val folderUri = folder.uri

        try {
            val docId = if (DocumentsContract.isDocumentUri(this, folderUri)) {
                DocumentsContract.getDocumentId(folderUri)
            } else {
                DocumentsContract.getTreeDocumentId(folderUri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIdx)
                    val displayName = cursor.getString(nameIdx) ?: "Unknown"
                    val mimeType = cursor.getString(mimeIdx) ?: ""
                    val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)

                    items.add(FileItem(name = displayName, uri = childUri, isDirectory = isDir))
                }
            }
        } catch (e: Exception) {
            folder.listFiles().forEach { child ->
                items.add(FileItem(name = child.name ?: "Unknown", uri = child.uri, isDirectory = child.isDirectory))
            }
        }

        return items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun updateDrawerNavHeader() {
        val curr = currentFolder
        if (curr != null) {
            binding.drawerCurrentFolder.text = curr.name ?: getString(R.string.app_name)
            binding.drawerPath.text = if (folderBackStack.isEmpty()) "Root Folder" else "... / ${curr.name}"
        } else {
            binding.drawerCurrentFolder.text = getString(R.string.app_name)
            binding.drawerPath.text = getString(R.string.drawer_empty)
        }
        updateDrawerEmptyState()
    }

    /** Show a friendly empty state when no folder is opened in the explorer. */
    private fun updateDrawerEmptyState() {
        val noFolder = currentFolder == null
        binding.drawerEmptyState.visibility = if (noFolder) View.VISIBLE else View.GONE
        binding.fileRecyclerView.visibility = if (noFolder) View.GONE else View.VISIBLE
        if (noFolder) {
            binding.emptyFolderText.visibility = View.GONE
            binding.drawerLoadingProgress.visibility = View.GONE
        }
    }

    private fun openNewUntitledTab(content: String = "") {
        val tabCount = openTabs.count { it.name.startsWith("Untitled") }
        val name = if (tabCount == 0) "Untitled" else "Untitled-$tabCount"
        val newTab = EditorTab(name = name, content = content, diskContent = content, isModified = false)
        openTabs.add(newTab)
        switchTab(openTabs.size - 1)
        saveSessionState()
    }

    /** INV-158 Vector drawable preview: drawable XML ko render karke dialog me dikhao. */
    private fun previewVectorDrawable(docFile: DocumentFile, gotoLine: Int) {
        binding.editorLoadingSpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val bytes = contentResolver.openInputStream(docFile.uri)?.use { it.readBytes() }
                        ?: return@withContext null
                    if (bytes.size > 512 * 1024) return@withContext null
                    val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
                    if (!head.contains("<vector")) return@withContext null
                    val parser = android.util.Xml.newPullParser()
                    parser.setInput(bytes.inputStream().reader(Charsets.UTF_8))
                    var et = parser.eventType
                    while (et != org.xmlpull.v1.XmlPullParser.START_TAG &&
                        et != org.xmlpull.v1.XmlPullParser.END_DOCUMENT
                    ) {
                        et = parser.next()
                    }
                    if (parser.name != "vector") return@withContext null
                    val vd = android.graphics.drawable.VectorDrawable()
                    vd.inflate(resources, parser, android.util.Xml.asAttributeSet(parser))
                    val iw = vd.intrinsicWidth.takeIf { it > 0 } ?: 512
                    val ih = vd.intrinsicHeight.takeIf { it > 0 } ?: 512
                    val scale = minOf(512f / iw, 512f / ih).coerceAtMost(8f)
                    val bw = (iw * scale).toInt().coerceAtLeast(1)
                    val bh = (ih * scale).toInt().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        bw, bh, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    vd.setBounds(0, 0, bw, bh)
                    vd.draw(canvas)
                    bmp
                } catch (_: Exception) {
                    null
                }
            }
            binding.editorLoadingSpinner.visibility = View.GONE
            binding.drawer.closeDrawer(GravityCompat.START)
            if (bitmap != null) {
                val iv = android.widget.ImageView(this@MainActivity).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    setBackgroundColor(android.graphics.Color.WHITE)
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                val sv = android.widget.HorizontalScrollView(this@MainActivity).apply {
                    addView(android.widget.ScrollView(this@MainActivity).apply { addView(iv) })
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(docFile.name)
                    .setView(sv)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Open as text") { _, _ ->
                        openFileInTab(
                            docFile, gotoLine,
                            skipImagePreview = true, skipVectorPreview = true
                        )
                    }
                    .show()
            } else {
                // Vector nahi hai (layout/values XML) — text ki tarah kholo.
                openFileInTab(docFile, gotoLine, skipImagePreview = true, skipVectorPreview = true)
            }
        }
    }

    /** Project Manager: Open a file directly from its URI. */
    private fun openFileFromUri(fileUri: Uri) {
        try {
            val doc = DocumentFile.fromSingleUri(this, fileUri)
            if (doc != null && doc.exists() && doc.isFile) {
                openFileInTab(docFile = doc)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Could not open file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFileInTab(
        docFile: DocumentFile,
        gotoLine: Int = -1,
        skipImagePreview: Boolean = false,
        skipVectorPreview: Boolean = false,
        remotePath: String? = null,
        remoteConnId: String? = null
    ) {
        // Quick Open recents: haal me kholi files (VS Code Ctrl+P jaisa).
        recentFileUris.remove(docFile.uri)
        recentFileUris.addFirst(docFile.uri)
        while (recentFileUris.size > 15) recentFileUris.removeLast()
        // Images: dialog me preview dikhao, tab mat kholo.
        val ext = docFile.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (!skipImagePreview && ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")) {
            binding.editorLoadingSpinner.visibility = View.VISIBLE
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(docFile.uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    } catch (e: Exception) { null }
                }
                binding.editorLoadingSpinner.visibility = View.GONE
                binding.drawer.closeDrawer(GravityCompat.START)
                if (bitmap != null) {
                    val iv = android.widget.ImageView(this@MainActivity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                    }
                    val sv = android.widget.HorizontalScrollView(this@MainActivity).apply {
                        addView(android.widget.ScrollView(this@MainActivity).apply { addView(iv) })
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(docFile.name)
                        .setView(sv)
                        .setPositiveButton("Close", null)
                        .setNeutralButton("Open as text") { _, _ ->
                            openFileInTab(docFile, gotoLine, skipImagePreview = true)
                        }
                        .show()
                } else {
                    Toast.makeText(this@MainActivity, "Could not load image", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        // INV-158: vector drawable preview — drawable XML render hokar dikhega.
        if (!skipVectorPreview && ext == "xml") {
            previewVectorDrawable(docFile, gotoLine)
            return
        }
        // INV-156: line-jump se pehle current caret yaad rakho.
        if (gotoLine > 0 && !suppressNavPush) pushNavPoint()
        val existingIndex = openTabs.indexOfFirst { it.uri == docFile.uri }
        if (existingIndex != -1) {
            val existing = openTabs[existingIndex]
            if (remotePath != null) existing.remotePath = remotePath
            if (remoteConnId != null) existing.remoteConnId = remoteConnId
            switchTab(existingIndex)
            if (gotoLine > 0) binding.editor.gotoLine(gotoLine)
            binding.drawer.closeDrawer(GravityCompat.START)
            return
        }

        binding.editorLoadingSpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val (content, isBinary) = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(docFile.uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        var bin = false
                        val checkLen = minOf(bytes.size, 8192)
                        for (i in 0 until checkLen) {
                            if (bytes[i] == 0.toByte()) { bin = true; break }
                        }
                        String(bytes, FollowupEncoding.charsetFor(FollowupStore.encodingFor(this@MainActivity, docFile.uri.toString()))) to bin
                    } ?: ("" to false)
                } catch (e: Exception) {
                    "" to false
                }
            }

            binding.editorLoadingSpinner.visibility = View.GONE

            val tab = EditorTab(
                uri = docFile.uri,
                name = docFile.name ?: "File",
                path = docFile.uri.path,
                content = content,
                diskContent = content,
                isModified = false,
                remotePath = remotePath,
                remoteConnId = remoteConnId
            )

            openTabs.add(tab)
            switchTab(openTabs.size - 1)
            if (gotoLine > 0) binding.editor.gotoLine(gotoLine)
            binding.drawer.closeDrawer(GravityCompat.START)
            saveSessionState()
            if (isBinary) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Binary file?")
                    .setMessage("\"${docFile.name}\" looks like a binary file and may not display correctly.")
                    .setPositiveButton("Keep open", null)
                    .setNegativeButton("Close file") { _, _ ->
                        if (openTabs.isNotEmpty()) performCloseTab(openTabs.size - 1)
                    }
                    .show()
            }
        }
    }

    private fun switchTab(index: Int) {
        if (index !in openTabs.indices) return

        if (activeTabIndex in openTabs.indices) {
            val curr = openTabs[activeTabIndex]
            curr.content = binding.editor.getUnfoldedText()
            curr.cursorPosition = binding.editor.selectionStart
        }

        activeTabIndex = index
        breadcrumbSymbolsDirty = true
        val target = openTabs[index]
        trackRecentFile(target)
        // v11 follow-up (4)(2)(8): recent persist, fast mode, per-language symbols.
        FollowupStore.recordRecent(this, target.uri?.toString(), target.name)
        applyFollowupFastMode(target)
        setupSymbolsBar()

        binding.editor.setTextCleanly(target.content)
        binding.editor.setSelection(target.cursorPosition.coerceIn(0, target.content.length))
        updateEncodingStatus()
        // Hide markdown preview on tab switch
        if (isMarkdownPreviewing) hideMarkdownPreview()
        if (isHtmlPreviewing) hideHtmlPreview()
        // Emmet for HTML/XML files
        val emmetName = target.name.lowercase()
        val isEmmetFile = emmetName.endsWith(".html") || emmetName.endsWith(".htm") ||
                emmetName.endsWith(".xml") || emmetName.endsWith(".xhtml")
        binding.editor.emmetEnabled = isEmmetFile && prefs.emmet
        binding.editor.currentFileExtension = target.name.substringAfterLast('.', "").lowercase()
        binding.editor.currentFileUri = target.uri?.toString() ?: ""

        val iconRes = FileIconProvider.getFileIcon(target.name)
        binding.statusLangIcon.setImageResource(iconRes)
        binding.statusLangIcon.colorFilter = null
        binding.statusLang.text = FileIconProvider.getLanguageName(target.name)
        binding.statusModifiedDot.visibility = if (target.isModified) View.VISIBLE else View.GONE

        // Highlight the open file in the explorer
        if (::fileAdapter.isInitialized) {
            fileAdapter.setHighlightUri(target.uri)
        }

        updateToolbarForTab()
        renderTabs()
        scrollToActiveTab()
        updateRunButtonVisibility()
        updateFooterButtonsState()
        breadcrumbSymbolsDirty = true
        lastBreadcrumbLine = -1
        refreshBreadcrumbs()
        scheduleGitDiff()
    }

    /** HEAD vs editor text ka diff debounce karke compute karo (gutter indicators). */
    /** Save ke baad background me history snapshot lo. */
    private fun recordLocalHistory(textToSave: String) {
        val uriKey = openTabs.getOrNull(activeTabIndex)?.uri?.toString() ?: return
        lifecycleScope.launch { LocalHistory.record(this@MainActivity, uriKey, textToSave) }
    }

    private fun formatHistoryLabel(v: LocalHistory.Version): String {
        val sdf = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        val size = if (v.size < 1024) "${v.size} B" else "${v.size / 1024} KB"
        return "${sdf.format(Date(v.time))}  •  $size"
    }

    /** Palette command: is file ke save-history versions dikhao. */
    private fun showLocalHistoryDialog() {
        val tab = openTabs.getOrNull(activeTabIndex)
        val uriKey = tab?.uri?.toString()
        if (uriKey == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val versions = LocalHistory.versions(this@MainActivity, uriKey)
            if (versions.isEmpty()) {
                Toast.makeText(this@MainActivity, "No history yet — save the file first", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = versions.map { formatHistoryLabel(it) }.toTypedArray()
            val listDialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Local History")
                .setItems(labels) { _, which -> showHistoryPreviewDialog(versions[which]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            listDialog.listView?.setOnItemLongClickListener { _, _, which, _ ->
                val v = versions[which]
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Delete this version?")
                    .setMessage(formatHistoryLabel(v))
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            LocalHistory.delete(v)
                            listDialog.dismiss()
                            showLocalHistoryDialog()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
        }
    }

    /** Ek version ka preview + Restore/Delete. */
    private fun showHistoryPreviewDialog(version: LocalHistory.Version) {
        lifecycleScope.launch {
            val content = LocalHistory.read(version) ?: return@launch
            val tv = TextView(this@MainActivity).apply {
                text = content
                typeface = Typeface.MONOSPACE
                textSize = 12f
                maxHeight = (resources.displayMetrics.heightPixels * 0.55).toInt()
                setPadding(28, 20, 28, 20)
                isVerticalScrollBarEnabled = true
            }
            val scroll = ScrollView(this@MainActivity).apply { addView(tv) }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(formatHistoryLabel(version))
                .setView(scroll)
                .setPositiveButton("Restore") { _, _ -> confirmRestoreHistory(content) }
                .setNeutralButton("Diff vs current") { _, _ -> showHistoryDiff(content, formatHistoryLabel(version)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** History version vs current editor text ka diff dikhao. */
    private fun showHistoryDiff(versionContent: String, versionLabel: String) {
        lifecycleScope.launch {
            val current = binding.editor.text?.toString() ?: return@launch
            val diff = withContext(Dispatchers.IO) {
                GitManager.diffTexts(versionContent, current)
            }
            if (diff.isBlank()) {
                Toast.makeText(this@MainActivity, "No differences", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val tv = TextView(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
                setPadding(24, 16, 24, 16)
                this.text = colorizeDiff(diff)
            }
            val scroll = ScrollView(this@MainActivity).apply { addView(tv) }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Diff vs $versionLabel")
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun confirmRestoreHistory(content: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore this version?")
            .setMessage("Current text will be replaced. You can Undo right after.")
            .setPositiveButton("Restore") { _, _ -> restoreHistoryVersion(content) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** History version wapas lao — ek hi undo step me. */
    private fun restoreHistoryVersion(content: String) {
        if (binding.editor.hasCollapsedFolds()) binding.editor.expandAllFoldsNow()
        val ed = binding.editor.text ?: return
        ed.replace(0, ed.length, content)
        if (activeTabIndex in openTabs.indices) {
            openTabs[activeTabIndex].content = content
            openTabs[activeTabIndex].isModified = true
            binding.statusModifiedDot.visibility = View.VISIBLE
            renderTabs()
            updateFooterButtonsState()
        }
    }

    /** Box (column) selection mode on/off — drag se rectangle select, har line par caret. */
    private fun toggleBoxSelect() {
        val ed = binding.editor
        if (ed.boxSelectMode) {
            ed.boxSelectMode = false
            Toast.makeText(this, "Box select off", Toast.LENGTH_SHORT).show()
        } else {
            if (ed.hasCollapsedFolds()) ed.expandAllFoldsNow()
            ed.boxSelectMode = true
            Toast.makeText(this, "Box select: text par drag karo", Toast.LENGTH_SHORT).show()
        }
    }

    /** Project TODO explorer: folder me TODO/FIXME/HACK/XXX/BUG/NOTE dhoondho. */
    private fun showTodoExplorer() {
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        data class TodoItem(
            val name: String, val relPath: String, val lineNo: Int,
            val marker: String, val snippet: String, val doc: DocumentFile
        )
        val density = resources.displayMetrics.density
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(this).apply {
            text = "Project TODOs"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val emptyText = TextView(this).apply {
            text = "Scanning..."
            textSize = 14f
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
            )
        }
        container.addView(titleView)
        container.addView(emptyText)
        container.addView(list)
        dialog.setContentView(container)

        val markerColor = mapOf(
            "TODO" to "#8B7CF6", "NOTE" to "#4CAF50",
            "FIXME" to "#F44336", "HACK" to "#FF9800",
            "XXX" to "#F44336", "BUG" to "#F44336"
        )
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<TodoItem> = emptyList()
            fun submit(newItems: List<TodoItem>) {
                items = newItems
                notifyDataSetChanged()
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = items[position]
                val fileView = holder.itemView.findViewById<TextView>(R.id.srFile)
                fileView.text = e.marker
                fileView.setTextColor(Color.parseColor(markerColor[e.marker] ?: "#8B7CF6"))
                holder.itemView.findViewById<TextView>(R.id.srDetail).text = "${e.relPath} · line ${e.lineNo}"
                val snippetView = holder.itemView.findViewById<TextView>(R.id.srSnippet)
                snippetView.text = e.snippet
                snippetView.visibility = if (e.snippet.isEmpty()) View.GONE else View.VISIBLE
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    binding.drawer.closeDrawer(GravityCompat.START)
                    openFileInTab(e.doc, e.lineNo)
                }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        dialog.show()

        // Active tab ka unsaved text Main par pakdo (layout IO par mat chhuo).
        val activeTab = openTabs.getOrNull(activeTabIndex)
        val activeBase: String? = activeTab?.let { binding.editor.getUnfoldedText() }
        lifecycleScope.launch(Dispatchers.IO) {
            val rx = Regex("\\b(TODO|FIXME|HACK|XXX|BUG|NOTE)\\b")
            val results = mutableListOf<TodoItem>()
            fun walk(dir: DocumentFile, prefix: String) {
                if (results.size >= 300) return
                val children = try { dir.listFiles() } catch (_: Exception) { return }
                for (child in children) {
                    if (results.size >= 300) return
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    try {
                        if (child.isDirectory) {
                            walk(child, "$prefix$name/")
                        } else if (child.isFile) {
                            if (child.length() > 512 * 1024) continue
                            val bytes = try {
                                contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                            } catch (_: Exception) { null } ?: continue
                            if (bytes.size > 512 * 1024) continue
                            var bi = 0
                            var isBinary = false
                            while (bi < bytes.size && bi < 8192) {
                                if (bytes[bi] == 0.toByte()) { isBinary = true; break }
                                bi++
                            }
                            if (isBinary) continue
                            val tab = openTabs.find { it.uri == child.uri }
                            val ftext = if (tab != null && tab == activeTab && activeBase != null) {
                                activeBase
                            } else {
                                bytes.toString(Charsets.UTF_8)
                            }
                            var perFile = 0
                            ftext.lineSequence().forEachIndexed { idx, line ->
                                if (perFile >= 10 || results.size >= 300) return@forEachIndexed
                                val m = rx.find(line) ?: return@forEachIndexed
                                perFile++
                                val marker = m.value
                                val snippet = line.trim().take(140)
                                results.add(TodoItem(name, "$prefix$name", idx + 1, marker, snippet, child))
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            try { walk(folder, "") } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                adapter.submit(results.toList())
                emptyText.text = if (results.isEmpty()) "No TODOs found" else "${results.size} items"
                emptyText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Relative time: "2h ago" jaisa. */
    private fun relativeTimeAgo(t: Long): String {
        val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            s < 86400 * 30 -> "${s / 86400}d ago"
            else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t))
        }
    }

    /**
     * INV-156 Navigate Back/Forward: har line-jump se pehle current caret
     * yaad rehta hai — Go Back pichhle jump point par, Go Forward aage.
     */
    private data class NavPoint(val uri: android.net.Uri, val offset: Int, val line: Int)
    private val navBackStack = ArrayDeque<NavPoint>()
    private val navFwdStack = ArrayDeque<NavPoint>()
    private var suppressNavPush = false

    private fun currentNavPoint(): NavPoint? {
        val tab = openTabs.getOrNull(activeTabIndex) ?: return null
        val uri = tab.uri ?: return null
        val ed = binding.editor
        val off = ed.selectionStart.coerceIn(0, ed.length())
        val text = ed.text?.toString() ?: ""
        val line = text.substring(0, off).count { it == '\n' } + 1
        return NavPoint(uri, off, line)
    }

    /** Kisi jump se pehle current caret ko back-stack me dalo. */
    private fun pushNavPoint() {
        if (suppressNavPush) return
        val p = currentNavPoint() ?: return
        val last = navBackStack.lastOrNull()
        if (last != null && last.uri == p.uri && kotlin.math.abs(last.offset - p.offset) < 4) return
        navBackStack.addLast(p)
        if (navBackStack.size > 50) navBackStack.removeFirst()
        navFwdStack.clear()
    }

    private fun jumpToNavPoint(p: NavPoint) {
        binding.drawer.closeDrawer(GravityCompat.START)
        val curTab = openTabs.getOrNull(activeTabIndex)
        if (curTab != null && curTab.uri == p.uri) {
            binding.editor.gotoOffset(p.offset)
        } else {
            val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, p.uri)
            if (doc == null) {
                Toast.makeText(this, "File not accessible", Toast.LENGTH_SHORT).show()
                return
            }
            suppressNavPush = true
            try {
                openFileInTab(doc, p.line)
            } finally {
                suppressNavPush = false
            }
        }
    }

    private fun goNavBack() {
        val target = navBackStack.removeLastOrNull()
        if (target == null) {
            Toast.makeText(this, "Nothing to go back to", Toast.LENGTH_SHORT).show()
            return
        }
        currentNavPoint()?.let {
            navFwdStack.addLast(it)
            if (navFwdStack.size > 50) navFwdStack.removeFirst()
        }
        jumpToNavPoint(target)
    }

    private fun goNavForward() {
        val target = navFwdStack.removeLastOrNull()
        if (target == null) {
            Toast.makeText(this, "Nothing to go forward to", Toast.LENGTH_SHORT).show()
            return
        }
        currentNavPoint()?.let {
            navBackStack.addLast(it)
            if (navBackStack.size > 50) navBackStack.removeFirst()
        }
        jumpToNavPoint(target)
    }

    /** INV-155 Git: caret wali line ka blame dialog (HEAD version par). */
    private fun showBlameForCaretLine() {
        val tab = openTabs.getOrNull(activeTabIndex)
        val uri = tab?.uri
        if (tab == null || uri == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val file = GitManager.resolveDocToFile(uri)
        val root = file?.let { findRepoRootFor(it) }
        if (file == null || root == null) {
            Toast.makeText(this, "Not in a git repo", Toast.LENGTH_SHORT).show()
            return
        }
        val rel = root.toURI().relativize(file.toURI()).path
        if (rel.isEmpty()) {
            Toast.makeText(this, "Not in a git repo", Toast.LENGTH_SHORT).show()
            return
        }
        val ed = binding.editor
        val caret = ed.selectionStart.coerceIn(0, ed.length())
        val text = ed.text?.toString() ?: ""
        val lineNo = text.substring(0, caret).count { it == '\n' } + 1
        val dirty = ed.isModifiedComparedTo(tab.diskContent)
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                GitManager.blameLine(root, rel, lineNo - 1).getOrNull()
            }
            if (info == null) {
                Toast.makeText(
                    this@MainActivity,
                    "No blame info (uncommitted file?)",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val dirtyNote =
                if (dirty) "\n\nBlame is for the saved version — you have unsaved changes." else ""
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Blame · line $lineNo")
                .setMessage(
                    "${info.shortHash} · ${info.author} · ${relativeTimeAgo(info.timeMillis)}\n\n" +
                        info.message + dirtyNote
                )
                .setPositiveButton(R.string.close, null)
                .setNeutralButton("Copy Hash") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("hash", info.shortHash))
                    Toast.makeText(this@MainActivity, "Hash copied", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    /** Git history viewer: commits ki list + filter + detail. */
    private fun showGitHistory() {
        val repoDir = getRepoDirForGit()
        if (repoDir == null) {
            Toast.makeText(this, "Not a git repo", Toast.LENGTH_SHORT).show()
            return
        }
        data class GHItem(val info: GitManager.CommitInfo)
        val density = resources.displayMetrics.density
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(this).apply {
            text = "Git History"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val filterInput = EditText(this).apply {
            hint = "Filter by message or author..."
            isSingleLine = true
        }
        val emptyText = TextView(this).apply {
            text = "Loading..."
            textSize = 14f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
            )
        }
        container.addView(titleView)
        container.addView(filterInput)
        container.addView(emptyText)
        container.addView(list)
        dialog.setContentView(container)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<GitManager.CommitInfo> = emptyList()
            fun submit(newItems: List<GitManager.CommitInfo>) {
                items = newItems
                notifyDataSetChanged()
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = items[position]
                val hashView = holder.itemView.findViewById<TextView>(R.id.srFile)
                hashView.text = e.shortHash
                hashView.setTextColor(Color.parseColor("#8B7CF6"))
                holder.itemView.findViewById<TextView>(R.id.srDetail).text =
                    "${e.author} · ${relativeTimeAgo(e.time)}"
                val msgView = holder.itemView.findViewById<TextView>(R.id.srSnippet)
                msgView.text = e.message.lineSequence().firstOrNull().orEmpty().take(140)
                msgView.visibility = View.VISIBLE
                holder.itemView.setOnClickListener { showCommitDetail(e) }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        dialog.show()

        var all: List<GitManager.CommitInfo> = emptyList()
        fun applyFilter() {
            val q = filterInput.text.toString().trim().lowercase()
            val filtered = if (q.isEmpty()) all else all.filter {
                it.message.lowercase().contains(q) || it.author.lowercase().contains(q) ||
                    it.hash.lowercase().startsWith(q)
            }
            adapter.submit(filtered)
            emptyText.text = if (filtered.isEmpty()) "No commits found" else "${filtered.size} commits"
            emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
        filterInput.doOnTextChanged { _, _, _, _ -> applyFilter() }
        lifecycleScope.launch {
            val res = GitManager.logEntries(repoDir, 100)
            res.onSuccess {
                all = it
                applyFilter()
            }.onFailure {
                emptyText.text = "Could not load history"
            }
        }
    }

    /** Ek commit ka detail: poora message + hash copy. */
    private fun showCommitDetail(info: GitManager.CommitInfo) {
        val msgView = TextView(this).apply {
            text = info.message.ifEmpty { "(empty message)" }
            textSize = 14f
            setPadding(32, 24, 32, 8)
        }
        val metaView = TextView(this).apply {
            text = "${info.author}\n${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(info.time))}\n${info.hash}"
            textSize = 12f
            setPadding(32, 8, 32, 24)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(msgView)
            addView(metaView)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(info.shortHash)
            .setView(container)
            .setPositiveButton("Copy Hash") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("commit", info.hash))
                Toast.makeText(this, "Hash copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Actions") { _, _ ->
                val repoDir = getRepoDirForGit()
                if (repoDir == null) {
                    Toast.makeText(this, "Not a git repo", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                val actions = arrayOf("Cherry-pick", "Revert")
                MaterialAlertDialogBuilder(this)
                    .setTitle(info.shortHash)
                    .setItems(actions) { _, which ->
                        val actionName = actions[which]
                        val confirmMsg = if (which == 0)
                            "Ye commit current branch par apply hoga."
                        else
                            "Is commit ko ulta karne wala naya commit banega."
                        MaterialAlertDialogBuilder(this)
                            .setTitle(actionName + " " + info.shortHash + "?")
                            .setMessage(confirmMsg)
                            .setPositiveButton(actionName) { _, _ ->
                                lifecycleScope.launch {
                                    val res = if (which == 0)
                                        GitManager.cherryPick(repoDir, info.hash)
                                    else
                                        GitManager.revertCommit(repoDir, info.hash)
                                    res.onSuccess {
                                        Toast.makeText(this@MainActivity,
                                            actionName + " ho gaya", Toast.LENGTH_SHORT).show()
                                    }.onFailure { e ->
                                        Toast.makeText(this@MainActivity,
                                            "Fail: " + e.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Git stash: current changes ko message ke saath stash karo. */
    private fun showStashSaveDialog() {
        val repoDir = getRepoDirForGit()
        if (repoDir == null) {
            Toast.makeText(this, "Not a git repo", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "WIP"
            isSingleLine = true
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Stash Changes")
            .setView(input)
            .setPositiveButton("Stash") { _, _ ->
                val msg = input.text.toString().trim().ifEmpty { "WIP" }
                lifecycleScope.launch {
                    GitManager.stashSave(repoDir, msg)
                        .onSuccess {
                            Toast.makeText(this@MainActivity, "Stashed ($it)", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { e ->
                            Toast.makeText(
                                this@MainActivity,
                                e.message ?: "Stash failed",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Stash list: tap -> Apply / Drop. */
    private fun showStashList() {
        val repoDir = getRepoDirForGit()
        if (repoDir == null) {
            Toast.makeText(this, "Not a git repo", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(this).apply {
            text = "Stashes"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val emptyText = TextView(this).apply {
            text = "Loading..."
            textSize = 14f
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
            )
        }
        container.addView(titleView)
        container.addView(emptyText)
        container.addView(list)
        dialog.setContentView(container)

        var stashSubmitter: ((List<GitManager.StashInfo>) -> Unit)? = null
        fun loadStashes() {
            lifecycleScope.launch {
                GitManager.stashList(repoDir)
                    .onSuccess {
                        stashSubmitter?.invoke(it)
                        emptyText.text = if (it.isEmpty()) "No stashes" else "${it.size} stashes"
                        emptyText.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                    }
                    .onFailure { emptyText.text = "Could not load stashes" }
            }
        }
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<GitManager.StashInfo> = emptyList()
            init { stashSubmitter = { submit(it) } }
            fun submit(newItems: List<GitManager.StashInfo>) {
                items = newItems
                notifyDataSetChanged()
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = items[position]
                val idxView = holder.itemView.findViewById<TextView>(R.id.srFile)
                idxView.text = "stash@{${e.index}}"
                idxView.setTextColor(Color.parseColor("#8B7CF6"))
                holder.itemView.findViewById<TextView>(R.id.srDetail).text = relativeTimeAgo(e.time)
                val msgView = holder.itemView.findViewById<TextView>(R.id.srSnippet)
                msgView.text = e.message.lineSequence().firstOrNull().orEmpty().take(140)
                msgView.visibility = View.VISIBLE
                holder.itemView.setOnClickListener { showStashActions(repoDir, e) { loadStashes() } }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        dialog.show()
        loadStashes()
    }

    /** Ek stash par Apply / Drop. */
    private fun showStashActions(
        repoDir: java.io.File,
        stash: GitManager.StashInfo,
        onDone: () -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle("stash@{${stash.index}}")
            .setMessage(stash.message.lineSequence().firstOrNull().orEmpty().take(200))
            .setPositiveButton("Apply") { _, _ ->
                lifecycleScope.launch {
                    GitManager.stashApply(repoDir, stash.index)
                        .onSuccess {
                            Toast.makeText(this@MainActivity, "Stash applied", Toast.LENGTH_SHORT).show()
                            onDone()
                        }
                        .onFailure { e ->
                            Toast.makeText(this@MainActivity, e.message ?: "Apply failed", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNeutralButton("Drop") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Drop stash@{${stash.index}}?")
                    .setMessage("Ye wapas nahi aayega.")
                    .setPositiveButton("Drop") { _, _ ->
                        lifecycleScope.launch {
                            GitManager.stashDrop(repoDir, stash.index)
                                .onSuccess {
                                    Toast.makeText(this@MainActivity, "Stash dropped", Toast.LENGTH_SHORT).show()
                                    onDone()
                                }
                                .onFailure { e ->
                                    Toast.makeText(this@MainActivity, e.message ?: "Drop failed", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Active tab ko pin/unpin karo — pinned tabs aage aate hain, bulk-close se safe. */
    private fun togglePinTab() {
        val tab = openTabs.getOrNull(activeTabIndex) ?: run {
            Toast.makeText(this, "No tab open", Toast.LENGTH_SHORT).show()
            return
        }
        tab.isPinned = !tab.isPinned
        if (tab.isPinned) {
            openTabs.removeAt(activeTabIndex)
            val insertAt = openTabs.indexOfFirst { !it.isPinned }
                .let { if (it == -1) openTabs.size else it }
            openTabs.add(insertAt, tab)
            activeTabIndex = insertAt
        }
        renderTabs()
        scrollToActiveTab()
        saveSessionState()
        Toast.makeText(
            this,
            if (tab.isPinned) "Tab pinned" else "Tab unpinned",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Kotlin/Java: unused imports hatao + sort (ek Undo). */
    private fun organizeImports() {
        val tab = openTabs.getOrNull(activeTabIndex) ?: return
        val ext = tab.name.substringAfterLast('.', "").lowercase()
        if (ext != "kt" && ext != "kts" && ext != "java") {
            Toast.makeText(this, "Organize Imports: Kotlin/Java files only", Toast.LENGTH_SHORT).show()
            return
        }
        when (val removed = binding.editor.organizeImports()) {
            -1 -> Toast.makeText(this, "Could not organize imports", Toast.LENGTH_SHORT).show()
            0 -> Toast.makeText(this, "Imports already clean", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(this, "Removed $removed unused import(s)", Toast.LENGTH_SHORT).show()
        }
    }

    /** Folder khulne par recents me sabse upar rakho (10 tak). */
    private fun pushRecentFolder(uriStr: String) {
        val list = prefs.recentFolders.toMutableList()
        list.remove(uriStr)
        list.add(0, uriStr)
        prefs.recentFolders = list.take(10)
    }

    /** Recent folders ki list — tap karne par turant switch. */
    private fun showRecentFolders() {
        val recents = prefs.recentFolders
        if (recents.isEmpty()) {
            Toast.makeText(this, "No recent folders", Toast.LENGTH_SHORT).show()
            return
        }
        val names = recents.map { uriStr ->
            try {
                DocumentFile.fromTreeUri(this, Uri.parse(uriStr))?.name ?: uriStr
            } catch (_: Exception) {
                uriStr
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Recent Folders")
            .setItems(names) { _, which -> openRecentFolder(recents[which]) }
            .setNeutralButton("Clear") { _, _ -> prefs.recentFolders = emptyList() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Recent folder kholo (permission pehle se persisted hai). */
    private fun openRecentFolder(uriStr: String) {
        val uri = Uri.parse(uriStr)
        val root = try {
            DocumentFile.fromTreeUri(this, uri)
        } catch (_: Exception) {
            null
        }
        if (root == null || !root.exists()) {
            Toast.makeText(this, "Folder not accessible", Toast.LENGTH_SHORT).show()
            prefs.recentFolders = prefs.recentFolders.filter { it != uriStr }
            return
        }
        prefs.rootFolderUri = uriStr
        initFileManager(uri)
        saveSessionState()
    }

    /** Current file ke saare changes (saved + unsaved) HEAD se discard karo. */
    private fun discardCurrentFileChanges() {
        val tab = openTabs.getOrNull(activeTabIndex)
        val uri = tab?.uri
        if (tab == null || uri == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val file = GitManager.resolveDocToFile(uri)
            val root = if (file != null && file.isFile) findRepoRootFor(file) else null
            if (root == null || file == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Not in a git repo", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val rel = root.toURI().relativize(file.toURI()).path
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Discard changes?")
                    .setMessage("${tab.name} ko HEAD par wapas lao? Saved + unsaved changes jayenge.")
                    .setPositiveButton("Discard") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val res = GitManager.discardFileChanges(root, rel)
                            withContext(Dispatchers.Main) {
                                res.onSuccess {
                                    reloadTabFromDisk(tab)
                                    Toast.makeText(this@MainActivity, "Changes discarded", Toast.LENGTH_SHORT).show()
                                }.onFailure { e ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        e.message ?: "Discard failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Tab ko disk se dobara padho (discard ke baad). Undo se wapas aa sakta hai. */
    private fun reloadTabFromDisk(tab: EditorTab) {
        val uri = tab.uri ?: return
        val content = try {
            contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return
        } catch (_: Exception) {
            return
        }
        if (binding.editor.hasCollapsedFolds()) binding.editor.expandAllFoldsNow()
        tab.content = content
        tab.diskContent = content
        tab.isModified = false
        binding.editor.text?.replace(0, binding.editor.length(), content)
        binding.statusModifiedDot.visibility = View.GONE
        renderTabs()
        updateFooterButtonsState()
        scheduleGitDiff()
    }

    /** Caret par R.type.name ho to us resource file par jump karo (Android Studio jaisa). */
    private fun jumpToAndroidResource() {
        val tab = openTabs.getOrNull(activeTabIndex)
        val uri = tab?.uri
        if (tab == null || uri == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val expr = binding.editor.resourceExprAtCaret()
        if (expr == null) {
            Toast.makeText(this, "No R.* reference at cursor", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val dest = findResourceDestination(uri, expr.first, expr.second)
            withContext(Dispatchers.Main) {
                if (dest == null) {
                    Toast.makeText(this@MainActivity, "Resource not found: R.${expr.first}.${expr.second}", Toast.LENGTH_SHORT).show()
                } else {
                    openFileInTab(DocumentFile.fromFile(dest.first), dest.second)
                }
            }
        }
    }

    /** R.type.name ka (file, line) dhoondho. Sirf primary storage par kaam karta hai. */
    private fun findResourceDestination(currentUri: Uri, type: String, name: String): Pair<File, Int>? {
        return try {
            val file = GitManager.resolveDocToFile(currentUri) ?: return null
            var dir: File? = file.parentFile
            var resDir: File? = null
            var guard = 0
            while (dir != null && guard++ < 12) {
                val res = File(dir, "res")
                if (res.isDirectory) { resDir = res; break }
                dir = dir.parentFile
            }
            val res = resDir ?: return null
            fun firstFile(sub: String, fname: String): File? {
                val f = File(res, "$sub/$fname")
                return if (f.isFile) f else null
            }
            fun firstFileAnyExt(subPrefix: String, fname: String): File? {
                val subs = res.listFiles()?.filter { it.isDirectory && it.name.startsWith(subPrefix) } ?: return null
                for (sub in subs) {
                    val hit = sub.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == fname }
                    if (hit != null) return hit
                }
                return null
            }
            fun valuesLine(valuesFile: String, tag: String): Pair<File, Int>? {
                val f = File(res, "values/$valuesFile")
                if (!f.isFile) return null
                val needle = "name=\"$name\""
                var lineNo = 0
                f.bufferedReader().useLines { seq ->
                    for ((idx, line) in seq.withIndex()) {
                        if (line.contains("<$tag") && line.contains(needle)) {
                            lineNo = idx + 1
                            break
                        }
                    }
                }
                return Pair(f, lineNo)
            }
            when (type) {
                "layout" -> firstFile("layout", "$name.xml")?.let { Pair(it, 0) }
                "menu" -> firstFile("menu", "$name.xml")?.let { Pair(it, 0) }
                "anim" -> firstFile("anim", "$name.xml")?.let { Pair(it, 0) }
                "animator" -> firstFile("animator", "$name.xml")?.let { Pair(it, 0) }
                "xml" -> firstFile("xml", "$name.xml")?.let { Pair(it, 0) }
                "raw" -> firstFileAnyExt("raw", name)?.let { Pair(it, 0) }
                "drawable" -> firstFileAnyExt("drawable", name)?.let { Pair(it, 0) }
                "mipmap" -> firstFileAnyExt("mipmap", name)?.let { Pair(it, 0) }
                "font" -> firstFileAnyExt("font", name)?.let { Pair(it, 0) }
                "color" -> valuesLine("colors.xml", "color")
                    ?: valuesLine("strings.xml", "color")
                "string" -> valuesLine("strings.xml", "string")
                    ?: valuesLine("donottranslate.xml", "string")
                "id" -> {
                    val needle = "@+id/$name\""
                    val layoutDir = File(res, "layout")
                    val files = layoutDir.listFiles()?.filter { it.isFile && it.extension == "xml" }?.take(60)
                        ?: return null
                    for (lf in files) {
                        var lineNo = 0
                        var found = false
                        lf.bufferedReader().useLines { seq ->
                            for ((idx, line) in seq.withIndex()) {
                                if (line.contains(needle)) { lineNo = idx + 1; found = true; break }
                            }
                        }
                        if (found) return Pair(lf, lineNo)
                    }
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Caret wale symbol ke saare references poore project me dhoondho (whole-word). */
    private fun findReferencesInProject() {
        val symbol = binding.editor.wordAtCaret()?.takeIf { it.isNotBlank() }
        if (symbol == null) {
            Toast.makeText(this, "No symbol at cursor", Toast.LENGTH_SHORT).show()
            return
        }
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        data class RefItem(
            val name: String, val relPath: String, val lineNo: Int,
            val snippet: String, val doc: DocumentFile
        )
        val density = resources.displayMetrics.density
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(this).apply {
            text = "References: $symbol"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val emptyText = TextView(this).apply {
            text = "Searching..."
            textSize = 14f
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
            )
        }
        container.addView(titleView)
        container.addView(emptyText)
        container.addView(list)
        dialog.setContentView(container)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<RefItem> = emptyList()
            fun submit(newItems: List<RefItem>) {
                items = newItems
                notifyDataSetChanged()
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = items[position]
                holder.itemView.findViewById<TextView>(R.id.srFile).text = e.name
                holder.itemView.findViewById<TextView>(R.id.srDetail).text = "${e.relPath} · line ${e.lineNo}"
                val snippetView = holder.itemView.findViewById<TextView>(R.id.srSnippet)
                snippetView.text = e.snippet
                snippetView.visibility = if (e.snippet.isEmpty()) View.GONE else View.VISIBLE
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    binding.drawer.closeDrawer(GravityCompat.START)
                    openFileInTab(e.doc, e.lineNo)
                }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        dialog.show()

        val activeTab = openTabs.getOrNull(activeTabIndex)
        val activeBase: String? = activeTab?.let { binding.editor.getUnfoldedText() }
        lifecycleScope.launch(Dispatchers.IO) {
            val rx = Regex("\\b" + Regex.escape(symbol) + "\\b")
            val results = mutableListOf<RefItem>()
            fun walk(dir: DocumentFile, prefix: String) {
                if (results.size >= 300) return
                val children = try { dir.listFiles() } catch (_: Exception) { return }
                for (child in children) {
                    if (results.size >= 300) return
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    try {
                        if (child.isDirectory) {
                            walk(child, "$prefix$name/")
                        } else if (child.isFile) {
                            if (child.length() > 512 * 1024) continue
                            val bytes = try {
                                contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                            } catch (_: Exception) { null } ?: continue
                            if (bytes.size > 512 * 1024) continue
                            var bi = 0
                            var isBinary = false
                            while (bi < bytes.size && bi < 8192) {
                                if (bytes[bi] == 0.toByte()) { isBinary = true; break }
                                bi++
                            }
                            if (isBinary) continue
                            val tab = openTabs.find { it.uri == child.uri }
                            val ftext = if (tab != null && tab == activeTab && activeBase != null) {
                                activeBase
                            } else {
                                bytes.toString(Charsets.UTF_8)
                            }
                            var perFile = 0
                            ftext.lineSequence().forEachIndexed { idx, line ->
                                if (perFile >= 10 || results.size >= 300) return@forEachIndexed
                                if (!rx.containsMatchIn(line)) return@forEachIndexed
                                perFile++
                                results.add(RefItem(name, "$prefix$name", idx + 1, line.trim().take(140), child))
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            try { walk(folder, "") } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                adapter.submit(results.toList())
                emptyText.text = if (results.isEmpty()) "No references found" else "${results.size} references"
                emptyText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * INV-153 Go to Definition: caret wale symbol ki definition poore project me
     * dhoondho (fun/class/val/def/function patterns). 1 result = seedha jump,
     * zyada = bottom-sheet list. Current file ke results sabse upar.
     */
    private fun goToDefinition() {
        val symbol = binding.editor.wordAtCaret()?.takeIf { it.isNotBlank() }
        if (symbol == null) {
            Toast.makeText(this, "No symbol at cursor", Toast.LENGTH_SHORT).show()
            return
        }
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        val esc = Regex.escape(symbol)
        val defPatterns = listOf(
            "function" to Regex("\\b(?:fun|def|function)\\s+$esc\\b"),
            "class" to Regex("\\b(?:class|interface|object|enum|typealias)\\s+$esc\\b"),
            "property" to Regex("\\b(?:val|var|const)\\s+$esc\\b"),
            "method" to Regex("^\\s*(?!new\\s|return\\s)(?:[\\w<>\\[\\],?]+\\s+)+$esc\\s*\\(")
        )
        data class DefItem(
            val name: String, val relPath: String, val lineNo: Int,
            val kind: String, val snippet: String, val doc: androidx.documentfile.provider.DocumentFile
        )
        val density = resources.displayMetrics.density
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(this).apply {
            text = "Definition: $symbol"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val emptyText = TextView(this).apply {
            text = "Searching..."
            textSize = 14f
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
            )
        }
        container.addView(titleView)
        container.addView(emptyText)
        container.addView(list)
        dialog.setContentView(container)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<DefItem> = emptyList()
            fun submit(newItems: List<DefItem>) {
                items = newItems
                notifyDataSetChanged()
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = items[position]
                holder.itemView.findViewById<TextView>(R.id.srFile).text = e.name
                holder.itemView.findViewById<TextView>(R.id.srDetail).text =
                    "${e.relPath} · line ${e.lineNo} · ${e.kind}"
                val snippetView = holder.itemView.findViewById<TextView>(R.id.srSnippet)
                snippetView.text = e.snippet
                snippetView.visibility = if (e.snippet.isEmpty()) View.GONE else View.VISIBLE
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    binding.drawer.closeDrawer(GravityCompat.START)
                    openFileInTab(e.doc, e.lineNo)
                }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter

        val activeTab = openTabs.getOrNull(activeTabIndex)
        val activeUri = activeTab?.uri
        val activeBase: String? = activeTab?.let { binding.editor.getUnfoldedText() }
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<DefItem>()
            fun walk(dir: androidx.documentfile.provider.DocumentFile, prefix: String) {
                if (results.size >= 300) return
                val children = try { dir.listFiles() } catch (_: Exception) { return }
                for (child in children) {
                    if (results.size >= 300) return
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    try {
                        if (child.isDirectory) {
                            walk(child, "$prefix$name/")
                        } else if (child.isFile) {
                            if (child.length() > 512 * 1024) continue
                            val bytes = try {
                                contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                            } catch (_: Exception) { null } ?: continue
                            if (bytes.size > 512 * 1024) continue
                            var bi = 0
                            var isBinary = false
                            while (bi < bytes.size && bi < 8192) {
                                if (bytes[bi] == 0.toByte()) { isBinary = true; break }
                                bi++
                            }
                            if (isBinary) continue
                            val tab = openTabs.find { it.uri == child.uri }
                            val ftext = if (tab != null && tab == activeTab && activeBase != null) {
                                activeBase
                            } else {
                                bytes.toString(Charsets.UTF_8)
                            }
                            // Ek file me pehli definition hi kaafi hai.
                            ftext.lineSequence().forEachIndexed { idx, line ->
                                for ((kind, rx) in defPatterns) {
                                    if (rx.containsMatchIn(line)) {
                                        results.add(
                                            DefItem(
                                                name, "$prefix$name", idx + 1, kind,
                                                line.trim().take(140), child
                                            )
                                        )
                                        return@forEachIndexed
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            try { walk(folder, "") } catch (_: Exception) { }
            // Current file ke results pehle.
            val sorted = results.sortedBy { if (it.doc.uri == activeUri) 0 else 1 }
            withContext(Dispatchers.Main) {
                when {
                    sorted.isEmpty() -> {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "No definition found", Toast.LENGTH_SHORT).show()
                    }
                    sorted.size == 1 -> {
                        dialog.dismiss()
                        val e = sorted[0]
                        binding.drawer.closeDrawer(GravityCompat.START)
                        openFileInTab(e.doc, e.lineNo)
                    }
                    else -> {
                        adapter.submit(sorted)
                        emptyText.visibility = View.GONE
                        if (!dialog.isShowing) dialog.show()
                    }
                }
            }
        }
        // Sheet tabhi dikhao jab 1 se zyada results hon — show() upar deferred hai.
    }

    /** Current file ka unified diff (HEAD vs text) rang-biranga dikhao. */
    private fun showFileDiff() {
        val tab = openTabs.getOrNull(activeTabIndex)
        val uri = tab?.uri
        if (tab == null || uri == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val text = binding.editor.text?.toString() ?: return@launch
            val diff = withContext(Dispatchers.IO) {
                val file = GitManager.resolveDocToFile(uri) ?: return@withContext null
                if (!file.isFile) return@withContext null
                val root = findRepoRootFor(file) ?: return@withContext null
                val rel = root.toURI().relativize(file.toURI()).path
                if (rel.isEmpty()) return@withContext null
                GitManager.unifiedDiff(root, rel, text).getOrNull()
            }
            if (diff.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, "No changes vs HEAD", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val tv = TextView(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
                setPadding(24, 16, 24, 16)
                this.text = colorizeDiff(diff)
            }
            val scroll = ScrollView(this@MainActivity).apply { addView(tv) }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Diff: ${tab.name}")
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    /** Unified diff lines ko rang do: + green, - red, @@ violet. */
    private fun colorizeDiff(diff: String): SpannableString {
        val sp = SpannableString(diff)
        val green = Color.parseColor("#4CAF50")
        val red = Color.parseColor("#F44336")
        val violet = Color.parseColor("#8B7CF6")
        var pos = 0
        for (line in diff.split('\n')) {
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> green
                line.startsWith("-") && !line.startsWith("---") -> red
                line.startsWith("@@") -> violet
                else -> null
            }
            if (color != null && line.isNotEmpty()) {
                sp.setSpan(
                    ForegroundColorSpan(color), pos, pos + line.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            pos += line.length + 1
        }
        return sp
    }

    /** Current file ko chhune wale commits — tap par us commit ka diff. */
    private fun showGitFileHistory() {
        val tab = openTabs.getOrNull(activeTabIndex)
        val uri = tab?.uri
        if (tab == null || uri == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val file = GitManager.resolveDocToFile(uri)
            val root = if (file != null && file.isFile) findRepoRootFor(file) else null
            if (root == null || file == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Not in a git repo", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val rel = root.toURI().relativize(file.toURI()).path
            val commits = GitManager.fileLog(root, rel, 50).getOrElse { emptyList() }
            withContext(Dispatchers.Main) {
                if (commits.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No history for this file", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                showFileHistoryList(tab.name, root, rel, commits)
            }
        }
    }

    private fun showFileHistoryList(
        fileName: String,
        repoDir: File,
        rel: String,
        commits: List<GitManager.CommitInfo>
    ) {
        val density = resources.displayMetrics.density
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(this).apply {
            text = "History: $fileName"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
            )
        }
        container.addView(titleView)
        container.addView(list)
        dialog.setContentView(container)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = commits[position]
                val hashView = holder.itemView.findViewById<TextView>(R.id.srFile)
                hashView.text = e.shortHash
                hashView.setTextColor(Color.parseColor("#8B7CF6"))
                holder.itemView.findViewById<TextView>(R.id.srDetail).text =
                    "${e.author} · ${relativeTimeAgo(e.time)}"
                val msgView = holder.itemView.findViewById<TextView>(R.id.srSnippet)
                msgView.text = e.message.lineSequence().firstOrNull().orEmpty().take(140)
                msgView.visibility = View.VISIBLE
                holder.itemView.setOnClickListener {
                    showFileCommitDiff(repoDir, rel, commits, position)
                }
            }
            override fun getItemCount(): Int = commits.size
        }
        list.adapter = adapter
        dialog.show()
    }

    /** Commit ne is file me kya badla — parent version se diff. */
    private fun showFileCommitDiff(
        repoDir: File,
        rel: String,
        commits: List<GitManager.CommitInfo>,
        position: Int
    ) {
        val commit = commits[position]
        lifecycleScope.launch(Dispatchers.IO) {
            val newText = GitManager.blobTextAtCommit(repoDir, commit.hash, rel).getOrNull()
            val parentHash = commits.getOrNull(position + 1)?.hash
            val oldText = if (parentHash != null) {
                GitManager.blobTextAtCommit(repoDir, parentHash, rel).getOrNull()
            } else null
            val diff = GitManager.diffTexts(oldText ?: "", newText ?: "")
            withContext(Dispatchers.Main) {
                if (newText == null) {
                    Toast.makeText(this@MainActivity, "Could not load version", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                if (diff.isBlank()) {
                    Toast.makeText(this@MainActivity, "No content changes", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val tv = TextView(this@MainActivity).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 12f
                    setTextIsSelectable(true)
                    maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
                    setPadding(24, 16, 24, 16)
                    this.text = colorizeDiff(diff)
                }
                val scroll = ScrollView(this@MainActivity).apply { addView(tv) }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("${commit.shortHash} — ${commit.message.lineSequence().firstOrNull().orEmpty().take(50)}")
                    .setView(scroll)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }

    /** Focus mode: caret wale function/class ke siva sab dim. */
    private fun toggleFocusMode() {
        binding.editor.focusMode = !binding.editor.focusMode
        binding.editor.invalidate()
        Toast.makeText(
            this,
            if (binding.editor.focusMode) "Focus mode on" else "Focus mode off",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** res/values/strings.xml dhoondho (current file se upar walk). */
    private fun findStringsXml(): File? {
        val uri = openTabs.getOrNull(activeTabIndex)?.uri ?: return null
        val file = try { GitManager.resolveDocToFile(uri) } catch (e: Exception) { null } ?: return null
        var dir: File? = file.parentFile
        var guard = 0
        while (dir != null && guard++ < 12) {
            val f = File(dir, "res/values/strings.xml")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    private data class StringRes(val name: String, val value: String, val lineIdx: Int)

    private fun unescapeStringRes(v: String): String {
        return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("\\'", "'").replace("\\\"", "\"")
    }

    private fun escapeStringRes(v: String): String {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /** strings.xml parse karo: (lines, entries). Sirf single-line <string> entries. */
    private fun parseStringResources(file: File): Pair<MutableList<String>, List<StringRes>> {
        val raw = try { file.readText() } catch (e: Exception) { return Pair(mutableListOf(), emptyList()) }
        val nl = if (raw.contains("\r\n")) "\r\n" else "\n"
        val lines = raw.split(nl).toMutableList()
        val rx = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*)</string>")
        val entries = mutableListOf<StringRes>()
        for ((idx, line) in lines.withIndex()) {
            val m = rx.find(line) ?: continue
            entries.add(StringRes(m.groupValues[1], unescapeStringRes(m.groupValues[2]), idx))
        }
        return Pair(lines, entries)
    }

    private fun writeStringsXml(file: File, lines: List<String>) {
        val raw = try { file.readText() } catch (e: Exception) { "" }
        val nl = if (raw.contains("\r\n")) "\r\n" else "\n"
        file.writeText(lines.joinToString(nl))
        // Agar strings.xml khuli hai to tab refresh karo.
        val tab = openTabs.find {
            try { GitManager.resolveDocToFile(it.uri ?: return@find false) == file } catch (e: Exception) { false }
        }
        if (tab != null) {
            val idx = openTabs.indexOf(tab)
            if (idx == activeTabIndex) reloadTabFromDisk(tab) else {
                try {
                    val content = file.readText()
                    tab.content = content
                    tab.diskContent = content
                    tab.isModified = false
                } catch (e: Exception) { }
            }
            renderTabs()
        }
    }

    /** String resources ka bottom-sheet editor: list + add/edit/delete. */
    private fun showStringResourcesEditor() {
        lifecycleScope.launch(Dispatchers.IO) {
            val stringsFile = findStringsXml()
            withContext(Dispatchers.Main) {
                if (stringsFile == null) {
                    Toast.makeText(this@MainActivity, "strings.xml not found", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                showStringsEditorDialog(stringsFile)
            }
        }
    }

    private fun showStringsEditorDialog(stringsFile: File) {
        val density = resources.displayMetrics.density
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = "String Resources"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "+ Add"
        }
        titleRow.addView(titleView)
        titleRow.addView(addBtn)
        val emptyText = TextView(this).apply {
            textSize = 14f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
            )
        }
        container.addView(titleRow)
        container.addView(emptyText)
        container.addView(list)
        dialog.setContentView(container)

        var stringsSubmitter: ((List<StringRes>) -> Unit)? = null
        fun refreshStrings() {
            lifecycleScope.launch(Dispatchers.IO) {
                val (_, newEntries) = parseStringResources(stringsFile)
                withContext(Dispatchers.Main) {
                    stringsSubmitter?.invoke(newEntries)
                    emptyText.text = if (newEntries.isEmpty()) "No strings" else "${newEntries.size} strings"
                    emptyText.visibility = if (newEntries.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var entries: List<StringRes> = emptyList()
            init { stringsSubmitter = { entries = it; notifyDataSetChanged() } }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = entries[position]
                val nameView = holder.itemView.findViewById<TextView>(R.id.srFile)
                nameView.text = e.name
                nameView.setTextColor(Color.parseColor("#8B7CF6"))
                holder.itemView.findViewById<TextView>(R.id.srDetail).text = "line ${e.lineIdx + 1}"
                val valueView = holder.itemView.findViewById<TextView>(R.id.srSnippet)
                valueView.text = e.value
                valueView.visibility = View.VISIBLE
                holder.itemView.setOnClickListener { showEditStringDialog(stringsFile, e) { refreshStrings() } }
                holder.itemView.setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Delete \"${e.name}\"?")
                        .setPositiveButton("Delete") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val (lines, _) = parseStringResources(stringsFile)
                                if (e.lineIdx in lines.indices) {
                                    lines.removeAt(e.lineIdx)
                                    writeStringsXml(stringsFile, lines)
                                }
                                withContext(Dispatchers.Main) { refreshStrings() }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            }
            override fun getItemCount(): Int = entries.size
        }
        list.adapter = adapter

        addBtn.setOnClickListener { showAddStringDialog(stringsFile) { refreshStrings() } }
        dialog.show()
        refreshStrings()
    }

    /** Ek string ki value edit karo. */
    private fun showEditStringDialog(stringsFile: File, entry: StringRes, onDone: () -> Unit) {
        val input = EditText(this).apply {
            setText(entry.value)
            isSingleLine = false
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newValue = input.text.toString()
                lifecycleScope.launch(Dispatchers.IO) {
                    val (lines, _) = parseStringResources(stringsFile)
                    if (entry.lineIdx in lines.indices) {
                        val rx = Regex("(<string\\s+name=\"" + Regex.escape(entry.name) + "\"[^>]*>)(.*)(</string>)")
                        val m = rx.find(lines[entry.lineIdx])
                        if (m != null) {
                            lines[entry.lineIdx] = m.groupValues[1] + escapeStringRes(newValue) + m.groupValues[3]
                            writeStringsXml(stringsFile, lines)
                        }
                    }
                    withContext(Dispatchers.Main) { onDone() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Nayi string add karo. */
    private fun showAddStringDialog(stringsFile: File, onDone: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(this).apply { hint = "name (e.g. app_name)" ; isSingleLine = true }
        val valueInput = EditText(this).apply { hint = "value" ; isSingleLine = false ; minLines = 2 }
        container.addView(nameInput)
        container.addView(valueInput)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add String")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val value = valueInput.text.toString()
                if (!Regex("^[a-zA-Z_][a-zA-Z0-9_]*$").matches(name)) {
                    Toast.makeText(this, "Invalid name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val (lines, entries) = parseStringResources(stringsFile)
                    if (entries.any { it.name == name }) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Name already exists", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val newLine = "    <string name=\"$name\">${escapeStringRes(value)}</string>"
                    val idx = lines.indexOfLast { it.contains("</resources>") }
                    if (idx >= 0) lines.add(idx, newLine) else lines.add(newLine)
                    writeStringsXml(stringsFile, lines)
                    withContext(Dispatchers.Main) { onDone() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private data class ViewNode(val tag: String, val label: String, val lineNo: Int, val depth: Int)

    /** XML layout ka view tree parse karo (tag + id + depth + line). */
    private fun parseLayoutOutline(text: String): List<ViewNode> {
        val nodes = mutableListOf<ViewNode>()
        val tagRx = Regex("<(/?)([A-Za-z0-9_.]+)([^>]*?)(/?)>")
        val idRx = Regex("android:id=\"@\\+?id/([^\"]+)\"")
        val textRx = Regex("android:text=\"([^\"]+)\"")
        var depth = 0
        for ((idx, line) in text.split('\n').withIndex()) {
            for (m in tagRx.findAll(line)) {
                val closing = m.groupValues[1] == "/"
                val tag = m.groupValues[2]
                if (tag.startsWith("?") || tag.startsWith("!")) continue
                val attrs = m.groupValues[3]
                val selfClose = m.groupValues[4] == "/"
                if (closing) {
                    depth = (depth - 1).coerceAtLeast(0)
                } else {
                    val id = idRx.find(attrs)?.groupValues?.get(1)
                    val txt = textRx.find(attrs)?.groupValues?.get(1)?.take(24)
                    val label = when {
                        id != null -> "@+id/$id"
                        txt != null -> "\"$txt\""
                        else -> ""
                    }
                    val simpleTag = tag.substringAfterLast('.')
                    nodes.add(ViewNode(simpleTag, label, idx + 1, depth))
                    if (!selfClose) depth++
                    if (nodes.size >= 500) return nodes
                }
            }
        }
        return nodes
    }

    /** Current XML file ka view hierarchy outline dikhao — tap par jump. */
    private fun showLayoutOutline() {
        val tab = openTabs.getOrNull(activeTabIndex)
        if (tab == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        if (!tab.name.lowercase().endsWith(".xml")) {
            Toast.makeText(this, "Open an XML layout file first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val text = binding.editor.text?.toString() ?: ""
            val nodes = parseLayoutOutline(text)
            withContext(Dispatchers.Main) {
                if (nodes.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No views found", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val density = resources.displayMetrics.density
                val dialog = BottomSheetDialog(this@MainActivity)
                val container = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (16 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                val titleView = TextView(this@MainActivity).apply {
                    this.text = "Layout Outline — ${tab.name}"
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, (8 * density).toInt())
                }
                val list = RecyclerView(this@MainActivity).apply {
                    layoutManager = LinearLayoutManager(this@MainActivity)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
                    )
                }
                container.addView(titleView)
                container.addView(list)
                dialog.setContentView(container)
                val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val tv = TextView(parent.context).apply {
                            textSize = 14f
                            setPadding(
                                (16 * density).toInt(),
                                (10 * density).toInt(),
                                (16 * density).toInt(),
                                (10 * density).toInt()
                            )
                        }
                        return object : RecyclerView.ViewHolder(tv) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val n = nodes[position]
                        val tv = holder.itemView as TextView
                        tv.text = n.tag + (if (n.label.isNotEmpty()) "  ${n.label}" else "")
                        tv.setPadding(
                            ((16 + n.depth * 22) * density).toInt(),
                            (10 * density).toInt(),
                            (16 * density).toInt(),
                            (10 * density).toInt()
                        )
                        tv.setTextColor(
                            Color.parseColor(if (n.depth == 0) "#8B7CF6" else "#E6E9F5")
                        )
                        tv.setOnClickListener {
                            dialog.dismiss()
                            binding.editor.gotoLine(n.lineNo)
                        }
                    }
                    override fun getItemCount(): Int = nodes.size
                }
                list.adapter = adapter
                dialog.show()
            }
        }
    }

    private data class MergeConflict(
        val startLine: Int, val midLine: Int, val endLine: Int,
        val ours: List<String>, val theirs: List<String>
    )

    /** File me <<<<<<< ======= >>>>>>> blocks dhoondho. */
    private fun findMergeConflicts(lines: List<String>): List<MergeConflict> {
        val out = mutableListOf<MergeConflict>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("<<<<<<<")) {
                val start = i
                var mid = -1
                var end = -1
                var j = i + 1
                while (j < lines.size) {
                    if (lines[j].startsWith("=======") && mid == -1) mid = j
                    else if (lines[j].startsWith(">>>>>>>")) { end = j; break }
                    j++
                }
                if (mid != -1 && end != -1) {
                    out.add(
                        MergeConflict(
                            start, mid, end,
                            lines.subList(start + 1, mid).toList(),
                            lines.subList(mid + 1, end).toList()
                        )
                    )
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    /** Merge conflicts resolve karo: har block ke liye Ours/Theirs/Both. */
    private fun showMergeConflicts() {
        val tab = openTabs.getOrNull(activeTabIndex)
        if (tab == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val text = binding.editor.text?.toString() ?: return
        val lines = text.split('\n')
        val conflicts = findMergeConflicts(lines)
        if (conflicts.isEmpty()) {
            Toast.makeText(this, "No merge conflicts", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(this).apply {
            this.text = "${conflicts.size} conflict(s) — ${tab.name}"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * density).toInt()
            )
        }
        container.addView(titleView)
        container.addView(list)
        dialog.setContentView(container)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    textSize = 14f
                    setPadding(
                        (16 * density).toInt(), (12 * density).toInt(),
                        (16 * density).toInt(), (12 * density).toInt()
                    )
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val c = conflicts[position]
                val tv = holder.itemView as TextView
                val oursPreview = c.ours.firstOrNull()?.trim()?.take(40) ?: "(empty)"
                tv.text = "Conflict ${position + 1} — line ${c.startLine + 1}\n$oursPreview"
                tv.setOnClickListener { showConflictResolveDialog(c) { dialog.dismiss(); showMergeConflicts() } }
            }
            override fun getItemCount(): Int = conflicts.size
        }
        list.adapter = adapter
        dialog.show()
    }

    /** Ek conflict ke liye Ours / Theirs / Both. */
    private fun showConflictResolveDialog(c: MergeConflict, onDone: () -> Unit) {
        val oursText = c.ours.joinToString("\n").take(400)
        val theirsText = c.theirs.joinToString("\n").take(400)
        val msgView = TextView(this).apply {
            text = "OURS (line ${c.startLine + 1}):\n$oursText\n\nTHEIRS:\n$theirsText"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(32, 20, 32, 20)
        }
        val scroll = ScrollView(this).apply {
            addView(msgView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (300 * resources.displayMetrics.density).toInt()
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Resolve Conflict")
            .setView(scroll)
            .setPositiveButton("Ours") { _, _ -> applyConflictResolution(c, c.ours); onDone() }
            .setNeutralButton("Both") { _, _ -> applyConflictResolution(c, c.ours + c.theirs); onDone() }
            .setNegativeButton("Theirs") { _, _ -> applyConflictResolution(c, c.theirs); onDone() }
            .show()
    }

    /** Conflict block ko chune gaye lines se replace karo (ek Undo). */
    private fun applyConflictResolution(c: MergeConflict, chosen: List<String>) {
        val ed = binding.editor.text ?: return
        val layout = binding.editor.layout ?: return
        val lineCount = binding.editor.lineCount
        if (c.startLine >= lineCount || c.endLine >= lineCount) return
        val start = layout.getLineStart(c.startLine)
        val end = layout.getLineEnd(c.endLine)
        // \r\n safe: line end tak replace karo
        ed.replace(start, end, chosen.joinToString("\n"))
        if (activeTabIndex in openTabs.indices) {
            openTabs[activeTabIndex].isModified = true
            binding.statusModifiedDot.visibility = View.VISIBLE
            renderTabs()
            updateFooterButtonsState()
        }
    }

    private fun scheduleGitDiff() {
        gitDiffJob?.cancel()
        val tabIndex = activeTabIndex
        val tab = openTabs.getOrNull(tabIndex)
        val uri = tab?.uri
        if (uri == null) {
            binding.editor.gitDiffHunks = emptyList()
            binding.editor.invalidate()
            return
        }
        gitDiffJob = lifecycleScope.launch {
            delay(1500)
            // Beech me tab switch ho gaya to purana result mat lagao.
            if (activeTabIndex != tabIndex || openTabs.getOrNull(tabIndex)?.uri != uri) return@launch
            val text = binding.editor.text?.toString() ?: return@launch
            val hunks = withContext(Dispatchers.IO) { computeGitDiffHunks(uri, text) }
            if (activeTabIndex != tabIndex || openTabs.getOrNull(tabIndex)?.uri != uri) return@launch
            binding.editor.gitDiffHunks = hunks
            binding.editor.invalidate()
        }
    }

    /** File ke parent chain me git repo root dhoondho. */
    private fun findRepoRootFor(file: File): File? {
        var dir: File? = file.parentFile
        var guard = 0
        while (dir != null && guard++ < 20) {
            if (GitManager.isRepo(dir)) return dir
            dir = dir.parentFile
        }
        return null
    }

    private suspend fun computeGitDiffHunks(tabUri: Uri, text: String): List<GitManager.DiffHunk> {
        return try {
            val file = GitManager.resolveDocToFile(tabUri) ?: return emptyList()
            if (!file.isFile) return emptyList()
            val root = findRepoRootFor(file) ?: return emptyList()
            val rel = root.toURI().relativize(file.toURI()).path
            if (rel.isEmpty()) return emptyList()
            GitManager.diffAgainstHead(root, rel, text).getOrElse { emptyList() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** w22: file line above the editor — short path • language • encoding • line ending. */
    private fun refreshFileBreadcrumb() {
        val line = binding.fileBreadcrumb
        if (activeTabIndex !in openTabs.indices) {
            line.visibility = View.GONE
            return
        }
        val tab = openTabs[activeTabIndex]
        val dir = shortPathForTab(tab)
        val filePart = if (dir.isEmpty()) tab.name else "$dir/${tab.name}"
        val lang = FileIconProvider.getLanguageName(tab.name)
        val enc = FollowupStore.encodingFor(this, tab.uri?.toString())
        val t = binding.editor.text
        val le = if (t != null && t.contains("\r\n")) "CRLF" else "LF"
        line.text = "$filePart • $lang • $enc • $le"
        line.setTextColor(android.graphics.Color.parseColor("#CC22D3EE"))
        line.visibility = View.VISIBLE
    }

    /** Breadcrumb strip: file > class > function — tap karne par us symbol par jump. */
    private fun refreshBreadcrumbs() {
        val scroll = binding.breadcrumbScroll
        val container = binding.breadcrumbContainer
        if (activeTabIndex !in openTabs.indices) {
            scroll.visibility = View.GONE
            return
        }
        val tab = openTabs[activeTabIndex]
        if (breadcrumbSymbolsDirty) {
            cachedBreadcrumbSymbols = collectSymbols()
            breadcrumbSymbolsDirty = false
        }
        val openers = binding.editor.enclosingScopeLines()
        val byLine = cachedBreadcrumbSymbols.associateBy { it.line - 1 }
        val crumbs = openers.mapNotNull { ol -> byLine[ol] }
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (3 * density).toInt()
        fun addCrumb(text: String, isCurrent: Boolean, onClick: (() -> Unit)?) {
            val tv = TextView(this)
            tv.text = text
            tv.textSize = 13f
            tv.isSingleLine = true
            tv.setTextColor(android.graphics.Color.parseColor(if (isCurrent) "#8B7CF6" else "#CC22D3EE"))
            tv.setPadding(padH, padV, padH, padV)
            if (onClick != null) tv.setOnClickListener { onClick() }
            container.addView(tv)
        }
        fun addSep() {
            val tv = TextView(this)
            tv.text = "›"
            tv.textSize = 13f
            tv.setTextColor(android.graphics.Color.parseColor("#5A5F75"))
            tv.setPadding(0, padV, 0, padV)
            container.addView(tv)
        }
        scroll.visibility = View.VISIBLE
        addCrumb(tab.name, crumbs.isEmpty()) { pushNavPoint(); binding.editor.gotoLine(1) }
        for ((idx, sym) in crumbs.withIndex()) {
            addSep()
            addCrumb(sym.name, idx == crumbs.lastIndex) { pushNavPoint(); binding.editor.gotoLine(sym.line) }
        }
        scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) }
        refreshFileBreadcrumb()
    }

    private var zenMode: Boolean = false

    private fun setupZenMode() {
        binding.zenExitBtn.setOnClickListener { setZenMode(false) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (zenMode) setZenMode(false)
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun toggleZenMode() {
        if (!zenMode && activeTabIndex !in openTabs.indices) {
            Toast.makeText(this, "Open a file first", Toast.LENGTH_SHORT).show()
            return
        }
        setZenMode(!zenMode)
    }

    /** Zen mode: sirf editor dikhta hai, baaki saara chrome chhup jata hai. */
    private fun setZenMode(on: Boolean) {
        zenMode = on
        if (on) {
            binding.appBar.visibility = View.GONE
            binding.tabScroll.visibility = View.GONE
            binding.breadcrumbScroll.visibility = View.GONE
            binding.fileBreadcrumb.visibility = View.GONE
            binding.symbolsBar.visibility = View.GONE
            binding.footerBar.visibility = View.GONE
            binding.statusBar.visibility = View.GONE
            binding.zenExitBtn.visibility = View.VISIBLE
        } else {
            binding.appBar.visibility = View.VISIBLE
            binding.tabScroll.visibility = View.VISIBLE
            updateSymbolsBarVisibility()
            binding.footerBar.visibility = View.VISIBLE
            binding.statusBar.visibility = View.VISIBLE
            binding.zenExitBtn.visibility = View.GONE
            refreshBreadcrumbs()
        }
    }

    private fun scrollToActiveTab() {
        binding.tabScroll.post {
            if (activeTabIndex in 0 until binding.tabContainer.childCount) {
                val activeTabChild = binding.tabContainer.getChildAt(activeTabIndex) ?: return@post
                val targetScrollX = activeTabChild.left - (binding.tabScroll.width / 2) + (activeTabChild.width / 2)
                binding.tabScroll.smoothScrollTo(targetScrollX.coerceAtLeast(0), 0)
            }
        }
    }

    private fun closeTab(index: Int) {
        if (index !in openTabs.indices) return
        val tab = openTabs[index]

        if (tab.isPinned) {
            tab.isPinned = false
            renderTabs()
            saveSessionState()
            Toast.makeText(this, "Unpinned — tap × again to close", Toast.LENGTH_SHORT).show()
            return
        }

        if (tab.isModified) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unsaved_title)
                .setMessage(getString(R.string.unsaved_msg, tab.name))
                .setPositiveButton(R.string.discard) { _, _ ->
                    performCloseTab(index)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            performCloseTab(index)
        }
    }

    private fun performCloseTab(index: Int) {
        if (index !in openTabs.indices) return
        if (openTabs[index].isPinned) return // pinned tabs bulk-close se safe
        val wasActive = (activeTabIndex == index)
        val tab = openTabs[index]
        if (wasActive) {
            tab.content = binding.editor.text?.toString() ?: tab.content
            tab.cursorPosition = binding.editor.selectionStart
        }
        if (tab.uri != null || tab.content.isNotEmpty()) {
            closedTabs.addLast(tab.copy())
            if (closedTabs.size > 20) closedTabs.removeFirst()
        }
        openTabs.removeAt(index)
        if (openTabs.isEmpty()) {
            activeTabIndex = -1
            openNewUntitledTab()
        } else if (wasActive) {
            val newIdx = index.coerceAtMost(openTabs.size - 1)
            activeTabIndex = -1
            switchTab(newIdx)
        } else if (activeTabIndex > index) {
            activeTabIndex--
            renderTabs()
            scrollToActiveTab()
        } else {
            renderTabs()
            scrollToActiveTab()
        }
        updateFooterButtonsState()
        updateRunButtonVisibility()
        saveSessionState()
    }

    /**
     * Active tab ke siva saare tabs band karta hai. performCloseTab reuse hota hai,
     * isliye band tabs reopen history me jaate hain aur session save hota hai.
     * Peeche se band karta hai taaki active index shift na ho.
     */
    /**
     * Saare open tabs band karta hai. Har tab reopen history me jaata hai,
     * isliye "Reopen closed tab" se wapas laaye jaa sakte hain.
     */
    private fun closeAllTabs() {
        if (openTabs.isEmpty()) {
            Toast.makeText(this, R.string.no_open_tabs, Toast.LENGTH_SHORT).show()
            return
        }
        if (openTabs.none { !it.isPinned }) {
            Toast.makeText(this, "Only pinned tabs open", Toast.LENGTH_SHORT).show()
            return
        }
        for (i in openTabs.size - 1 downTo 0) {
            performCloseTab(i)
        }
    }

    private fun closeOtherTabs() {
        if (openTabs.size <= 1 || activeTabIndex !in openTabs.indices) {
            Toast.makeText(this, R.string.no_other_tabs, Toast.LENGTH_SHORT).show()
            return
        }
        for (i in openTabs.size - 1 downTo 0) {
            if (i != activeTabIndex) performCloseTab(i)
        }
    }

    /**
     * Active tab ke right wale saare tabs band karta hai. performCloseTab reuse
     * (reopen history + session save). Right se band karne par active index safe.
     */
    private fun closeTabsToRight() {
        if (activeTabIndex !in openTabs.indices) return
        if (activeTabIndex >= openTabs.size - 1) {
            Toast.makeText(this, R.string.no_tabs_to_right, Toast.LENGTH_SHORT).show()
            return
        }
        for (i in openTabs.size - 1 downTo activeTabIndex + 1) performCloseTab(i)
    }

    /** Active tab ko left (-1) / right (+1) move karta hai (tab reorder). */
    private fun moveTab(direction: Int) {
        val idx = activeTabIndex
        if (idx !in openTabs.indices) return
        val ni = idx + direction
        if (ni !in openTabs.indices) return
        val tab = openTabs.removeAt(idx)
        openTabs.add(ni, tab)
        activeTabIndex = ni
        renderTabs()
        scrollToActiveTab()
        saveSessionState()
    }

    /**
     * Current tab ka text Android share sheet se share karta hai (WhatsApp, Drive...).
     * Plain text share hai, isliye FileProvider ki zaroorat nahi.
     */
    private fun shareCurrentFile() {
        if (activeTabIndex !in openTabs.indices) {
            Toast.makeText(this, R.string.no_file_open, Toast.LENGTH_SHORT).show()
            return
        }
        val tab = openTabs[activeTabIndex]
        tab.content = binding.editor.text?.toString() ?: tab.content
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, tab.name)
            putExtra(android.content.Intent.EXTRA_TEXT, tab.content)
        }
        startActivity(android.content.Intent.createChooser(send, getString(R.string.action_share_file)))
    }

    /** Current tab ka file path clipboard me copy karta hai (path > uri > name). */
    private fun copyFilePath() {
        if (activeTabIndex !in openTabs.indices) {
            Toast.makeText(this, R.string.no_file_open, Toast.LENGTH_SHORT).show()
            return
        }
        val tab = openTabs[activeTabIndex]
        val path = tab.path ?: tab.uri?.toString() ?: tab.name
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(android.content.ClipData.newPlainText("File path", path))
        Toast.makeText(this, R.string.path_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    /** Galti se band hua tab wapas kholta hai (content + cursor ke saath). */
    private fun reopenClosedTab() {
        val tab = closedTabs.removeLastOrNull()
        if (tab == null) {
            Toast.makeText(this, R.string.no_closed_tabs, Toast.LENGTH_SHORT).show()
            return
        }
        if (tab.uri != null) {
            val existing = openTabs.indexOfFirst { it.uri == tab.uri }
            if (existing != -1) {
                switchTab(existing)
                return
            }
        }
        openTabs.add(tab)
        switchTab(openTabs.size - 1)
        saveSessionState()
    }

    /** idx wale tab ko chhodkar baaki sab band karo. */
    private fun closeOtherTabs(keepIdx: Int) {
        if (openTabs.size <= 1) return
        val unsaved = openTabs.indices.filter { it != keepIdx && openTabs[it].isModified }
        if (unsaved.isNotEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Close ${unsaved.size} unsaved tab(s)?")
                .setMessage("Some tabs have unsaved changes.")
                .setPositiveButton("Close all") { _, _ ->
                    for (i in openTabs.indices.reversed()) {
                        if (i != keepIdx) performCloseTab(i)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            for (i in openTabs.indices.reversed()) {
                if (i != keepIdx) performCloseTab(i)
            }
        }
    }

    /** Long-press se tab drag mode: tab lift hota hai, phir horizontal drag se reorder. */
    private fun startTabDrag(tabView: View) {
        if (tabDragView != null) return
        tabDragView = tabView
        tabDragTargetIndex = binding.tabContainer.indexOfChild(tabView)
        tabView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        tabView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
        tabView.translationZ = 12f * resources.displayMetrics.density
        tabView.parent?.requestDisallowInterceptTouchEvent(true)
    }

    /** Dragged tab ka center jis position par hai, wahan ka target index track karo.
     * Views ko physically move nahi karte (fragile tha) - sirf drop par openTabs reorder hota hai. */
    private var tabDragTargetIndex: Int = -1
    private fun moveDraggedTabInStrip(dragged: View) {
        val container = binding.tabContainer
        val from = container.indexOfChild(dragged)
        if (from < 0) return
        val centerX = dragged.left + dragged.translationX + dragged.width / 2f
        var target = from
        for (i in 0 until container.childCount) {
            if (i == from) continue
            val child = container.getChildAt(i)
            val mid = child.left + child.width / 2f
            if (i < from && centerX < mid) { target = i; break }
            if (i > from && centerX >= mid) { target = i }
        }
        tabDragTargetIndex = target
    }

    /** Drag khatm: drop par tracked target index par openTabs reorder; cancel par sirf reset. */
    private fun finishTabDrag(commit: Boolean) {
        val dragView = tabDragView ?: return
        tabDragView = null
        dragView.parent?.requestDisallowInterceptTouchEvent(false)
        // Reset drag visuals
        dragView.scaleX = 1.0f
        dragView.scaleY = 1.0f
        dragView.translationX = 0f
        dragView.translationZ = 0f
        if (commit) {
            val from = (dragView.tag as? EditorTab)?.let { openTabs.indexOf(it) } ?: -1
            val target = tabDragTargetIndex
            if (from >= 0 && target >= 0 && from != target && target < openTabs.size) {
                val tab = openTabs.removeAt(from)
                // Adjust target if removing from before target shifts indices
                val adjustedTarget = if (from < target) target - 1 else target
                openTabs.add(adjustedTarget.coerceIn(0, openTabs.size), tab)
                // Update activeTabIndex to follow the moved tab
                activeTabIndex = openTabs.indexOf(tab).takeIf { it >= 0 } ?: 0
                saveSessionState()
            }
        }
        tabDragTargetIndex = -1
        renderTabs()
    }

    private fun renderTabs() {
        binding.tabContainer.removeAllViews()

        for ((idx, tab) in openTabs.withIndex()) {
            val tabView = LayoutInflater.from(this).inflate(R.layout.item_tab, binding.tabContainer, false)
            val indicator = tabView.findViewById<View>(R.id.tabIndicator)
            val rowView = tabView.findViewById<LinearLayout>(R.id.tabRow)
            val iconView = tabView.findViewById<ImageView>(R.id.tabIcon)
            val titleView = tabView.findViewById<TextView>(R.id.tabTitle)
            val dotView = tabView.findViewById<View>(R.id.tabModifiedDot)
            val closeView = tabView.findViewById<ImageView>(R.id.tabClose)
            val pinView = tabView.findViewById<ImageView>(R.id.tabPin)

            iconView.setImageResource(FileIconProvider.getFileIcon(tab.name))
            iconView.colorFilter = null
            titleView.text = tab.name

            val isActive = idx == activeTabIndex
            indicator.visibility = if (isActive) View.VISIBLE else View.GONE
            // Keep the whole tab (including the × button) fully opaque;
            // dim only the icon + title on inactive tabs so × stays crisp
            tabView.alpha = 1.0f
            iconView.alpha = if (isActive) 1.0f else 0.55f
            titleView.alpha = if (isActive) 1.0f else 0.6f
            closeView.alpha = 1.0f
            if (isActive) {
                rowView.setBackgroundResource(R.drawable.bg_tab)
            } else {
                rowView.setBackgroundColor(Color.TRANSPARENT)
            }
            // v7 (71): tab bar style, shared with the terminal tabs.
            if (isActive) {
                val v7Style = getSharedPreferences("nobi_v7", MODE_PRIVATE)
                    .getString("tab_style", "rounded")
                if (v7Style == "underline") {
                    rowView.setBackgroundColor(Color.TRANSPARENT)
                    titleView.paintFlags = titleView.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                } else if (v7Style == "dots") {
                    titleView.text = "● " + tab.name
                }
            }

            // × close button shows on ALL tabs; unsaved dot sits next to it
            dotView.visibility = if (tab.isModified) View.VISIBLE else View.GONE
            closeView.visibility = View.VISIBLE
            pinView.visibility = if (tab.isPinned) View.VISIBLE else View.GONE

            tabView.setOnClickListener { switchTab(idx) }
            closeView.setOnClickListener { closeTab(idx) }
            tabView.tag = tab
            // Long-press: tab reorder drag mode (close-others ab 3-dot menu me hai).
            tabView.setOnLongClickListener {
                startTabDrag(tabView)
                true
            }
            attachTabSwipeToClose(tabView, idx)

            binding.tabContainer.addView(tabView)
        }
        updateEmptyState()
    }

    /** Swipe a tab upward to close it (vertical fling avoids scroll conflicts). */
    private fun attachTabSwipeToClose(tabView: View, idx: Int) {
        var downX = 0f
        var downY = 0f
        var downRawX = 0f
        var startTransX = 0f
        var swiped = false
        tabView.setOnTouchListener { v, event ->
            val dragging = tabDragView === v
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downRawX = event.rawX
                    startTransX = v.translationX
                    swiped = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        v.translationX = startTransX + (event.rawX - downRawX)
                        moveDraggedTabInStrip(v)
                        return@setOnTouchListener true
                    }
                    if (!swiped) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (dy < -90 && abs(dx) < 70) {
                            swiped = true
                            v.animate().translationY(-v.height * 0.7f).alpha(0f)
                                .setDuration(140)
                                .withEndAction { closeTab(idx) }
                                .start()
                            return@setOnTouchListener true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        finishTabDrag(commit = event.actionMasked == MotionEvent.ACTION_UP)
                        return@setOnTouchListener true
                    }
                    if (!swiped) {
                        v.animate().translationY(0f).setDuration(100).start()
                    }
                }
            }
            false
        }
    }

    /** Saare modified tabs save karta hai (untitled skip). */
    private fun saveAllTabs(quiet: Boolean = false) {
        val originalIndex = activeTabIndex
        var saved = 0
        for (i in openTabs.indices) {
            val tab = openTabs[i]
            if (tab.uri == null || !tab.isModified) continue
            if (i != activeTabIndex) switchTab(i)
            saveActiveTabContent(isManualSave = false, showToast = false)
            saved++
        }
        if (originalIndex in openTabs.indices && originalIndex != activeTabIndex) {
            switchTab(originalIndex)
        }
        if (!quiet) Toast.makeText(this, if (saved == 1) "Saved 1 tab" else "Saved $saved tabs", Toast.LENGTH_SHORT).show()
    }

    private fun saveActiveTabContent(isManualSave: Boolean = false, showToast: Boolean = false) {
        if (activeTabIndex !in openTabs.indices) return
        val tab = openTabs[activeTabIndex]
        var textToSave = binding.editor.getUnfoldedText()
        if (prefs.trimTrailingWhitespace) {
            binding.editor.trimTrailingWhitespace()
            textToSave = binding.editor.getUnfoldedText()
        }
        if (prefs.insertFinalNewline) {
            val ed = binding.editor.text
            if (ed != null && ed.isNotEmpty() && ed[ed.length - 1] != '\n') {
                ed.append("\n")
                textToSave = binding.editor.getUnfoldedText()
            }
        }

        // v6 (12): format on save — trailing whitespace + final newline for
        // every language, bracket reindent for brace languages only (Python /
        // YAML / shell indentation is semantic, never reindented).
        if (prefs.formatOnSave) {
            val ext = if (activeTabIndex in openTabs.indices) {
                openTabs[activeTabIndex].name.substringAfterLast('.', "").lowercase()
            } else ""
            textToSave = formatCodeForSave(textToSave, ext)
        }

        // v11 follow-up (19): stored line ending applies at save time.
        textToSave = applyFollowupSaveText(tab, textToSave)

        if (tab.uri != null) {
            // v7 (41): remote tab — upload back to the server first.
            if (tab.remotePath != null) {
                val conn = remoteConn
                if (conn != null && conn.id == tab.remoteConnId &&
                    com.nobidev.nobicode.remote.RemoteManager.isConnected
                ) {
                    lifecycleScope.launch {
                        try {
                            val tmp = java.io.File(cacheDir, "nobi_remote_upload.tmp")
                            tmp.writeText(textToSave)
                            val up = com.nobidev.nobicode.remote.RemoteManager.upload(tmp, tab.remotePath!!)
                            up.onSuccess {
                                tab.content = textToSave
                                tab.diskContent = textToSave
                                tab.isModified = false
                                binding.statusModifiedDot.visibility = View.GONE
                                renderTabs()
                                if (showToast) Toast.makeText(this@MainActivity, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "Remote upload failed — saved locally only", Toast.LENGTH_LONG).show()
                                saveLocalTabContent(tab, textToSave, showToast)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Remote upload failed — saved locally only", Toast.LENGTH_LONG).show()
                            saveLocalTabContent(tab, textToSave, showToast)
                        }
                    }
                    return
                }
            }
            saveLocalTabContent(tab, textToSave, showToast, isManualSave)
        } else {
            tab.content = textToSave
            if (isManualSave) {
                showSaveAsDialog()
            }
        }
    }

    /** v7 (41): the original local-save path, shared by remote fallback. */
    private fun saveLocalTabContent(tab: EditorTab, textToSave: String, showToast: Boolean, isManualSave: Boolean = false) {
        if (tab.uri == null) return
        try {
            contentResolver.openOutputStream(tab.uri!!, "wt")?.use { out ->
                out.write(textToSave.toByteArray(FollowupEncoding.charsetFor(FollowupStore.encodingFor(this, tab.uri?.toString()))))
            }
            tab.content = textToSave
            tab.diskContent = textToSave
            tab.isModified = false
            binding.statusModifiedDot.visibility = View.GONE
            renderTabs()
            updateFooterButtonsState()
            saveSessionState()
            recordLocalHistory(textToSave)
            if (showToast) Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (isManualSave) {
                Toast.makeText(this, getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // v6 (12): conservative formatter. Brace languages get bracket-depth
    // reindent (4 spaces, strings/comments/block comments skipped); every
    // language gets trailing-whitespace trim + exactly one final newline.
    private fun formatCodeForSave(text: String, ext: String): String {
        if (text.isEmpty()) return text
        // v7 (46): an enabled plugin formatter with mode "trim" wins over the
        // built-in formatter. Command/script formatters are listed in the
        // Plugins UI but their execution engine is still pending.
        try {
            val fmt = com.nobidev.nobicode.plugins.PluginManager.formatterFor(this, ext)
            if (fmt != null && fmt.optString("mode", "") == "trim") {
                return text.split('\n').joinToString("\n") { it.trimEnd() }.trimEnd('\n') + "\n"
            }
        } catch (e: Exception) { }
        val braceLangs = setOf(
            "kt", "kts", "java", "js", "ts", "c", "h", "cpp", "cc", "css", "json"
        )
        val lines = text.split('\n')
        if (ext.lowercase() !in braceLangs) {
            return lines.joinToString("\n") { it.trimEnd() }.trimEnd('\n') + "\n"
        }
        val out = ArrayList<String>()
        var depth = 0
        var inBlockComment = false
        for (raw in lines) {
            val trimmed = raw.trim()
            var lineDelta = 0
            var startsWithCloser = false
            var firstSignificant = true
            var inString = false
            var stringDelim = '"'
            var inChar = false
            var i = 0
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
                    if (c == '\\' && i + 1 < raw.length) {
                        i += 2
                        continue
                    }
                    if (c == stringDelim) inString = false
                    i++
                    continue
                }
                if (inChar) {
                    if (c == '\\' && i + 1 < raw.length) {
                        i += 2
                        continue
                    }
                    if (c == '\'') inChar = false
                    i++
                    continue
                }
                if (c == '/' && i + 1 < raw.length && raw[i + 1] == '/') break
                if (c == '/' && i + 1 < raw.length && raw[i + 1] == '*') {
                    inBlockComment = true
                    i += 2
                    continue
                }
                if (c == '"') {
                    inString = true
                    stringDelim = '"'
                    i++
                    firstSignificant = false
                    continue
                }
                if (c == '\'') {
                    inChar = true
                    i++
                    firstSignificant = false
                    continue
                }
                if (c == ' ' || c == '\t') {
                    i++
                    continue
                }
                if (firstSignificant && (c == ')' || c == ']' || c == '}')) {
                    startsWithCloser = true
                }
                firstSignificant = false
                when (c) {
                    '(', '[', '{' -> lineDelta++
                    ')', ']', '}' -> lineDelta--
                }
                i++
            }
            val indentDepth = (depth - if (startsWithCloser) 1 else 0).coerceAtLeast(0)
            out.add("    ".repeat(indentDepth) + trimmed)
            depth = (depth + lineDelta).coerceAtLeast(0)
        }
        return out.joinToString("\n").trimEnd('\n') + "\n"
    }

    private fun showSaveAsDialog() {
        val input = EditText(this).apply {
            hint = "filename.txt"
            setText(if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex].name else "file.txt")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_save_as)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                val targetFolder = currentFolder ?: rootDocumentFile
                if (newName.isNotEmpty() && targetFolder != null) {
                    val created = targetFolder.createFile("text/plain", newName)
                    if (created != null) {
                        val tab = if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex] else EditorTab()
                        tab.uri = created.uri
                        tab.name = newName
                        saveActiveTabContent(isManualSave = true, showToast = true)
                        currentFolder?.uri?.let { folderCache.remove(it.toString()) }
                        loadCurrentFolderContentsFast()
                        saveSessionState()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFileContextMenu(item: DocumentFile) {
        val items = listOf(
            MenuItemData("File Info", R.drawable.ic_info),
            MenuItemData("Rename", R.drawable.ic_rename),
            MenuItemData("Duplicate", R.drawable.ic_duplicate),
            MenuItemData("Delete", R.drawable.ic_delete)
        )
        val adapter = MenuActionAdapter(this, items)

        MaterialAlertDialogBuilder(this)
            .setTitle(item.name ?: "File Actions")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> showItemInfoDialog(item)
                    1 -> showRenameDialog(item)
                    2 -> duplicateFile(item)
                    3 -> deleteEntry(item)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFolderContextMenu(item: DocumentFile) {
        val items = listOf(
            MenuItemData("New File Inside", R.drawable.ic_file),
            MenuItemData("New Folder Inside", R.drawable.ic_new_folder),
            MenuItemData("Folder Info", R.drawable.ic_info),
            MenuItemData("Rename", R.drawable.ic_rename),
            MenuItemData("Delete Folder", R.drawable.ic_delete)
        )
        val adapter = MenuActionAdapter(this, items)

        MaterialAlertDialogBuilder(this)
            .setTitle(item.name ?: "Folder Actions")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> showCreateNewFileDialog(item)
                    1 -> showCreateNewFolderDialog(item)
                    2 -> showItemInfoDialog(item)
                    3 -> showRenameDialog(item)
                    4 -> deleteEntry(item)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showItemInfoDialog(item: DocumentFile) {
        val name = item.name ?: "Unknown"
        val isDir = item.isDirectory
        val sizeStr = if (isDir) {
            "${item.listFiles().size} items"
        } else {
            val length = item.length()
            if (length < 1024) "$length B" else if (length < 1024 * 1024) "${length / 1024} KB" else "${String.format(Locale.ROOT, "%.2f", length / (1024f * 1024f))} MB"
        }
        val lastModified = if (item.lastModified() > 0) {
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.lastModified()))
        } else "Unknown"

        val type = if (isDir) "Directory / Folder" else (item.type ?: FileIconProvider.getLanguageName(name))
        val pathUri = item.uri.toString()

        val infoMsg = """
            Name: $name
            Type: $type
            Size: $sizeStr
            Modified: $lastModified
            
            URI:
            $pathUri
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isDir) "Folder Details" else "File Details")
            .setMessage(infoMsg)
            .setPositiveButton("Copy URI") { _, _ ->
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clip?.setPrimaryClip(ClipData.newPlainText("File URI", pathUri))
                Toast.makeText(this, "URI copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showCreateNewFileDialog(parent: DocumentFile?) {
        val templateNames = arrayOf("Empty", "HTML", "Kotlin", "Java", "Python", "JavaScript")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 4, pad, 0)
        }
        val input = EditText(this).apply { hint = "index.html" }
        val templateLabel = TextView(this).apply {
            text = "Template"
            textSize = 13f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, templateNames)
        }
        container.addView(input)
        container.addView(templateLabel)
        container.addView(spinner)
        // Naam ki extension se template auto-select karo (pehle naam likho, phir chaho to badlo).
        val extToTemplate = mapOf(
            "html" to 1, "htm" to 1,
            "kt" to 2, "kts" to 2,
            "java" to 3,
            "py" to 4,
            "js" to 5, "mjs" to 5
        )
        input.doOnTextChanged { _, _, _, _ ->
            val ext = input.text.toString().substringAfterLast('.', "").lowercase()
            val idx = extToTemplate[ext]
            if (idx != null && spinner.selectedItemPosition != idx) spinner.setSelection(idx)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Create New File")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && parent != null) {
                    val created = parent.createFile("text/plain", name)
                    if (created != null) {
                        val tpl = fileTemplate(templateNames[spinner.selectedItemPosition], name)
                        if (tpl.isNotEmpty()) {
                            try {
                                contentResolver.openOutputStream(created.uri, "wt")?.use { it.write(tpl.toByteArray()) }
                            } catch (_: Exception) { }
                        }
                        currentFolder?.uri?.let { folderCache.remove(it.toString()) }
                        openFileInTab(created)
                        loadCurrentFolderContentsFast()
                        saveSessionState()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Nayi file ke liye template content (template naam + file naam ke hisaab se). */
    private fun fileTemplate(template: String, fileName: String): String {
        return when (template) {
            "HTML" -> "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Document</title>\n</head>\n<body>\n\n</body>\n</html>\n"
            "Kotlin" -> "fun main() {\n    \n}\n"
            "Java" -> {
                val cls = fileName.substringBeforeLast('.').filter { it.isLetterOrDigit() }
                    .replaceFirstChar { it.uppercase() }.ifEmpty { "Main" }
                "public class $cls {\n    public static void main(String[] args) {\n        \n    }\n}\n"
            }
            "Python" -> "def main():\n    pass\n\n\nif __name__ == \"__main__\":\n    main()\n"
            "JavaScript" -> "function main() {\n    \n}\n\nmain();\n"
            else -> ""
        }
    }

    private fun showCreateNewFolderDialog(parent: DocumentFile?) {
        val input = EditText(this).apply { hint = "components" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Create New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && parent != null) {
                    parent.createDirectory(name)
                    currentFolder?.uri?.let { folderCache.remove(it.toString()) }
                    loadCurrentFolderContentsFast()
                    saveSessionState()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(doc: DocumentFile) {
        val input = EditText(this).apply { setText(doc.name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_rename)
            .setView(input)
            .setPositiveButton(R.string.file_rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    doc.renameTo(newName)
                    currentFolder?.uri?.let { folderCache.remove(it.toString()) }
                    loadCurrentFolderContentsFast()
                    saveSessionState()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun duplicateFile(doc: DocumentFile) {
        try {
            contentResolver.openInputStream(doc.uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                val parent = doc.parentFile ?: currentFolder ?: rootDocumentFile
                val copyName = "copy_${doc.name}"
                val created = parent?.createFile("text/plain", copyName)
                created?.let {
                    contentResolver.openOutputStream(it.uri)?.use { out ->
                        out.write(text.toByteArray())
                    }
                }
                currentFolder?.uri?.let { folderCache.remove(it.toString()) }
                loadCurrentFolderContentsFast()
                saveSessionState()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Duplication failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteEntry(doc: DocumentFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_delete)
            .setMessage(getString(R.string.file_delete_confirm, doc.name))
            .setPositiveButton(R.string.file_delete) { _, _ ->
                val index = openTabs.indexOfFirst { it.uri == doc.uri }
                if (index != -1) {
                    performCloseTab(index)
                }
                doc.delete()
                currentFolder?.uri?.let { folderCache.remove(it.toString()) }
                loadCurrentFolderContentsFast()
                saveSessionState()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFindReplaceDialog() {
        val dBinding = DialogFindReplaceBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_find)
            .setView(dBinding.root)
            .create()
        // Title me query dikhao.
        dBinding.findInput.doOnTextChanged { it, _, _, _ ->
            val q = it.toString()
            dialog.setTitle(if (q.isEmpty()) getString(R.string.menu_find) else "${getString(R.string.menu_find)}: $q")
        }

        // Selected text (ya caret wala word) se prefill karo.
        val ed = binding.editor.text
        if (ed != null) {
            val s0 = minOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, ed.length)
            val e0 = maxOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, ed.length)
            val prefill = if (e0 > s0) {
                ed.substring(s0, e0).take(80)
            } else {
                binding.editor.wordAtCaret().orEmpty()
            }
            if (prefill.isNotEmpty() && !prefill.contains('\n')) {
                dBinding.findInput.setText(prefill)
                dBinding.findInput.selectAll()
            }
        }

        val defaultCountColor = dBinding.findCount.currentTextColor
        // Find history: haal ke queries (browser address-bar jaisa dropdown).
        val findHistoryList = prefs.findHistory.toMutableList()
        fun pushFindHistory(q: String) {
            val t = q.trim()
            if (t.isEmpty()) return
            findHistoryList.remove(t)
            findHistoryList.add(0, t)
            while (findHistoryList.size > 8) findHistoryList.removeAt(findHistoryList.size - 1)
            prefs.findHistory = findHistoryList
        }
        fun showFindHistory() {
            if (findHistoryList.isEmpty()) return
            val popup = androidx.appcompat.widget.ListPopupWindow(this)
            popup.anchorView = dBinding.findInput
            if (dBinding.findInput.width > 0) popup.width = dBinding.findInput.width
            popup.setAdapter(
                android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, findHistoryList.toList())
            )
            popup.isModal = true
            popup.setOnItemClickListener { _, _, position, _ ->
                popup.dismiss()
                dBinding.findInput.setText(findHistoryList[position])
                dBinding.findInput.selectAll()
            }
            popup.show()
        }
        dBinding.findInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && dBinding.findInput.text?.isEmpty() == true && findHistoryList.isNotEmpty()) showFindHistory()
        }
        fun refreshFindCount() {
            val q = dBinding.findInput.text?.toString().orEmpty()
            if (q.isEmpty()) {
                dBinding.findCount.text = ""
            } else if (dBinding.cbRegex.isChecked) {
                // Regex valid hai ya nahi, check karo.
                try {
                    buildFindRegex(q, dBinding.cbCase.isChecked, true, dBinding.cbWholeWord.isChecked)
                    val n = countFindMatches(q, dBinding.cbCase.isChecked, true, dBinding.cbWholeWord.isChecked)
                    dBinding.findCount.text = if (n == 1) "1 match" else "$n matches"
                    dBinding.findCount.setTextColor(
                        if (n == 0) android.graphics.Color.parseColor("#FF6B6B")
                        else defaultCountColor
                    )
                } catch (e: Exception) {
                    dBinding.findCount.text = "Invalid regex"
                    dBinding.findCount.setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
                }
            } else {
                val n = countFindMatches(q, dBinding.cbCase.isChecked, false, dBinding.cbWholeWord.isChecked)
                dBinding.findCount.text = if (n == 1) "1 match" else "$n matches"
                // Zero matches laal rang me.
                dBinding.findCount.setTextColor(
                    if (n == 0) android.graphics.Color.parseColor("#FF6B6B")
                    else defaultCountColor
                )
            }
        }
        dBinding.findInput.doOnTextChanged { _, _, _, _ -> refreshFindCount() }
        // Count par tap karne se saare matches copy.
        dBinding.findCount.setOnClickListener {
            val q = dBinding.findInput.text?.toString().orEmpty()
            val matches = getFindMatches(q, dBinding.cbCase.isChecked, dBinding.cbRegex.isChecked, dBinding.cbWholeWord.isChecked)
            if (matches.isNotEmpty()) {
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("matches", matches.joinToString("\n")))
                Toast.makeText(this, "${matches.size} matches copied", Toast.LENGTH_SHORT).show()
            }
        }
        // Long-press par line numbers ke saath copy.
        dBinding.findCount.setOnLongClickListener {
            val q = dBinding.findInput.text?.toString().orEmpty()
            val lines = getFindMatchLines(q, dBinding.cbCase.isChecked, dBinding.cbRegex.isChecked, dBinding.cbWholeWord.isChecked)
            if (lines.isNotEmpty()) {
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("matches", lines.joinToString("\n")))
                Toast.makeText(this, "${lines.size} matches copied with lines", Toast.LENGTH_SHORT).show()
            }
            true
        }
        dBinding.cbCase.setOnCheckedChangeListener { _, _ -> refreshFindCount() }
        dBinding.cbRegex.setOnCheckedChangeListener { _, _ -> refreshFindCount() }
        refreshFindCount()

        // Enter dabane par Next match par jao.
        dBinding.cbCase.isChecked = prefs.findCaseSensitive
        dBinding.cbRegex.isChecked = prefs.findUseRegex
        dBinding.cbWholeWord.isChecked = prefs.findWholeWord
        dBinding.cbCase.setOnCheckedChangeListener { _, c -> prefs.findCaseSensitive = c; refreshFindCount() }
        dBinding.cbRegex.setOnCheckedChangeListener { _, c -> prefs.findUseRegex = c; refreshFindCount() }
        dBinding.cbWholeWord.setOnCheckedChangeListener { _, c -> prefs.findWholeWord = c; refreshFindCount() }
        dBinding.cbRegex.setOnLongClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Regex help")
                .setMessage(".* = any chars\n\\d = digit\n\\w = word char\n^ = start, $ = end\n(a|b) = a or b\n\\b = word boundary")
                .setPositiveButton(R.string.close, null)
                .show()
            true
        }
        dBinding.findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                dBinding.btnNext.performClick()
                true
            } else false
        }
        // Replace box me Enter dabane par Replace.
        dBinding.replaceInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                dBinding.btnReplace.performClick()
                true
            } else false
        }
        dBinding.btnNext.setOnClickListener {
            val query = dBinding.findInput.text?.toString().orEmpty()
            pushFindHistory(query)
            val before = binding.editor.selectionStart
            val found = binding.editor.findNext(query, dBinding.cbCase.isChecked, dBinding.cbRegex.isChecked, dBinding.cbWholeWord.isChecked)
            if (!found) {
                Toast.makeText(this, getString(R.string.no_match), Toast.LENGTH_SHORT).show()
            } else if (binding.editor.selectionStart < before) {
                Toast.makeText(this, "Wrapped to top", Toast.LENGTH_SHORT).show()
            }
        }
        // Next button par long-press: pichhla match.
        dBinding.btnNext.setOnLongClickListener {
            val query = dBinding.findInput.text?.toString().orEmpty()
            val before = binding.editor.selectionStart
            val found = binding.editor.findPrev(query, dBinding.cbCase.isChecked, dBinding.cbRegex.isChecked, dBinding.cbWholeWord.isChecked)
            if (!found) {
                Toast.makeText(this, getString(R.string.no_match), Toast.LENGTH_SHORT).show()
            } else if (binding.editor.selectionStart > before) {
                Toast.makeText(this, "Wrapped to bottom", Toast.LENGTH_SHORT).show()
            }
            true
        }
        // Cursors button: query ke saare occurrences par multiple carets (VS Code jaisa multi-cursor).
        dBinding.btnCursors.setOnClickListener {
            val q = dBinding.findInput.text?.toString().orEmpty()
            if (q.isEmpty()) {
                Toast.makeText(this, "Type a word first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val offsets = getFindMatchOffsets(q, dBinding.cbCase.isChecked, dBinding.cbRegex.isChecked, dBinding.cbWholeWord.isChecked)
            if (offsets.size < 2) {
                Toast.makeText(this, if (offsets.isEmpty()) getString(R.string.no_match) else "Only 1 occurrence", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pushFindHistory(q)
            binding.editor.setMultiCursors(offsets)
            dialog.dismiss()
            binding.editor.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editor, InputMethodManager.SHOW_IMPLICIT)
            Toast.makeText(this, "${offsets.size} carets - type to edit all together (Back/ESC/tap to exit)", Toast.LENGTH_LONG).show()
        }
        // Cursors par long-press: agla occurrence bhi caret banao (Ctrl+D jaisa).
        dBinding.btnCursors.setOnLongClickListener {
            val q = dBinding.findInput.text?.toString().orEmpty()
            if (q.isEmpty()) {
                Toast.makeText(this, "Type a word first", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            if (!binding.editor.hasMultiCursors()) {
                dBinding.btnCursors.performClick()
                return@setOnLongClickListener true
            }
            val offsets = getFindMatchOffsets(q, dBinding.cbCase.isChecked, dBinding.cbRegex.isChecked, dBinding.cbWholeWord.isChecked)
            val curMax = binding.editor.primaryCaretOffset()
            val next = offsets.firstOrNull { it > curMax } ?: offsets.firstOrNull()
            if (next == null) {
                Toast.makeText(this, getString(R.string.no_match), Toast.LENGTH_SHORT).show()
            } else if (binding.editor.addCaretAt(next)) {
                if (next <= curMax) Toast.makeText(this, "Wrapped to top - caret added", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Already a caret there", Toast.LENGTH_SHORT).show()
            }
            true
        }

        dBinding.btnReplace.setOnClickListener {
            val replaceWith = dBinding.replaceInput.text?.toString().orEmpty()
            binding.editor.replaceOne(replaceWith)
            updateFooterButtonsState()
            refreshFindCount()
        }

        dBinding.btnReplaceAll.setOnClickListener {
            val query = dBinding.findInput.text?.toString().orEmpty()
            val replaceWith = dBinding.replaceInput.text?.toString().orEmpty()
            val count = binding.editor.replaceAll(query, replaceWith, dBinding.cbCase.isChecked, dBinding.cbRegex.isChecked, dBinding.cbWholeWord.isChecked)
            Toast.makeText(this, getString(R.string.replaced_count, count), Toast.LENGTH_SHORT).show()
            updateFooterButtonsState()
            refreshFindCount()
        }

        dialog.setOnShowListener {
            dBinding.findInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(dBinding.findInput, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    /** Find ke liye Regex banao: regex ya plain query + case + whole-word. */
    private fun buildFindRegex(query: String, caseSensitive: Boolean, useRegex: Boolean, wholeWord: Boolean): Regex {
        val flags = if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
        val pattern = if (useRegex) {
            if (wholeWord) "\\b(?:$query)\\b" else query
        } else {
            val lit = Regex.escape(query)
            if (wholeWord) "\\b$lit\\b" else lit
        }
        return Regex(pattern, flags)
    }

    /** Saare matches "L#: match" format me (find-count long-press copy ke liye). */
    private fun getFindMatchLines(query: String, caseSensitive: Boolean, useRegex: Boolean, wholeWord: Boolean): List<String> {
        if (query.isEmpty()) return emptyList()
        val textStr = binding.editor.text?.toString() ?: return emptyList()
        val out = mutableListOf<String>()
        try {
            if (useRegex) {
                for (m in buildFindRegex(query, caseSensitive, useRegex, wholeWord).findAll(textStr).take(1000)) {
                    val line = textStr.substring(0, m.range.first).count { it == '\n' } + 1
                    out.add("L$line: ${m.value}")
                }
            } else {
                var from = 0
                while (out.size < 1000) {
                    val idx = textStr.indexOf(query, from, ignoreCase = !caseSensitive)
                    if (idx < 0) break
                    if (!wholeWord || binding.editor.isWholeWordAt(textStr, idx, query.length)) {
                        val line = textStr.substring(0, idx).count { it == '\n' } + 1
                        out.add("L$line: ${textStr.substring(idx, idx + query.length)}")
                    }
                    from = idx + query.length
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Find dialog ke liye match count (regex ya plain, case-sensitive optional). */
    /** Saare matches ki list (copy ke liye). */
    private fun getFindMatches(query: String, caseSensitive: Boolean, useRegex: Boolean, wholeWord: Boolean = false): List<String> {
        if (query.isEmpty()) return emptyList()
        val textStr = binding.editor.text?.toString() ?: return emptyList()
        return try {
            if (useRegex) {
                buildFindRegex(query, caseSensitive, useRegex, wholeWord).findAll(textStr).take(1000).map { it.value }.toList()
            } else {
                val out = mutableListOf<String>()
                var from = 0
                while (out.size < 1000) {
                    val idx = textStr.indexOf(query, from, ignoreCase = !caseSensitive)
                    if (idx < 0) break
                    if (!wholeWord || binding.editor.isWholeWordAt(textStr, idx, query.length)) {
                        out.add(textStr.substring(idx, idx + query.length))
                    }
                    from = idx + query.length
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Find dialog ke "Cursors" button ke liye: saare match offsets (multi-cursor). */
    private fun getFindMatchOffsets(query: String, caseSensitive: Boolean, useRegex: Boolean, wholeWord: Boolean = false): List<Int> {
        if (query.isEmpty()) return emptyList()
        val textStr = binding.editor.text?.toString() ?: return emptyList()
        return try {
            if (useRegex) {
                buildFindRegex(query, caseSensitive, useRegex, wholeWord).findAll(textStr).take(100).map { it.range.first }.toList()
            } else {
                val out = mutableListOf<Int>()
                var from = 0
                while (out.size < 100) {
                    val idx = textStr.indexOf(query, from, ignoreCase = !caseSensitive)
                    if (idx < 0) break
                    if (!wholeWord || binding.editor.isWholeWordAt(textStr, idx, query.length)) {
                        out.add(idx)
                    }
                    from = idx + query.length
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun countFindMatches(query: String, caseSensitive: Boolean, useRegex: Boolean, wholeWord: Boolean = false): Int {
        if (query.isEmpty()) return 0
        val textStr = binding.editor.text?.toString() ?: return 0
        return try {
            if (useRegex) {
                var n = 0
                for (m in buildFindRegex(query, caseSensitive, useRegex, wholeWord).findAll(textStr)) {
                    n++
                    if (n >= 10000) break
                }
                n
            } else {
                var n = 0
                var idx = 0
                val step = query.length.coerceAtLeast(1)
                while (n < 10000) {
                    idx = textStr.indexOf(query, idx, ignoreCase = !caseSensitive)
                    if (idx < 0) break
                    if (!wholeWord || binding.editor.isWholeWordAt(textStr, idx, query.length)) n++
                    idx += step
                }
                n
            }
        } catch (e: Exception) {
            0
        }
    }

    // v6 (11): list of functions/classes in the active file, tap to jump.
    // Two patterns: keyword definitions (fun/def/class/...) and C-style
    // "name(args) {" definitions; control words (if/for/while/...) are
    // filtered out of the C-style matches.
    private fun showCodeOutline() {
        val fullText = binding.editor.text?.toString() ?: return
        val lines = fullText.split('\n')
        val keywordPattern = Regex("""^\s*(fun|def|function|class|interface|object|enum|trait|struct)\s+([A-Za-z_]\w*)""")
        val cStylePattern = Regex("""^\s*(?:[\w<>\[\],\s]+?\s+)?([A-Za-z_]\w*)\s*\([^;{}]*\)\s*\{\s*$""")
        val controlWords = setOf("if", "for", "while", "switch", "catch", "return", "sizeof")
        val items = ArrayList<String>()
        val offsets = ArrayList<Int>()
        var offset = 0
        for ((index, line) in lines.withIndex()) {
            val m1 = keywordPattern.find(line)
            val name = when {
                m1 != null -> m1.groupValues[2]
                else -> {
                    val n = cStylePattern.find(line)?.groupValues?.get(1)
                    if (n != null && n !in controlWords) n else null
                }
            }
            if (name != null) {
                items.add("$name  ·  line ${index + 1}")
                offsets.add(offset)
            }
            offset += line.length + 1
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "No functions found", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Code Outline")
            .setItems(items.toTypedArray()) { _, which ->
                binding.editor.requestFocus()
                binding.editor.setSelection(offsets[which].coerceIn(0, fullText.length))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGotoLineDialog() {
        val gBinding = DialogGotoBinding.inflate(layoutInflater)
        gBinding.gotoWrap.hint = "Line (1-${totalLines()})"
        gBinding.gotoInput.setText(lastCursorLine.toString())
        gBinding.gotoInput.selectAll()
        val gotoDialog = MaterialAlertDialogBuilder(this)
           .setTitle(R.string.action_goto)
            .setView(gBinding.root)
            .setPositiveButton(R.string.goto_action) { _, _ ->
                val line = gBinding.gotoInput.text.toString().toIntOrNull()
                if (line != null) binding.editor.gotoLine(line)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Enter dabane par Go.
        gBinding.gotoInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                val line = gBinding.gotoInput.text.toString().toIntOrNull()
                if (line != null) {
                    binding.editor.gotoLine(line)
                    gotoDialog.dismiss()
                }
                true
            } else false
        }
        gotoDialog.setOnShowListener {
            gBinding.gotoInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(gBinding.gotoInput, InputMethodManager.SHOW_IMPLICIT)
        }
        gotoDialog.show()
    }

    private data class Symbol(val name: String, val kind: String, val line: Int)

    /** Current file me functions/classes dhoondhta hai (Kotlin, Python, JS/TS, Java-style). */
    private fun collectSymbols(): List<Symbol> =
        parseSymbols(binding.editor.text?.toString() ?: "")

    /** Kisi bhi text me se symbols nikalo — project-wide search me reuse hota hai. */
    private fun parseSymbols(content: String): List<Symbol> {
        val result = ArrayList<Symbol>()
        val funPat = Regex("""^\s*(?:(?:public|private|protected|internal|open|abstract|override|suspend|inline|tailrec|operator|infix)\s+)*(fun|def|function)\s+([A-Za-z_]\w*)""")
        val typePat = Regex("""^\s*(?:(?:public|private|protected|internal|open|abstract|data|sealed)\s+)*(class|interface|object|enum)\s+([A-Za-z_]\w*)""")
        val javaMethodPat = Regex("""^\s*(public|private|protected)\s+(?:static\s+)?(?:[\w<>\[\].,]+\s+)?([A-Za-z_]\w*)\s*\(""")
        val todoPat = Regex("""\b(TODO|FIXME|XXX|HACK)\b\s*:?\s*(.*)""")
        content.lineSequence().forEachIndexed { idx, line ->
            val lineNo = idx + 1
            val fm = funPat.find(line)
            if (fm != null) {
                result.add(Symbol(fm.groupValues[2], "function", lineNo))
                return@forEachIndexed
            }
            val tm = typePat.find(line)
            if (tm != null) {
                result.add(Symbol(tm.groupValues[2], "type", lineNo))
                return@forEachIndexed
            }
            val jm = javaMethodPat.find(line)
            if (jm != null) {
                result.add(Symbol(jm.groupValues[2], "method", lineNo))
            }
            val todoM = todoPat.find(line)
            if (todoM != null) {
                val tag = todoM.groupValues[1].uppercase()
                val txt = todoM.groupValues[2].trim().take(60)
                result.add(Symbol(if (txt.isNotEmpty()) "$tag: $txt" else tag, "todo", lineNo))
            }
        }
        return result
    }

    /** File ke symbols ki list — tap karne par us line par jump. */
    private fun showGoToSymbolDialog() {
        val allSymbols = collectSymbols()
        if (allSymbols.isEmpty()) {
            Toast.makeText(this, R.string.no_symbols_found, Toast.LENGTH_SHORT).show()
            return
        }
        fun labelOf(sym: Symbol): String = "${sym.name}  ·  ${sym.kind}  ·  L${sym.line}"
        val visible = allSymbols.toMutableList()
        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            visible.map { labelOf(it) }.toMutableList()
        )
        val density = resources.displayMetrics.density
        val filterInput = android.widget.EditText(this).apply {
            hint = "Filter symbols..."
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            val pad = (16 * density).toInt()
            setPadding(pad, (12 * density).toInt(), pad, (8 * density).toInt())
        }
        val listView = android.widget.ListView(this).apply { this.adapter = adapter }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(
                filterInput,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                listView,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (320 * density).toInt()
                )
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("${getString(R.string.action_go_to_symbol)} (${allSymbols.size})")
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        fun jumpTo(line: Int) {
            dialog.dismiss()
            pushNavPoint()
            binding.editor.gotoLine(line)
        }
        fun refreshFilter() {
            val q = filterInput.text.toString().trim()
            visible.clear()
            visible.addAll(
                if (q.isEmpty()) allSymbols
                else allSymbols.filter {
                    it.name.contains(q, ignoreCase = true) || it.kind.contains(q, ignoreCase = true)
                }
            )
            adapter.clear()
            adapter.addAll(visible.map { labelOf(it) })
        }
        filterInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshFilter()
        })
        filterInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            ) {
                if (visible.isNotEmpty()) jumpTo(visible[0].line)
                true
            } else false
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in visible.indices) jumpTo(visible[position].line)
        }
        dialog.setOnShowListener {
            filterInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(filterInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private data class ProjectSymbol(
        val name: String, val kind: String, val fileName: String,
        val relPath: String, val line: Int, val doc: DocumentFile
    )

    /**
     * INV-154 Search Symbols in Project (VS Code Ctrl+T jaisa): poore folder ke
     * saare functions/classes/properties ek filterable list me — tap karne par
     * us file ki us line par jump. Indexing background me hoti hai.
     */
    private fun showProjectSymbolsDialog() {
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        fun labelOf(s: ProjectSymbol): String = "${s.name}  ·  ${s.kind}  ·  ${s.fileName}:${s.line}"
        val visible = mutableListOf<ProjectSymbol>()
        val indexed = mutableListOf<ProjectSymbol>()
        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf<String>()
        )
        val filterInput = android.widget.EditText(this).apply {
            hint = "Search symbols..."
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            val pad = (16 * density).toInt()
            setPadding(pad, (12 * density).toInt(), pad, (8 * density).toInt())
        }
        val statusView = android.widget.TextView(this).apply {
            text = "Indexing..."
            textSize = 12f
            val pad = (16 * density).toInt()
            setPadding(pad, (4 * density).toInt(), pad, (4 * density).toInt())
        }
        val listView = android.widget.ListView(this).apply { this.adapter = adapter }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(
                filterInput,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                statusView,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                listView,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (320 * density).toInt()
                )
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Symbols in Project")
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Fuzzy: query ke saare chars naam me isi order me hone chahiye.
        fun fuzzyMatch(name: String, q: String): Boolean {
            var ni = 0
            for (qc in q) {
                ni = name.indexOf(qc, ni, ignoreCase = true)
                if (ni < 0) return false
                ni++
            }
            return true
        }
        fun refreshFilter() {
            val q = filterInput.text.toString().trim()
            visible.clear()
            visible.addAll(
                if (q.isEmpty()) indexed
                else indexed.filter { fuzzyMatch(it.name, q) }
            )
            adapter.clear()
            adapter.addAll(visible.map { labelOf(it) })
            statusView.text =
                if (q.isEmpty()) "${indexed.size} symbols"
                else "${visible.size} of ${indexed.size}"
        }
        fun jumpTo(s: ProjectSymbol) {
            dialog.dismiss()
            binding.drawer.closeDrawer(GravityCompat.START)
            openFileInTab(s.doc, s.line)
        }
        filterInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshFilter()
        })
        filterInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            ) {
                if (visible.isNotEmpty()) jumpTo(visible[0])
                true
            } else false
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in visible.indices) jumpTo(visible[position])
        }
        dialog.setOnShowListener {
            filterInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(filterInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()

        val activeTab = openTabs.getOrNull(activeTabIndex)
        val activeBase: String? = activeTab?.let { binding.editor.getUnfoldedText() }
        lifecycleScope.launch(Dispatchers.IO) {
            val found = mutableListOf<ProjectSymbol>()
            var filesSeen = 0
            fun walk(dir: DocumentFile, prefix: String) {
                if (found.size >= 3000 || filesSeen >= 800) return
                val children = try { dir.listFiles() } catch (_: Exception) { return }
                for (child in children) {
                    if (found.size >= 3000 || filesSeen >= 800) return
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    try {
                        if (child.isDirectory) {
                            walk(child, "$prefix$name/")
                        } else if (child.isFile) {
                            filesSeen++
                            if (child.length() > 512 * 1024) continue
                            val bytes = try {
                                contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                            } catch (_: Exception) { null } ?: continue
                            if (bytes.size > 512 * 1024) continue
                            var bi = 0
                            var isBinary = false
                            while (bi < bytes.size && bi < 8192) {
                                if (bytes[bi] == 0.toByte()) { isBinary = true; break }
                                bi++
                            }
                            if (isBinary) continue
                            val tab = openTabs.find { it.uri == child.uri }
                            val ftext = if (tab != null && tab == activeTab && activeBase != null) {
                                activeBase
                            } else {
                                bytes.toString(Charsets.UTF_8)
                            }
                            val rel = "$prefix$name"
                            for (sym in parseSymbols(ftext)) {
                                if (found.size >= 3000) break
                                found.add(ProjectSymbol(sym.name, sym.kind, name, rel, sym.line, child))
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            try { walk(folder, "") } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                indexed.addAll(found)
                refreshFilter()
            }
        }
    }

    /** INV-162 Bookmarks panel: saare bookmarks ki list, tap par jump. */
    private fun showBookmarksPanel() {
        val items = binding.editor.getBookmarkedLines()
        if (items.isEmpty()) {
            Toast.makeText(this, "No bookmarks", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { (line, preview) ->
            "L$line  ·  ${preview.ifEmpty { "(blank line)" }}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Bookmarks (${items.size})")
            .setItems(labels) { _, which ->
                if (which in items.indices) {
                    pushNavPoint()
                    binding.editor.gotoLine(items[which].first)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Pure folder me text search — result par tap karne par file us line par khulti hai. */
    /** INV-157 Surround With: selection ko if/try/for/... template me lapeto. */
    private fun showSurroundWithDialog() {
        val ed = binding.editor
        if (ed.selectionStart == ed.selectionEnd) {
            Toast.makeText(this, "Select code first", Toast.LENGTH_SHORT).show()
            return
        }
        if (openTabs.isEmpty() || activeTabIndex !in openTabs.indices) return
        val ext = openTabs[activeTabIndex].name.substringAfterLast('.', "").lowercase()
        val items = if (ext in setOf("py", "pyw")) {
            arrayOf("if", "if / else", "try / except", "for", "while", "while True")
        } else {
            arrayOf("if", "if / else", "try / catch", "for", "while", "do / while")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Surround With")
            .setItems(items) { _, which -> ed.surroundSelection(which, ext) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * INV-163 Add Import: caret wale symbol ki class poore project me dhoondho
     * aur uska import current file me add karo (package line/imports ke baad).
     * 1 candidate = seedha insert, zyada = chooser dialog. Ek hi Undo step.
     */
    private fun addImportForCaretSymbol() {
        val word = binding.editor.wordAtCaret()
        if (word.isNullOrEmpty()) {
            Toast.makeText(this, "No symbol at cursor", Toast.LENGTH_SHORT).show()
            return
        }
        val tab = openTabs.getOrNull(activeTabIndex)
        if (tab == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val ext = tab.name.substringAfterLast('.', "").lowercase()
        if (ext != "kt" && ext != "java") {
            Toast.makeText(this, "Only for Kotlin/Java files", Toast.LENGTH_SHORT).show()
            return
        }
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        data class ImportCand(val pkg: String, val fileName: String)
        binding.editor.expandAllFoldsNow()
        val curText = binding.editor.text?.toString() ?: ""
        val curPkg = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE)
            .find(curText)?.groupValues?.get(1)
        lifecycleScope.launch(Dispatchers.IO) {
            val esc = Regex.escape(word)
            val clsPat = Regex("\\b(?:class|interface|object|enum)\\s+$esc\\b")
            val pkgPat = Regex("^\\s*package\\s+([\\w.]+)")
            val cands = mutableListOf<ImportCand>()
            val seen = mutableSetOf<String>()
            fun walk(dir: DocumentFile) {
                if (cands.size >= 20) return
                val children = try { dir.listFiles() } catch (_: Exception) { return }
                for (child in children) {
                    if (cands.size >= 20) return
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    try {
                        if (child.isDirectory) {
                            walk(child)
                        } else if (child.isFile) {
                            val ce = name.substringAfterLast('.', "").lowercase()
                            if (ce != "kt" && ce != "java") continue
                            if (child.length() > 512 * 1024) continue
                            val bytes = try {
                                contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                            } catch (_: Exception) { null } ?: continue
                            if (bytes.size > 512 * 1024) continue
                            var bi = 0
                            var isBinary = false
                            while (bi < bytes.size && bi < 8192) {
                                if (bytes[bi] == 0.toByte()) { isBinary = true; break }
                                bi++
                            }
                            if (isBinary) continue
                            val ftext = bytes.toString(Charsets.UTF_8)
                            var pkg: String? = null
                            var found = false
                            for (line in ftext.lineSequence()) {
                                if (pkg == null) {
                                    pkgPat.find(line)?.let { pkg = it.groupValues[1] }
                                }
                                if (clsPat.containsMatchIn(line)) {
                                    found = true
                                    break
                                }
                            }
                            if (found && pkg != null && seen.add(pkg!!)) {
                                cands.add(ImportCand(pkg!!, name))
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            try { walk(folder) } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                // Same package ya pehle se imported — kuch nahi karna.
                val useful = cands.filter { it.pkg != curPkg }
                if (useful.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        if (cands.isNotEmpty()) "Same package — no import needed"
                        else "No class '$word' found in project",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
                if (useful.size == 1) {
                    insertImport(useful[0].pkg, word)
                } else {
                    val labels = useful.map { "${it.pkg}.${word}  ·  ${it.fileName}" }.toTypedArray()
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Import '$word' from:")
                        .setItems(labels) { _, which ->
                            if (which in useful.indices) insertImport(useful[which].pkg, word)
                        }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
            }
        }
    }

    /** INV-163: import line insert karo (package/imports ke baad), single undo. */
    private fun insertImport(pkg: String, word: String) {
        val ed = binding.editor.text ?: return
        val importLine = "import $pkg.$word"
        val curText = ed.toString()
        val already = curText.lineSequence().any {
            val t = it.trim()
            t == importLine || t == "import $pkg.*"
        }
        if (already) {
            Toast.makeText(this, "Already imported", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = curText.split("\n")
        var packageLine = -1
        var lastImport = -1
        for ((i, line) in lines.withIndex()) {
            val t = line.trim()
            when {
                t.startsWith("package ") -> packageLine = i
                t.startsWith("import ") -> lastImport = i
            }
        }
        val insertLine = maxOf(packageLine, lastImport) + 1
        var off = 0
        for (i in 0 until insertLine.coerceIn(0, lines.size)) {
            off += lines[i].length + 1
        }
        ed.replace(off.coerceIn(0, ed.length), off.coerceIn(0, ed.length), importLine + "\n")
        Toast.makeText(this, "Imported $pkg.$word", Toast.LENGTH_SHORT).show()
    }

    /**
     * INV-164 Android Manifest Editor: versionCode/versionName edit, permissions
     * add/remove, activities list — sab visual, line-based surgical edits.
     */
    private data class ManifestInfo(
        val doc: DocumentFile,
        val pkg: String?,
        val versionCode: String?,
        val versionName: String?,
        val permissions: List<String>,
        val activities: List<String>
    )

    private val commonPermissions = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CAMERA",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.RECORD_AUDIO",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.VIBRATE",
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.USE_BIOMETRIC",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.NFC"
    )

    private fun showManifestEditor() {
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var manifestDoc: DocumentFile? = null
            fun walk(dir: DocumentFile): Boolean {
                val children = try { dir.listFiles() } catch (_: Exception) { return false }
                for (child in children) {
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    try {
                        if (child.isDirectory) {
                            if (walk(child)) return true
                        } else if (child.isFile && name == "AndroidManifest.xml") {
                            manifestDoc = child
                            return true
                        }
                    } catch (_: Exception) { }
                }
                return false
            }
            try { walk(folder) } catch (_: Exception) { }
            val info = manifestDoc?.let { parseManifest(it) }
            withContext(Dispatchers.Main) {
                if (info == null) {
                    Toast.makeText(this@MainActivity, "No AndroidManifest.xml found", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                showManifestSheet(info)
            }
        }
    }

    private fun parseManifest(doc: DocumentFile): ManifestInfo? {
        val text = try {
            contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
        } catch (_: Exception) { null } ?: return null
        val manifestTag = Regex("<manifest[^>]*>", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: ""
        fun attr(name: String): String? =
            Regex(Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"").find(manifestTag)?.groupValues?.get(1)
        val perms = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
            .findAll(text).map { it.groupValues[1] }.distinct().toList()
        val acts = Regex("<activity[^>]*android:name=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[1] }.distinct().toList()
        return ManifestInfo(doc, attr("package"), attr("android:versionCode"), attr("android:versionName"), perms, acts)
    }

    private fun showManifestSheet(info: ManifestInfo) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        fun label(text: String, bold: Boolean = false, size: Float = 14f): android.widget.TextView {
            return android.widget.TextView(this).apply {
                this.text = text
                textSize = size
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        container.addView(label("AndroidManifest.xml", bold = true, size = 18f))
        container.addView(label("Package: ${info.pkg ?: "Not set (AGP 8: build.gradle me hai)"}", size = 13f))

        val verCodeInput = android.widget.EditText(this).apply {
            hint = "Version code"
            setText(info.versionCode ?: "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val verNameInput = android.widget.EditText(this).apply {
            hint = "Version name (e.g. 1.2.3)"
            setText(info.versionName ?: "")
            isSingleLine = true
        }
        container.addView(label("Version code", bold = true))
        container.addView(verCodeInput)
        container.addView(label("Version name", bold = true))
        container.addView(verNameInput)
        val saveBtn = android.widget.Button(this).apply { text = "Save Versions" }
        container.addView(saveBtn)

        val permHeader = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        permHeader.addView(label("Permissions", bold = true), android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val addPermBtn = android.widget.Button(this).apply { text = "Add" }
        permHeader.addView(addPermBtn)
        container.addView(permHeader)
        val permList = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        container.addView(permList)

        container.addView(label("Activities", bold = true))
        if (info.activities.isEmpty()) {
            container.addView(label("(none)", size = 13f))
        } else {
            for (a in info.activities) container.addView(label("• $a", size = 13f))
        }

        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(scroll)

        fun shortPerm(p: String) = p.substringAfterLast('.')
        fun refreshPerms(perms: List<String>) {
            permList.removeAllViews()
            if (perms.isEmpty()) permList.addView(label("(none)", size = 13f))
            for (p in perms) {
                val row = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                row.addView(
                    label(shortPerm(p), size = 13f),
                    android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                val rm = android.widget.Button(this@MainActivity).apply { text = "✕" }
                rm.setOnClickListener {
                    applyManifestEdits(info.doc, { t -> removeManifestPermission(t, p) }, "Permission removed") {
                        showManifestSheet(parseManifest(info.doc) ?: info)
                    }
                    dialog.dismiss()
                }
                row.addView(rm)
                permList.addView(row)
            }
        }
        refreshPerms(info.permissions)

        saveBtn.setOnClickListener {
            val vc = verCodeInput.text.toString().trim()
            val vn = verNameInput.text.toString().trim()
            if (vc.isNotEmpty() && !vc.all { it.isDigit() }) {
                Toast.makeText(this, "Version code must be a number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            applyManifestEdits(info.doc, { t ->
                var r = setManifestAttr(t, "android:versionCode", vc)
                r = setManifestAttr(r, "android:versionName", vn)
                r
            }, "Versions saved") {
                showManifestSheet(parseManifest(info.doc) ?: info)
            }
            dialog.dismiss()
        }
        addPermBtn.setOnClickListener {
            val choices = commonPermissions.filter { it !in info.permissions }
            if (choices.isEmpty()) {
                Toast.makeText(this, "All common permissions added", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val labels = choices.map { it.substringAfterLast('.') }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("Add Permission")
                .setItems(labels) { _, which ->
                    if (which in choices.indices) {
                        applyManifestEdits(info.doc, { t -> addManifestPermission(t, choices[which]) }, "Permission added") {
                            showManifestSheet(parseManifest(info.doc) ?: info)
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
        dialog.show()
    }

    /**
     * Manifest text par transform lagao — khuli tab ho to usme (single undo),
     * warna seedha file me. onDone ke baad sheet refresh hoti hai.
     */
    private fun applyManifestEdits(
        doc: DocumentFile,
        transform: (String) -> String,
        toastMsg: String,
        onDone: () -> Unit
    ) {
        val tab = openTabs.find { it.uri == doc.uri }
        val oldText = if (tab != null) {
            if (tab == openTabs.getOrNull(activeTabIndex)) {
                binding.editor.expandAllFoldsNow()
                binding.editor.text?.toString()
            } else tab.content
        } else {
            try {
                contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            } catch (_: Exception) { null }
        } ?: return
        val newText = transform(oldText)
        if (newText == oldText) {
            onDone()
            return
        }
        if (tab != null) {
            tab.content = newText
            tab.isModified = true
            if (tab == openTabs.getOrNull(activeTabIndex)) {
                binding.editor.text?.replace(0, binding.editor.length(), newText)
            }
            renderTabs()
        } else {
            try {
                contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                    out.write(newText.toByteArray())
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Could not write manifest", Toast.LENGTH_SHORT).show()
                return
            }
        }
        saveSessionState()
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        onDone()
    }

    /** manifest tag me attr set karo (blank value = attr hatao). */
    private fun setManifestAttr(text: String, attr: String, value: String): String {
        val tagPat = Regex("<manifest[^>]*>", RegexOption.DOT_MATCHES_ALL)
        val m = tagPat.find(text) ?: return text
        val tag = m.value
        val attrPat = Regex("\\s*" + Regex.escape(attr) + "\\s*=\\s*\"[^\"]*\"")
        val newTag = if (value.isBlank()) {
            attrPat.replace(tag, "")
        } else if (attrPat.containsMatchIn(tag)) {
            attrPat.replace(tag) { "$attr=\"$value\"" }
        } else {
            tag.replaceFirst("<manifest", "<manifest\n    $attr=\"$value\"")
        }
        return text.replaceRange(m.range, newTag)
    }

    private fun removeManifestPermission(text: String, perm: String): String {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        return text.split("\n")
            .map { it.trimEnd('\r') }
            .filterNot { it.contains("<uses-permission") && it.contains("\"$perm\"") }
            .joinToString(eol)
    }

    private fun addManifestPermission(text: String, perm: String): String {
        if (text.contains("\"$perm\"")) return text
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split("\n").map { it.trimEnd('\r') }.toMutableList()
        var insertAt = -1
        for ((i, line) in lines.withIndex()) {
            if (line.contains("<uses-permission")) insertAt = i
        }
        val indent = if (insertAt >= 0) {
            lines[insertAt].takeWhile { it == ' ' || it == '\t' }
        } else {
            "    "
        }
        val newLine = "$indent<uses-permission android:name=\"$perm\" />"
        if (insertAt >= 0) {
            lines.add(insertAt + 1, newLine)
        } else {
            val mi = lines.indexOfFirst { it.contains("<manifest") }
            lines.add(if (mi >= 0) mi + 1 else 0, newLine)
        }
        return lines.joinToString(eol)
    }

    /** Caret wale word ka naam poori file me badalta hai (whole-word, ek Undo). */
    private fun showRenameSymbolDialog() {
        val word = binding.editor.wordAtCaret()
        if (word.isNullOrEmpty()) {
            Toast.makeText(this, "No word at cursor", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            setText(word)
            selectAll()
            hint = "New name"
        }
        val renameDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Rename \"" + word + "\" in file")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val count = binding.editor.renameSymbol(word, input.text.toString())
                Toast.makeText(
                    this,
                    if (count > 0) "$count occurrence(s) renamed" else "Nothing renamed",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.close, null)
            .create()
        // Enter dabane par Rename.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val count = binding.editor.renameSymbol(word, input.text.toString())
                Toast.makeText(
                    this,
                    if (count > 0) "$count occurrence(s) renamed" else "Nothing renamed",
                    Toast.LENGTH_SHORT
                ).show()
                renameDialog.dismiss()
                true
            } else false
        }
        renameDialog.setOnShowListener {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        renameDialog.show()
    }

    /**
     * INV-152 Rename Symbol in Project: caret wale word ka poore project me
     * whole-word rename. Pehle naya naam puchhta hai, phir background me scan
     * karke per-file counts ke saath confirm dialog, phir apply. Active tab me
     * ek hi Undo step; unsaved-changes wali aur read-only files skip.
     */
    /** XML me hardcoded string ko strings.xml me extract karo: "text" -> "@string/name". */
    private fun extractStringResourceAtCaret() {
        val tab = openTabs.getOrNull(activeTabIndex)
        if (tab == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val name = try {
            GitManager.resolveDocToFile(currentFileUri!!)?.name ?: ""
        } catch (e: Exception) { "" }
        if (!name.endsWith(".xml", ignoreCase = true)) {
            Toast.makeText(this, "Sirf XML files me kaam karta hai", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val full = ed.text?.toString() ?: return
        val selS = ed.selectionStart; val selE = ed.selectionEnd
        // Quoted literal ka range nikalo: selection ho to wahi, warna caret wali "...".
        var qs = -1; var qe = -1; var value = ""
        if (selE > selS) {
            var a = selS; var b = selE
            if (full.getOrNull(a) == '"') b = (full.indexOf('"', a + 1).takeIf { it >= 0 } ?: (b - 1)) + 1
            else {
                if (full.getOrNull(a - 1) == '"') a -= 1
                val close = full.indexOf('"', b)
                if (close >= 0) b = close + 1
            }
            if (full.getOrNull(a) == '"' && full.getOrNull(b - 1) == '"' && b > a + 1) {
                qs = a; qe = b; value = full.substring(a + 1, b - 1)
            }
        } else {
            val lineS = full.lastIndexOf('\n', (selS - 1).coerceAtLeast(0)) + 1
            val lineE = full.indexOf('\n', selS).let { if (it < 0) full.length else it }
            val line = full.substring(lineS, lineE)
            val caretInLine = selS - lineS
            for (m in Regex("\"([^\"\\n]*?)\"").findAll(line)) {
                if (m.range.first <= caretInLine && caretInLine <= m.range.last + 1) {
                    qs = lineS + m.range.first; qe = lineS + m.range.last + 1
                    value = m.groupValues[1]; break
                }
            }
        }
        if (qs < 0 || value.isBlank()) {
            Toast.makeText(this, "Caret ko kisi \"text\" par rakho", Toast.LENGTH_SHORT).show(); return
        }
        if (value.startsWith("@string/") || value.startsWith("@")) {
            Toast.makeText(this, "Ye pehle se resource hai", Toast.LENGTH_SHORT).show(); return
        }
        val stringsFile = findStringsXml()
        if (stringsFile == null) {
            Toast.makeText(this, "res/values/strings.xml nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        // Naam suggest karo: lowercase_underscore.
        var sug = value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (sug.isEmpty()) sug = "string_res"
        if (sug.first().isDigit()) sug = "str_$sug"
        val input = android.widget.EditText(this).apply {
            setText(sug); selectAll()
            hint = "string_name"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Extract String Resource")
            .setMessage("\"" + value.take(80) + "\"")
            .setView(wrap)
            .setPositiveButton("Extract") { _, _ ->
                val resName = input.text.toString().trim()
                if (!Regex("^[a-z][a-z0-9_]*$").matches(resName)) {
                    Toast.makeText(this, "Naam: a-z, 0-9, _ (letter se shuru)", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val (lines, entries) = parseStringResources(stringsFile)
                if (entries.any { it.name == resName }) {
                    Toast.makeText(this, "Ye naam pehle se hai", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                // 1) Editor me "@string/name" (single undo).
                val live = binding.editor.text?.toString() ?: return@setPositiveButton
                if (qe <= live.length && live.substring(qs, qe) == "\"" + value + "\"") {
                    binding.editor.expandAllFoldsNow()
                    binding.editor.text?.replace(qs, qe, "\"@string/$resName\"")
                }
                // 2) strings.xml me entry jodo (</resources> se pehle).
                val indent = lines.firstOrNull { it.trimStart().startsWith("<string ") }
                    ?.let { it.substring(0, it.length - it.trimStart().length) } ?: "    "
                val newLine = "$indent<string name=\"$resName\">${escapeStringRes(value)}</string>"
                val closeIdx = lines.indexOfLast { it.contains("</resources>") }
                val out = lines.toMutableList()
                if (closeIdx >= 0) out.add(closeIdx, newLine) else out.add(newLine)
                writeStringsXml(stringsFile, out)
                Toast.makeText(this, "@string/$resName ban gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** build.gradle(.kts) dhoondho: pehle app/ module, phir upar walk. */
    private fun findBuildGradle(): java.io.File? {
        val uri = openTabs.getOrNull(activeTabIndex)?.uri ?: return null
        val file = try { GitManager.resolveDocToFile(uri) } catch (e: Exception) { null } ?: return null
        var dir: java.io.File? = file.parentFile
        var guard = 0
        while (dir != null && guard++ < 12) {
            for (nm in listOf("app/build.gradle.kts", "app/build.gradle",
                              "build.gradle.kts", "build.gradle")) {
                val f = java.io.File(dir, nm)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }

    /** Nayi Activity banao: Kotlin file + manifest entry, phir file kholo. */
    private fun showNewActivityDialog() {
        val resDir = findResDir()
        if (resDir == null) {
            Toast.makeText(this, "Project folder nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val root = resDir.parentFile ?: return
        val input = android.widget.EditText(this).apply { hint = "SettingsActivity" }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("New Activity")
            .setView(wrap)
            .setPositiveButton("Create") { _, _ ->
                var name = input.text.toString().trim().removeSurrounding("`")
                if (!Regex("^[A-Z][A-Za-z0-9_]*$").matches(name)) {
                    Toast.makeText(this, "PascalCase naam likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!name.endsWith("Activity")) name += "Activity"
                createActivityFiles(root, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createActivityFiles(root: java.io.File, name: String) {
        Thread {
            try {
                // 1. Manifest dhoondho (File walk, 6 level tak).
                var manifest: java.io.File? = null
                fun walkM(dir: java.io.File, depth: Int) {
                    if (manifest != null || depth > 6) return
                    val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: return
                    for (k in kids) {
                        if (manifest != null) return
                        if (k.isDirectory) {
                            if (k.name != "build" && !k.name.startsWith(".")) walkM(k, depth + 1)
                        } else if (k.name == "AndroidManifest.xml") { manifest = k; return }
                    }
                }
                walkM(root, 0)
                val mf = manifest ?: run {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "AndroidManifest.xml nahi mila",
                            Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val mText = mf.readText()
                val pkg = Regex("package\\s*=\\s*\"([^\"]+)\"").find(mText)?.groupValues?.get(1)
                if (pkg.isNullOrBlank()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Manifest me package nahi mila",
                            Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                // 2. Source dir: app/src/main/java (ya kotlin).
                val srcDir = listOf(java.io.File(root, "app/src/main/java"),
                                    java.io.File(root, "app/src/main/kotlin"),
                                    java.io.File(root, "src/main/java"))
                               .firstOrNull { it.isDirectory } ?: run {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Source dir nahi mila",
                            Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val pkgDir = java.io.File(srcDir, pkg.replace('.', '/'))
                if (!pkgDir.isDirectory && !pkgDir.mkdirs()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Package dir nahi bana",
                            Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val ktFile = java.io.File(pkgDir, "$name.kt")
                if (ktFile.exists()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "File pehle se hai",
                            Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                // 3. Kotlin file likho.
                ktFile.writeText(
                    "package " + pkg + "\n\n" +
                    "import android.os.Bundle\n" +
                    "import androidx.appcompat.app.AppCompatActivity\n\n" +
                    "class " + name + " : AppCompatActivity() {\n\n" +
                    "    override fun onCreate(savedInstanceState: Bundle?) {\n" +
                    "        super.onCreate(savedInstanceState)\n" +
                    "    }\n" +
                    "}\n"
                )
                // 4. Manifest me <activity> jodo (</application> se pehle).
                val nl = if (mText.contains("\r\n")) "\r\n" else "\n"
                val lines = mText.split(nl).toMutableList()
                val appClose = lines.indexOfLast { it.contains("</application>") }
                if (appClose < 0) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "<application> nahi mila",
                            Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val indent = lines[appClose].substring(0,
                    lines[appClose].length - lines[appClose].trimStart().length) + "    "
                val entry = indent + "<activity android:name=\"." + name +
                            "\" android:exported=\"false\" />"
                if (!mText.contains("\"." + name + "\"")) {
                    lines.add(appClose, entry)
                    mf.writeText(lines.joinToString(nl))
                }
                runOnUiThread {
                    try {
                        openFileInTab(androidx.documentfile.provider.DocumentFile.fromFile(ktFile))
                        Toast.makeText(this@MainActivity, name + " ban gaya", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "File ban gayi, khul nahi payi",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fail: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Gradle dependency jodo: notation + configuration puchhkar dependencies { } me insert. */
    private fun showAddDependencyDialog() {
        val gradleFile = findBuildGradle()
        if (gradleFile == null) {
            Toast.makeText(this, "build.gradle nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val isKts = gradleFile.name.endsWith(".kts")
        val den = resources.displayMetrics.density
        val pad = (20 * den).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = "androidx.core:core-ktx:1.12.0"
        }
        val configs = arrayOf("implementation", "api", "testImplementation",
                              "debugImplementation", "androidTestImplementation")
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, configs)
        }
        layout.addView(input)
        layout.addView(spinner)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Add Gradle Dependency")
            .setMessage(gradleFile.name)
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val notation = input.text.toString().trim()
                if (!Regex("^[\\w.\\-]+:[\\w.\\-]+:[\\w.\\-\\+]+$").matches(notation)) {
                    Toast.makeText(this, "Format: group:artifact:version", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val config = configs[spinner.selectedItemPosition]
                val artifact = notation.split(":")[1]
                val raw = try { gradleFile.readText() } catch (e: Exception) { "" }
                val depStart = Regex("dependencies\\s*\\{").find(raw)?.range?.last
                if (depStart == null) {
                    Toast.makeText(this, "dependencies { } nahi mila", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val depEnd = matchScope(raw, depStart, '{', '}') ?: run {
                    Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val block = raw.substring(depStart, depEnd)
                if (block.contains(artifact)) {
                    Toast.makeText(this, "Ye artifact pehle se hai", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val quote = if (isKts) "\"" else "'"
                val newLine = "    " + config + "(" + quote + notation + quote + ")"
                // Aakhri non-blank line ke baad, closing brace se pehle jodo.
                val inner = raw.substring(depStart + 1, depEnd - 1)
                val lines = inner.split("\n").toMutableList()
                var insAt = lines.size
                while (insAt > 0 && lines[insAt - 1].isBlank()) insAt--
                lines.add(insAt, newLine)
                val newRaw = raw.substring(0, depStart + 1) + lines.joinToString("\n") +
                             raw.substring(depEnd - 1)
                try { gradleFile.writeText(newRaw) } catch (e: Exception) {
                    Toast.makeText(this, "Likh nahi paya", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Khuli tab ho to refresh karo.
                val tab = openTabs.find {
                    try { GitManager.resolveDocToFile(it.uri ?: return@find false) == gradleFile }
                    catch (e: Exception) { false }
                }
                if (tab != null) {
                    if (openTabs.indexOf(tab) == activeTabIndex) reloadTabFromDisk(tab)
                    else { tab.content = newRaw; tab.diskContent = newRaw; tab.isModified = false }
                    renderTabs()
                }
                Toast.makeText(this, "Dependency jud gayi — Gradle sync karo", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** res/ folder dhoondho (current file se upar walk). */
    private fun findResDir(): java.io.File? {
        val uri = openTabs.getOrNull(activeTabIndex)?.uri ?: return null
        val file = try { GitManager.resolveDocToFile(uri) } catch (e: Exception) { null } ?: return null
        var dir: java.io.File? = file.parentFile
        var guard = 0
        while (dir != null && guard++ < 12) {
            val f = java.io.File(dir, "res")
            if (f.isDirectory) return f
            dir = dir.parentFile
        }
        return null
    }

    private data class UnusedRes(val type: String, val name: String, val file: java.io.File)

    /** MD5 hash se duplicate files dhoondho (cleanup ke liye). */
    private fun findDuplicateFiles() {
        val resDir = findResDir()
        if (resDir == null) {
            Toast.makeText(this, "Project folder nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val projectRoot = resDir.parentFile ?: return
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Duplicate files dhoondh rahe hain...")
            .setView(android.widget.ProgressBar(this).apply {
                isIndeterminate = true
                val p = (32 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            })
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            try {
                val skipDirs = setOf("build", ".git", ".gradle", ".idea")
                val byHash = mutableMapOf<String, MutableList<java.io.File>>()
                val md = java.security.MessageDigest.getInstance("MD5")
                val buf = ByteArray(8192)
                fun hashOf(f: java.io.File): String? {
                    return try {
                        md.reset()
                        f.inputStream().buffered().use { ins ->
                            var r = ins.read(buf)
                            while (r > 0) { md.update(buf, 0, r); r = ins.read(buf) }
                        }
                        md.digest().joinToString("") { "%02x".format(it) }
                    } catch (e: Exception) { null }
                }
                fun walk(dir: java.io.File) {
                    val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: return
                    for (k in kids) {
                        if (k.isDirectory) { if (k.name !in skipDirs) walk(k); continue }
                        if (k.length() == 0L || k.length() > 20_000_000) continue
                        val h = hashOf(k) ?: continue
                        byHash.getOrPut(h) { mutableListOf() }.add(k)
                    }
                }
                walk(projectRoot)
                val groups = byHash.values.filter { it.size > 1 }
                    .sortedByDescending { it[0].length() }
                runOnUiThread { dlg.dismiss(); showDuplicateFilesSheet(groups) }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this@MainActivity, "Scan fail: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showDuplicateFilesSheet(groups: List<List<java.io.File>>) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val den = resources.displayMetrics.density
        val pad = (16 * den).toInt()
        val rootPath = try { findResDir()?.parentFile?.absolutePath ?: "" } catch (e: Exception) { "" }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val totalDupes = groups.sumOf { it.size - 1 }
        root.addView(android.widget.TextView(this).apply {
            text = if (groups.isEmpty()) "Koi duplicate file nahi mili!"
                   else groups.size.toString() + " groups, " + totalDupes.toString() + " extra copies"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (8 * den).toInt())
        })
        if (groups.isNotEmpty()) {
            root.addView(android.widget.TextView(this).apply {
                text = "Tap karke kholo — delete khud karo."
                textSize = 13f
                setTextColor(Color.parseColor("#9AA0B4"))
                setPadding(0, 0, 0, (8 * den).toInt())
            })
            val list = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            for (g in groups) {
                list.addView(android.widget.TextView(this).apply {
                    text = android.text.format.Formatter.formatFileSize(this@MainActivity, g[0].length()) +
                           "  •  " + g.size.toString() + " copies"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#8B7CF6"))
                    val hp = (10 * den).toInt()
                    setPadding(hp, (8 * den).toInt(), hp, (2 * den).toInt())
                })
                for (f in g) {
                    val row = android.widget.TextView(this).apply {
                        text = f.name + "\n" + (try {
                            f.parentFile?.absolutePath?.substringAfter(rootPath) ?: ""
                        } catch (e: Exception) { "" })
                        textSize = 13f
                        val rp = (10 * den).toInt()
                        setPadding(rp * 2, (6 * den).toInt(), rp, (6 * den).toInt())
                        setOnClickListener {
                            sheet.dismiss()
                            try {
                                openFileInTab(androidx.documentfile.provider.DocumentFile.fromFile(f))
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Khul nahi paya", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    list.addView(row)
                }
            }
            val scroll = android.widget.ScrollView(this)
            scroll.addView(list)
            root.addView(scroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        sheet.setContentView(root)
        sheet.show()
    }

    /** Project me unused resources dhoondho: drawables, layouts, strings, colors, dimens. */
    private fun findUnusedResources() {
        val resDir = findResDir()
        if (resDir == null) {
            Toast.makeText(this, "res/ folder nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val projectRoot = resDir.parentFile ?: return
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Unused resources dhoondh rahe hain...")
            .setView(android.widget.ProgressBar(this).apply {
                isIndeterminate = true
                val p = (32 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            })
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            try {
                // 1) Saare resources collect karo.
                val resources = mutableListOf<UnusedRes>()
                val valRx = Regex("<(string|color|dimen)\\s+name=\"([^\"]+)\"")
                fun walkRes(dir: java.io.File) {
                    val kids = dir.listFiles() ?: return
                    for (k in kids) {
                        if (k.isDirectory) { walkRes(k); continue }
                        val parent = k.parentFile?.name ?: ""
                        when {
                            parent.startsWith("drawable") || parent.startsWith("mipmap") -> {
                                val nm = k.nameWithoutExtension
                                if (nm != "ic_launcher" && nm != "ic_launcher_round")
                                    resources.add(UnusedRes("drawable", nm, k))
                            }
                            parent == "layout" ->
                                resources.add(UnusedRes("layout", k.nameWithoutExtension, k))
                            parent.startsWith("values") && k.name.endsWith(".xml") -> {
                                val text = try { k.readText() } catch (e: Exception) { "" }
                                for (m in valRx.findAll(text))
                                    resources.add(UnusedRes(m.groupValues[1], m.groupValues[2], k))
                            }
                        }
                    }
                }
                walkRes(resDir)
                // 2) Poore project ka text EK BAAR padho (build/.git/binaries skip).
                val skipDirs = setOf("build", ".git", ".gradle", ".idea")
                val skipExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp",
                    "jar", "class", "so", "aar", "dex", "ap_", "bin")
                val big = StringBuilder()
                fun walkProj(dir: java.io.File) {
                    val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: return
                    for (k in kids) {
                        if (k.isDirectory) { if (k.name !in skipDirs) walkProj(k) }
                        else {
                            if (k.extension.lowercase() in skipExt) continue
                            if (k.length() > 2_000_000) continue
                            try { big.append('\n').append(k.readText()) } catch (e: Exception) { }
                        }
                    }
                }
                walkProj(projectRoot)
                val haystack = big.toString()
                // 3) Reference check: R.type.name ya @type/name.
                val unused = resources.filter { r ->
                    !haystack.contains("R." + r.type + "." + r.name) &&
                    !haystack.contains("@" + r.type + "/" + r.name)
                }.distinctBy { it.type + "/" + it.name }
                runOnUiThread { dlg.dismiss(); showUnusedResourcesSheet(unused) }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this@MainActivity, "Scan fail: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Unused resources ki report: tap karke file kholo. */
    private fun showUnusedResourcesSheet(unused: List<UnusedRes>) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val den = resources.displayMetrics.density
        val pad = (16 * den).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = android.widget.TextView(this).apply {
            text = if (unused.isEmpty()) "Sab clean — koi unused resource nahi!"
                   else unused.size.toString() + " unused resources"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (4 * den).toInt())
        }
        root.addView(title)
        if (unused.isNotEmpty()) {
            val hint = android.widget.TextView(this).apply {
                text = "Tap karke file kholo — delete khud karo (safe side)."
                textSize = 13f
                setTextColor(Color.parseColor("#9AA0B4"))
                setPadding(0, 0, 0, (8 * den).toInt())
            }
            root.addView(hint)
            val list = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            for (r in unused) {
                val row = android.widget.TextView(this).apply {
                    text = r.type + "/" + r.name + "   (" + r.file.name + ")"
                    textSize = 14f
                    val rp = (10 * den).toInt()
                    setPadding(rp, rp, rp, rp)
                    setOnClickListener {
                        sheet.dismiss()
                        try {
                            openFileInTab(androidx.documentfile.provider.DocumentFile.fromFile(r.file))
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Khul nahi paya", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                list.addView(row)
                val div = android.view.View(this).apply {
                    setBackgroundColor(Color.parseColor("#232838"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                }
                list.addView(div)
            }
            val scroll = android.widget.ScrollView(this)
            scroll.addView(list)
            root.addView(scroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        sheet.setContentView(root)
        sheet.show()
    }

    private data class IfBranch(val cond: String, val stmt: String, val hadBraces: Boolean)

    /** Caret wali `class Foo(val x: ...)` ko `data class` me badlo. */
    private fun convertToDataClass() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        // Caret se pehle najdeeki ASLI `class` keyword dhoondho (comment/string wala nahi).
        val classRx = Regex("\\bclass\\b")
        val maskedOnce = maskCodeForRefs(text)
        var ck = -1
        for (m in classRx.findAll(text).filter { it.range.first < caret }.toList().asReversed()) {
            if (maskedOnce[m.range.first] != ' ') { ck = m.range.first; break }
        }
        if (ck < 0) {
            Toast.makeText(this, "Caret ko class par rakho", Toast.LENGTH_SHORT).show(); return
        }
        // Modifiers check (class se pehle wali line ka hissa).
        val lineS = text.lastIndexOf('\n', (ck - 1).coerceAtLeast(0)) + 1
        val before = text.substring(lineS, ck)
        if ("data" in before.split(Regex("\\s+"))) {
            Toast.makeText(this, "Pehle se data class hai", Toast.LENGTH_SHORT).show(); return
        }
        for (bad in listOf("enum", "interface", "object", "annotation", "sealed", "abstract", "inner")) {
            if (bad in before.split(Regex("\\s+"))) {
                Toast.makeText(this, "`$bad` ko data class nahi bana sakte", Toast.LENGTH_SHORT).show(); return
            }
        }
        // class Name<...>(params) parse karo.
        var i = ck + 5
        val n = text.length
        while (i < n && text[i].isWhitespace()) i++
        if (i < n && text[i] == '`') {
            i++
            while (i < n && text[i] != '`') i++
            i++
        } else {
            while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        }
        while (i < n && text[i].isWhitespace()) i++
        if (i < n && text[i] == '<') {
            i = matchScope(text, i, '<', '>') ?: run {
                Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
            }
            while (i < n && text[i].isWhitespace()) i++
        }
        if (text.startsWith("constructor", i) &&
            (i + 11 >= n || (!text[i + 11].isLetterOrDigit() && text[i + 11] != '_'))) {
            i += 11
            while (i < n && text[i].isWhitespace()) i++
        }
        if (i >= n || text[i] != '(') {
            Toast.makeText(this, "Primary constructor chahiye", Toast.LENGTH_SHORT).show(); return
        }
        val pEnd = matchScope(text, i, '(', ')') ?: run {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val params = text.substring(i + 1, pEnd - 1)
        if (!Regex("\\b(val|var)\\b").containsMatchIn(params)) {
            Toast.makeText(this, "Kam se kam ek val/var property chahiye", Toast.LENGTH_SHORT).show(); return
        }
        ed.expandAllFoldsNow()
        ed.text?.replace(ck, ck + 5, "data class")
        Toast.makeText(this, "Data class ban gaya", Toast.LENGTH_SHORT).show()
    }

    private data class TabSession(val name: String, val uris: List<String>, val active: Int)

    /** Khuli tabs ka named session save karo. */
    private fun saveTabSession() {
        if (openTabs.isEmpty()) {
            Toast.makeText(this, "Koi tab khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val input = android.widget.EditText(this).apply { hint = "Session naam" }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Save Tab Session")
            .setMessage(openTabs.size.toString() + " tabs")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Naam likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val sessions = loadTabSessions().toMutableList()
                sessions.removeAll { it.name == name }
                sessions.add(0, TabSession(name,
                    openTabs.mapNotNull { it.uri?.toString() }, activeTabIndex))
                saveTabSessions(sessions)
                Toast.makeText(this, "Session save ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tabSessionsPrefs() =
        getSharedPreferences("tab_sessions", android.content.Context.MODE_PRIVATE)

    private fun loadTabSessions(): List<TabSession> {
        val out = mutableListOf<TabSession>()
        try {
            val arr = org.json.JSONArray(
                tabSessionsPrefs().getString("sessions", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uris = mutableListOf<String>()
                val ua = o.optJSONArray("uris")
                if (ua != null) for (j in 0 until ua.length()) uris.add(ua.getString(j))
                out.add(TabSession(o.getString("name"), uris, o.optInt("active", 0)))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveTabSessions(sessions: List<TabSession>) {
        try {
            val arr = org.json.JSONArray()
            for (s in sessions.take(20)) {
                val o = org.json.JSONObject()
                o.put("name", s.name)
                o.put("active", s.active)
                val ua = org.json.JSONArray()
                for (u in s.uris) ua.put(u)
                o.put("uris", ua)
                arr.put(o)
            }
            tabSessionsPrefs().edit().putString("sessions", arr.toString()).apply()
        } catch (e: Exception) { }
    }

    /** Saved sessions ki list — tap karke restore, long-press karke delete. */
    private fun showTabSessionsDialog() {
        val sessions = loadTabSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(this, "Koi session nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val names = sessions.map { it.name + "  (" + it.uris.size.toString() + " tabs)" }
            .toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Tab Sessions")
            .setItems(names) { d, which ->
                d.dismiss()
                restoreTabSession(sessions[which])
            }
            .setNeutralButton("Delete...") { _, _ -> showDeleteSessionDialog(sessions) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteSessionDialog(sessions: List<TabSession>) {
        val names = sessions.map { it.name }.toTypedArray()
        val checked = BooleanArray(names.size)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Delete Sessions")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Delete") { _, _ ->
                saveTabSessions(sessions.filterIndexed { i, _ -> !checked[i] })
                Toast.makeText(this, "Delete ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Session restore karo: saari tabs band karke saved wali kholo. */
    private fun restoreTabSession(session: TabSession) {
        if (session.uris.isEmpty()) {
            Toast.makeText(this, "Session khaali hai", Toast.LENGTH_SHORT).show(); return
        }
        // Bina save wali tabs ka puchho? Seedha: sab band karo (dirty tabs confirm).
        val dirty = openTabs.any { it.isModified }
        val doRestore = {
            // Saari tabs band karo (performCloseTab: closedTabs backup + pinned safe).
            for (i in openTabs.size - 1 downTo 0) performCloseTab(i)
            // Saved uris kholo.
            var opened = 0
            for (u in session.uris) {
                try {
                    val doc = androidx.documentfile.provider.DocumentFile
                        .fromSingleUri(this, android.net.Uri.parse(u)) ?: continue
                    if (!doc.isFile) continue
                    openFileInTab(doc)
                    opened++
                } catch (e: Exception) { }
            }
            // performCloseTab ne khaali hone par jo untitled banayi thi, use hatao.
            if (opened > 0) {
                val ui = openTabs.indexOfFirst {
                    it.uri == null && !it.isModified && it.content.isEmpty()
                }
                if (ui >= 0) {
                    openTabs.removeAt(ui)
                    if (activeTabIndex >= openTabs.size) activeTabIndex = openTabs.size - 1
                    renderTabs()
                }
            }
            val target = session.active.coerceIn(0, openTabs.size - 1)
            if (target in openTabs.indices) switchTab(target)
            Toast.makeText(this,
                if (opened == 0) "Koi file nahi khul payi"
                else opened.toString() + " tabs restore ho gayin",
                Toast.LENGTH_SHORT).show()
        }
        if (dirty) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Unsaved changes hain")
                .setMessage("Restore karne se bina-save tabs band ho jayengi. Aage badho?")
                .setPositiveButton("Restore") { _, _ -> doRestore() }
                .setNegativeButton("Cancel", null)
                .show()
        } else doRestore()
    }

    /** Caret wali top-level class ko nayi file me le jao (package + imports samet). */
    private fun moveClassToNewFile() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val curFile = try { GitManager.resolveDocToFile(currentFileUri!!) } catch (e: Exception) { null }
        if (curFile == null || !curFile.name.endsWith(".kt")) {
            Toast.makeText(this, "Kotlin (.kt) file kholo", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val maskedOnce = maskCodeForRefs(text)
        // Nearest top-level class/object/interface dhoondho.
        var ck = -1
        var keyword = ""
        for (m in Regex("\\b(class|object|interface)\\b").findAll(maskedOnce)
                 .filter { it.range.first < caret }.toList().asReversed()) {
            if (maskedOnce[m.range.first] != ' ') continue
            // Top-level check: isse pehle braces balanced hone chahiye.
            var depth = 0
            var ok = true
            var qi = 0
            while (qi < m.range.first) {
                val ch = maskedOnce[qi]
                if (ch == '{') depth++
                else if (ch == '}') depth--
                if (depth < 0) { ok = false; break }
                qi++
            }
            if (ok && depth == 0) { ck = m.range.first; keyword = m.groupValues[1]; break }
        }
        if (ck < 0) {
            Toast.makeText(this, "Caret ko top-level class par rakho", Toast.LENGTH_SHORT).show(); return
        }
        if (keyword != "class") {
            Toast.makeText(this, "Sirf `class` move hoti hai", Toast.LENGTH_SHORT).show(); return
        }
        // Class ka poora range nikalo: keyword se body-end (ya header-end) tak.
        var i = ck + 5
        val n = text.length
        while (i < n && text[i].isWhitespace()) i++
        // Naam.
        val nameStart = i
        if (i < n && text[i] == '`') {
            i++
            while (i < n && text[i] != '`') i++
            i++
        } else {
            while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        }
        val className = text.substring(nameStart, i).trim('`')
        if (className.isEmpty()) {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        while (i < n && text[i].isWhitespace()) i++
        if (i < n && text[i] == '<') {
            i = matchScope(text, i, '<', '>') ?: run {
                Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
            }
            while (i < n && text[i].isWhitespace()) i++
        }
        if (text.startsWith("constructor", i) && isWordBoundary(text, i, 11)) {
            i += 11
            while (i < n && text[i].isWhitespace()) i++
        }
        if (i < n && text[i] == '(') {
            i = matchScope(text, i, '(', ')') ?: run {
                Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
            }
            while (i < n && text[i].isWhitespace()) i++
        }
        // Supertypes / body.
        var classEnd: Int
        if (i < n && text[i] == '{') {
            classEnd = matchScope(text, i, '{', '}') ?: run {
                Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
            }
        } else if (i < n && text[i] == ':') {
            // `class Foo : Bar` — supertype ke baad body ya end.
            var j = i + 1
            var depth = 0
            var inS = false; var inC = false; var esc = false
            var stop = -1
            while (j < n) {
                val ch = text[j]
                if (esc) { esc = false; j++; continue }
                if (ch == '\\' && (inS || inC)) { esc = true; j++; continue }
                if (inS) { if (ch == '"') inS = false; j++; continue }
                if (inC) { if (ch == '\'') inC = false; j++; continue }
                if (ch == '"') { inS = true; j++; continue }
                if (ch == '\'') { inC = true; j++; continue }
                if (ch == '(' || ch == '[') depth++
                else if (ch == ')' || ch == ']') { if (depth > 0) depth-- }
                else if (ch == '{' && depth == 0) { stop = j; break }
                else if (ch == '\n' && depth == 0) { stop = j; break }
                j++
            }
            if (stop < 0) {
                Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
            }
            classEnd = if (text[stop] == '{') {
                matchScope(text, stop, '{', '}') ?: run {
                    Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
                }
            } else stop
        } else {
            classEnd = i
        }
        // KDoc/comment upar hai to saath le jao.
        var ls = text.lastIndexOf('\n', (ck - 1).coerceAtLeast(0)) + 1
        while (ls > 0) {
            val prevEnd = ls - 1
            val prevStart = text.lastIndexOf('\n', (prevEnd - 1).coerceAtLeast(0)) + 1
            val prevLine = text.substring(prevStart, prevEnd).trim()
            if (prevLine.startsWith("/**") || prevLine.startsWith("*") ||
                prevLine.startsWith("*/") || prevLine.startsWith("//") ||
                prevLine.startsWith("@")) {
                ls = prevStart
            } else break
        }
        // Lekin modifiers wali line chhootni nahi chahiye — clsStart ko line start tak badhao
        // sirf agar wahan se `class` tak sirf modifiers/annotations hon.
        val classText = text.substring(ls, classEnd).trim('\n')
        val pkg = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: ""
        val imports = Regex("^import\\s+.*$", RegexOption.MULTILINE).findAll(text)
            .map { it.value }.distinct().joinToString("\n")
        val newFile = java.io.File(curFile.parentFile, "$className.kt")
        if (newFile.exists()) {
            Toast.makeText(this, "$className.kt pehle se hai", Toast.LENGTH_SHORT).show(); return
        }
        val newContent = (if (pkg.isNotEmpty()) "package " + pkg + "\n\n" else "") +
                         (if (imports.isNotEmpty()) imports + "\n\n" else "") +
                         classText.trim() + "\n"
        try {
            newFile.writeText(newContent)
        } catch (e: Exception) {
            Toast.makeText(this, "File nahi bani", Toast.LENGTH_SHORT).show(); return
        }
        // Purani file se hatao (single undo).
        ed.expandAllFoldsNow()
        var rs = ls
        var re = classEnd
        while (re < text.length && (text[re] == '\n' || text[re] == '\r')) re++
        // Aas-paas khaali lines saaf karo (ek chhodo).
        ed.text?.replace(rs, re, "")
        try {
            openFileInTab(androidx.documentfile.provider.DocumentFile.fromFile(newFile))
        } catch (e: Exception) { }
        Toast.makeText(this@MainActivity, className + ".kt ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** `if (a) { foo() }` -> `if (a) foo()` (single-statement braces hatao). */
    private fun removeRedundantBraces() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val maskedOnce = maskCodeForRefs(text)
        var ss = ed.selectionStart; var se = ed.selectionEnd
        var bOpen = -1
        var bEnd = -1
        if (se > ss) {
            // Selection `{ ... }` hona chahiye.
            var a = ss; var b = se
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (b - a < 2 || text[a] != '{') {
                Toast.makeText(this, "`{ ... }` select karo", Toast.LENGTH_SHORT).show(); return
            }
            val me_ = matchScope(text, a, '{', '}')
            if (me_ == null || me_ != b) {
                Toast.makeText(this, "Poora `{ ... }` select karo", Toast.LENGTH_SHORT).show(); return
            }
            bOpen = a; bEnd = me_
            ss = a; se = b
        } else {
            // Caret ke baad pehla `{` dhoondho (if/for/while ke baad wala).
            var k = ss
            while (k < text.length) {
                if (maskedOnce[k] == ' ') { k++; continue }
                if (text[k] == '{') { bOpen = k; break }
                if (text[k] == '\n' || text[k] == ';') break
                k++
            }
            if (bOpen < 0) {
                Toast.makeText(this, "Caret ko `{` se pehle rakho", Toast.LENGTH_SHORT).show(); return
            }
            bEnd = matchScope(text, bOpen, '{', '}') ?: run {
                Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
            }
        }
        // `{` se pehle if/for/while/else/try/do/finally hona chahiye (function/when nahi).
        var p = bOpen - 1
        while (p >= 0 && text[p].isWhitespace()) p--
        var allowed = false
        if (p >= 0 && text[p] == ')') {
            // `)` se pehle ka keyword nikalo: if/for/while hi allowed.
            for (m in Regex("\\b(if|for|while|when|fun)\\b").findAll(maskedOnce)
                     .filter { it.range.first < bOpen }.toList().asReversed()) {
                if (maskedOnce[m.range.first] != ' ') continue
                val kw = m.value
                var qi = m.range.last + 1
                while (qi < text.length && text[qi].isWhitespace()) qi++
                if (qi < text.length && text[qi] == '(') {
                    val qe = matchScope(text, qi, '(', ')') ?: break
                    var ri = qe
                    while (ri < text.length && text[ri].isWhitespace()) ri++
                    if (ri == bOpen) {
                        allowed = kw == "if" || kw == "for" || kw == "while"
                    }
                    break
                }
            }
        } else if (p >= 0) {
            var wEnd = p + 1
            var wStart = p
            while (wStart >= 0 && (text[wStart].isLetterOrDigit() || text[wStart] == '_')) wStart--
            wStart++
            val word = text.substring(wStart, wEnd)
            allowed = word == "else" || word == "try" || word == "do" || word == "finally"
        }
        if (!allowed) {
            Toast.makeText(this, "Sirf if/for/while/try ke braces hat sakte hain", Toast.LENGTH_SHORT).show(); return
        }
        val inner = text.substring(bOpen + 1, bEnd - 1).trim()
        if (inner.isEmpty() || countTopLevelStatements(inner) != 1) {
            Toast.makeText(this, "Single-statement block chahiye", Toast.LENGTH_SHORT).show(); return
        }
        // `else` ke baad `if` ho to dangling-else khatra — mana.
        if (inner.startsWith("if") && isWordBoundary(inner, 0, 2)) {
            Toast.makeText(this, "Andar `if` hai — unsafe", Toast.LENGTH_SHORT).show(); return
        }
        ed.expandAllFoldsNow()
        ed.text?.replace(bOpen, bEnd, inner)
        Toast.makeText(this, "Braces hat gaye", Toast.LENGTH_SHORT).show()
    }

    /** `when { x == 1 -> a() }` -> `when (x) { 1 -> a() }` (selection par). */
    private fun convertWhenToSubjectForm() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "`when { ... }` select karo", Toast.LENGTH_SHORT).show(); return
        }
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        if (!text.startsWith("when", ss) || !isWordBoundary(text, ss, 4)) {
            Toast.makeText(this, "`when` se select karo", Toast.LENGTH_SHORT).show(); return
        }
        var i = ss + 4
        val n = text.length
        while (i < n && text[i].isWhitespace()) i++
        if (i < n && text[i] == '(') {
            Toast.makeText(this, "Pehle se subject form hai", Toast.LENGTH_SHORT).show(); return
        }
        if (i >= n || text[i] != '{') {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val bEnd = matchScope(text, i, '{', '}') ?: run {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        // Selection ke andar poora when hona chahiye (aage-peeche sirf whitespace).
        var tail = bEnd
        while (tail < se && text[tail].isWhitespace()) tail++
        if (tail != se) {
            Toast.makeText(this, "Poora `when` select karo", Toast.LENGTH_SHORT).show(); return
        }
        val body = text.substring(i + 1, bEnd - 1)
        // Entries: har non-blank line `cond -> stmt`.
        data class WEntry(val cond: String, val stmt: String, val isElse: Boolean)
        val entries = mutableListOf<WEntry>()
        for (rawLine in body.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("else") && isWordBoundary(line, 0, 4)) {
                val arrow = line.indexOf("->")
                if (arrow < 0) {
                    Toast.makeText(this, "else-branch parse nahi hua", Toast.LENGTH_SHORT).show(); return
                }
                entries.add(WEntry("", line.substring(arrow + 2).trim(), true))
                continue
            }
            // Top-level `->` dhoondho.
            var depth = 0
            var inS = false; var inC = false; var esc = false
            var ai = -1
            var j = 0
            while (j < line.length) {
                val ch = line[j]
                if (esc) { esc = false; j++; continue }
                if (ch == '\\' && (inS || inC)) { esc = true; j++; continue }
                if (inS) { if (ch == '"') inS = false; j++; continue }
                if (inC) { if (ch == '\'') inC = false; j++; continue }
                if (ch == '"') { inS = true; j++; continue }
                if (ch == '\'') { inC = true; j++; continue }
                if (ch == '(' || ch == '[' || ch == '{') depth++
                else if (ch == ')' || ch == ']' || ch == '}') { if (depth > 0) depth-- }
                else if (depth == 0 && line.startsWith("->", j)) { ai = j; break }
                j++
            }
            if (ai < 0) {
                Toast.makeText(this, "`cond -> stmt` entries chahiye", Toast.LENGTH_SHORT).show(); return
            }
            entries.add(WEntry(line.substring(0, ai).trim(), line.substring(ai + 2).trim(), false))
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, "Koi branch nahi mili", Toast.LENGTH_SHORT).show(); return
        }
        // Sab `X == value` aur same X?
        var subject: String? = null
        val vals = mutableListOf<String>()
        for (e in entries) {
            if (e.isElse) { vals.add(""); continue }
            val m = Regex("^([A-Za-z_][A-Za-z0-9_.]*)\\s*==\\s*(.+)$").find(e.cond)
            if (m == null) {
                Toast.makeText(this, "`X == value` conditions chahiye", Toast.LENGTH_SHORT).show(); return
            }
            if (subject == null) subject = m.groupValues[1]
            else if (subject != m.groupValues[1]) {
                Toast.makeText(this, "Sab me same subject chahiye", Toast.LENGTH_SHORT).show(); return
            }
            vals.add(m.groupValues[2])
        }
        if (subject == null) {
            Toast.makeText(this, "Koi `==` condition nahi mili", Toast.LENGTH_SHORT).show(); return
        }
        // Indent.
        val lineS = text.lastIndexOf('\n', (ss - 1).coerceAtLeast(0)) + 1
        val baseIndent = text.substring(lineS, ss).takeWhile { it == ' ' || it == '\t' }
        val inIndent = baseIndent + "    "
        val sb = StringBuilder("when (").append(subject).append(") {\n")
        for (k in entries.indices) {
            val e = entries[k]
            sb.append(inIndent)
            if (e.isElse) sb.append("else")
            else sb.append(vals[k])
            sb.append(" -> ").append(e.stmt).append("\n")
        }
        sb.append(baseIndent).append("}")
        ed.expandAllFoldsNow()
        ed.text?.replace(ss, bEnd, sb.toString())
        Toast.makeText(this, "Subject form ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** `{ x -> foo(x) }` -> `::foo` (selection par). */
    private fun convertLambdaToReference() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "`{ x -> foo(x) }` select karo", Toast.LENGTH_SHORT).show(); return
        }
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        val sel = text.substring(ss, se)
        if (!sel.startsWith("{") || !sel.endsWith("}")) {
            Toast.makeText(this, "Lambda `{ ... }` select karo", Toast.LENGTH_SHORT).show(); return
        }
        // Braces match hone chahiye (poora selection ek lambda ho).
        if (matchScope(sel, 0, '{', '}') != sel.length) {
            Toast.makeText(this, "Poora lambda select karo", Toast.LENGTH_SHORT).show(); return
        }
        val inner = sel.substring(1, sel.length - 1).trim()
        // Params aur body alag karo (top-level `->` par).
        val arrow = splitOnTopLevelWord(inner, "->")
        val param: String
        val body: String
        if (arrow != null) {
            val ps = arrow.first.trim()
            if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(ps)) {
                Toast.makeText(this, "Single param wala lambda chahiye", Toast.LENGTH_SHORT).show(); return
            }
            param = ps
            body = arrow.second.trim()
        } else {
            param = "it"
            body = inner
        }
        if (countTopLevelStatements(body) != 1) {
            Toast.makeText(this, "Single-statement lambda chahiye", Toast.LENGTH_SHORT).show(); return
        }
        // Body: `receiver.foo(param)` ya `foo(param)`.
        val cm = Regex("^([A-Za-z_][A-Za-z0-9_.]*)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)$")
            .find(body)
        if (cm == null || cm.groupValues[2] != param) {
            Toast.makeText(this, "`{ x -> foo(x) }` pattern chahiye", Toast.LENGTH_SHORT).show(); return
        }
        val ref = "::" + cm.groupValues[1]
        ed.expandAllFoldsNow()
        ed.text?.replace(ss, se, ref)
        Toast.makeText(this, "Reference ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** `if (a) { if (b) { x() } }` -> `if (a && b) { x() }` (selection ya caret wale if par). */
    private fun mergeNestedIfs() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        var ifStart = -1
        if (se > ss) {
            while (ss < se && text[ss].isWhitespace()) ss++
            if (!text.startsWith("if", ss) || !isWordBoundary(text, ss, 2)) {
                Toast.makeText(this, "`if` se select karo", Toast.LENGTH_SHORT).show(); return
            }
            ifStart = ss
        } else {
            val maskedOnce = maskCodeForRefs(text)
            for (m in Regex("\\bif\\b").findAll(text)
                     .filter { it.range.first < ss }.toList().asReversed()) {
                if (maskedOnce[m.range.first] == ' ') continue
                var j = m.range.last + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && text[j] == '(') { ifStart = m.range.first; break }
            }
            if (ifStart < 0) {
                Toast.makeText(this, "Caret ko `if` par rakho", Toast.LENGTH_SHORT).show(); return
            }
        }
        // Outer if parse karo: (cond, branchInner, hasElse, branchInnerStart, branchEnd, ifEnd).
        val outer = parseSingleIf(text, ifStart) ?: run {
            Toast.makeText(this, "if parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        if (outer.third) {
            Toast.makeText(this, "Outer if me else nahi hona chahiye", Toast.LENGTH_SHORT).show(); return
        }
        // Branch ke andar sirf ek inner `if` hona chahiye.
        val innerRel = Regex("\\bif\\b").find(outer.second) ?: run {
            Toast.makeText(this, "Andar single `if` chahiye", Toast.LENGTH_SHORT).show(); return
        }
        if (outer.second.substring(0, innerRel.range.first).trim().isNotEmpty()) {
            Toast.makeText(this, "Andar sirf `if` hona chahiye", Toast.LENGTH_SHORT).show(); return
        }
        val innerAbs = outer.fourth + innerRel.range.first
        val inner = parseSingleIf(text, innerAbs) ?: run {
            Toast.makeText(this, "Inner if parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        if (inner.third) {
            Toast.makeText(this, "Inner if me else nahi hona chahiye", Toast.LENGTH_SHORT).show(); return
        }
        if (text.substring(inner.sixth, outer.fifth).trim().isNotEmpty()) {
            Toast.makeText(this, "Inner if ke baad kuch aur hai", Toast.LENGTH_SHORT).show(); return
        }
        val condA = parenthesizeIfOr(outer.first)
        val condB = parenthesizeIfOr(inner.first)
        val body = inner.second.trim()
        if (body.isEmpty()) {
            Toast.makeText(this, "Body khaali hai", Toast.LENGTH_SHORT).show(); return
        }
        val hadBraces = outer.third || inner.third
        val merged = if (hadBraces) {
            val lineS = text.lastIndexOf('\n', (ifStart - 1).coerceAtLeast(0)) + 1
            val ind = text.substring(lineS, ifStart).takeWhile { it == ' ' || it == '\t' }
            "if (" + condA + " && " + condB + ") {\n" + ind + "    " +
            body.replace("\n", "\n" + ind + "    ") + "\n" + ind + "}"
        } else {
            "if (" + condA + " && " + condB + ") " + body
        }
        ed.expandAllFoldsNow()
        ed.text?.replace(ifStart, outer.sixth, merged)
        Toast.makeText(this, "if merge ho gaya", Toast.LENGTH_SHORT).show()
    }

    /** `||` wali condition ko parens me lapeto (&& se pehle evaluate ho). */
    private fun parenthesizeIfOr(cond: String): String {
        val t = cond.trim()
        if (t.startsWith("(") && t.endsWith(")")) return t
        return if (splitOnTopLevelOp(t, "||") != null) "($t)" else t
    }

    /**
     * text[pos] se single `if` parse karo (else ke saath ya bina).
     * Returns (cond, branchInner, hasElse, branchStart, branchEnd, ifEnd) ya null.
     * branchInner: braces ke andar ka text (ya bina-brace statement).
     */
    private fun parseSingleIf(text: String, pos: Int):
            Sextuple<String, String, Boolean, Int, Int, Int>? {
        val n = text.length
        var i = pos + 2
        while (i < n && text[i].isWhitespace()) i++
        if (i >= n || text[i] != '(') return null
        val cEnd = matchScope(text, i, '(', ')') ?: return null
        val cond = text.substring(i + 1, cEnd - 1).trim()
        if (cond.isEmpty()) return null
        i = cEnd
        while (i < n && text[i].isWhitespace()) i++
        var branchInner: String
        var bStart: Int
        var branchEnd: Int
        if (i < n && text[i] == '{') {
            val bEnd = matchScope(text, i, '{', '}') ?: return null
            bStart = i + 1
            branchInner = text.substring(i + 1, bEnd - 1)
            branchEnd = bEnd - 1
            i = bEnd
        } else {
            // Bina braces: `else`/';'/'}' tak.
            var depth = 0
            var j = i
            var inS = false; var inC = false; var esc = false
            var stop = -1
            while (j < n) {
                val ch = text[j]
                if (esc) { esc = false; j++; continue }
                if (ch == '\\' && (inS || inC)) { esc = true; j++; continue }
                if (inS) { if (ch == '"') inS = false; j++; continue }
                if (inC) { if (ch == '\'') inC = false; j++; continue }
                if (ch == '"') { inS = true; j++; continue }
                if (ch == '\'') { inC = true; j++; continue }
                if (ch == '(' || ch == '[') depth++
                else if (ch == ')' || ch == ']') { if (depth > 0) depth-- }
                else if (ch == '{' || ch == ';' || ch == '}') { stop = j; break }
                else if (depth == 0 && text.startsWith("else", j) && isWordBoundary(text, j, 4)) {
                    stop = j; break
                }
                j++
            }
            if (stop < 0) return null
            bStart = i
            branchInner = text.substring(i, stop)
            branchEnd = stop
            i = stop
        }
        while (i < n && text[i].isWhitespace()) i++
        var hasElse = false
        if (text.startsWith("else", i) && isWordBoundary(text, i, 4)) {
            hasElse = true
            i += 4
            while (i < n && text[i].isWhitespace()) i++
            if (i < n && text[i] == '{') {
                i = matchScope(text, i, '{', '}') ?: return null
            } else {
                // else-branch skip karo (parse nahi chahiye, bas end dhoondho).
                var depth = 0
                var j = i
                var inS = false; var inC = false; var esc = false
                while (j < n) {
                    val ch = text[j]
                    if (esc) { esc = false; j++; continue }
                    if (ch == '\\' && (inS || inC)) { esc = true; j++; continue }
                    if (inS) { if (ch == '"') inS = false; j++; continue }
                    if (inC) { if (ch == '\'') inC = false; j++; continue }
                    if (ch == '"') { inS = true; j++; continue }
                    if (ch == '\'') { inC = true; j++; continue }
                    if (ch == '(' || ch == '[' || ch == '{') depth++
                    else if (ch == ')' || ch == ']' || ch == '}') {
                        if (depth > 0) depth-- else break
                    }
                    else if (ch == ';' && depth == 0) { j++; break }
                    j++
                }
                i = j
            }
        }
        // branchInner me sirf inner `if` hona chahiye (merge ke liye caller check karega).
        return Sextuple(cond, branchInner, hasElse, bStart, branchEnd, i)
    }

    /** 6 values ka holder (Triple se zyada chahiye). */
    private data class Sextuple<A, B, C, D, E, F>(
        val first: A, val second: B, val third: C,
        val fourth: D, val fifth: E, val sixth: F
    )

    /** Paste kiye JSON se Kotlin data class(es) generate karo. */
    private fun showJsonToDataClassDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val den = resources.displayMetrics.density
        val nameIn = android.widget.EditText(this).apply {
            hint = "RootClass"
            setSingleLine(true)
        }
        val jsonIn = android.widget.EditText(this).apply {
            hint = "{\"name\": \"Sam\"}"
            minLines = 6
            gravity = android.view.Gravity.TOP
        }
        val pad = (20 * den).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameIn)
            addView(jsonIn)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Data Class from JSON")
            .setView(layout)
            .setPositiveButton("Generate") { _, _ ->
                var rootName = nameIn.text.toString().trim()
                if (!Regex("^[A-Z][A-Za-z0-9_]*$").matches(rootName)) rootName = "Root"
                val jsonStr = jsonIn.text.toString().trim()
                if (jsonStr.isEmpty()) {
                    Toast.makeText(this, "JSON paste karo", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val code = try {
                    generateDataClassFromJson(rootName, jsonStr)
                } catch (e: Exception) {
                    Toast.makeText(this, "JSON parse fail: " + e.message, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (code == null) {
                    Toast.makeText(this, "Root JSON object/array chahiye", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ed = binding.editor
                ed.expandAllFoldsNow()
                val caret = ed.selectionStart.coerceIn(0, ed.text?.length ?: 0)
                val lineS = (ed.text?.toString() ?: "")
                    .lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
                val text = ed.text?.toString() ?: return@setPositiveButton
                val newText = text.substring(0, lineS) + "\n" + code + "\n" + text.substring(lineS)
                ed.text?.replace(0, text.length, newText)
                Toast.makeText(this@MainActivity, "Data class ban gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** JSON string se data class code banao; null agar root object/array na ho. */
    private fun generateDataClassFromJson(rootName: String, jsonStr: String): String? {
        val nested = mutableListOf<String>()
        val usedNames = mutableSetOf(rootName)
        fun uniqueName(base: String): String {
            var n = base
            var k = 2
            while (n in usedNames) { n = base + k.toString(); k++ }
            usedNames.add(n)
            return n
        }
        fun safeField(key: String): String {
            return if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(key)) key else "`$key`"
        }
        fun cap(s: String): String {
            val clean = s.replace(Regex("[^A-Za-z0-9_]"), "_").trim('_')
            if (clean.isEmpty()) return "Item"
            return clean.replaceFirstChar(Char::uppercase)
        }
        // buildClassFn breaks the typeOf <-> buildClass mutual-recursion
        // cycle: Kotlin does not resolve forward references to local
        // functions, so typeOf calls buildClass through this variable.
        lateinit var buildClassFn: (String, org.json.JSONObject) -> String
        fun typeOf(v: Any?, key: String, owner: String): String {
            return when (v) {
                null, org.json.JSONObject.NULL -> "Any?"
                is String -> "String"
                is Boolean -> "Boolean"
                is Int -> "Int"
                is Long -> "Long"
                is Double, is Float -> "Double"
                is org.json.JSONObject -> {
                    val cn = uniqueName(cap(key))
                    nested.add(buildClassFn(cn, v))
                    cn
                }
                is org.json.JSONArray -> {
                    if (v.length() == 0) return "List<Any>"
                    val first = v.opt(0)
                    val et = typeOf(first, key, owner)
                    // Nested class ka naam singular banao.
                    "List<" + et + ">"
                }
                else -> "Any"
            }
        }
        fun buildClass(className: String, obj: org.json.JSONObject): String {
            val sb = StringBuilder()
            sb.append("data class ").append(className).append("(\n")
            val keys = obj.keys()
            val fields = mutableListOf<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                val t = typeOf(obj.opt(k), k, className)
                fields.add("    val " + safeField(k) + ": " + t)
            }
            sb.append(fields.joinToString(",\n"))
            sb.append("\n)")
            return sb.toString()
        }
        buildClassFn = ::buildClass
        val trimmed = jsonStr.trim()
        val code: String
        if (trimmed.startsWith("{")) {
            code = buildClass(rootName, org.json.JSONObject(trimmed))
        } else if (trimmed.startsWith("[")) {
            val arr = org.json.JSONArray(trimmed)
            if (arr.length() == 0) return null
            val first = arr.opt(0)
            if (first !is org.json.JSONObject) return null
            code = buildClass(rootName, first)
        } else return null
        // Nested classes ko andar rakho (readability ke liye end me jodo).
        return if (nested.isEmpty()) code
               else code + "\n\n" + nested.joinToString("\n\n")
    }

    /** Caret wale `when` me `else -> TODO()` jodo (agar nahi hai). */
    private fun addElseToWhen() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        // Nearest asli `when` dhoondho.
        val maskedOnce = maskCodeForRefs(text)
        var wStart = -1
        for (m in Regex("\\bwhen\\b").findAll(text)
                 .filter { it.range.first < caret }.toList().asReversed()) {
            if (maskedOnce[m.range.first] != ' ') { wStart = m.range.first; break }
        }
        if (wStart < 0) {
            Toast.makeText(this, "Caret ko `when` ke andar rakho", Toast.LENGTH_SHORT).show(); return
        }
        var i = wStart + 4
        val n = text.length
        while (i < n && text[i].isWhitespace()) i++
        if (i < n && text[i] == '(') {
            i = matchScope(text, i, '(', ')') ?: run {
                Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
            }
            while (i < n && text[i].isWhitespace()) i++
        }
        if (i >= n || text[i] != '{') {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val bOpen = i
        val bEnd = matchScope(text, i, '{', '}') ?: run {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        if (caret < bOpen || caret > bEnd) {
            Toast.makeText(this, "Caret ko `when` ke andar rakho", Toast.LENGTH_SHORT).show(); return
        }
        val body = text.substring(bOpen + 1, bEnd - 1)
        // Top-level `else` hai kya?
        var depth = 0
        var inS = false; var inC = false; var esc = false
        var j = 0
        var hasElse = false
        while (j < body.length) {
            val ch = body[j]
            if (esc) { esc = false; j++; continue }
            if (ch == '\\' && (inS || inC)) { esc = true; j++; continue }
            if (inS) { if (ch == '"') inS = false; j++; continue }
            if (inC) { if (ch == '\'') inC = false; j++; continue }
            if (ch == '"') { inS = true; j++; continue }
            if (ch == '\'') { inC = true; j++; continue }
            if (ch == '{') depth++
            else if (ch == '}') { if (depth > 0) depth-- }
            else if (depth == 0 && body.startsWith("else", j) && isWordBoundary(body, j, 4)) {
                hasElse = true; break
            }
            j++
        }
        if (hasElse) {
            Toast.makeText(this, "`else` pehle se hai", Toast.LENGTH_SHORT).show(); return
        }
        // Indent: pehli branch wali line se.
        var indent = "    "
        for (line in body.split('\n')) {
            if (line.isNotBlank()) {
                indent = line.substring(0, line.length - line.trimStart().length)
                break
            }
        }
        ed.expandAllFoldsNow()
        val insert = "\n" + indent + "else -> TODO()"
        ed.text?.replace(bEnd - 1, bEnd - 1, insert)
        Toast.makeText(this, "`else` jud gaya", Toast.LENGTH_SHORT).show()
    }

    /** Caret wali `val x = expr` ko `val x by lazy { expr }` me badlo. */
    private fun convertToLazyDelegate() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
        val lineE = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val line = text.substring(lineS, lineE)
        // `val x [: Type] = expr` — `by` pehle se nahi hona chahiye.
        val dm = Regex("^(\\s*(?:private|public|protected|internal|open|lateinit)\\s+)*val\\s+" +
                       "`?[A-Za-z_][A-Za-z0-9_]*`?\\s*(?::\\s*[^=]+?)?=([^=].*)$").find(line)
        if (dm == null) {
            Toast.makeText(this, "Caret ko `val x = ...` par rakho", Toast.LENGTH_SHORT).show(); return
        }
        if (" by " in line) {
            Toast.makeText(this, "Pehle se delegate hai", Toast.LENGTH_SHORT).show(); return
        }
        val expr = dm.groupValues[2].trim()
        if (expr.isEmpty()) {
            Toast.makeText(this, "Initializer chahiye", Toast.LENGTH_SHORT).show(); return
        }
        // `=` ki position: `val` ke baad pehla `=` jo `==` ka hissa na ho.
        var epos = -1
        var si = line.indexOf("val")
        si = line.indexOf('=', si)
        if (si >= 0 && (si + 1 >= line.length || line[si + 1] != '=')) epos = si
        if (epos < 0) {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val head = line.substring(0, epos).trimEnd()
        ed.expandAllFoldsNow()
        val newLine = head + " by lazy { " + expr + " }"
        ed.text?.replace(lineS, lineE, newLine)
        Toast.makeText(this, "lazy ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** Saari settings ko JSON file me export karo (backup/share ke liye). */
    private fun exportSettings() {
        try {
            val prefs = getSharedPreferences("nobi_ide_prefs", MODE_PRIVATE)
            val json = org.json.JSONObject()
            for ((k, v) in prefs.all) {
                when (v) {
                    is Boolean -> json.put(k, v)
                    is Int -> json.put(k, v)
                    is Long -> json.put(k, v)
                    is Float -> json.put(k, v.toDouble())
                    is String -> json.put(k, v)
                    is Set<*> -> {
                        val arr = org.json.JSONArray()
                        for (item in v) arr.put(item.toString())
                        json.put(k, arr)
                    }
                }
            }
            val outFile = java.io.File(getExternalFilesDir(null), "nobiide-settings.json")
            outFile.writeText(json.toString(2))
            // Share ka option do.
            val shareUri = androidx.core.content.FileProvider.getUriForFile(
                this, packageName + ".fileprovider", outFile)
            val chooser = android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Settings share karo")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Settings export ho gayin")
                .setMessage(outFile.absolutePath)
                .setPositiveButton("Share") { _, _ -> startActivity(chooser) }
                .setNegativeButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export fail: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    /** JSON file se settings wapas lao. */
    private fun doImportSettings(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: run {
                    Toast.makeText(this, "File padh nahi paya", Toast.LENGTH_SHORT).show()
                    return
                }
            val json = org.json.JSONObject(raw)
            val prefs = getSharedPreferences("nobi_ide_prefs", MODE_PRIVATE)
            val ed = prefs.edit()
            val keys = json.keys()
            var count = 0
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = json.get(k)) {
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Double -> ed.putFloat(k, v.toFloat())
                    is String -> ed.putString(k, v)
                    is org.json.JSONArray -> {
                        val set = mutableSetOf<String>()
                        for (i in 0 until v.length()) set.add(v.getString(i))
                        ed.putStringSet(k, set)
                    }
                }
                count++
            }
            ed.apply()
            applyPreferences()
            Toast.makeText(this, count.toString() + " settings import ho gayin",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import fail: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Current file ka clipboard text se diff dikhao. */
    private fun compareWithClipboard() {
        if (openTabs.getOrNull(activeTabIndex) == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (clip.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard khaali hai", Toast.LENGTH_SHORT).show(); return
        }
        val fileText = binding.editor.text?.toString() ?: ""
        if (fileText == clip) {
            Toast.makeText(this, "Dono ek jaise hain", Toast.LENGTH_SHORT).show(); return
        }
        // Line-based diff (LCS). Badi files par cap.
        val aLines = clip.split('\n')
        val bLines = fileText.split('\n')
        if (aLines.size > 3000 || bLines.size > 3000) {
            Toast.makeText(this, "Bahut badi file hai", Toast.LENGTH_SHORT).show(); return
        }
        val diffHtml = buildLineDiff(aLines, bLines)
        val tv = android.widget.TextView(this).apply {
            text = android.text.Html.fromHtml(diffHtml,
                android.text.Html.FROM_HTML_MODE_LEGACY)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(16, 16, 16, 16)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Clipboard vs Current File")
            .setView(sv)
            .setPositiveButton("OK", null)
            .show()
    }

    /** Do line lists ka simple LCS diff → HTML (red/green). */
    private fun buildLineDiff(a: List<String>, b: List<String>): String {
        val n = a.size
        val m = b.size
        // LCS table (memory: (n+1)*(m+1) ints — 3000 cap par ~36MB, theek hai).
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                           else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val sb = StringBuilder()
        var i = 0
        var j = 0
        fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;")
        while (i < n || j < m) {
            when {
                i < n && j < m && a[i] == b[j] -> {
                    sb.append(esc(a[i])).append("<br>")
                    i++; j++
                }
                j < m && (i >= n || dp[i][j + 1] > dp[i + 1][j]) -> {
                    // b me extra (file me nayi line) — green.
                    sb.append("<font color='#4CAF50'>+ ").append(esc(b[j]))
                        .append("</font><br>")
                    j++
                }
                else -> {
                    // a me extra (clipboard me thi, file me nahi) — red.
                    sb.append("<font color='#F44336'>- ").append(esc(a[i]))
                        .append("</font><br>")
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** Caret wale if/for/while/try block ko hatao, andar ka code rakho. */
    private fun unwrapBlock() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val maskedOnce = maskCodeForRefs(text)
        // Caret ko gherne wala sabse andaruni `{...}` dhoondho.
        var openB = -1
        var closeB = -1
        var i = (caret - 1).coerceAtLeast(0)
        // Peeche jao: pehla `{` jo caret se pehle band na hua ho.
        var depth = 0
        while (i >= 0) {
            val ch = maskedOnce[i]
            if (ch == '}') depth++
            else if (ch == '{') {
                if (depth == 0) { openB = i; break }
                depth--
            }
            i--
        }
        if (openB < 0) {
            Toast.makeText(this, "Koi block nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        // Iska matching `}` dhoondho.
        var d2 = 0
        var j = openB
        while (j < maskedOnce.length) {
            val ch = maskedOnce[j]
            if (ch == '{') d2++
            else if (ch == '}') {
                d2--
                if (d2 == 0) { closeB = j; break }
            }
            j++
        }
        if (closeB < 0) {
            Toast.makeText(this, "Block ka ant nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        // `{` se pehle ka keyword dekho (if/for/while/try/when/do).
        var k = openB - 1
        while (k >= 0 && (maskedOnce[k] == ' ' || maskedOnce[k] == '\t' ||
               maskedOnce[k] == '\n' || maskedOnce[k] == '\r')) k--
        // Agar `)` hai to uska matching `(` dhoondho, phir keyword.
        var kwEnd = k
        if (k >= 0 && maskedOnce[k] == ')') {
            var pd = 0
            while (k >= 0) {
                val ch = maskedOnce[k]
                if (ch == ')') pd++
                else if (ch == '(') {
                    pd--
                    if (pd == 0) break
                }
                k--
            }
            kwEnd = k - 1
            while (kwEnd >= 0 && (maskedOnce[kwEnd] == ' ' || maskedOnce[kwEnd] == '\t' ||
                   maskedOnce[kwEnd] == '\n' || maskedOnce[kwEnd] == '\r')) kwEnd--
        }
        // Keyword nikalo.
        var kwStart = kwEnd
        while (kwStart >= 0 && (maskedOnce[kwStart].isLetterOrDigit() ||
               maskedOnce[kwStart] == '_')) kwStart--
        kwStart++
        val keyword = if (kwStart <= kwEnd) text.substring(kwStart, kwEnd + 1) else ""
        if (keyword !in listOf("if", "for", "while", "try", "when", "do", "else")) {
            Toast.makeText(this, "Sirf if/for/while/try/when unwrap hota hai",
                Toast.LENGTH_SHORT).show(); return
        }
        // `else` ke case me — `} else {` ka hissa hai, poora if-else chahiye.
        // v1 me sirf simple case: keyword se lekar closeB tak hatao.
        // Statement ki shuruaat (line start) dhoondho.
        var stmtStart = kwStart
        // `if` se pehle `else` ho sakta hai (`} else if`) — use bhi include karo.
        var b = kwStart - 1
        while (b >= 0 && (maskedOnce[b] == ' ' || maskedOnce[b] == '\t')) b--
        if (b >= 3 && text.substring(b - 3, b + 1) == "else") {
            stmtStart = b - 3
        }
        // Inner content nikalo aur dedent karo.
        val inner = text.substring(openB + 1, closeB)
        val innerLines = inner.split('\n')
        // Pehli line khaali ho sakti hai (`{\n`), aakhri bhi.
        val dedented = innerLines.map { ln ->
            when {
                ln.startsWith("    ") -> ln.substring(4)
                ln.startsWith("\t") -> ln.substring(1)
                ln.isBlank() -> ""
                else -> ln
            }
        }
        // Statement ke indent par rakho.
        val lineS = text.lastIndexOf('\n', (stmtStart - 1).coerceAtLeast(0)) + 1
        val baseIndent = text.substring(lineS, stmtStart).takeWhile { it == ' ' || it == '\t' }
        val newInner = dedented.joinToString("\n") { ln ->
            if (ln.isEmpty()) ln else baseIndent + ln
        }.trim()
        // `try` ke baad `catch/finally` ho to use bhi hatao.
        var stmtEnd = closeB + 1
        if (keyword == "try" || keyword == "else") {
            var q = stmtEnd
            while (q < text.length) {
                while (q < text.length && (text[q] == ' ' || text[q] == '\t' ||
                       text[q] == '\n' || text[q] == '\r')) q++
                if (text.startsWith("catch", q) || text.startsWith("finally", q)) {
                    // Iska block dhoondho aur skip karo.
                    val cbOpen = text.indexOf('{', q)
                    if (cbOpen < 0) break
                    var cd = 0
                    var r = cbOpen
                    while (r < maskedOnce.length) {
                        if (maskedOnce[r] == '{') cd++
                        else if (maskedOnce[r] == '}') {
                            cd--
                            if (cd == 0) { q = r + 1; break }
                        }
                        r++
                    }
                    if (r >= maskedOnce.length) break
                    stmtEnd = q
                } else {
                    break
                }
            }
        }
        ed.expandAllFoldsNow()
        val newText = text.substring(0, stmtStart) + baseIndent + newInner +
                      text.substring(stmtEnd)
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, "Unwrap ho gaya", Toast.LENGTH_SHORT).show()
    }

    /** File me hardcoded `"..."` literals dhoondho (i18n ke liye). */
    private fun findHardcodedStrings() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        data class Hit(val line: Int, val col: Int, val value: String, val offset: Int)
        val hits = mutableListOf<Hit>()
        var i = 0
        var line = 1
        var lineStart = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch == '\n') { line++; lineStart = i + 1; i++; continue }
            // Comments skip karo.
            if (ch == '/' && i + 1 < n && text[i + 1] == '/') {
                while (i < n && text[i] != '\n') i++
                continue
            }
            if (ch == '/' && i + 1 < n && text[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) {
                    if (text[i] == '\n') { line++; lineStart = i + 1 }
                    i++
                }
                i += 2
                continue
            }
            if (ch == '"') {
                // Raw string `"""` skip karo (poori).
                if (i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                    i += 3
                    while (i + 2 < n && !(text[i] == '"' && text[i + 1] == '"' &&
                           text[i + 2] == '"')) {
                        if (text[i] == '\n') { line++; lineStart = i + 1 }
                        i++
                    }
                    i += 3
                    continue
                }
                // Normal string.
                val sStart = i
                val sLine = line
                val sCol = i - lineStart + 1
                i++
                val sb = StringBuilder()
                while (i < n && text[i] != '"' && text[i] != '\n') {
                    if (text[i] == '\\' && i + 1 < n) {
                        sb.append(text[i]); sb.append(text[i + 1]); i += 2
                    } else {
                        sb.append(text[i]); i++
                    }
                }
                if (i < n && text[i] == '"') i++ // closing quote
                val value = sb.toString()
                // Filter: khaali, sirf whitespace, ya package/import me nahi (wo upar lines me hain).
                // Import/package lines skip karne ke liye line ka prefix dekho.
                val lineText = text.substring(lineStart,
                    text.indexOf('\n', lineStart).let { if (it < 0) n else it })
                val trimmedLine = lineText.trim()
                if (value.isNotBlank() && !trimmedLine.startsWith("import ") &&
                    !trimmedLine.startsWith("package ")) {
                    hits.add(Hit(sLine, sCol, value, sStart))
                }
                continue
            }
            i++
        }
        if (hits.isEmpty()) {
            Toast.makeText(this, "Koi hardcoded string nahi mili", Toast.LENGTH_SHORT).show()
            return
        }
        // Dialog me list dikhao.
        val items = hits.map { "\"" + it.value.take(60) +
            (if (it.value.length > 60) "..." else "") + "\" - L" + it.line }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(hits.size.toString() + " hardcoded strings")
            .setItems(items.toTypedArray()) { _, which ->
                val hit = hits[which]
                // Us line par jao.
                ed.requestFocus()
                val targetLine = hit.line - 1
                // Line ki offset nikalo.
                var off = 0
                var l = 0
                while (l < targetLine && off < text.length) {
                    if (text[off] == '\n') l++
                    off++
                }
                ed.setSelection(hit.offset, hit.offset + hit.value.length + 2)
                // Scroll karo.
                val layout = ed.layout
                if (layout != null && targetLine < layout.lineCount) {
                    val y = layout.getLineTop(targetLine)
                    ed.scrollTo(ed.scrollX, (y - ed.height / 3).coerceAtLeast(0))
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    /** `"a\nb"` ↔ `"""a<newline>b"""` convert karo. */
    private fun convertToRawString() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val maskedOnce = maskCodeForRefs(text)
        // Caret wali string dhoondho (masked me strings spaces hain — raw text use karo).
        // Peeche `"` dhoondho jo string start ho.
        var strStart = -1
        var isRaw = false
        var i = (caret - 1).coerceAtLeast(0)
        while (i >= 0) {
            if (text[i] == '"') {
                // `"""` check karo.
                if (i >= 2 && text[i - 1] == '"' && text[i - 2] == '"') {
                    strStart = i - 2
                    isRaw = true
                    break
                }
                // Single `"` — kya ye string ka start hai? (peeche dekhkar)
                // Simple: agar iske pehle `\` nahi hai to candidate.
                if (i == 0 || text[i - 1] != '\\') {
                    // Aage dekho — kya ye closing hai ya opening?
                    // Caret iske baad hai, to ye opening ho sakta hai agar
                    // iske baad closing `"` hai.
                    strStart = i
                    break
                }
            }
            if (i < caret - 500) break
            i--
        }
        if (strStart < 0) {
            Toast.makeText(this, "Caret kisi string par rakho", Toast.LENGTH_SHORT).show(); return
        }
        ed.expandAllFoldsNow()
        var newText: String? = null
        if (isRaw) {
            // `"""..."""` → `"..."` (escapes ke saath).
            var e = strStart + 3
            while (e < text.length - 2) {
                if (text[e] == '"' && text[e + 1] == '"' && text[e + 2] == '"') break
                e++
            }
            if (e >= text.length - 2) {
                Toast.makeText(this, "String ka ant nahi mila", Toast.LENGTH_SHORT).show(); return
            }
            val content = text.substring(strStart + 3, e)
            val escaped = content.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
            newText = text.substring(0, strStart) + "\"" + escaped + "\"" +
                      text.substring(e + 3)
        } else {
            // `"..."` → `"""..."""` (unescaped).
            var e = strStart + 1
            while (e < text.length) {
                if (text[e] == '"' && text[e - 1] != '\\') break
                e++
            }
            if (e >= text.length) {
                Toast.makeText(this, "String ka ant nahi mila", Toast.LENGTH_SHORT).show(); return
            }
            val content = text.substring(strStart + 1, e)
            // Unescape karo.
            val sb = StringBuilder()
            var k = 0
            while (k < content.length) {
                val ch = content[k]
                if (ch == '\\' && k + 1 < content.length) {
                    when (content[k + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'r' -> sb.append('\r')
                        '$' -> sb.append('$')
                        else -> {
                            sb.append(ch)
                            sb.append(content[k + 1])
                        }
                    }
                    k += 2
                } else {
                    sb.append(ch)
                    k++
                }
            }
            newText = text.substring(0, strStart) + "\"\"\"" + sb.toString() +
                      "\"\"\"" + text.substring(e + 1)
        }
        if (newText != null) {
            ed.text?.replace(0, text.length, newText)
            Toast.makeText(this@MainActivity, "String convert ho gayi",
                Toast.LENGTH_SHORT).show()
        }
    }

    /** `a.equals(b)` → `a == b`, `a.plus(b)` → `a + b` jaise operator calls badlo. */
    private fun replaceWithOperator() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
        var lineE = text.indexOf('\n', caret)
        if (lineE < 0) lineE = text.length
        val line = text.substring(lineS, lineE)
        val maskedLine = maskCodeForRefs(line)
        // `(!)? X . METHOD ( Y )` dhoondho.
        val m = Regex("(!)?\\s*([A-Za-z_][\\w.]*(?:\\([^()]*\\))?)\\s*\\.\\s*" +
                      "(equals|plus|minus|times|div|rem)\\s*\\(([^()]*?)\\)")
            .find(maskedLine)
        if (m == null) {
            Toast.makeText(this, "Yahan koi operator call nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        val negated = m.groupValues[1].isNotEmpty()
        val receiver = line.substring(m.groups[2]!!.range.first,
                                      m.groups[2]!!.range.last + 1).trim()
        val method = m.groupValues[3]
        val arg = m.groupValues[4].trim()
        if (arg.isEmpty()) {
            Toast.makeText(this, "Argument nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val op = when (method) {
            "equals" -> if (negated) "!=" else "=="
            "plus" -> "+"
            "minus" -> "-"
            "times" -> "*"
            "div" -> "/"
            "rem" -> "%"
            else -> return
        }
        // `!a.plus(b)` ka matlab `!(a + b)` nahi — sirf equals ke saath `!` valid hai.
        if (negated && method != "equals") {
            Toast.makeText(this, "Sirf `!x.equals(y)` support hai", Toast.LENGTH_SHORT).show()
            return
        }
        val replacement = receiver + " " + op + " " + arg
        ed.expandAllFoldsNow()
        val absStart = lineS + m.range.first
        val absEnd = lineS + m.range.last + 1
        val newText = text.substring(0, absStart) + replacement + text.substring(absEnd)
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, "Operator ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** `if (x) true else false` jaise redundant boolean patterns simplify karo. */
    private fun simplifyBoolean() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        // Caret wali poori statement dhoondho (line ya multi-line if-else).
        val maskedOnce = maskCodeForRefs(text)
        // Pattern 1: `if (COND) true else false` / `if (COND) false else true`
        // Caret se pehle `if` dhoondho.
        var ifIdx = -1
        var si = (caret - 1).coerceAtLeast(0)
        while (si >= 0) {
            if (maskedOnce.startsWith("if", si)) {
                val beforeOk = si == 0 || (!maskedOnce[si - 1].isLetterOrDigit() &&
                                          maskedOnce[si - 1] != '_')
                val afterOk = si + 2 >= maskedOnce.length ||
                              (!maskedOnce[si + 2].isLetterOrDigit() &&
                               maskedOnce[si + 2] != '_')
                if (beforeOk && afterOk) {
                    // `if` keyword confirm karo (aage `(` hona chahiye).
                    var q = si + 2
                    while (q < maskedOnce.length && maskedOnce[q] == ' ') q++
                    if (q < maskedOnce.length && maskedOnce[q] == '(') {
                        ifIdx = si
                        break
                    }
                }
            }
            if (si < caret - 300) break // zyada peeche mat jao
            si--
        }
        var replacement: String? = null
        var repStart = -1
        var repEnd = -1
        if (ifIdx >= 0) {
            // `if (COND) TRUE else FALSE` parse karo.
            var p = maskedOnce.indexOf('(', ifIdx)
            var pd = 0
            var condEnd = -1
            var k = p
            while (k < maskedOnce.length) {
                val ch = maskedOnce[k]
                if (ch == '(') pd++
                else if (ch == ')') {
                    pd--
                    if (pd == 0) { condEnd = k; break }
                }
                k++
            }
            if (condEnd > p) {
                val cond = text.substring(p + 1, condEnd).trim()
                var r = condEnd + 1
                while (r < text.length && (text[r] == ' ' || text[r] == '\t' ||
                       text[r] == '\n')) r++
                // Pehla branch (true/false).
                val b1m = Regex("^(true|false)\\b").find(text.substring(r))
                if (b1m != null) {
                    val b1 = b1m.groupValues[1]
                    var r2 = r + b1.length
                    while (r2 < text.length && (text[r2] == ' ' || text[r2] == '\t' ||
                           text[r2] == '\n')) r2++
                    if (text.startsWith("else", r2)) {
                        var r3 = r2 + 4
                        while (r3 < text.length && (text[r3] == ' ' || text[r3] == '\t' ||
                               text[r3] == '\n')) r3++
                        val b2m = Regex("^(true|false)\\b").find(text.substring(r3))
                        if (b2m != null) {
                            val b2 = b2m.groupValues[1]
                            if (b1 != b2) {
                                replacement = if (b1 == "true") cond else "!(" + cond + ")"
                                repStart = ifIdx
                                repEnd = r3 + b2.length
                            }
                        }
                    }
                }
            }
        }
        // Pattern 2: `X == true` / `X == false` / `X != true` / `X != false`
        if (replacement == null) {
            val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
            var lineE = text.indexOf('\n', caret)
            if (lineE < 0) lineE = text.length
            val line = text.substring(lineS, lineE)
            // Operand: identifier chain, optional simple call `foo()` / `foo(a)`, optional `[0]`.
            val m = Regex("([A-Za-z_][\\w.]*(?:\\([^()]*\\))?(?:\\[\\d+\\])?)\\s*([=!]=)\\s*(true|false)\\b")
                .find(maskCodeForRefs(line))
            if (m != null) {
                val operand = line.substring(m.groups[1]!!.range.first,
                                             m.groups[1]!!.range.last + 1).trim()
                val op = m.groupValues[2]
                val lit = m.groupValues[3]
                replacement = when {
                    op == "==" && lit == "true" -> operand
                    op == "==" && lit == "false" -> "!" + operand
                    op == "!=" && lit == "true" -> "!" + operand
                    else -> operand // `!= false`
                }
                repStart = lineS + m.range.first
                repEnd = lineS + m.range.last + 1
            }
        }
        if (replacement == null) {
            Toast.makeText(this, "Yahan simplify karne layak kuch nahi",
                Toast.LENGTH_SHORT).show()
            return
        }
        ed.expandAllFoldsNow()
        val newText = text.substring(0, repStart) + replacement + text.substring(repEnd)
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, "Simplify ho gaya", Toast.LENGTH_SHORT).show()
    }

    /** Jo imports istemal nahi ho rahe, unhe hatao. */
    private fun removeUnusedImports() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val lines = text.split('\n')
        // Import lines ke indices nikalo.
        val importIdx = mutableListOf<Int>()
        for ((idx, ln) in lines.withIndex()) {
            if (ln.trim().startsWith("import ")) importIdx.add(idx)
        }
        if (importIdx.isEmpty()) {
            Toast.makeText(this, "Koi import nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        // Import block ke bahar ka text (usage check ke liye).
        val maskedAll = maskCodeForRefs(text)
        val importLineSet = importIdx.toSet()
        val bodyLines = maskedAll.split('\n')
            .filterIndexed { idx, _ -> idx !in importLineSet }
            .joinToString("\n")
        val toRemove = mutableListOf<Int>()
        for (idx in importIdx) {
            val imp = lines[idx].trim()
            // `import a.b.C` ya `import a.b.C as D`
            val m = Regex("^import\\s+([\\w.]+)(?:\\s+as\\s+([\\w]+))?").find(imp)
                ?: continue
            val fullPath = m.groupValues[1]
            // Star import skip karo (usage check mushkil hai).
            if (fullPath.endsWith(".*")) continue
            val alias = m.groupValues[2]
            val simpleName = alias.ifEmpty { fullPath.substringAfterLast('.') }
            // Body me istemal hua hai?
            val used = Regex("\\b" + Regex.escape(simpleName) + "\\b")
                .containsMatchIn(bodyLines)
            if (!used) toRemove.add(idx)
        }
        if (toRemove.isEmpty()) {
            Toast.makeText(this, "Saare imports istemal ho rahe hain", Toast.LENGTH_SHORT).show()
            return
        }
        ed.expandAllFoldsNow()
        val newLines = lines.filterIndexed { idx, _ -> idx !in toRemove.toSet() }
        val newText = newLines.joinToString("\n")
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity,
            toRemove.size.toString() + " unused imports hataye",
            Toast.LENGTH_SHORT).show()
    }

    /** Import statements ko alphabetically sort karo. */
    private fun sortImports() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val lines = text.split('\n').toMutableList()
        // Import block dhoondho (lagatar `import` lines).
        var blockStart = -1
        var blockEnd = -1
        for ((idx, ln) in lines.withIndex()) {
            val trimmed = ln.trim()
            if (trimmed.startsWith("import ")) {
                if (blockStart < 0) blockStart = idx
                blockEnd = idx
            } else if (blockStart >= 0) {
                // Khali line ya comment ke baad bhi import ho sakta hai — lekin
                // 2+ khaali lines ya code aane par block khatm.
                if (trimmed.isNotEmpty() && !trimmed.startsWith("//") &&
                    !trimmed.startsWith("*") && !trimmed.startsWith("/*")) {
                    break
                }
            }
        }
        if (blockStart < 0 || blockEnd <= blockStart) {
            Toast.makeText(this, "Koi import block nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val imports = lines.subList(blockStart, blockEnd + 1)
            .filter { it.trim().startsWith("import ") }
        if (imports.size < 2) {
            Toast.makeText(this, "Sort karne layak imports nahi hain",
                Toast.LENGTH_SHORT).show(); return
        }
        val sorted = imports.sortedWith(compareBy(
            // `import static` / alias alag group me nahi — bas alphabetical.
            { it.trim().substringAfter("import ").trim() }
        ))
        if (sorted == imports) {
            Toast.makeText(this, "Imports pehle se sorted hain", Toast.LENGTH_SHORT).show()
            return
        }
        // Replace karo (sirf import lines, beech ki khaali lines/comments rehne do).
        var si = 0
        for (idx in blockStart..blockEnd) {
            if (lines[idx].trim().startsWith("import ")) {
                // Original indent preserve karo.
                val indent = lines[idx].substringBefore("import")
                lines[idx] = indent + sorted[si].trim()
                si++
            }
        }
        ed.expandAllFoldsNow()
        val newText = lines.joinToString("\n")
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, "Imports sort ho gaye", Toast.LENGTH_SHORT).show()
    }

    /** Kabhi reassign na hone wale `var` ko `val` me badlo. */
    private fun convertVarToVal() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
        var lineE = text.indexOf('\n', caret)
        if (lineE < 0) lineE = text.length
        val line = text.substring(lineS, lineE)
        val maskedLine = maskCodeForRefs(line)
        val m = Regex("\\bvar\\s+([A-Za-z_][\\w]*)\\b").find(maskedLine)
        if (m == null) {
            Toast.makeText(this, "Caret kisi `var` par rakho", Toast.LENGTH_SHORT).show(); return
        }
        val varName = m.groupValues[1]
        // Poori file me reassignment dhoondho (declaration wali line chhodkar).
        val maskedAll = maskCodeForRefs(text)
        // `x =`, `x +=`, `x++`, `x--` — lekin `==`, `!=`, `<=`, `>=` nahi.
        val assignRx = Regex("\\b" + Regex.escape(varName) +
            "\\s*(?:=(?![=])|\\+=|-=|\\*=|/=|%=|\\+\\+|--)")
        var reassigned = false
        for (am in assignRx.findAll(maskedAll)) {
            // Declaration wali line par hai to skip karo.
            val aLineS = text.lastIndexOf('\n', (am.range.first - 1).coerceAtLeast(0)) + 1
            if (aLineS == lineS) continue
            reassigned = true
            break
        }
        if (reassigned) {
            Toast.makeText(this, "'" + varName + "' ko dobara assign kiya gaya hai",
                Toast.LENGTH_SHORT).show()
            return
        }
        // `var` ko `val` me badlo (sirf is occurrence par).
        ed.expandAllFoldsNow()
        val absVar = lineS + m.range.first
        // Confirm karo ki asal me `var` hi hai.
        if (!text.startsWith("var", absVar)) {
            Toast.makeText(this, "Kuch gadbad hui", Toast.LENGTH_SHORT).show(); return
        }
        val newText = text.substring(0, absVar) + "val" + text.substring(absVar + 3)
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, "'" + varName + "' ab val hai",
            Toast.LENGTH_SHORT).show()
    }

    /** `class Foo` ko `object Foo` me badlo (singleton). */
    private fun convertToObject() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val maskedOnce = maskCodeForRefs(text)
        // Caret wali class dhoondho.
        var classIdx = -1
        var classNameEnd = -1
        for (m in Regex("\\bclass\\s+([A-Za-z_][\\w]*)").findAll(maskedOnce)) {
            if (maskedOnce[m.range.first] != ' ') continue
            if (m.range.first > caret) break
            // `object` ya `data class`/`sealed`/`abstract` to nahi?
            val lineS = text.lastIndexOf('\n', (m.range.first - 1).coerceAtLeast(0)) + 1
            val prefix = text.substring(lineS, m.range.first)
            if (prefix.contains("object") || prefix.contains("data") ||
                prefix.contains("sealed") || prefix.contains("abstract") ||
                prefix.contains("enum") || prefix.contains("annotation")) {
                continue
            }
            classIdx = m.range.first
            classNameEnd = m.range.last + 1
        }
        if (classIdx < 0) {
            Toast.makeText(this, "Caret kisi normal class me rakho", Toast.LENGTH_SHORT).show()
            return
        }
        // Constructor params check karo — khaali hona chahiye.
        var hp = classNameEnd
        while (hp < text.length && (text[hp] == ' ' || text[hp] == '\t')) hp++
        // Type params skip karo.
        if (hp < text.length && text[hp] == '<') {
            var ad = 0
            while (hp < text.length) {
                if (text[hp] == '<') ad++
                else if (text[hp] == '>') { ad--; if (ad == 0) { hp++; break } }
                hp++
            }
            while (hp < text.length && (text[hp] == ' ' || text[hp] == '\t')) hp++
        }
        var hasParams = false
        var parenStart = -1
        var parenEnd = -1
        if (hp < text.length && text[hp] == '(') {
            parenStart = hp
            var pd = 0
            var q = hp
            while (q < text.length) {
                val ch = maskedOnce[q]
                if (ch == '(') pd++
                else if (ch == ')') {
                    pd--
                    if (pd == 0) { parenEnd = q; break }
                }
                q++
            }
            if (parenEnd > parenStart + 1) {
                val inside = text.substring(parenStart + 1, parenEnd).trim()
                if (inside.isNotEmpty()) hasParams = true
            }
        }
        if (hasParams) {
            Toast.makeText(this, "Constructor params wali class object nahi ban sakti",
                Toast.LENGTH_SHORT).show()
            return
        }
        ed.expandAllFoldsNow()
        var newText = text
        // 1. Khali `()` hatao (agar hai).
        if (parenStart >= 0 && parenEnd >= 0 && !hasParams) {
            newText = newText.substring(0, parenStart) + newText.substring(parenEnd + 1)
        }
        // 2. `class` ko `object` me badlo.
        // `()` class keyword ke baad tha, isliye classIdx ab bhi sahi hai.
        val m2 = Regex("\\bclass\\b").find(newText, classIdx)
        if (m2 == null) {
            Toast.makeText(this, "Kuch gadbad hui", Toast.LENGTH_SHORT).show(); return
        }
        newText = newText.substring(0, m2.range.first) + "object" +
                  newText.substring(m2.range.last + 1)
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, "Object ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** Class ke public functions se interface banao. */
    private fun showExtractInterfaceDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val maskedOnce = maskCodeForRefs(text)
        // Caret wali class dhoondho.
        var classIdx = -1
        var className = ""
        var classMatchEnd = -1
        for (m in Regex("\\bclass\\s+([A-Za-z_][\\w]*)").findAll(maskedOnce)) {
            if (maskedOnce[m.range.first] != ' ') continue
            if (m.range.first > caret) break
            classIdx = m.range.first
            className = m.groupValues[1]
            classMatchEnd = m.range.last + 1
        }
        if (classIdx < 0) {
            Toast.makeText(this, "Caret kisi class ke andar rakho", Toast.LENGTH_SHORT).show()
            return
        }
        // Class body dhoondho.
        val openB = text.indexOf('{', classMatchEnd)
        if (openB < 0) {
            Toast.makeText(this, "Class body nahi mili", Toast.LENGTH_SHORT).show(); return
        }
        var d = 0
        var closeB = -1
        var j = openB
        while (j < maskedOnce.length) {
            val ch = maskedOnce[j]
            if (ch == '{') d++
            else if (ch == '}') {
                d--
                if (d == 0) { closeB = j; break }
            }
            j++
        }
        if (closeB < 0) {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        // Public functions (private/override nahi) ke signatures nikalo.
        data class FunSig(val name: String, val signature: String, val funStart: Int)
        val funs = mutableListOf<FunSig>()
        val bodyMasked = maskedOnce.substring(openB, closeB + 1)
        for (m in Regex("\\bfun\\s+([A-Za-z_][\\w]*)\\s*(?:<[^>]*>)?\\s*\\(")
                .findAll(bodyMasked)) {
            val absStart = openB + m.range.first
            if (bodyMasked[m.range.first] != ' ') continue
            // Line ki shuruaat dekho — private/override/abstract to nahi?
            val lineS = text.lastIndexOf('\n', (absStart - 1).coerceAtLeast(0)) + 1
            val linePrefix = text.substring(lineS, absStart)
            if (linePrefix.contains("private") || linePrefix.contains("override")) continue
            val fnName = m.groupValues[1]
            // Signature: `fun name(...)` se lekar `{` ya `=` tak.
            var sigEnd = absStart
            var pd = 0
            var k = text.indexOf('(', absStart)
            if (k < 0) continue
            // Params ka ant dhoondho.
            pd = 0
            var p = k
            while (p < text.length) {
                val ch = maskedOnce[p]
                if (ch == '(') pd++
                else if (ch == ')') {
                    pd--
                    if (pd == 0) break
                }
                p++
            }
            if (p >= text.length) continue
            // Return type (agar hai).
            var q = p + 1
            while (q < text.length && (text[q] == ' ' || text[q] == '\t')) q++
            var retType = ""
            if (q < text.length && text[q] == ':') {
                q++
                while (q < text.length && (text[q] == ' ' || text[q] == '\t')) q++
                val rtStart = q
                while (q < text.length && text[q] != '{' && text[q] != '=' &&
                       text[q] != '\n') q++
                retType = text.substring(rtStart, q).trim()
                sigEnd = q
            } else {
                sigEnd = p + 1
            }
            val params = text.substring(k, p + 1)
            val sig = "fun " + fnName + params + (if (retType.isNotEmpty()) ": " + retType else "")
            funs.add(FunSig(fnName, sig, absStart))
        }
        if (funs.isEmpty()) {
            Toast.makeText(this, "Koi public function nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = className + "Interface"
            setSingleLine(true)
            setText(className + "Interface")
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Extract Interface (" + funs.size + " functions)")
            .setView(wrap)
            .setPositiveButton("Extract") { _, _ ->
                val ifaceName = input.text.toString().trim()
                if (!Regex("^[A-Z][A-Za-z0-9_]*$").matches(ifaceName)) {
                    Toast.makeText(this, "PascalCase naam likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Interface code banao.
                val code = StringBuilder()
                code.append("\ninterface ").append(ifaceName).append(" {\n")
                for (f in funs) {
                    code.append("    ").append(f.signature).append("\n")
                }
                code.append("}\n")
                ed.expandAllFoldsNow()
                var newText = text
                // 1. Interface class se pehle jodo.
                newText = newText.substring(0, classIdx) + code.toString() +
                          newText.substring(classIdx)
                // Offsets shift hue — dobara calculate karo.
                val shift = code.length
                val newClassIdx = classIdx + shift
                val newOpenB = openB + shift
                val newCloseB = closeB + shift
                // 2. Class header me `: Iface` jodo.
                // Constructor ke baad `:` hai to supertype pehle se hai.
                var hp = classMatchEnd + shift
                // Type params `<>` skip karo.
                if (hp < newText.length && newText[hp] == '<') {
                    var ad = 0
                    while (hp < newText.length) {
                        val c = newText[hp]
                        if (c == '<') ad++
                        else if (c == '>') { ad--; if (ad == 0) { hp++; break } }
                        hp++
                    }
                }
                // Constructor `()` skip karo.
                while (hp < newText.length && (newText[hp] == ' ' || newText[hp] == '\t' ||
                       newText[hp] == '\n')) hp++
                if (hp < newText.length && newText[hp] == '(') {
                    var pd = 0
                    while (hp < newText.length) {
                        val cc = newText[hp]
                        if (cc == '(') pd++
                        else if (cc == ')') { pd--; if (pd == 0) { hp++; break } }
                        hp++
                    }
                }
                while (hp < newText.length && (newText[hp] == ' ' || newText[hp] == '\t' ||
                       newText[hp] == '\n')) hp++
                val hasSupertype = hp < newText.length && newText[hp] == ':'
                val ifaceDecl = if (hasSupertype) ", " + ifaceName else " : " + ifaceName
                // `{` se pehle jodo (newOpenB par).
                newText = newText.substring(0, newOpenB) + ifaceDecl + newText.substring(newOpenB)
                val shift2 = ifaceDecl.length
                // 3. Har function me `override` jodo (peeche se taaki offset na bigde).
                val sortedFuns = funs.sortedByDescending { it.funStart }
                for (f in sortedFuns) {
                    val absPos = f.funStart + shift + shift2
                    // `fun` se pehle `override ` jodo.
                    newText = newText.substring(0, absPos) + "override " +
                              newText.substring(absPos)
                }
                ed.text?.replace(0, text.length, newText)
                Toast.makeText(this@MainActivity,
                    "Interface '" + ifaceName + "' ban gaya",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** `val x: String = ...` se redundant `: String` hatao. */
    private fun removeExplicitType() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
        var lineE = text.indexOf('\n', caret)
        if (lineE < 0) lineE = text.length
        val line = text.substring(lineS, lineE)
        val maskedLine = maskCodeForRefs(line)
        // `val|var name : Type =` dhoondho (initializer hona chahiye).
        val m = Regex("\\b(val|var)\\s+([A-Za-z_][\\w]*)\\s*:\\s*([^=;]+?)\\s*=").find(maskedLine)
        if (m == null) {
            Toast.makeText(this, "Yahan koi redundant type nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        // `:` se lekar `=` se pehle tak hatao.
        val colonIdx = line.indexOf(':', m.range.first)
        val eqIdx = line.indexOf('=', m.range.first)
        if (colonIdx < 0 || eqIdx < 0 || colonIdx > eqIdx) {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        ed.expandAllFoldsNow()
        val absColon = lineS + colonIdx
        val absEq = lineS + eqIdx
        // `:` ke pehle ka space rakho, type hatao: `val x = ...`
        var newDecl = line.substring(0, colonIdx).trimEnd() + " " + line.substring(eqIdx)
        val newText = text.substring(0, lineS) + newDecl + text.substring(lineE)
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, "Type hata diya", Toast.LENGTH_SHORT).show()
    }

    /** Layout ke ek section ko alag file me nikalo + <include> lagao. */
    private fun showExtractIncludeDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val curFile = try { GitManager.resolveDocToFile(currentFileUri!!) }
                      catch (e: Exception) { null } ?: run {
            Toast.makeText(this, "File nahi mili", Toast.LENGTH_SHORT).show(); return
        }
        if (!curFile.absolutePath.replace('\\', '/').contains("/res/layout/")) {
            Toast.makeText(this, "Sirf layout XML files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        val maskedOnce = maskCodeForRefs(text)
        // Caret wala tag dhoondho.
        var tagStart = -1
        var i = (caret - 1).coerceAtLeast(0)
        while (i >= 0) {
            val ch = maskedOnce[i]
            if (ch == '<') { tagStart = i; break }
            if (ch == '>') break
            i--
        }
        if (tagStart < 0 || text[tagStart + 1] == '/' || text[tagStart + 1] == '!' ||
            text[tagStart + 1] == '?') {
            Toast.makeText(this, "Caret kisi view tag par rakho", Toast.LENGTH_SHORT).show(); return
        }
        // Tag ka naam nikalo.
        val tagNameM = Regex("<([A-Za-z_][\\w.]*)").find(text.substring(tagStart))
        val tagName = tagNameM?.groupValues?.get(1) ?: run {
            Toast.makeText(this, "Tag parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        // Poora tag (matching close tak) dhoondho.
        var tagEnd = -1
        var depth = 0
        var j = tagStart
        val openTagEnd = text.indexOf('>', tagStart)
        if (openTagEnd < 0) {
            Toast.makeText(this, "Tag parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val isSelfClosing = text[openTagEnd - 1] == '/'
        if (isSelfClosing) {
            tagEnd = openTagEnd
        } else {
            depth = 1
            j = openTagEnd + 1
            fun isTagBoundary(pos: Int): Boolean {
                if (pos >= text.length) return true
                val c = text[pos]
                return c == '>' || c == '/' || c == ' ' || c == '\t' ||
                       c == '\n' || c == '\r'
            }
            while (j < text.length && depth > 0) {
                if (maskedOnce[j] == '<') {
                    if (text.startsWith("</" + tagName, j) &&
                        isTagBoundary(j + 2 + tagName.length)) {
                        depth--
                        if (depth == 0) {
                            tagEnd = text.indexOf('>', j)
                            break
                        }
                    } else if (text.startsWith("<" + tagName, j) &&
                               isTagBoundary(j + 1 + tagName.length)) {
                        // Self-closing check: <Tag ... />
                        val oe = text.indexOf('>', j)
                        if (oe >= 0 && text[oe - 1] != '/') depth++
                    }
                }
                j++
            }
        }
        if (tagEnd < 0) {
            Toast.makeText(this, "Tag ka ant nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val tagText = text.substring(tagStart, tagEnd + 1)
        val input = android.widget.EditText(this).apply {
            hint = "component_header"
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Extract Include (<" + tagName + ">)")
            .setView(wrap)
            .setPositiveButton("Extract") { _, _ ->
                val layoutName = input.text.toString().trim()
                if (!Regex("^[a-z][a-z0-9_]*$").matches(layoutName)) {
                    Toast.makeText(this, "snake_case naam likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // res/layout dir dhoondho.
                var dir: File? = curFile.parentFile
                var layoutDir: File? = null
                var guard = 0
                while (dir != null && guard++ < 12) {
                    val ld = File(dir, "res/layout")
                    if (ld.isDirectory) { layoutDir = ld; break }
                    // Khud hi layout dir ho sakta hai.
                    if (dir.name == "layout" && dir.parentFile?.name == "res") {
                        layoutDir = dir; break
                    }
                    dir = dir.parentFile
                }
                if (layoutDir == null) {
                    Toast.makeText(this, "res/layout nahi mila", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newFile = File(layoutDir, layoutName + ".xml")
                if (newFile.exists()) {
                    Toast.makeText(this, "File pehle se hai", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Nayi file banao (xmlns ke saath).
                var newContent = tagText
                if (!newContent.contains("xmlns:android")) {
                    val insAt = newContent.indexOf(' ')
                    if (insAt > 0) {
                        newContent = newContent.substring(0, insAt) +
                            "\n    xmlns:android=\"http://schemas.android.com/apk/res/android\"" +
                            newContent.substring(insAt)
                    }
                }
                newContent = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + newContent + "\n"
                try {
                    newFile.writeText(newContent)
                } catch (e: Exception) {
                    Toast.makeText(this, "File bana nahi paya", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Original me <include> lagao.
                ed.expandAllFoldsNow()
                val lineS = text.lastIndexOf('\n', (tagStart - 1).coerceAtLeast(0)) + 1
                val indent = text.substring(lineS, tagStart).takeWhile { it == ' ' || it == '\t' }
                val includeTag = indent + "<include layout=\"@layout/" + layoutName + "\" />"
                val newText = text.substring(0, tagStart) + includeTag +
                              text.substring(tagEnd + 1)
                ed.text?.replace(0, text.length, newText)
                Toast.makeText(this@MainActivity,
                    layoutName + ".xml ban gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Current class ke liye JUnit test stub banao (src/test me nayi file). */
    private fun generateUnitTest() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val curFile = try { GitManager.resolveDocToFile(currentFileUri!!) }
                      catch (e: Exception) { null } ?: run {
            Toast.makeText(this, "File nahi mili", Toast.LENGTH_SHORT).show(); return
        }
        if (!curFile.name.endsWith(".kt") || curFile.name.endsWith("Test.kt")) {
            Toast.makeText(this, "Sirf non-test Kotlin files me", Toast.LENGTH_SHORT).show()
            return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        // Class naam dhoondho (pehli top-level class).
        val maskedOnce = maskCodeForRefs(text)
        var className: String? = null
        for (m in Regex("\\bclass\\s+([A-Za-z_][\\w]*)").findAll(maskedOnce)) {
            if (maskedOnce[m.range.first] != ' ') continue
            className = m.groupValues[1]
            break
        }
        if (className == null) {
            Toast.makeText(this, "Class nahi mili", Toast.LENGTH_SHORT).show(); return
        }
        // Public functions dhoondho (class body ke andar).
        val funNames = mutableListOf<String>()
        for (m in Regex("\\b(public\\s+)?fun\\s+([A-Za-z_][\\w]*)\\s*(?:[<][^>]*[>])?\\s*\\(")
                .findAll(maskedOnce)) {
            if (maskedOnce[m.range.first] != ' ') continue
            val fn = m.groupValues[2]
            if (fn !in funNames) funNames.add(fn)
        }
        // Package nikalo.
        val pkg = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1) ?: ""
        // Test file ka path: src/main -> src/test, Foo.kt -> FooTest.kt
        val absPath = curFile.absolutePath.replace('\\', '/')
        val testPath = absPath.replace("/src/main/", "/src/test/")
        if (testPath == absPath) {
            Toast.makeText(this, "src/main me nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val testFile = java.io.File(
            testPath.substringBeforeLast('/') + "/" + className + "Test.kt")
        if (testFile.exists()) {
            Toast.makeText(this, "Test pehle se hai", Toast.LENGTH_SHORT).show(); return
        }
        // Test code banao.
        val code = StringBuilder()
        if (pkg.isNotEmpty()) code.append("package ").append(pkg).append("\n\n")
        code.append("import org.junit.Test\n")
            .append("import org.junit.Assert.*\n\n")
            .append("class ").append(className).append("Test {\n\n")
        if (funNames.isEmpty()) {
            code.append("    @Test\n")
                .append("    fun `sample test`() {\n")
                .append("        // TODO: test likho\n")
                .append("    }\n")
        } else {
            for (fn in funNames) {
                code.append("    @Test\n")
                    .append("    fun `").append(fn).append(" works`() {\n")
                    .append("        // TODO: ").append(fn).append(" ko test karo\n")
                    .append("    }\n\n")
            }
        }
        code.append("}\n")
        try {
            testFile.parentFile?.mkdirs()
            testFile.writeText(code.toString())
        } catch (e: Exception) {
            Toast.makeText(this, "File bana nahi paya: " + e.message,
                Toast.LENGTH_SHORT).show()
            return
        }
        // Nayi test file kholo.
        try {
            val doc = androidx.documentfile.provider.DocumentFile
                .fromFile(testFile)
            openFileInTab(doc)
            Toast.makeText(this@MainActivity, className + "Test.kt ban gaya",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "File ban gayi: " + testFile.absolutePath,
                Toast.LENGTH_LONG).show()
        }
    }

    /** Data class ke liye Builder pattern generate karo (nested class). */
    private fun generateBuilder() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        if (text.contains("class Builder")) {
            Toast.makeText(this, "Builder pehle se hai", Toast.LENGTH_SHORT).show(); return
        }
        // Caret wali class dhoondho.
        val caret = ed.selectionStart
        val maskedOnce = maskCodeForRefs(text)
        var classIdx = -1
        var className = ""
        // Caret se pehle wali sabse najdeek class.
        for (m in Regex("\\bclass\\s+([A-Za-z_][\\w]*)").findAll(maskedOnce)) {
            if (maskedOnce[m.range.first] != ' ') continue
            if (m.range.first > caret) break
            classIdx = m.range.first
            className = m.groupValues[1]
        }
        if (classIdx < 0) {
            Toast.makeText(this, "Caret kisi class ke andar rakho", Toast.LENGTH_SHORT).show()
            return
        }
        // Primary constructor ke params nikalo.
        val openParen = text.indexOf('(', classIdx)
        if (openParen < 0 || openParen - classIdx > 300) {
            Toast.makeText(this, "Constructor nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        var depth = 0
        var closeParen = -1
        var j = openParen
        while (j < text.length) {
            val ch = maskedOnce[j]
            if (ch == '(') depth++
            else if (ch == ')') {
                depth--
                if (depth == 0) { closeParen = j; break }
            }
            j++
        }
        if (closeParen < 0) {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val paramsRaw = text.substring(openParen + 1, closeParen)
        // Params split karo (top-level commas par).
        val params = mutableListOf<Triple<String, String, String?>>()
        var sb = StringBuilder()
        var d2 = 0
        var k = 0
        val maskedParams = maskCodeForRefs(paramsRaw)
        while (k <= paramsRaw.length) {
            val ch = if (k < paramsRaw.length) maskedParams[k] else ','
            if (ch == '<' || ch == '(' || ch == '[') d2++
            else if (ch == '>' || ch == ')' || ch == ']') d2--
            if (ch == ',' && d2 == 0) {
                val pstr = sb.toString().trim()
                if (pstr.isNotEmpty()) {
                    val pm = Regex("(?:val|var)\\s+([A-Za-z_][\\w]*)\\s*:" +
                                  "\\s*([^=]+?)(?:\\s*=\\s*(.+))?$").find(pstr)
                    if (pm != null) {
                        params.add(Triple(pm.groupValues[1], pm.groupValues[2].trim(),
                                          pm.groupValues[3].ifEmpty { null }))
                    }
                }
                sb = StringBuilder()
            } else if (k < paramsRaw.length) {
                sb.append(paramsRaw[k])
            }
            k++
        }
        if (params.isEmpty()) {
            Toast.makeText(this, "Koi val/var param nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        // Builder code banao.
        val code = StringBuilder()
        code.append("\n    class Builder {\n")
        for ((pname, ptype, pdefault) in params) {
            val fieldInit = if (pdefault != null) ": " + ptype + " = " + pdefault
                            else ": " + ptype + "? = null"
            code.append("        private var ").append(pname).append(fieldInit).append("\n")
        }
        code.append("\n")
        for ((pname, ptype, _) in params) {
            code.append("        fun ").append(pname).append("(value: ").append(ptype)
                .append(") = apply { ").append(pname).append(" = value }\n")
        }
        code.append("\n        fun build(): ").append(className).append(" {\n")
        code.append("            return ").append(className).append("(\n")
        for ((idx, p) in params.withIndex()) {
            val (pname, _, pdefault) = p
            val argVal = if (pdefault != null) pname
                         else pname + " ?: throw IllegalStateException(\"" +
                              pname + " required\")"
            code.append("                ").append(pname).append(" = ").append(argVal)
            if (idx < params.size - 1) code.append(",")
            code.append("\n")
        }
        code.append("            )\n        }\n    }\n")
        // Class ke aakhri `}` se pehle jodo.
        val openB = text.indexOf('{', closeParen)
        var insertAt = caret
        if (openB >= 0) {
            var d3 = 0
            var m2 = openB
            while (m2 < maskedOnce.length) {
                val ch = maskedOnce[m2]
                if (ch == '{') d3++
                else if (ch == '}') {
                    d3--
                    if (d3 == 0) { insertAt = m2; break }
                }
                m2++
            }
        }
        ed.expandAllFoldsNow()
        val newText = text.substring(0, insertAt) + code.toString() + text.substring(insertAt)
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, "Builder ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** Hardcoded dp/sp ko dimens.xml me nikalo. */
    private fun showExtractDimenDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val curFile = try { GitManager.resolveDocToFile(currentFileUri!!) }
                      catch (e: Exception) { null } ?: run {
            Toast.makeText(this, "File nahi mili", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        // Caret ke aas-paas `16dp` / `24sp` dhoondho.
        val dimRx = Regex("(\\d+(?:\\.\\d+)?)(dp|sp)")
        var hit: MatchResult? = null
        for (m in dimRx.findAll(text)) {
            if (caret in m.range.first..m.range.last + 1) { hit = m; break }
        }
        if (hit == null) {
            Toast.makeText(this, "Caret kisi dp/sp value par rakho", Toast.LENGTH_SHORT).show()
            return
        }
        val dimenValue = hit.value
        val input = android.widget.EditText(this).apply {
            hint = "margin_large"
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Extract Dimension (" + dimenValue + ")")
            .setView(wrap)
            .setPositiveButton("Extract") { _, _ ->
                val dimenName = input.text.toString().trim()
                if (!Regex("^[a-z][a-z0-9_]*$").matches(dimenName)) {
                    Toast.makeText(this, "snake_case naam likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // dimens.xml dhoondho ya banao.
                var dir: File? = curFile.parentFile
                var valuesDir: File? = null
                var guard = 0
                while (dir != null && guard++ < 12) {
                    val v = File(dir, "res/values")
                    if (v.isDirectory) { valuesDir = v; break }
                    dir = dir.parentFile
                }
                if (valuesDir == null) {
                    Toast.makeText(this, "res/values nahi mila", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val dimensFile = File(valuesDir, "dimens.xml")
                if (dimensFile.exists()) {
                    val existing = try { dimensFile.readText() } catch (e: Exception) { "" }
                    if (Regex("<dimen\\s+name=\"" + Regex.escape(dimenName) + "\"")
                            .containsMatchIn(existing)) {
                        Toast.makeText(this, "Dimen pehle se hai", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }
                val dimenBlock = "    <dimen name=\"" + dimenName + "\">" +
                                 dimenValue + "</dimen>\n"
                if (dimensFile.exists()) {
                    var content = try { dimensFile.readText() } catch (e: Exception) { "" }
                    val closeIdx = content.lastIndexOf("</resources>")
                    if (closeIdx >= 0) {
                        content = content.substring(0, closeIdx) + dimenBlock +
                                  content.substring(closeIdx)
                    } else {
                        content = content.trimEnd() + "\n" + dimenBlock
                    }
                    try { dimensFile.writeText(content) }
                    catch (e: Exception) {
                        Toast.makeText(this, "dimens.xml likh nahi paya", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                } else {
                    val newContent = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                                     "<resources>\n" + dimenBlock + "</resources>\n"
                    try { dimensFile.writeText(newContent) }
                    catch (e: Exception) {
                        Toast.makeText(this, "dimens.xml bana nahi paya", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }
                // Layout me replace karo.
                ed.expandAllFoldsNow()
                val h = hit
                val newText = text.substring(0, h.range.first) + "@dimen/" + dimenName +
                              text.substring(h.range.last + 1)
                ed.text?.replace(0, text.length, newText)
                Toast.makeText(this@MainActivity,
                    "Dimen '" + dimenName + "' dimens.xml me ban gaya",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Layout tag ke attributes ko styles.xml me naye style me nikalo. */
    private fun showExtractStyleDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val curFile = try { GitManager.resolveDocToFile(currentFileUri!!) }
                      catch (e: Exception) { null }
        if (curFile == null || !curFile.absolutePath.replace('\\', '/')
                .contains("/res/layout/")) {
            Toast.makeText(this, "Sirf layout XML files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        // Caret wala tag dhoondho (uski shuruaat `<`).
        val maskedOnce = maskCodeForRefs(text)
        var tagStart = -1
        var i = (caret - 1).coerceAtLeast(0)
        while (i >= 0) {
            val ch = maskedOnce[i]
            if (ch == '<') { tagStart = i; break }
            if (ch == '>') break // pichhle tag ka ant — caret tag ke bahar
            i--
        }
        if (tagStart < 0 || text[tagStart + 1] == '/' || text[tagStart + 1] == '!' ||
            text[tagStart + 1] == '?') {
            Toast.makeText(this, "Caret kisi view tag par rakho", Toast.LENGTH_SHORT).show(); return
        }
        var tagEnd = text.indexOf('>', tagStart)
        if (tagEnd < 0) {
            Toast.makeText(this, "Tag parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val tagText = text.substring(tagStart, tagEnd + 1)
        // Attributes nikalo (android:id chhodkar — wo style me nahi jata).
        val attrRx = Regex("([a-zA-Z_:][\\w:.-]*)\\s*=\\s*\"([^\"]*)\"")
        val attrs = attrRx.findAll(tagText).map { it.groupValues[1] to it.groupValues[2] }
            .filter { (n, _) -> n != "android:id" }.toList()
        if (attrs.isEmpty()) {
            Toast.makeText(this, "Koi attribute nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val input = android.widget.EditText(this).apply {
            hint = "MyStyle"
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Extract Style (" + attrs.size + " attributes)")
            .setView(wrap)
            .setPositiveButton("Extract") { _, _ ->
                val styleName = input.text.toString().trim()
                if (!Regex("^[A-Z][A-Za-z0-9_]*$").matches(styleName)) {
                    Toast.makeText(this, "PascalCase naam likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // styles.xml dhoondho ya banao.
                var dir: File? = curFile.parentFile
                var valuesDir: File? = null
                var guard = 0
                while (dir != null && guard++ < 12) {
                    val v = File(dir, "res/values")
                    if (v.isDirectory) { valuesDir = v; break }
                    dir = dir.parentFile
                }
                if (valuesDir == null) {
                    Toast.makeText(this, "res/values nahi mila", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val stylesFile = File(valuesDir, "styles.xml")
                if (stylesFile.exists()) {
                    val existing = try { stylesFile.readText() } catch (e: Exception) { "" }
                    if (Regex("<style\\s+name=\"" + Regex.escape(styleName) + "\"")
                            .containsMatchIn(existing)) {
                        Toast.makeText(this, "Style pehle se hai", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }
                // Style block banao.
                val sb = StringBuilder()
                sb.append("    <style name=\"").append(styleName).append("\">\n")
                for ((n, v) in attrs) {
                    sb.append("        <item name=\"").append(n).append("\">")
                        .append(v).append("</item>\n")
                }
                sb.append("    </style>\n")
                val styleBlock = sb.toString()
                // styles.xml me jodo (</resources> se pehle, ya nayi file).
                if (stylesFile.exists()) {
                    var content = try { stylesFile.readText() } catch (e: Exception) { "" }
                    val closeIdx = content.lastIndexOf("</resources>")
                    if (closeIdx >= 0) {
                        content = content.substring(0, closeIdx) + styleBlock +
                                  content.substring(closeIdx)
                    } else {
                        content = content.trimEnd() + "\n" + styleBlock
                    }
                    try { stylesFile.writeText(content) }
                    catch (e: Exception) {
                        Toast.makeText(this, "styles.xml likh nahi paya", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                } else {
                    val newContent = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                                     "<resources>\n" + styleBlock + "</resources>\n"
                    try { stylesFile.writeText(newContent) }
                    catch (e: Exception) {
                        Toast.makeText(this, "styles.xml bana nahi paya", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }
                // Layout me attributes hatao, style lagao.
                var newTag = tagText
                for ((n, v) in attrs) {
                    val aRx = Regex(Regex.escape(n) + "\\s*=\\s*\"" +
                                    Regex.escape(v) + "\"")
                    newTag = aRx.replaceFirst(newTag, "")
                }
                // style attribute jodo (tag naam ke baad).
                val nameEnd = newTag.indexOfAny(charArrayOf(' ', '\t', '\n', '\r', '/', '>'))
                if (nameEnd > 0) {
                    newTag = newTag.substring(0, nameEnd) +
                             "\n        style=\"@style/" + styleName + "\"" +
                             newTag.substring(nameEnd)
                }
                // Extra khaali lines saaf karo.
                newTag = newTag.replace(Regex("\n\\s*\n"), "\n")
                ed.expandAllFoldsNow()
                val newText = text.substring(0, tagStart) + newTag +
                              text.substring(tagEnd + 1)
                ed.text?.replace(0, text.length, newText)
                Toast.makeText(this@MainActivity,
                    "Style '" + styleName + "' styles.xml me ban gaya",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Notification channel + showNotification() boilerplate caret par generate karo. */
    private fun generateNotificationHelper() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        var text = ed.text?.toString() ?: return
        if (text.contains("fun showNotification(")) {
            Toast.makeText(this, "Helper pehle se hai", Toast.LENGTH_SHORT).show(); return
        }
        val code = StringBuilder()
        code.append("\n    private fun createNotificationChannel() {\n")
            .append("        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {\n")
            .append("            val channel = NotificationChannel(\n")
            .append("                \"default_channel\",\n")
            .append("                \"Default\",\n")
            .append("                NotificationManager.IMPORTANCE_DEFAULT\n")
            .append("            )\n")
            .append("            getSystemService(NotificationManager::class.java)\n")
            .append("                .createNotificationChannel(channel)\n")
            .append("        }\n")
            .append("    }\n\n")
            .append("    private fun showNotification(title: String, message: String) {\n")
            .append("        createNotificationChannel()\n")
            .append("        val notification = NotificationCompat.Builder(this, \"default_channel\")\n")
            .append("            .setSmallIcon(android.R.drawable.ic_dialog_info)\n")
            .append("            .setContentTitle(title)\n")
            .append("            .setContentText(message)\n")
            .append("            .setPriority(NotificationCompat.PRIORITY_DEFAULT)\n")
            .append("            .setAutoCancel(true)\n")
            .append("            .build()\n")
            .append("        NotificationManagerCompat.from(this).notify(1, notification)\n")
            .append("    }\n")
        val caretOrig = ed.selectionStart
        val needImports = listOf(
            "android.app.NotificationChannel",
            "android.app.NotificationManager",
            "android.os.Build",
            "androidx.core.app.NotificationCompat",
            "androidx.core.app.NotificationManagerCompat"
        ).filter { imp ->
            !Regex("^import\\s+" + Regex.escape(imp) + "\\b", RegexOption.MULTILINE)
                .containsMatchIn(text)
        }
        var shift = 0
        if (needImports.isNotEmpty()) {
            val impRx = Regex("^import\\s+.*\\n", RegexOption.MULTILINE)
            val lastImp = impRx.findAll(text).lastOrNull()
            val impBlock = needImports.joinToString("\n") { "import " + it } + "\n"
            if (lastImp != null) {
                val at = lastImp.range.last + 1
                text = text.substring(0, at) + impBlock + text.substring(at)
                if (at <= caretOrig) shift = impBlock.length
            } else {
                val pkgEnd = text.indexOf('\n')
                if (pkgEnd >= 0) {
                    val at = pkgEnd + 1
                    text = text.substring(0, at) + "\n" + impBlock + text.substring(at)
                    if (at <= caretOrig) shift = impBlock.length + 1
                } else {
                    text = impBlock + text
                    shift = impBlock.length
                }
            }
        }
        ed.expandAllFoldsNow()
        val caret = (caretOrig + shift).coerceIn(0, text.length)
        // Class body dhoondho, uske aakhri `}` se pehle jodo.
        val maskedOnce = maskCodeForRefs(text)
        var classIdx = -1
        for (m in Regex("\bclass\b").findAll(maskedOnce)) {
            if (maskedOnce[m.range.first] != ' ') continue
            classIdx = m.range.first
            break
        }
        var insertAt = caret
        if (classIdx >= 0) {
            val openB = text.indexOf('{', classIdx)
            if (openB >= 0) {
                var d = 0
                var j = openB
                while (j < maskedOnce.length) {
                    val ch = maskedOnce[j]
                    if (ch == '{') d++
                    else if (ch == '}') {
                        d--
                        if (d == 0) { insertAt = j; break }
                    }
                    j++
                }
            }
        }
        val newText = text.substring(0, insertAt) + code.toString() + text.substring(insertAt)
        ed.text?.replace(0, ed.text?.length ?: 0, newText)
        Toast.makeText(this@MainActivity, "Notification helper ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** Room @Entity + @Dao ka boilerplate caret par generate karo. */
    private fun showGenerateRoomDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        fun field(hint: String): android.widget.EditText {
            return android.widget.EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
            }
        }
        val nameIn = field("User")
        val tableIn = field("users (optional)")
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameIn); addView(tableIn)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Generate Room Entity+DAO")
            .setView(layout)
            .setPositiveButton("Generate") { _, _ ->
                val eName = nameIn.text.toString().trim()
                var tName = tableIn.text.toString().trim()
                if (!Regex("^[A-Z][A-Za-z0-9_]*$").matches(eName)) {
                    Toast.makeText(this, "PascalCase naam likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (tName.isEmpty()) tName = eName.lowercase() + "s"
                if (!Regex("^[a-z][a-z0-9_]*$").matches(tName)) {
                    Toast.makeText(this, "Table naam sahi likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val code = StringBuilder()
                code.append("\n@Entity(tableName = \"").append(tName).append("\")\n")
                    .append("data class ").append(eName).append("(\n")
                    .append("    @PrimaryKey(autoGenerate = true)\n")
                    .append("    val id: Long = 0,\n")
                    .append("    // TODO: apne fields jodo\n")
                    .append(")\n\n")
                    .append("@Dao\n")
                    .append("interface ").append(eName).append("Dao {\n\n")
                    .append("    @Query(\"SELECT * FROM ").append(tName).append("\")\n")
                    .append("    suspend fun getAll(): List<").append(eName).append(">\n\n")
                    .append("    @Query(\"SELECT * FROM ").append(tName)
                    .append(" WHERE id = :id\")\n")
                    .append("    suspend fun getById(id: Long): ").append(eName).append("?\n\n")
                    .append("    @Insert\n")
                    .append("    suspend fun insert(item: ").append(eName).append("): Long\n\n")
                    .append("    @Update\n")
                    .append("    suspend fun update(item: ").append(eName).append(")\n\n")
                    .append("    @Delete\n")
                    .append("    suspend fun delete(item: ").append(eName).append(")\n")
                    .append("}\n")
                val ed = binding.editor
                var text = ed.text?.toString() ?: return@setPositiveButton
                val caretOrig = ed.selectionStart
                val needImports = listOf(
                    "androidx.room.Dao",
                    "androidx.room.Delete",
                    "androidx.room.Entity",
                    "androidx.room.Insert",
                    "androidx.room.PrimaryKey",
                    "androidx.room.Query",
                    "androidx.room.Update"
                ).filter { imp ->
                    !Regex("^import\\s+" + Regex.escape(imp) + "\\b", RegexOption.MULTILINE)
                        .containsMatchIn(text)
                }
                var shift = 0
                if (needImports.isNotEmpty()) {
                    val impRx = Regex("^import\\s+.*\\n", RegexOption.MULTILINE)
                    val lastImp = impRx.findAll(text).lastOrNull()
                    val impBlock = needImports.joinToString("\n") { "import " + it } + "\n"
                    if (lastImp != null) {
                        val at = lastImp.range.last + 1
                        text = text.substring(0, at) + impBlock + text.substring(at)
                        if (at <= caretOrig) shift = impBlock.length
                    } else {
                        val pkgEnd = text.indexOf('\n')
                        if (pkgEnd >= 0) {
                            val at = pkgEnd + 1
                            text = text.substring(0, at) + "\n" + impBlock + text.substring(at)
                            if (at <= caretOrig) shift = impBlock.length + 1
                        } else {
                            text = impBlock + text
                            shift = impBlock.length
                        }
                    }
                }
                ed.expandAllFoldsNow()
                val caret = (caretOrig + shift).coerceIn(0, text.length)
                val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
                val newText = text.substring(0, lineS) + code.toString() + text.substring(lineS)
                ed.text?.replace(0, ed.text?.length ?: 0, newText)
                Toast.makeText(this@MainActivity, eName + " entity+DAO ban gaya",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Activity me ViewBinding setup karo: property + inflate + setContentView. */
    private fun generateViewBinding() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        if (text.contains("private lateinit var binding:")) {
            Toast.makeText(this, "Binding pehle se hai", Toast.LENGTH_SHORT).show(); return
        }
        // setContentView(R.layout.xxx) dhoondho.
        val scm = Regex("setContentView\\s*\\(\\s*R\\.layout\\.([a-z][a-z0-9_]*)\\s*\\)")
            .find(text) ?: run {
                Toast.makeText(this, "setContentView(R.layout.xxx) nahi mila", Toast.LENGTH_SHORT).show()
                return
            }
        val layoutName = scm.groupValues[1]
        val bindingName = layoutName.split("_").joinToString("") {
            it.replaceFirstChar(Char::uppercase)
        } + "Binding"
        // Class body ka `{` dhoondho (pehli class).
        val maskedOnce = maskCodeForRefs(text)
        var classIdx = -1
        for (m in Regex("\\bclass\\b").findAll(maskedOnce)) {
            if (maskedOnce[m.range.first] != ' ') continue
            classIdx = m.range.first
            break
        }
        if (classIdx < 0) {
            Toast.makeText(this, "Class nahi mili", Toast.LENGTH_SHORT).show(); return
        }
        var bi = text.indexOf('{', classIdx)
        if (bi < 0) {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        // Property jodo (class ke andar, pehli line par).
        val propIndent = "    "
        val prop = "\n" + propIndent + "private lateinit var binding: " + bindingName + "\n"
        // setContentView replace karo.
        val scLineS = text.lastIndexOf('\n', (scm.range.first - 1).coerceAtLeast(0)) + 1
        val scIndent = text.substring(scLineS, scm.range.first)
            .takeWhile { it == ' ' || it == '\t' }
        val scReplacement = "binding = " + bindingName + ".inflate(layoutInflater)\n" +
                            scIndent + "setContentView(binding.root)"
        ed.expandAllFoldsNow()
        var newText = text.substring(0, bi + 1) + prop + text.substring(bi + 1)
        // Offsets shift hue (prop jud gayi) — setContentView dobara dhoondho.
        val scm2 = Regex("setContentView\\s*\\(\\s*R\\.layout\\." +
                         Regex.escape(layoutName) + "\\s*\\)").find(newText)
        if (scm2 == null) {
            Toast.makeText(this, "Kuch gadbad hui", Toast.LENGTH_SHORT).show(); return
        }
        val ls2 = newText.lastIndexOf('\n', (scm2.range.first - 1).coerceAtLeast(0)) + 1
        val le2 = newText.indexOf('\n', scm2.range.last).let { if (it < 0) newText.length else it }
        newText = newText.substring(0, ls2) + scIndent + scReplacement +
                  newText.substring(le2)
        ed.text?.replace(0, text.length, newText)
        Toast.makeText(this@MainActivity, bindingName + " setup ho gaya", Toast.LENGTH_SHORT).show()
    }

    /** Custom View ka boilerplate (constructors + onDraw + onMeasure) caret par generate karo. */
    private fun showGenerateCustomViewDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val input = android.widget.EditText(this).apply {
            hint = "MyView"
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Generate Custom View")
            .setView(wrap)
            .setPositiveButton("Generate") { _, _ ->
                val vName = input.text.toString().trim()
                if (!Regex("^[A-Z][A-Za-z0-9_]*$").matches(vName)) {
                    Toast.makeText(this, "PascalCase naam likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val code = StringBuilder()
                code.append("\nclass ").append(vName).append(" @JvmOverloads constructor(\n")
                    .append("    context: Context,\n")
                    .append("    attrs: AttributeSet? = null,\n")
                    .append("    defStyleAttr: Int = 0\n")
                    .append(") : View(context, attrs, defStyleAttr) {\n\n")
                    .append("    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)\n\n")
                    .append("    init {\n")
                    .append("        // TODO: attrs padho\n")
                    .append("    }\n\n")
                    .append("    override fun onDraw(canvas: Canvas) {\n")
                    .append("        super.onDraw(canvas)\n")
                    .append("        // TODO: draw karo\n")
                    .append("    }\n\n")
                    .append("    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {\n")
                    .append("        super.onMeasure(widthMeasureSpec, heightMeasureSpec)\n")
                    .append("        // TODO: measure karo\n")
                    .append("    }\n")
                    .append("}\n")
                val ed = binding.editor
                var text = ed.text?.toString() ?: return@setPositiveButton
                val caretOrig = ed.selectionStart
                val needImports = listOf(
                    "android.content.Context",
                    "android.graphics.Canvas",
                    "android.graphics.Paint",
                    "android.util.AttributeSet",
                    "android.view.View"
                ).filter { imp ->
                    !Regex("^import\\s+" + Regex.escape(imp) + "\\b", RegexOption.MULTILINE)
                        .containsMatchIn(text)
                }
                var shift = 0
                if (needImports.isNotEmpty()) {
                    val impRx = Regex("^import\\s+.*\\n", RegexOption.MULTILINE)
                    val lastImp = impRx.findAll(text).lastOrNull()
                    val impBlock = needImports.joinToString("\n") { "import " + it } + "\n"
                    if (lastImp != null) {
                        val at = lastImp.range.last + 1
                        text = text.substring(0, at) + impBlock + text.substring(at)
                        if (at <= caretOrig) shift = impBlock.length
                    } else {
                        val pkgEnd = text.indexOf('\n')
                        if (pkgEnd >= 0) {
                            val at = pkgEnd + 1
                            text = text.substring(0, at) + "\n" + impBlock + text.substring(at)
                            if (at <= caretOrig) shift = impBlock.length + 1
                        } else {
                            text = impBlock + text
                            shift = impBlock.length
                        }
                    }
                }
                ed.expandAllFoldsNow()
                val caret = (caretOrig + shift).coerceIn(0, text.length)
                val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
                val newText = text.substring(0, lineS) + code.toString() + text.substring(lineS)
                ed.text?.replace(0, ed.text?.length ?: 0, newText)
                Toast.makeText(this@MainActivity, vName + " ban gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** RecyclerView Adapter ka poora boilerplate caret par generate karo. */
    private fun showGenerateAdapterDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val den = resources.displayMetrics.density
        fun field(hint: String): android.widget.EditText {
            return android.widget.EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
            }
        }
        val nameIn = field("UserAdapter")
        val itemIn = field("User")
        val layoutIn = field("item_user")
        val pad = (20 * den).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameIn); addView(itemIn); addView(layoutIn)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Generate RecyclerView Adapter")
            .setView(layout)
            .setPositiveButton("Generate") { _, _ ->
                val aName = nameIn.text.toString().trim()
                val iType = itemIn.text.toString().trim()
                val lName = layoutIn.text.toString().trim()
                if (!Regex("^[A-Z][A-Za-z0-9_]*$").matches(aName) ||
                    !Regex("^[A-Z][A-Za-z0-9_]*$").matches(iType) ||
                    !Regex("^[a-z][a-z0-9_]*$").matches(lName)) {
                    Toast.makeText(this, "Naam sahi likho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val code = StringBuilder()
                code.append("\nclass ").append(aName).append("(\n")
                    .append("    private var items: List<").append(iType).append("> = emptyList()\n")
                    .append(") : RecyclerView.Adapter<").append(aName).append(".ViewHolder>() {\n\n")
                    .append("    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {\n")
                    .append("        // TODO: findViewById se views pakdo\n")
                    .append("    }\n\n")
                    .append("    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {\n")
                    .append("        val view = LayoutInflater.from(parent.context)\n")
                    .append("            .inflate(R.layout.").append(lName).append(", parent, false)\n")
                    .append("        return ViewHolder(view)\n")
                    .append("    }\n\n")
                    .append("    override fun onBindViewHolder(holder: ViewHolder, position: Int) {\n")
                    .append("        val item = items[position]\n")
                    .append("        // TODO: item ko views me bandho\n")
                    .append("    }\n\n")
                    .append("    override fun getItemCount(): Int = items.size\n\n")
                    .append("    fun submitList(newItems: List<").append(iType).append(">) {\n")
                    .append("        items = newItems\n")
                    .append("        notifyDataSetChanged()\n")
                    .append("    }\n")
                    .append("}\n")
                val ed = binding.editor
                var text = ed.text?.toString() ?: return@setPositiveButton
                val caretOrig = ed.selectionStart
                // Zaroori imports jodo.
                val needImports = listOf(
                    "androidx.recyclerview.widget.RecyclerView",
                    "android.view.View",
                    "android.view.ViewGroup",
                    "android.view.LayoutInflater"
                ).filter { imp ->
                    !Regex("^import\\s+" + Regex.escape(imp) + "\\b", RegexOption.MULTILINE)
                        .containsMatchIn(text)
                }
                var shift = 0
                if (needImports.isNotEmpty()) {
                    val impRx = Regex("^import\\s+.*\\n", RegexOption.MULTILINE)
                    val lastImp = impRx.findAll(text).lastOrNull()
                    val impBlock = needImports.joinToString("\n") { "import " + it } + "\n"
                    if (lastImp != null) {
                        val at = lastImp.range.last + 1
                        text = text.substring(0, at) + impBlock + text.substring(at)
                        if (at <= caretOrig) shift = impBlock.length
                    } else {
                        val pkgEnd = text.indexOf('\n')
                        if (pkgEnd >= 0) {
                            val at = pkgEnd + 1
                            text = text.substring(0, at) + "\n" + impBlock + text.substring(at)
                            if (at <= caretOrig) shift = impBlock.length + 1
                        } else {
                            text = impBlock + text
                            shift = impBlock.length
                        }
                    }
                }
                ed.expandAllFoldsNow()
                val caret = (caretOrig + shift).coerceIn(0, text.length)
                // Caret wali line ke start par class jodo.
                val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
                val newText = text.substring(0, lineS) + code.toString() + text.substring(lineS)
                ed.text?.replace(0, ed.text?.length ?: 0, newText)
                Toast.makeText(this@MainActivity, aName + " ban gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Runtime permission ka ActivityResultLauncher boilerplate caret par generate karo. */
    private fun showGeneratePermissionDialog() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val perms = commonPermissions.map { it.substringAfterLast('.') }
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, perms)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(spinner)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Generate Permission Request")
            .setView(wrap)
            .setPositiveButton("Generate") { _, _ ->
                val perm = commonPermissions[spinner.selectedItemPosition]
                val short = perm.substringAfterLast('.')
                // launcher naam: CAMERA -> cameraPermissionLauncher
                val base = short.lowercase().split("_").joinToString("") {
                    it.replaceFirstChar(Char::uppercase)
                }.replaceFirstChar(Char::lowercase)
                val varName = base + "PermissionLauncher"
                val code = "private val " + varName + " = registerForActivityResult(\n" +
                           "    ActivityResultContracts.RequestPermission()\n" +
                           ") { granted ->\n" +
                           "    if (granted) {\n" +
                           "        // TODO: " + short + " granted\n" +
                           "    } else {\n" +
                           "        // TODO: rationale dikhao\n" +
                           "    }\n" +
                           "}\n"
                val ed = binding.editor
                var text = ed.text?.toString() ?: return@setPositiveButton
                val caretOrig = ed.selectionStart
                // Import jodo agar nahi hai (caret shift track karo).
                var shift = 0
                if (!text.contains("ActivityResultContracts")) {
                    val impRx = Regex("^import\\s+.*\\n", RegexOption.MULTILINE)
                    val lastImp = impRx.findAll(text).lastOrNull()
                    val impLine = "import androidx.activity.result.contract.ActivityResultContracts\n"
                    if (lastImp != null) {
                        val at = lastImp.range.last + 1
                        text = text.substring(0, at) + impLine + text.substring(at)
                        if (at <= caretOrig) shift = impLine.length
                    } else {
                        val pkgEnd = text.indexOf('\n')
                        if (pkgEnd >= 0) {
                            val at = pkgEnd + 1
                            text = text.substring(0, at) + "\n" + impLine + text.substring(at)
                            if (at <= caretOrig) shift = impLine.length + 1
                        } else {
                            text = impLine + text
                            shift = impLine.length
                        }
                    }
                }
                // Caret par code jodo.
                ed.expandAllFoldsNow()
                val caret = (caretOrig + shift).coerceIn(0, text.length)
                val newText = text.substring(0, caret) + code + text.substring(caret)
                ed.text?.replace(0, ed.text?.length ?: 0, newText)
                Toast.makeText(this@MainActivity,
                    "Launcher ban gaya — manifest me " + short + " check karo",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** `!(a && b)` -> `!a || !b` (De Morgan, selection par). */
    private fun applyDeMorgan() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "`!(a && b)` select karo", Toast.LENGTH_SHORT).show(); return
        }
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        val sel = text.substring(ss, se)
        if (!sel.startsWith("!")) {
            Toast.makeText(this, "`!` se shuru hona chahiye", Toast.LENGTH_SHORT).show(); return
        }
        var i = 1
        while (i < sel.length && sel[i].isWhitespace()) i++
        if (i >= sel.length || sel[i] != '(') {
            Toast.makeText(this, "`!(...)` pattern chahiye", Toast.LENGTH_SHORT).show(); return
        }
        val pEnd = matchScope(sel, i, '(', ')') ?: run {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        if (sel.substring(pEnd).isNotBlank()) {
            Toast.makeText(this, "Sirf `!(...)` select karo", Toast.LENGTH_SHORT).show(); return
        }
        val inner = sel.substring(i + 1, pEnd - 1).trim()
        // Top-level && / || par split (dono mix nahi hone chahiye).
        val andParts = splitOnTopLevelOp(inner, "&&")
        val orParts = splitOnTopLevelOp(inner, "||")
        val result: String? = when {
            andParts != null && orParts == null -> {
                val negs = andParts.map { negateCondition(it.trim()) }
                if (negs.any { it == null }) null
                else negs.filterNotNull().joinToString(" || ")
            }
            orParts != null && andParts == null -> {
                val negs = orParts.map { negateCondition(it.trim()) }
                if (negs.any { it == null }) null
                else negs.filterNotNull().joinToString(" && ")
            }
            andParts == null && orParts == null ->
                negateCondition(inner)
            else -> null
        }
        if (result == null) {
            Toast.makeText(this, "Mixed &&/|| — convert nahi ho sakta", Toast.LENGTH_SHORT).show(); return
        }
        ed.expandAllFoldsNow()
        ed.text?.replace(ss, se, result)
        Toast.makeText(this, "De Morgan lag gaya", Toast.LENGTH_SHORT).show()
    }

    /** Top-level `&&`/`||` par split karo; null agar na mile. */
    private fun splitOnTopLevelOp(s: String, op: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var inS = false; var inC = false; var esc = false
        var last = 0
        var found = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (esc) { esc = false; i++; continue }
            if (ch == '\\' && (inS || inC)) { esc = true; i++; continue }
            if (inS) { if (ch == '"') inS = false; i++; continue }
            if (inC) { if (ch == '\'') inC = false; i++; continue }
            if (ch == '"') { inS = true; i++; continue }
            if (ch == '\'') { inC = true; i++; continue }
            if (ch == '(' || ch == '[') depth++
            else if (ch == ')' || ch == ']') { if (depth > 0) depth-- }
            else if (depth == 0 && s.startsWith(op, i)) {
                found = true
                parts.add(s.substring(last, i))
                last = i + op.length
                i += op.length
                continue
            }
            i++
        }
        if (!found) return null
        parts.add(s.substring(last))
        return parts
    }

    /** `if (x != null) x.foo()` -> `x?.foo()`; warna `x?.let { ... }` (selection par). */
    private fun convertNullCheckToSafeCall() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "`if (x != null) ...` select karo", Toast.LENGTH_SHORT).show(); return
        }
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        val parsed = parseIfChain(text, ss, se) ?: run {
            Toast.makeText(this, "if nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val (end, branches, elseStmt) = parsed
        if (branches.size != 1 || elseStmt != null) {
            Toast.makeText(this, "else nahi hona chahiye", Toast.LENGTH_SHORT).show(); return
        }
        val b = branches[0]
        val cm = Regex("^(.+)\\s*!=\\s*null$").find(b.cond)
        if (cm == null) {
            Toast.makeText(this, "`x != null` check chahiye", Toast.LENGTH_SHORT).show(); return
        }
        val subject = cm.groupValues[1].trim()
        if (subject.isEmpty()) {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val stmt = b.stmt
        val simpleIdent = Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(subject)
        val result: String? = when {
            // Pehle se safe call hai to bas if hatao.
            stmt.startsWith(subject + "?.") || stmt.startsWith(subject + "!!") -> stmt
            // Direct safe call: `x.foo()` -> `x?.foo()`.
            stmt.startsWith(subject + ".") || stmt.startsWith(subject + "[") ->
                subject + "?" + stmt.substring(subject.length)
            // `x?.let { ... }`: N -> it.
            simpleIdent -> {
                val masked = maskCodeForRefs(stmt)
                val sb = StringBuilder()
                var last = 0
                val rx = Regex("\\b" + Regex.escape(subject) + "\\b")
                for (m in rx.findAll(masked)) {
                    sb.append(stmt.substring(last, m.range.first)).append("it")
                    last = m.range.last + 1
                }
                sb.append(stmt.substring(last))
                val inner = sb.toString()
                if (countTopLevelStatements(stmt) == 1 && !inner.contains('\n'))
                    subject + "?.let { " + inner + " }"
                else
                    subject + "?.let {\n" + indentBlock(inner) + "\n}"
            }
            else -> null
        }
        if (result == null) {
            Toast.makeText(this, "Is pattern ko convert nahi kar sakte", Toast.LENGTH_SHORT).show(); return
        }
        // Base indent lagao.
        val lineS = text.lastIndexOf('\n', (ss - 1).coerceAtLeast(0)) + 1
        val baseIndent = text.substring(lineS, ss).takeWhile { it == ' ' || it == '\t' }
        val final = baseIndent + result.replace("\n", "\n" + baseIndent)
        ed.expandAllFoldsNow()
        // Poori line(s) replace karo taaki indent sahi rahe.
        val rs = lineS
        var re = end
        while (re < text.length && text[re] != '\n') re++
        ed.text?.replace(rs, re, final.trimEnd())
        Toast.makeText(this, "Safe call ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** Har line par indent jodo (multi-line let body ke liye). */
    private fun indentBlock(s: String): String {
        return s.split("\n").joinToString("\n") { "    " + it }
    }

    /** `for (x in xs) { body }` -> `xs.forEach { x -> body }` (selection ya caret wale for par). */
    private fun convertForToForEach() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        var forStart = -1
        if (se > ss) {
            while (ss < se && text[ss].isWhitespace()) ss++
            if (!text.startsWith("for", ss) || !isWordBoundary(text, ss, 3)) {
                Toast.makeText(this, "`for` se select karo", Toast.LENGTH_SHORT).show(); return
            }
            forStart = ss
        } else {
            // Caret se pehle najdeeki asli `for` dhoondho.
            val maskedOnce = maskCodeForRefs(text)
            for (m in Regex("\\bfor\\b").findAll(text)
                     .filter { it.range.first < ss }.toList().asReversed()) {
                if (maskedOnce[m.range.first] == ' ') continue
                // `for` ke baad `(`, `forEach` nahi hona chahiye.
                var j = m.range.last + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && text[j] == '(') { forStart = m.range.first; break }
            }
            if (forStart < 0) {
                Toast.makeText(this, "Caret ko `for` par rakho", Toast.LENGTH_SHORT).show(); return
            }
        }
        // `for (var in iterable) { body }` parse karo.
        var i = forStart + 3
        val n = text.length
        while (i < n && text[i].isWhitespace()) i++
        if (i >= n || text[i] != '(') {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val pEnd = matchScope(text, i, '(', ')') ?: run {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val header = text.substring(i + 1, pEnd - 1).trim()
        // `var in iterable` — `in` top-level par split karo.
        val inSplit = splitOnTopLevelWord(header, "in") ?: run {
            Toast.makeText(this, "`for (x in xs)` pattern chahiye", Toast.LENGTH_SHORT).show(); return
        }
        val varName = inSplit.first.trim()
        val iterable = inSplit.second.trim()
        if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(varName) || iterable.isEmpty() ||
            varName == "_" || iterable.contains('\n')) {
            Toast.makeText(this, "Simple `for (x in xs)` chahiye", Toast.LENGTH_SHORT).show(); return
        }
        i = pEnd
        while (i < n && text[i].isWhitespace()) i++
        if (i >= n || text[i] != '{') {
            Toast.makeText(this, "Braces wala for chahiye", Toast.LENGTH_SHORT).show(); return
        }
        val bEnd = matchScope(text, i, '{', '}') ?: run {
            Toast.makeText(this, "Parse nahi hua", Toast.LENGTH_SHORT).show(); return
        }
        val body = text.substring(i + 1, bEnd - 1).trim()
        if (body.isEmpty()) {
            Toast.makeText(this, "Body khaali hai", Toast.LENGTH_SHORT).show(); return
        }
        // break/continue/return (non-local) ho to unsafe.
        if (Regex("\\b(break|continue|return)\\b").containsMatchIn(maskCodeForRefs(body))) {
            Toast.makeText(this, "break/continue/return hai — unsafe", Toast.LENGTH_SHORT).show(); return
        }
        ed.expandAllFoldsNow()
        val replacement = iterable + ".forEach { " + varName + " -> " + body + " }"
        ed.text?.replace(forStart, bEnd, replacement)
        Toast.makeText(this, "forEach ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** Top-level word par split karo (string/paren-aware); null agar na mile. */
    private fun splitOnTopLevelWord(s: String, word: String): Pair<String, String>? {
        var depth = 0
        var inS = false; var inC = false; var esc = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (esc) { esc = false; i++; continue }
            if (ch == '\\' && (inS || inC)) { esc = true; i++; continue }
            if (inS) { if (ch == '"') inS = false; i++; continue }
            if (inC) { if (ch == '\'') inC = false; i++; continue }
            if (ch == '"') { inS = true; i++; continue }
            if (ch == '\'') { inC = true; i++; continue }
            if (ch == '(' || ch == '[') depth++
            else if (ch == ')' || ch == ']') { if (depth > 0) depth-- }
            else if (depth == 0 && s.startsWith(word, i) && isWordBoundary(s, i, word.length)) {
                return Pair(s.substring(0, i), s.substring(i + word.length))
            }
            i++
        }
        return null
    }

    /** `if (a) x() else y()` -> `if (!a) y() else x()` (selection par, single if-else). */
    private fun invertIfCondition() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "`if (cond) ... else ...` select karo", Toast.LENGTH_SHORT).show(); return
        }
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        val parsed = parseIfChain(text, ss, se) ?: run {
            Toast.makeText(this, "if-else nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val (end, branches, elseStmt) = parsed
        if (branches.size != 1 || elseStmt == null) {
            Toast.makeText(this, "Sirf single if-else par kaam karta hai", Toast.LENGTH_SHORT).show(); return
        }
        val b = branches[0]
        val neg = negateCondition(b.cond) ?: run {
            Toast.makeText(this, "Condition negate nahi ho payi", Toast.LENGTH_SHORT).show(); return
        }
        // Base indent.
        val lineS = text.lastIndexOf('\n', (ss - 1).coerceAtLeast(0)) + 1
        val baseIndent = text.substring(lineS, ss).takeWhile { it == ' ' || it == '\t' }
        fun fmtStmt(stmt: String): String {
            return if (countTopLevelStatements(stmt) <= 1 && !stmt.contains('\n')) stmt
                   else "{ " + stmt.replace("\n", " ") + " }"
        }
        val out = StringBuilder("if (").append(neg).append(") ")
            .append(fmtStmt(elseStmt)).append(" else ").append(fmtStmt(b.stmt))
        ed.expandAllFoldsNow()
        ed.text?.replace(ss, end, out.toString())
        Toast.makeText(this, "if invert ho gaya", Toast.LENGTH_SHORT).show()
    }

    /** Boolean condition negate karo: `==`->`!=`, `is`->`!is`, warna `!(...)`. */
    private fun negateCondition(cond: String): String? {
        val c = cond.trim()
        if (c.isEmpty()) return null
        if (c.startsWith("!") && !c.startsWith("!=")) {
            val rest = c.substring(1).trim()
            return if (rest.isEmpty()) null else rest
        }
        val symOps = listOf("==" to "!=", "!=" to "==", "<=" to ">",
                            ">=" to "<", "<" to ">=", ">" to "<=")
        val wordOps = listOf("is" to "!is", "!is" to "is", "in" to "!in", "!in" to "in")
        var depth = 0
        var inS = false; var inC = false; var esc = false
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            if (esc) { esc = false; i++; continue }
            if (ch == '\\' && (inS || inC)) { esc = true; i++; continue }
            if (inS) { if (ch == '"') inS = false; i++; continue }
            if (inC) { if (ch == '\'') inC = false; i++; continue }
            if (ch == '"') { inS = true; i++; continue }
            if (ch == '\'') { inC = true; i++; continue }
            if (ch == '(' || ch == '[') depth++
            else if (ch == ')' || ch == ']') { if (depth > 0) depth-- }
            else if (depth == 0) {
                for ((op, flip) in symOps) {
                    if (c.startsWith(op, i)) {
                        return c.substring(0, i).trimEnd() + " " + flip + " " +
                               c.substring(i + op.length).trimStart()
                    }
                }
                for ((op, flip) in wordOps) {
                    if (c.startsWith(op, i) && isWordBoundary(c, i, op.length)) {
                        return c.substring(0, i).trimEnd() + " " + flip + " " +
                               c.substring(i + op.length).trimStart()
                    }
                }
            }
            i++
        }
        return if (c.contains("&&") || c.contains("||")) "!(" + c + ")" else "!"+c
    }

    /** idx par word boundary hai kya (op ke aas-paas identifier char nahi). */
    private fun isWordBoundary(s: String, idx: Int, len: Int): Boolean {
        val beforeOk = idx == 0 || (!s[idx - 1].isLetterOrDigit() && s[idx - 1] != '_')
        val e = idx + len
        val afterOk = e >= s.length || (!s[e].isLetterOrDigit() && s[e] != '_')
        return beforeOk && afterOk
    }

    /** `if (x != null) x else y` -> `x ?: y` (selection par, single if-else). */
    private fun convertToElvis() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "`if (x != null) ...` select karo", Toast.LENGTH_SHORT).show(); return
        }
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        val parsed = parseIfChain(text, ss, se) ?: run {
            Toast.makeText(this, "if-else nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val (end, branches, elseStmt) = parsed
        if (branches.size != 1 || elseStmt == null) {
            Toast.makeText(this, "Sirf single if-else par kaam karta hai", Toast.LENGTH_SHORT).show(); return
        }
        val b = branches[0]
        // cond: `X != null` ya `X == null`.
        val cm = Regex("^(.+)\\s*(!=|==)\\s*null$").find(b.cond)
        if (cm == null) {
            Toast.makeText(this, "Null-check nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val subject = cm.groupValues[1].trim()
        val isNotNull = cm.groupValues[2] == "!="
        val elvis: String? = if (isNotNull && b.stmt == subject) {
            "$subject ?: $elseStmt"
        } else if (!isNotNull && elseStmt == subject) {
            "$subject ?: ${b.stmt}"
        } else null
        if (elvis == null) {
            Toast.makeText(this, "`if (x != null) x else y` pattern chahiye", Toast.LENGTH_SHORT).show(); return
        }
        ed.expandAllFoldsNow()
        ed.text?.replace(ss, end, elvis)
        Toast.makeText(this, "Elvis ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** Selected expression ko `val name = expr` me nikalo (selection ki line se pehle). */
    private fun extractVariableFromSelection() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "Expression select karo", Toast.LENGTH_SHORT).show(); return
        }
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        val expr = text.substring(ss, se)
        if (expr.isBlank() || expr.contains('\n')) {
            Toast.makeText(this, "Single-line expression select karo", Toast.LENGTH_SHORT).show(); return
        }
        val input = android.widget.EditText(this).apply { hint = "variable_name" }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Extract Variable")
            .setMessage(expr.take(80))
            .setView(wrap)
            .setPositiveButton("Extract") { _, _ ->
                val name = input.text.toString().trim().removeSurrounding("`")
                if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name)) {
                    Toast.makeText(this, "Sahi naam likho", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                val live = binding.editor.text?.toString() ?: return@setPositiveButton
                if (se > live.length || live.substring(ss, se) != expr) {
                    Toast.makeText(this, "Text badal gaya — dobara try karo", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(maskCodeForRefs(live))) {
                    Toast.makeText(this, "Ye naam pehle se istemal me hai", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Selection ki line ke start par `val name = expr` jodo.
                val lineS = live.lastIndexOf('\n', (ss - 1).coerceAtLeast(0)) + 1
                val indent = live.substring(lineS, ss).takeWhile { it == ' ' || it == '\t' }
                val decl = indent + "val " + name + " = " + expr + "\n"
                val newText = live.substring(0, lineS) + decl +
                              live.substring(lineS, ss) + name + live.substring(se)
                binding.editor.expandAllFoldsNow()
                binding.editor.text?.replace(0, live.length, newText)
                Toast.makeText(this, "val $name ban gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** `"a" + x + "b"` ko `"a${x}b"` template me badlo (selection par). */
    private fun convertConcatToTemplate() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "Concatenation select karo", Toast.LENGTH_SHORT).show(); return
        }
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        val sel = text.substring(ss, se)
        val parts = splitTopLevelPlus(sel)
        if (parts == null || parts.size < 2) {
            Toast.makeText(this, "Top-level `+` nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        var hasLiteral = false
        val out = StringBuilder("\"")
        for (p in parts) {
            val t = p.trim()
            if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") && !t.startsWith("\"\"\"")) {
                hasLiteral = true
                // Literal ka content: `$` escape karo (template me `${'$'}`).
                val content = t.substring(1, t.length - 1).replace("$", "\${'$'}")
                out.append(content)
            } else {
                out.append("\${").append(t).append("}")
            }
        }
        out.append("\"")
        if (!hasLiteral) {
            Toast.makeText(this, "Koi string literal nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        ed.expandAllFoldsNow()
        ed.text?.replace(ss, se, out.toString())
        Toast.makeText(this, "Template ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** `+` par split karo — strings/parens ke andar wala `+` nahi. Null agar koi top-level `+` na ho. */
    private fun splitTopLevelPlus(expr: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var inS = false; var inC = false; var esc = false
        var last = 0
        var found = false
        var i = 0
        val n = expr.length
        while (i < n) {
            val c = expr[i]
            if (esc) { esc = false; i++; continue }
            if (c == '\\' && (inS || inC)) { esc = true; i++; continue }
            if (inS) { if (c == '"') inS = false; i++; continue }
            if (inC) { if (c == '\'') inC = false; i++; continue }
            if (c == '"') { inS = true; i++; continue }
            if (c == '\'') { inC = true; i++; continue }
            if (c == '/' && i + 1 < n && expr[i + 1] == '/') break // comment ke baad kuch nahi
            if (c == '(' || c == '[' || c == '{') depth++
            else if (c == ')' || c == ']' || c == '}') { if (depth > 0) depth-- }
            else if (c == '+' && depth == 0) {
                found = true
                parts.add(expr.substring(last, i))
                last = i + 1
            }
            i++
        }
        if (!found) return null
        parts.add(expr.substring(last))
        return parts
    }

    /** Selected if/else-if/else chain ko `when` me badlo. */
    private fun convertIfToWhen() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        var ss = ed.selectionStart; var se = ed.selectionEnd
        if (se <= ss) {
            Toast.makeText(this, "if-chain select karo", Toast.LENGTH_SHORT).show(); return
        }
        // Selection ke aas-paas whitespace trim karo.
        while (ss < se && text[ss].isWhitespace()) ss++
        while (se > ss && text[se - 1].isWhitespace()) se--
        val parsed = parseIfChain(text, ss, se) ?: run {
            Toast.makeText(this, "if/else-if chain nahi mila", Toast.LENGTH_SHORT).show(); return
        }
        val (end, branches, elseStmt) = parsed
        if (branches.size < 1 || (branches.size == 1 && elseStmt == null)) {
            Toast.makeText(this, "else-if ya else chahiye", Toast.LENGTH_SHORT).show(); return
        }
        // Base indent nikalo (if wali line ka).
        val lineS = text.lastIndexOf('\n', (ss - 1).coerceAtLeast(0)) + 1
        val baseIndent = text.substring(lineS, ss).takeWhile { it == ' ' || it == '\t' }
        val inIndent = baseIndent + "    "
        // Subject form? sab `X == value`.
        var subject: String? = null
        var subjectOk = true
        val condVals = mutableListOf<String>()
        for (b in branches) {
            val m = Regex("^([A-Za-z_][A-Za-z0-9_.]*)\\s*==\\s*(.+)$").find(b.cond)
            if (m == null) { subjectOk = false; break }
            if (subject == null) subject = m.groupValues[1]
            else if (subject != m.groupValues[1]) { subjectOk = false; break }
            condVals.add(m.groupValues[2])
        }
        val sb = StringBuilder()
        if (subjectOk && subject != null) {
            sb.append("when (").append(subject).append(") {\n")
            for (i in branches.indices) {
                sb.append(inIndent).append(condVals[i]).append(" -> ")
                    .append(branchToWhenBody(branches[i], inIndent)).append("\n")
            }
        } else {
            sb.append("when {\n")
            for (b in branches) {
                sb.append(inIndent).append(b.cond).append(" -> ")
                    .append(branchToWhenBody(b, inIndent)).append("\n")
            }
        }
        if (elseStmt != null) {
            sb.append(inIndent).append("else -> ").append(elseStmt).append("\n")
        }
        sb.append(baseIndent).append("}")
        ed.expandAllFoldsNow()
        ed.text?.replace(ss, end, sb.toString())
        Toast.makeText(this, "when ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** When-branch ki body: single statement seedha, multi-statement braces me. */
    private fun branchToWhenBody(b: IfBranch, indent: String): String {
        if (!b.hadBraces) return b.stmt
        return if (countTopLevelStatements(b.stmt) <= 1) b.stmt
               else "{\n" + indent + "    " +
                    b.stmt.replace("\n", "\n" + indent + "    ") + "\n" + indent + "}"
    }

    /** text[start, selEnd) me if-chain parse karo; (endExclusive, branches, elseStmt?) ya null. */
    private fun parseIfChain(text: String, start: Int, selEnd: Int): Triple<Int, List<IfBranch>, String?>? {
        val n = text.length
        var i = start
        fun skipWs() { while (i < n && text[i].isWhitespace()) i++ }
        fun isKw(word: String): Boolean {
            if (!text.startsWith(word, i)) return false
            val e = i + word.length
            return e >= n || (!text[e].isLetterOrDigit() && text[e] != '_')
        }
        skipWs()
        if (!isKw("if")) return null
        val branches = mutableListOf<IfBranch>()
        var elseStmt: String? = null
        i += 2
        while (true) {
            skipWs()
            if (i >= n || text[i] != '(') return null
            val condEnd = matchScope(text, i, '(', ')') ?: return null
            val cond = text.substring(i + 1, condEnd - 1).trim()
            if (cond.isEmpty()) return null
            i = condEnd
            skipWs()
            // Branch parse karo.
            var stmt: String
            var hadBraces: Boolean
            if (i < n && text[i] == '{') {
                val bEnd = matchScope(text, i, '{', '}') ?: return null
                val inner = text.substring(i + 1, bEnd - 1).trim()
                hadBraces = true
                stmt = inner
                i = bEnd
            } else {
                // Bina braces: depth-0 `else` tak (nested if mana hai).
                var depth = 0
                var j = i
                var inS = false; var inC = false; var esc = false
                var elseAt = -1
                while (j < n) {
                    val c = text[j]
                    if (esc) { esc = false; j++; continue }
                    if (c == '\\' && (inS || inC)) { esc = true; j++; continue }
                    if (inS) { if (c == '"') inS = false; j++; continue }
                    if (inC) { if (c == '\'') inC = false; j++; continue }
                    if (c == '"') { inS = true; j++; continue }
                    if (c == '\'') { inC = true; j++; continue }
                    if (c == '/' && j + 1 < n && text[j + 1] == '/') {
                        while (j < n && text[j] != '\n') j++
                        continue
                    }
                    if (c == '(' || c == '[') depth++
                    else if (c == ')' || c == ']') { if (depth > 0) depth-- }
                    else if (depth == 0 && (c == 'i' && text.startsWith("if", j) &&
                             (j + 2 >= n || (!text[j + 2].isLetterOrDigit() && text[j + 2] != '_')))) {
                        return null // dangling-else khatra
                    }
                    else if (depth == 0 && c == 'e' && text.startsWith("else", j) &&
                             (j + 4 >= n || (!text[j + 4].isLetterOrDigit() && text[j + 4] != '_'))) {
                        elseAt = j; break
                    }
                    j++
                }
                if (elseAt < 0) return null
                stmt = text.substring(i, elseAt).trim()
                hadBraces = false
                if (stmt.isEmpty()) return null
                i = elseAt
            }
            branches.add(IfBranch(cond, stmt, hadBraces))
            skipWs()
            if (isKw("else")) {
                i += 4
                skipWs()
                if (isKw("if")) { i += 2; continue }
                // Aakhri else-branch.
                if (i < n && text[i] == '{') {
                    val bEnd = matchScope(text, i, '{', '}') ?: return null
                    elseStmt = text.substring(i + 1, bEnd - 1).trim()
                    i = bEnd
                } else {
                    var j = i
                    var depth = 0
                    var inS = false; var inC = false; var esc = false
                    while (j < n && j < selEnd) {
                        val c = text[j]
                        if (esc) { esc = false; j++; continue }
                        if (c == '\\' && (inS || inC)) { esc = true; j++; continue }
                        if (inS) { if (c == '"') inS = false; j++; continue }
                        if (inC) { if (c == '\'') inC = false; j++; continue }
                        if (c == '"') { inS = true; j++; continue }
                        if (c == '\'') { inC = true; j++; continue }
                        if (c == '(' || c == '[' || c == '{') depth++
                        else if (c == ')' || c == ']' || c == '}') {
                            if (depth > 0) depth-- else break
                        }
                        else if (c == ';' && depth == 0) break
                        j++
                    }
                    elseStmt = text.substring(i, j).trim()
                    i = j
                }
                break
            } else break
        }
        skipWs()
        return Triple(i, branches, elseStmt)
    }

    /** Caret wali `val x = expr` declaration ko inline karo: saare uses me expr, line delete. */
    private fun inlineVariableAtCaret() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt") && !fname.endsWith(".java")) {
            Toast.makeText(this, "Sirf Kotlin/Java files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val caret = ed.selectionStart
        // Caret wali line me `val name = init` dhoondho.
        val lineS = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
        val lineE = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val line = text.substring(lineS, lineE)
        val dm = Regex("^(?:\\s*(?:private|public|protected|internal|open|lateinit)\\s+)*val\\s+(`?)([A-Za-z_][A-Za-z0-9_]*)(`?)\\s*(?::\\s*[^=]+?)?=([^=].*)$")
            .find(line) ?: run {
                Toast.makeText(this, "Caret ko `val x = ...` line par rakho", Toast.LENGTH_SHORT).show()
                return
            }
        val name = dm.groupValues[2]
        var init = dm.groupValues[4].trim()
        if (init.isEmpty() || " by " in " $init ") {
            Toast.makeText(this, "Isko inline nahi kar sakte", Toast.LENGTH_SHORT).show(); return
        }
        // Strings/comments mask karke references dhoondho.
        val masked = maskCodeForRefs(text)
        val nameRx = Regex("\\b" + Regex.escape(name) + "\\b")
        data class Ref(val s: Int, val e: Int)
        val refs = mutableListOf<Ref>()
        for (m in nameRx.findAll(masked)) {
            val ms = m.range.first
            if (ms >= lineS && ms < lineE) continue // declaration khud
            // Doosri declaration? (val/var/fun/class ke baad naam)
            val before = masked.substring(maxOf(0, ms - 8), ms)
            if (before.endsWith("val ") || before.endsWith("var ") ||
                before.endsWith("fun ") || before.endsWith("class ")) {
                Toast.makeText(this, "Ek se zyada declarations — unsafe", Toast.LENGTH_SHORT).show()
                return
            }
            // Named-argument naam (`foo(x = ...)`) — value nahi, naam hai: skip.
            var j = m.range.last + 1
            while (j < masked.length && masked[j].isWhitespace()) j++
            if (j < masked.length && masked[j] == '=' &&
                (j + 1 >= masked.length || masked[j + 1] != '=')) continue
            refs.add(Ref(ms, m.range.last + 1))
        }
        // Parenthesize zaroori hai kya?
        val needParens = init.any { it in "+-*/%<>=|&^?:!" } &&
                         !(init.startsWith("(") && init.endsWith(")"))
        val repl = if (needParens) "($init)" else init
        // Saare edits original offsets par nikalo, phir end-se-start apply karo.
        data class Edit(val s: Int, val e: Int, val r: String)
        val edits = mutableListOf<Edit>()
        for (r in refs) edits.add(Edit(r.s, r.e, repl))
        var da = lineS; var db = lineE
        if (db < text.length && text[db] == '\n') db++
        else if (da > 0 && text[da - 1] == '\n') da--
        edits.add(Edit(da, db, ""))
        ed.expandAllFoldsNow()
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.s }) sb.replace(e.s, e.e, e.r)
        val edLen = ed.text?.length ?: 0
        ed.text?.replace(0, edLen, sb.toString())
        Toast.makeText(this,
            if (refs.isEmpty()) "Unused variable hataya"
            else refs.size.toString() + " jagah inline kiya", Toast.LENGTH_SHORT).show()
    }

    /** Strings, chars aur comments ko space se mask karo (reference scan ke liye). */
    private fun maskCodeForRefs(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                while (i < n && text[i] != '\n') { out.append(' '); i++ }
                continue
            }
            if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                out.append("  "); i += 2
                while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) { out.append(' '); i++ }
                out.append("  "); i += 2
                continue
            }
            if (c == '"') {
                out.append(' '); i++
                while (i < n) {
                    if (text[i] == '\\') { out.append("  "); i += 2; continue }
                    if (text[i] == '"') break
                    out.append(' '); i++
                }
                out.append(' '); i++
                continue
            }
            if (c == '\'') {
                out.append(' '); i++
                if (i < n && text[i] == '\\') { out.append(' '); i++ }
                if (i < n) { out.append(' '); i++ }
                if (i < n && text[i] == '\'') { out.append(' '); i++ }
                continue
            }
            out.append(c); i++
        }
        return out.toString()
    }

    /** `fun f(): T { return x }` -> `fun f(): T = x`. Selection ya caret wale function par. */
    private fun convertToExpressionBody() {
        if (openTabs.getOrNull(activeTabIndex) == null || currentFileUri == null) {
            Toast.makeText(this, "Koi file khuli nahi hai", Toast.LENGTH_SHORT).show(); return
        }
        val fname = try { GitManager.resolveDocToFile(currentFileUri!!)?.name ?: "" }
                    catch (e: Exception) { "" }
        if (!fname.endsWith(".kt")) {
            Toast.makeText(this, "Sirf Kotlin (.kt) files me", Toast.LENGTH_SHORT).show(); return
        }
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val selS = ed.selectionStart; val selE = ed.selectionEnd
        var target: Triple<Int, Int, String>? = null
        if (selE > selS) {
            val sel = text.substring(selS, selE)
            val conv = tryConvertFunToExprBody(sel)
            if (conv != null) target = Triple(selS, selE, conv)
        } else {
            target = findEnclosingFunExpr(text, selS)
        }
        if (target == null) {
            Toast.makeText(this, "Koi single-statement function nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        ed.expandAllFoldsNow()
        ed.text?.replace(target.first, target.second, target.third)
        Toast.makeText(this, "Expression body ban gaya", Toast.LENGTH_SHORT).show()
    }

    /** Caret wale `fun` ko dhoondho aur convert karo; (start, end, converted) ya null. */
    private fun findEnclosingFunExpr(text: String, caret: Int): Triple<Int, Int, String>? {
        val rx = Regex("\\bfun\\b")
        val cands = rx.findAll(text).filter { it.range.first < caret }.toList()
        for (m in cands.asReversed()) {
            val parsed = parseFunAt(text, m.range.first) ?: continue
            if (caret <= parsed.third) {
                val conv = tryConvertFunToExprBody(text.substring(parsed.first, parsed.third))
                if (conv != null) return Triple(parsed.first, parsed.third, conv)
            }
        }
        return null
    }

    /** text[idx] se `fun...{...}` parse karo; (funStart, bodyOpen, endExclusive) ya null. */
    private fun parseFunAt(text: String, idx: Int): Triple<Int, Int, Int>? {
        var i = idx + 3
        val n = text.length
        fun skipWs() { while (i < n && text[i].isWhitespace()) i++ }
        skipWs()
        // Type parameters <...> (fun ke turant baad).
        if (i < n && text[i] == '<') {
            val e = matchScope(text, i, '<', '>') ?: return null
            i = e
            skipWs()
        }
        // Naam (backtick wala bhi).
        if (i < n && text[i] == '`') {
            i++
            while (i < n && text[i] != '`') { if (text[i] == '\\') i++; i++ }
            if (i >= n) return null
            i++
        } else {
            val ns = i
            while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            if (i == ns) return null
        }
        skipWs()
        if (i >= n || text[i] != '(') return null
        i = matchScope(text, i, '(', ')') ?: return null
        skipWs()
        // Return type (optional): agle '{' tak.
        if (i < n && text[i] == ':') {
            i++
            var depthA = 0
            while (i < n) {
                val c = text[i]
                if (c == '<') depthA++
                else if (c == '>') { if (depthA > 0) depthA-- }
                else if (c == '{' && depthA == 0) break
                else if (c == '=' && depthA == 0) return null // pehle se expression body
                i++
            }
        }
        skipWs()
        if (i >= n || text[i] != '{') return null
        val bodyOpen = i
        val end = matchScope(text, i, '{', '}') ?: return null
        return Triple(idx, bodyOpen, end)
    }

    /** idx par khulne wale scope ka end (exclusive); strings/comments skip karta hai. */
    private fun matchScope(text: String, idx: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = idx
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                while (i < n && text[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                i += 2
                continue
            }
            if (c == '"') {
                i++
                while (i < n) {
                    if (text[i] == '\\') { i += 2; continue }
                    if (text[i] == '"') break
                    // Triple-quoted raw string: aasaan raasta — pehle """ dhoondho.
                    i++
                }
                i++
                continue
            }
            if (c == '\'') {
                i++
                if (i < n && text[i] == '\\') i++
                i += 2
                continue
            }
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return i + 1
            }
            i++
        }
        return null
    }

    /** Body me top-level statements gino (strings aur nested parens skip). */
    private fun countTopLevelStatements(body: String): Int {
        var count = 0
        var hasToken = false
        var depth = 0
        var inS = false; var inC = false; var esc = false
        fun flush() { if (hasToken) { count++; hasToken = false } }
        for (ch in body) {
            if (esc) { esc = false; hasToken = true; continue }
            if (ch == '\\') { esc = true; hasToken = true; continue }
            if (inS) { if (ch == '"') inS = false; hasToken = true; continue }
            if (inC) { if (ch == '\'') inC = false; hasToken = true; continue }
            when (ch) {
                '"' -> { inS = true; hasToken = true }
                '\'' -> { inC = true; hasToken = true }
                '(', '[' -> { depth++; hasToken = true }
                ')', ']' -> { if (depth > 0) depth--; hasToken = true }
                ';' -> if (depth == 0) flush() else hasToken = true
                '\n' -> if (depth == 0) flush() else hasToken = true
                else -> if (!ch.isWhitespace()) hasToken = true
            }
        }
        flush()
        return count
    }

    /** Poore `fun...{...}` text ko expression body me badlo; na ho sake to null. */
    private fun tryConvertFunToExprBody(funText: String): String? {
        val fi = funText.indexOf("fun")
        if (fi < 0) return null
        val parsed = parseFunAt(funText, fi) ?: return null
        val (funStart, bodyOpen, end) = parsed
        if (funText.substring(end).isNotBlank()) return null // '}' ke baad kuch aur hai
        val head = funText.substring(funStart, bodyOpen).trimEnd()
        val body = funText.substring(bodyOpen + 1, end - 1).trim()
        if (body.isEmpty() || body.contains('{') || body.contains('}')) return null
        // Body me sirf EK statement hona chahiye (top-level ';'/newline par split).
        if (countTopLevelStatements(body) != 1) return null
        val expr: String
        if (body == "return" || body.startsWith("return ") || body.startsWith("return\n") ||
            body.startsWith("return\t")) {
            expr = body.removePrefix("return").trim()
            if (expr.isEmpty()) return null
        } else {
            expr = body
        }
        return "$head = $expr"
    }

    private fun renameSymbolInProject() {
        val word = binding.editor.wordAtCaret()
        if (word.isNullOrEmpty()) {
            Toast.makeText(this, "No word at cursor", Toast.LENGTH_SHORT).show()
            return
        }
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        data class RenamePlan(
            val doc: androidx.documentfile.provider.DocumentFile,
            val tab: EditorTab?,
            val rel: String,
            val newText: String,
            val count: Int
        )

        fun applyRenamePlans(plans: List<RenamePlan>, total: Int) {
            var failed = 0
            for (p in plans) {
                try {
                    val tab = p.tab
                    if (tab != null) {
                        tab.content = p.newText
                        tab.isModified = true
                        if (openTabs.getOrNull(activeTabIndex) == tab) {
                            // Ek hi replace call = TextWatcher se ek Undo step.
                            binding.editor.text?.replace(0, binding.editor.length(), p.newText)
                        }
                        renderTabs()
                    } else {
                        val ok = try {
                            contentResolver.openOutputStream(p.doc.uri, "wt")?.use { out ->
                                out.write(p.newText.toByteArray())
                            } != null
                        } catch (_: Exception) { false }
                        if (!ok) failed++
                    }
                } catch (_: Exception) { failed++ }
            }
            saveSessionState()
            val failNote = if (failed > 0) " ($failed failed)" else ""
            Toast.makeText(
                this,
                "Renamed $total occurrence(s) in ${plans.size} file(s)$failNote",
                Toast.LENGTH_LONG
            ).show()
        }

        fun scanAndConfirm(oldName: String, newName: String) {
            // Folded text me placeholder hota hai — scan se pehle sab kholo
            binding.editor.expandAllFoldsNow()
            val activeTab = openTabs.getOrNull(activeTabIndex)
            val activeBase: String? = activeTab?.let { binding.editor.getUnfoldedText() }
            val activeDirty: Boolean =
                activeTab?.let { binding.editor.isModifiedComparedTo(it.diskContent) } ?: false
            val progress = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Renaming \"$oldName\"...")
                .setMessage("Scanning project files...")
                .setCancelable(false)
                .create()
            progress.show()
            lifecycleScope.launch(Dispatchers.IO) {
                val rx = Regex("\\b" + Regex.escape(oldName) + "\\b")
                val repl = java.util.regex.Matcher.quoteReplacement(newName)
                val plans = mutableListOf<RenamePlan>()
                var skippedDirty = 0
                var skippedRO = 0
                fun walk(dir: androidx.documentfile.provider.DocumentFile, prefix: String) {
                    if (plans.size >= 300) return
                    val children = try { dir.listFiles() } catch (_: Exception) { return }
                    for (child in children) {
                        if (plans.size >= 300) return
                        val name = child.name ?: continue
                        if (name.startsWith(".")) continue
                        try {
                            if (child.isDirectory) {
                                walk(child, "$prefix$name/")
                            } else if (child.isFile) {
                                if (child.length() > 512 * 1024) continue
                                val bytes = try {
                                    contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                                } catch (_: Exception) { null } ?: continue
                                if (bytes.size > 512 * 1024) continue
                                var bi = 0
                                var isBinary = false
                                while (bi < bytes.size && bi < 8192) {
                                    if (bytes[bi] == 0.toByte()) { isBinary = true; break }
                                    bi++
                                }
                                if (isBinary) continue
                                val tab = openTabs.find { it.uri == child.uri }
                                val dirty = if (tab == null) false
                                else if (tab == activeTab) activeDirty else tab.isModified
                                if (dirty) { skippedDirty++; continue }
                                if (!child.canWrite()) { skippedRO++; continue }
                                val base = if (tab != null && tab == activeTab && activeBase != null)
                                    activeBase else bytes.toString(Charsets.UTF_8)
                                val count = rx.findAll(base).count()
                                if (count == 0) continue
                                val newText = rx.replace(base, repl)
                                if (newText == base) continue
                                plans.add(RenamePlan(child, tab, "$prefix$name", newText, count))
                            }
                        } catch (_: Exception) { }
                    }
                }
                try { walk(folder, "") } catch (_: Exception) { }
                val total = plans.sumOf { it.count }
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    if (plans.isEmpty()) {
                        val note = when {
                            skippedDirty > 0 -> " ($skippedDirty file(s) have unsaved changes)"
                            skippedRO > 0 -> " ($skippedRO read-only file(s) skipped)"
                            else -> ""
                        }
                        Toast.makeText(this@MainActivity, "No occurrences found$note", Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    val sb = StringBuilder()
                    for (p in plans.take(15)) {
                        sb.append("- ").append(p.rel).append(" (").append(p.count).append(")\n")
                    }
                    if (plans.size > 15) sb.append("...and ${plans.size - 15} more files\n")
                    if (skippedDirty > 0) sb.append("\n$skippedDirty file(s) skipped (unsaved changes)")
                    if (skippedRO > 0) sb.append("\n$skippedRO file(s) skipped (read-only)")
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Rename $total occurrence(s) in ${plans.size} file(s)?")
                        .setMessage(sb.toString().trimEnd())
                        .setPositiveButton("Rename") { _, _ -> applyRenamePlans(plans, total) }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
            }
        }

        val nameOk = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        val input = android.widget.EditText(this).apply {
            setText(word)
            selectAll()
            hint = "New name"
        }
        fun doRename(newName: String, dlg: android.content.DialogInterface) {
            if (!nameOk.matches(newName)) {
                Toast.makeText(this, "Invalid name", Toast.LENGTH_SHORT).show()
                return
            }
            if (newName == word) {
                Toast.makeText(this, "Same name - nothing to rename", Toast.LENGTH_SHORT).show()
                return
            }
            dlg.dismiss()
            scanAndConfirm(word, newName)
        }
        val projectRenameDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Rename \"$word\" in project")
            .setView(input)
            .setPositiveButton("Rename") { d, _ -> doRename(input.text.toString().trim(), d) }
            .setNegativeButton(R.string.close, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doRename(input.text.toString().trim(), projectRenameDialog)
                true
            } else false
        }
        projectRenameDialog.setOnShowListener {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        projectRenameDialog.show()
    }

    /** Lines/words/characters + cursor/selection info ka simple dialog. */
    /** File extension se language ka naam. */
    private fun languageForFile(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "xml" -> "XML"
            "py" -> "Python"
            "js", "mjs" -> "JavaScript"
            "ts" -> "TypeScript"
            "html", "htm", "xhtml" -> "HTML"
            "css" -> "CSS"
            "md", "markdown" -> "Markdown"
            "json" -> "JSON"
            "c", "h" -> "C"
            "cpp", "hpp", "cc", "cxx" -> "C++"
            "go" -> "Go"
            "rs" -> "Rust"
            "php" -> "PHP"
            "rb" -> "Ruby"
            "swift" -> "Swift"
            "sh", "bash" -> "Shell"
            "sql" -> "SQL"
            "yaml", "yml" -> "YAML"
            "toml" -> "TOML"
            "gradle" -> "Gradle"
            "properties" -> "Properties"
            else -> ""
        }
    }

    private fun showDocumentStats() {
        val text = binding.editor.text?.toString() ?: ""
        val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        val words = Regex("\\S+").findAll(text).count()
        val chars = text.length
        val sizeBytes = text.toByteArray(Charsets.UTF_8).size
        val sizeStr = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        }
        val caret = binding.editor.selectionStart.coerceIn(0, chars)
        val lineNo = text.substring(0, caret).count { it == '\n' } + 1
        val lineStart = text.lastIndexOf('\n', caret - 1).let { if (it == -1) 0 else it + 1 }
        val colNo = caret - lineStart + 1
        val selLen = kotlin.math.abs(binding.editor.selectionEnd - binding.editor.selectionStart)
        val selStart = minOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, chars)
        val selEnd = maxOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, chars)
        val selWords = if (selEnd > selStart) Regex("\\S+").findAll(text.substring(selStart, selEnd)).count() else 0
        val tabPath = openTabs.getOrNull(activeTabIndex)?.let { fullPathForTab(it) }.orEmpty()
        val tabName = openTabs.getOrNull(activeTabIndex)?.name.orEmpty()
        val lang = languageForFile(tabName)
        val readMin = (words / 200.0).coerceAtLeast(0.5)
        val readStr = if (readMin < 1) "<1 min" else "${readMin.toInt()} min"
        val msg = "Lines: $lines\nWords: $words\nCharacters: $chars\nSize: $sizeStr\nReading time: $readStr" +
                (if (lang.isNotEmpty()) "\nLanguage: $lang" else "") +
                (if (tabPath.isNotEmpty()) "\nPath: $tabPath" else "") + "\n\n" +
                "Cursor: line $lineNo, column $colNo\nSelection: $selLen chars, $selWords words"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_doc_stats)
            .setMessage(msg)
            .setNeutralButton("Copy") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("doc_stats", msg))
                Toast.makeText(this, "Stats copied", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showSearchInFilesDialog(initialQuery: String? = null) {
        val folder = currentFolder
        if (folder == null) {
            Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        data class SFResult(val name: String, val relPath: String, val lineNo: Int, val snippet: String, val doc: DocumentFile)
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_search_files, null)
        dialog.setContentView(view)
        val searchInput: EditText = view.findViewById(R.id.searchFilesInput)
        searchInput.post { searchInput.requestFocus() }
        // Selected text (ya caret wala word) se prefill karo.
        val ed74 = binding.editor.text
        if (ed74 != null) {
            val s0 = minOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, ed74.length)
            val e0 = maxOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, ed74.length)
            val prefill74 = if (e0 > s0) {
                ed74.substring(s0, e0).take(80)
            } else {
                binding.editor.wordAtCaret().orEmpty()
            }
            if (prefill74.isNotEmpty() && !prefill74.contains('\n')) {
                searchInput.setText(prefill74)
                searchInput.selectAll()
            }
        }
        val list: RecyclerView = view.findViewById(R.id.searchFilesList)
        val emptyText: TextView = view.findViewById(R.id.searchFilesEmpty)
        val replaceInput: EditText = view.findViewById(R.id.replaceFilesInput)
        val replaceAllBtn: android.widget.Button = view.findViewById(R.id.replaceAllFilesBtn)
        val sfCase = view.findViewById<android.widget.CheckBox>(R.id.sfCase)
        val sfWord = view.findViewById<android.widget.CheckBox>(R.id.sfWord)
        val sfRegex = view.findViewById<android.widget.CheckBox>(R.id.sfRegex)
        sfCase.isChecked = prefs.findCaseSensitive
        sfWord.isChecked = prefs.findWholeWord
        sfRegex.isChecked = prefs.findUseRegex
        list.layoutManager = LinearLayoutManager(this)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<SFResult> = emptyList()
            fun submit(newItems: List<SFResult>) {
                items = newItems
                notifyDataSetChanged()
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = items[position]
                holder.itemView.findViewById<TextView>(R.id.srFile).text = e.name
                holder.itemView.findViewById<TextView>(R.id.srDetail).text = if (e.lineNo > 0) "${e.relPath} · line ${e.lineNo}" else e.relPath
                val snippetView = holder.itemView.findViewById<TextView>(R.id.srSnippet)
                snippetView.text = e.snippet
                snippetView.visibility = if (e.snippet.isEmpty()) View.GONE else View.VISIBLE
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    binding.drawer.closeDrawer(GravityCompat.START)
                    openFileInTab(e.doc, e.lineNo)
                }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter

        var searchToken = 0
        fun runSearch() {
            val q = searchInput.text.toString()
            if (q.isBlank()) return
            val myToken = ++searchToken
            adapter.submit(emptyList())
            emptyText.text = "Searching..."
            emptyText.visibility = View.VISIBLE
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
            val useCase = sfCase.isChecked
            val useWord = sfWord.isChecked
            val useRegex = sfRegex.isChecked
            val lineRegex: Regex? = if (useRegex) {
                try { buildFindRegex(q, useCase, true, useWord) } catch (e: Exception) { null }
            } else null
            if (useRegex && lineRegex == null) {
                emptyText.text = "Invalid regex"
                return
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val query = q
                val results = mutableListOf<SFResult>()
                fun walk(dir: DocumentFile, prefix: String) {
                    if (myToken != searchToken || results.size >= 300) return
                    val children = try { dir.listFiles() } catch (_: Exception) { return }
                    for (child in children) {
                        if (myToken != searchToken || results.size >= 300) return
                        val name = child.name ?: continue
                        if (name.startsWith(".")) continue
                        try {
                            if (child.isDirectory) {
                                walk(child, "$prefix$name/")
                            } else if (child.isFile) {
                                if (name.lowercase().contains(query)) {
                                    if (results.size < 300) results.add(SFResult(name, prefix + name, 0, "", child))
                                }
                                continue
                                if (child.length() > 512 * 1024) continue
                                val bytes = try {
                                    contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                                } catch (_: Exception) { null } ?: continue
                                if (bytes.size > 512 * 1024) continue
                                var bi = 0
                                var isBinary = false
                                while (bi < bytes.size && bi < 8192) {
                                    if (bytes[bi] == 0.toByte()) { isBinary = true; break }
                                    bi++
                                }
                                if (isBinary) continue
                                val ftext = bytes.toString(Charsets.UTF_8)
                                var perFile = 0
                                ftext.lineSequence().forEachIndexed { idx, line ->
                                    if (perFile >= 5 || results.size >= 300) return@forEachIndexed
                                    val hit: Boolean
                                    if (useRegex) {
                                        hit = lineRegex?.containsMatchIn(line) == true
                                    } else if (useWord) {
                                        var okw = false
                                        var i2 = 0
                                        while (true) {
                                            val ix = line.indexOf(query, i2, ignoreCase = !useCase)
                                            if (ix < 0) break
                                            if (binding.editor.isWholeWordAt(line, ix, query.length)) { okw = true; break }
                                            i2 = ix + 1
                                        }
                                        hit = okw
                                    } else if (useCase) {
                                        hit = line.indexOf(query) >= 0
                                    } else {
                                        hit = line.lowercase().indexOf(query.lowercase()) >= 0
                                    }
                                    if (hit) {
                                        perFile++
                                        results.add(SFResult(name, "$prefix$name", idx + 1, line.trim().take(140), child))
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
                try { walk(folder, "") } catch (_: Exception) { }
                withContext(Dispatchers.Main) {
                    if (myToken != searchToken) return@withContext
                    adapter.submit(results.toList())
                    emptyText.text = if (results.isEmpty()) "No matches found" else "${results.size} matches"
                    emptyText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // Search-in-files history: haal ke queries (find dialog jaisa dropdown).
        val sfHistoryList = prefs.searchFilesHistory.toMutableList()
        fun pushSfHistory(q: String) {
            val t = q.trim()
            if (t.isEmpty()) return
            sfHistoryList.remove(t)
            sfHistoryList.add(0, t)
            while (sfHistoryList.size > 8) sfHistoryList.removeAt(sfHistoryList.size - 1)
            prefs.searchFilesHistory = sfHistoryList
        }
        fun showSfHistory() {
            if (sfHistoryList.isEmpty()) return
            val popup = androidx.appcompat.widget.ListPopupWindow(this)
            popup.anchorView = searchInput
            if (searchInput.width > 0) popup.width = searchInput.width
            popup.setAdapter(
                android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, sfHistoryList.toList())
            )
            popup.isModal = true
            popup.setOnItemClickListener { _, _, position, _ ->
                popup.dismiss()
                searchInput.setText(sfHistoryList[position])
                runSearch()
            }
            popup.show()
        }
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && searchInput.text.isEmpty() && sfHistoryList.isNotEmpty()) showSfHistory()
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                pushSfHistory(searchInput.text.toString())
                runSearch()
                true
            } else false
        }
        sfCase.setOnCheckedChangeListener { _, c -> prefs.findCaseSensitive = c; if (searchInput.text.isNotBlank()) runSearch() }
        sfWord.setOnCheckedChangeListener { _, c -> prefs.findWholeWord = c; if (searchInput.text.isNotBlank()) runSearch() }
        sfRegex.setOnCheckedChangeListener { _, c -> prefs.findUseRegex = c; if (searchInput.text.isNotBlank()) runSearch() }

        // INV-121 Replace in files: folder ke CONTENTS me scan karke replace-all.
        // (Upar wali results list file-NAME search hai; ye usse independent hai.)
        data class ReplacePlan(val doc: DocumentFile, val tab: EditorTab?, val newText: String, val count: Int)

        fun applyReplacePlans(plans: List<ReplacePlan>, totalCount: Int) {
            var failed = 0
            for (p in plans) {
                try {
                    val tab = p.tab
                    if (tab != null) {
                        tab.content = p.newText
                        tab.isModified = true
                        if (openTabs.getOrNull(activeTabIndex) == tab) {
                            // Poora text ek hi edit me = ek Undo (renameSymbol wala pattern).
                            binding.editor.text?.replace(0, binding.editor.length(), p.newText)
                        }
                        renderTabs()
                    } else {
                        val ok = try {
                            contentResolver.openOutputStream(p.doc.uri, "wt")?.use { out ->
                                out.write(p.newText.toByteArray())
                            } != null
                        } catch (_: Exception) { false }
                        if (!ok) failed++
                    }
                } catch (_: Exception) { failed++ }
            }
            saveSessionState()
            val failNote = if (failed > 0) " ($failed failed)" else ""
            Toast.makeText(this, "Replaced $totalCount in ${plans.size} files$failNote", Toast.LENGTH_LONG).show()
            runSearch()
        }

        fun runReplaceInFiles() {
            val q = searchInput.text.toString()
            val repl = replaceInput.text.toString()
            if (q.isBlank()) {
                Toast.makeText(this, "Type a search query first", Toast.LENGTH_SHORT).show()
                return
            }
            val useCase = sfCase.isChecked
            val useWord = sfWord.isChecked
            val useRegex = sfRegex.isChecked
            val rx: Regex = try {
                buildFindRegex(q, useCase, useRegex, useWord)
            } catch (_: Exception) {
                Toast.makeText(this, "Invalid regex", Toast.LENGTH_SHORT).show()
                return
            }
            if (useRegex && rx.containsMatchIn("")) {
                Toast.makeText(this, "Regex matches empty text - replace skipped", Toast.LENGTH_LONG).show()
                return
            }
            val activeTab = openTabs.getOrNull(activeTabIndex)
            val activeBase: String? = activeTab?.let { binding.editor.getUnfoldedText() }
            val activeDirty: Boolean = activeTab?.let { binding.editor.isModifiedComparedTo(it.diskContent) } ?: false
            emptyText.text = "Scanning files..."
            lifecycleScope.launch(Dispatchers.IO) {
                val plans = mutableListOf<ReplacePlan>()
                var skippedDirty = 0
                var skippedReadOnly = 0
                var capped = false
                fun walk(dir: DocumentFile) {
                    if (plans.size >= 300) { capped = true; return }
                    val children = try { dir.listFiles() } catch (_: Exception) { return }
                    for (child in children) {
                        if (plans.size >= 300) { capped = true; return }
                        val name = child.name ?: continue
                        if (name.startsWith(".")) continue
                        try {
                            if (child.isDirectory) {
                                walk(child)
                            } else if (child.isFile) {
                                if (child.length() > 512 * 1024) continue
                                val bytes = try {
                                    contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                                } catch (_: Exception) { null } ?: continue
                                if (bytes.size > 512 * 1024) continue
                                var bi = 0
                                var isBinary = false
                                while (bi < bytes.size && bi < 8192) {
                                    if (bytes[bi] == 0.toByte()) { isBinary = true; break }
                                    bi++
                                }
                                if (isBinary) continue
                                val tab = openTabs.find { it.uri == child.uri }
                                val dirty = if (tab == null) false else if (tab == activeTab) activeDirty else tab.isModified
                                if (dirty) { skippedDirty++; continue }
                                if (!child.canWrite()) { skippedReadOnly++; continue }
                                val base = if (tab != null && tab == activeTab && activeBase != null) activeBase else bytes.toString(Charsets.UTF_8)
                                val count = rx.findAll(base).count()
                                if (count == 0) continue
                                val newText = rx.replace(base, java.util.regex.Matcher.quoteReplacement(repl))
                                if (newText == base) continue
                                plans.add(ReplacePlan(child, tab, newText, count))
                            }
                        } catch (_: Exception) { }
                    }
                }
                try { walk(folder) } catch (_: Exception) { }
                val totalCount = plans.sumOf { it.count }
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) return@withContext
                    if (plans.isEmpty()) {
                        val why = when {
                            skippedDirty > 0 -> " ($skippedDirty skipped: unsaved changes)"
                            skippedReadOnly > 0 -> " ($skippedReadOnly skipped: read-only)"
                            else -> ""
                        }
                        Toast.makeText(this@MainActivity, "Nothing to replace$why", Toast.LENGTH_SHORT).show()
                        runSearch()
                        return@withContext
                    }
                    val skipNote = buildString {
                        if (skippedDirty > 0) append("\n$skippedDirty file(s) skipped: unsaved changes")
                        if (skippedReadOnly > 0) append("\n$skippedReadOnly file(s) skipped: read-only")
                        if (capped) append("\nCapped at 300 files")
                    }
                    val action = if (repl.isEmpty()) "Delete $totalCount occurrence(s) in ${plans.size} file(s)?"
                        else "Replace $totalCount occurrence(s) in ${plans.size} file(s)?"
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Replace in files")
                        .setMessage(action + skipNote)
                        .setPositiveButton("Replace") { _, _ -> applyReplacePlans(plans, totalCount) }
                        .setNegativeButton("Cancel") { _, _ -> runSearch() }
                        .show()
                }
            }
        }
        replaceAllBtn.setOnClickListener { runReplaceInFiles() }

        dialog.setOnDismissListener { searchToken++ }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        searchInput.requestFocus()
        // v7 (62): `nobi find "text"` — prefill and run immediately.
        if (!initialQuery.isNullOrBlank()) {
            searchInput.setText(initialQuery)
            searchInput.post { runSearch() }
        }
    }

    /** Current file ki extension ke hisaab se selected lines / current line ka comment toggle karta hai. */
    private fun toggleCommentOnCurrentFile() {
        if (openTabs.isEmpty() || activeTabIndex !in openTabs.indices) return
        val ext = openTabs[activeTabIndex].name.substringAfterLast('.', "").lowercase()
        binding.editor.toggleComment(ext)
    }

    private fun showColorPickerDialog() {
        val cBinding = DialogColorPickerBinding.inflate(layoutInflater)
        val range = binding.editor.getHexColorAtCursor()
        val initialHex = range?.let { binding.editor.text?.substring(it.first, it.last + 1) } ?: "#8B7CF6"
        var current = parseColorHex(initialHex)
        var updating = false

        fun hexOf(c: Int) = String.format("#%06X", 0xFFFFFF and c)

        fun updateViews() {
            val r = Color.red(current)
            val g = Color.green(current)
            val b = Color.blue(current)
            cBinding.redValue.text = r.toString()
            cBinding.greenValue.text = g.toString()
            cBinding.blueValue.text = b.toString()
            cBinding.hexLabel.text = hexOf(current)
            val bg = cBinding.colorPreview.background
            if (bg is GradientDrawable) bg.setColor(current)
            else cBinding.colorPreview.setBackgroundColor(current)
        }

        fun refresh() {
            updating = true
            cBinding.seekRed.progress = Color.red(current)
            cBinding.seekGreen.progress = Color.green(current)
            cBinding.seekBlue.progress = Color.blue(current)
            updating = false
            updateViews()
        }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating) return
                current = Color.rgb(cBinding.seekRed.progress, cBinding.seekGreen.progress, cBinding.seekBlue.progress)
                updateViews()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        cBinding.seekRed.setOnSeekBarChangeListener(seekListener)
        cBinding.seekGreen.setOnSeekBarChangeListener(seekListener)
        cBinding.seekBlue.setOnSeekBarChangeListener(seekListener)

        val presets = intArrayOf(
            Color.parseColor("#8B7CF6"), Color.parseColor("#EF4444"), Color.parseColor("#F59E0B"), Color.parseColor("#10B981"),
            Color.parseColor("#3B82F6"), Color.parseColor("#EC4899"), Color.parseColor("#000000"), Color.parseColor("#FFFFFF")
        )
        val dp = resources.displayMetrics.density
        for (pc in presets) {
            val swatch = android.view.View(this)
            val lp = LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt())
            val m = (6 * dp).toInt()
            lp.setMargins(m, 0, m, 0)
            swatch.layoutParams = lp
            val oval = GradientDrawable()
            oval.shape = GradientDrawable.OVAL
            oval.setColor(pc)
            oval.setStroke((2 * dp).toInt(), Color.parseColor("#3A3D55"))
            swatch.background = oval
            swatch.setOnClickListener { current = pc; refresh() }
            cBinding.presetRow.addView(swatch)
        }

        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_color_picker)
            .setView(cBinding.root)
            .setPositiveButton(R.string.color_picker_apply) { _, _ ->
                val hex = hexOf(current)
                if (range != null) binding.editor.replaceColorAtRange(range, hex)
                else binding.editor.insertTextAtCursor(hex)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun parseColorHex(hex: String): Int {
        val h = hex.trim().removePrefix("#")
        val full = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("")
            4 -> h.map { "$it$it" }.joinToString("")
            6, 8 -> h
            else -> return Color.parseColor("#8B7CF6")
        }
        return try {
            Color.parseColor("#$full")
        } catch (e: IllegalArgumentException) {
            Color.parseColor("#8B7CF6")
        }
    }

    private fun showCommandPalette() {
        data class PaletteCmd(
            val id: String,
            val title: String,
            val hint: String,
            val icon: Int,
            val run: () -> Unit
        )
        val all = listOf(
            PaletteCmd("quick_open", "Quick Open", "Go", R.drawable.ic_folder_open) { showQuickOpenDialog() },
            PaletteCmd("find_replace", "Find and Replace", "Edit", R.drawable.ic_find_replace) { showFindReplaceDialog() },
            PaletteCmd("goto_line", "Go to Line", "Go", R.drawable.ic_goto) { showGotoLineDialog() },
            PaletteCmd("goto_symbol", "Go to Symbol", "Go", R.drawable.ic_symbol) { showGoToSymbolDialog() },
            PaletteCmd("search_files", "Search in Files", "Go", R.drawable.ic_search_files) { showSearchInFilesDialog() },
            PaletteCmd("todo_explorer", "Project TODOs", "Go", R.drawable.ic_todo) { showTodoExplorer() },
            PaletteCmd("git_history", "Git: View History", "Git", R.drawable.ic_history) { showGitHistory() },
            PaletteCmd("git_stash", "Git: Stash Changes", "Git", R.drawable.ic_history) { showStashSaveDialog() },
            PaletteCmd("git_stashes", "Git: Stashes", "Git", R.drawable.ic_history) { showStashList() },
            PaletteCmd("pin_tab", "Pin/Unpin Tab", "File", R.drawable.ic_pin) { togglePinTab() },
            PaletteCmd("organize_imports", "Organize Imports", "Edit", R.drawable.ic_organize_imports) { organizeImports() },
            PaletteCmd("recent_folders", "Open Recent Folder", "File", R.drawable.ic_folder_open) { showRecentFolders() },
            PaletteCmd("git_discard", "Git: Discard File Changes", "Git", R.drawable.ic_history) { discardCurrentFileChanges() },
            PaletteCmd("goto_resource", "Go to Resource", "Go", R.drawable.ic_goto) { jumpToAndroidResource() },
            PaletteCmd("find_refs", "Find References in Project", "Go", R.drawable.ic_search_files) { findReferencesInProject() },
            PaletteCmd("goto_def", "Go to Definition", "Go", R.drawable.ic_goto) { goToDefinition() },
            PaletteCmd("project_symbols", "Search Symbols in Project", "Go", R.drawable.ic_symbol) { showProjectSymbolsDialog() },
            PaletteCmd("nav_back", "Go Back", "Go", R.drawable.ic_arrow_left) { goNavBack() },
            PaletteCmd("nav_forward", "Go Forward", "Go", R.drawable.ic_arrow_right) { goNavForward() },
            PaletteCmd("bookmarks", "Bookmarks", "Go", R.drawable.ic_bookmark) { showBookmarksPanel() },
            PaletteCmd("git_diff", "Git: View File Diff", "Git", R.drawable.ic_history) { showFileDiff() },
            PaletteCmd("git_blame", "Git: Blame Current Line", "Git", R.drawable.ic_history) { showBlameForCaretLine() },
            PaletteCmd("git_file_history", "Git: File History", "Git", R.drawable.ic_history) { showGitFileHistory() },
            PaletteCmd("focus_mode", "Toggle Focus Mode", "View", R.drawable.ic_focus) { toggleFocusMode() },
            PaletteCmd("string_res", "Edit String Resources", "File", R.drawable.ic_strings) { showStringResourcesEditor() },
            PaletteCmd("layout_outline", "Layout Outline", "Go", R.drawable.ic_symbol) { showLayoutOutline() },
            PaletteCmd("merge_conflicts", "Resolve Merge Conflicts", "Git", R.drawable.ic_history) { showMergeConflicts() },
            PaletteCmd("local_history", "File: Local History", "File", R.drawable.ic_history) { showLocalHistoryDialog() },
            PaletteCmd("format", "Format Document", "Edit", R.drawable.ic_code) {
                if (openTabs.isEmpty() || activeTabIndex !in openTabs.indices) {
                    Toast.makeText(this, getString(R.string.no_file_open), Toast.LENGTH_SHORT).show()
                } else {
                    val ext = openTabs[activeTabIndex].name.substringAfterLast('.', "").lowercase()
                    when (binding.editor.formatCurrentCode(ext)) {
                        0 -> Toast.makeText(this, getString(R.string.format_done), Toast.LENGTH_SHORT).show()
                        2 -> Toast.makeText(this, getString(R.string.format_failed), Toast.LENGTH_SHORT).show()
                        else -> Toast.makeText(this, getString(R.string.format_no_change), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            PaletteCmd("rename", "Rename Symbol", "Edit", R.drawable.ic_rename) { showRenameSymbolDialog() },
            PaletteCmd("rename_project", "Rename Symbol in Project", "Edit", R.drawable.ic_rename) { renameSymbolInProject() },
            PaletteCmd("comment", "Toggle Comment", "Edit", R.drawable.ic_comment_toggle) { toggleCommentOnCurrentFile() },
            PaletteCmd("box_select", "Toggle Box Selection", "Edit", R.drawable.ic_box_select) { toggleBoxSelect() },
            PaletteCmd("join", "Join Lines", "Edit", R.drawable.ic_join_lines) { binding.editor.joinLines() },
            PaletteCmd("select_next", "Select Next Occurrence", "Edit", R.drawable.ic_select_next) { binding.editor.selectNextOccurrence() },
            PaletteCmd("expand_sel", "Expand Selection", "Edit", R.drawable.ic_expand_select) { binding.editor.expandSelection() },
            PaletteCmd("surround", "Surround With...", "Edit", R.drawable.ic_symbol) { showSurroundWithDialog() },
            PaletteCmd("add_import", "Add Import for Symbol", "Edit", R.drawable.ic_organize_imports) { addImportForCaretSymbol() },
            PaletteCmd("extract_string", "Extract String Resource", "Edit", R.drawable.ic_strings) { extractStringResourceAtCaret() },
            PaletteCmd("expr_body", "Convert to Expression Body", "Edit", R.drawable.ic_code) { convertToExpressionBody() },
            PaletteCmd("inline_var", "Inline Variable", "Edit", R.drawable.ic_code) { inlineVariableAtCaret() },
            PaletteCmd("if_to_when", "Convert if to when", "Edit", R.drawable.ic_code) { convertIfToWhen() },
            PaletteCmd("concat_to_template", "Concatenation to Template", "Edit", R.drawable.ic_code) { convertConcatToTemplate() },
            PaletteCmd("extract_var", "Extract Variable", "Edit", R.drawable.ic_code) { extractVariableFromSelection() },
            PaletteCmd("to_elvis", "Convert to Elvis Operator", "Edit", R.drawable.ic_code) { convertToElvis() },
            PaletteCmd("invert_if", "Invert if Condition", "Edit", R.drawable.ic_code) { invertIfCondition() },
            PaletteCmd("for_to_foreach", "Convert for to forEach", "Edit", R.drawable.ic_code) { convertForToForEach() },
            PaletteCmd("to_safe_call", "Null-check to Safe Call", "Edit", R.drawable.ic_code) { convertNullCheckToSafeCall() },
            PaletteCmd("de_morgan", "De Morgan (Distribute !)", "Edit", R.drawable.ic_code) { applyDeMorgan() },
            PaletteCmd("gen_perm", "Generate Permission Request", "Edit", R.drawable.ic_code) { showGeneratePermissionDialog() },
            PaletteCmd("gen_adapter", "Generate RecyclerView Adapter", "Edit", R.drawable.ic_code) { showGenerateAdapterDialog() },
            PaletteCmd("gen_view", "Generate Custom View", "Edit", R.drawable.ic_code) { showGenerateCustomViewDialog() },
            PaletteCmd("gen_binding", "Generate ViewBinding", "Edit", R.drawable.ic_code) { generateViewBinding() },
            PaletteCmd("gen_room", "Generate Room Entity+DAO", "Edit", R.drawable.ic_code) { showGenerateRoomDialog() },
            PaletteCmd("gen_notif", "Generate Notification Helper", "Edit", R.drawable.ic_code) { generateNotificationHelper() },
            PaletteCmd("extract_style", "Extract Style", "Edit", R.drawable.ic_code) { showExtractStyleDialog() },
            PaletteCmd("extract_dimen", "Extract Dimension", "Edit", R.drawable.ic_code) { showExtractDimenDialog() },
            PaletteCmd("gen_builder", "Generate Builder", "Edit", R.drawable.ic_code) { generateBuilder() },
            PaletteCmd("gen_test", "Generate Unit Test", "Edit", R.drawable.ic_code) { generateUnitTest() },
            PaletteCmd("extract_include", "Extract Include", "Edit", R.drawable.ic_code) { showExtractIncludeDialog() },
            PaletteCmd("remove_type", "Remove Explicit Type", "Edit", R.drawable.ic_code) { removeExplicitType() },
            PaletteCmd("extract_iface", "Extract Interface", "Edit", R.drawable.ic_code) { showExtractInterfaceDialog() },
            PaletteCmd("to_object", "Convert to Object", "Edit", R.drawable.ic_code) { convertToObject() },
            PaletteCmd("var_to_val", "Convert var to val", "Edit", R.drawable.ic_code) { convertVarToVal() },
            PaletteCmd("sort_imports", "Sort Imports", "Edit", R.drawable.ic_code) { sortImports() },
            PaletteCmd("remove_unused_imports", "Remove Unused Imports", "Edit", R.drawable.ic_code) { removeUnusedImports() },
            PaletteCmd("simplify_bool", "Simplify Boolean", "Edit", R.drawable.ic_code) { simplifyBoolean() },
            PaletteCmd("to_operator", "Replace with Operator", "Edit", R.drawable.ic_code) { replaceWithOperator() },
            PaletteCmd("raw_string", "Convert to Raw String", "Edit", R.drawable.ic_code) { convertToRawString() },
            PaletteCmd("hardcoded_str", "Find Hardcoded Strings", "Edit", R.drawable.ic_code) { findHardcodedStrings() },
            PaletteCmd("unwrap", "Unwrap", "Edit", R.drawable.ic_code) { unwrapBlock() },
            PaletteCmd("compare_clipboard", "Compare with Clipboard", "File", R.drawable.ic_code) { compareWithClipboard() },
            PaletteCmd("export_settings", "Export Settings", "File", R.drawable.ic_code) { exportSettings() },
            PaletteCmd("import_settings", "Import Settings", "File", R.drawable.ic_code) { importSettingsLauncher.launch(arrayOf("application/json")) },
            PaletteCmd("to_lazy", "Convert to lazy", "Edit", R.drawable.ic_code) { convertToLazyDelegate() },
            PaletteCmd("when_else", "Add else to when", "Edit", R.drawable.ic_code) { addElseToWhen() },
            PaletteCmd("json_to_class", "Data Class from JSON", "Edit", R.drawable.ic_code) { showJsonToDataClassDialog() },
            PaletteCmd("merge_ifs", "Merge Nested ifs", "Edit", R.drawable.ic_code) { mergeNestedIfs() },
            PaletteCmd("lambda_to_ref", "Lambda to Function Reference", "Edit", R.drawable.ic_code) { convertLambdaToReference() },
            PaletteCmd("when_subject", "when to Subject Form", "Edit", R.drawable.ic_code) { convertWhenToSubjectForm() },
            PaletteCmd("no_braces", "Remove Redundant Braces", "Edit", R.drawable.ic_code) { removeRedundantBraces() },
            PaletteCmd("move_class", "Move Class to New File", "Edit", R.drawable.ic_code) { moveClassToNewFile() },
            PaletteCmd("save_session", "Save Tab Session", "File", R.drawable.ic_save) { saveTabSession() },
            PaletteCmd("open_session", "Open Tab Session", "File", R.drawable.ic_folder_open) { showTabSessionsDialog() },
            PaletteCmd("to_data_class", "Convert to Data Class", "Edit", R.drawable.ic_code) { convertToDataClass() },
            PaletteCmd("align_assign", "Align Assignments", "Edit", R.drawable.ic_code) {
                if (!binding.editor.alignAssignments()) {
                    Toast.makeText(this, "Select lines with '=' to align", Toast.LENGTH_SHORT).show()
                }
            },
            PaletteCmd("save", "Save", "File", R.drawable.ic_save) { saveActiveTabContent(isManualSave = true, showToast = true) },
            PaletteCmd("save_as", "Save As", "File", R.drawable.ic_save) { showSaveAsDialog() },
            PaletteCmd("manifest", "Edit Android Manifest", "File", R.drawable.ic_file) { showManifestEditor() },
            PaletteCmd("unused_res", "Find Unused Resources", "File", R.drawable.ic_search_files) { findUnusedResources() },
            PaletteCmd("dup_files", "Find Duplicate Files", "File", R.drawable.ic_duplicate) { findDuplicateFiles() },
            PaletteCmd("add_dep", "Add Gradle Dependency", "File", R.drawable.ic_add) { showAddDependencyDialog() },
            PaletteCmd("new_activity", "New Activity", "File", R.drawable.ic_add) { showNewActivityDialog() },
            PaletteCmd("new_file", "New File", "File", R.drawable.ic_file) { openNewUntitledTab() },
            PaletteCmd("open_file", "Open File", "File", R.drawable.ic_folder_open) { openSingleFileLauncher.launch(arrayOf("*/*")) },
            PaletteCmd("close_tab", "Close Tab", "File", R.drawable.ic_close) { if (activeTabIndex != -1) closeTab(activeTabIndex) },
            PaletteCmd("close_others", "Close Other Tabs", "File", R.drawable.ic_close_tabs) { closeOtherTabs() },
            PaletteCmd("reopen_tab", "Reopen Closed Tab", "File", R.drawable.ic_refresh) { reopenClosedTab() },
            PaletteCmd("md_preview", "Markdown Preview", "View", R.drawable.ic_file_md) { toggleMarkdownPreview() },
            PaletteCmd("color_picker", "Color Picker", "View", R.drawable.ic_palette) { showColorPickerDialog() },
            PaletteCmd("doc_stats", "Document Stats", "View", R.drawable.ic_stats) { showDocumentStats() },
            PaletteCmd("run", "Run Preview", "View", R.drawable.ic_run) { triggerRunPreview() },
            PaletteCmd("zen", "Toggle Zen Mode", "View", R.drawable.ic_visibility) { toggleZenMode() },
            PaletteCmd("fold_all", "Fold All", "View", R.drawable.ic_fold) { binding.editor.foldAllFolds() },
            PaletteCmd("unfold_all", "Unfold All", "View", R.drawable.ic_expand_select) { binding.editor.expandAllFoldsNow() },
            PaletteCmd("settings", "Open Settings", "View", R.drawable.ic_settings) { startActivity(Intent(this, SettingsActivity::class.java)) },
            // ---- Moved here from 3-dot menu (menu cleanup): Edit actions ----
            PaletteCmd("dup_line", "Duplicate Line", "Edit", R.drawable.ic_duplicate) { binding.editor.duplicateLine() },
            PaletteCmd("move_up", "Move Line Up", "Edit", R.drawable.ic_arrow_up) { binding.editor.moveLine(-1) },
            PaletteCmd("move_down", "Move Line Down", "Edit", R.drawable.ic_arrow_down) { binding.editor.moveLine(1) },
            PaletteCmd("del_line", "Delete Line", "Edit", R.drawable.ic_delete_line) { binding.editor.deleteLines() },
            PaletteCmd("indent_in", "Increase Indent", "Edit", R.drawable.ic_indent_in) { binding.editor.indentSelection(true) },
            PaletteCmd("indent_out", "Decrease Indent", "Edit", R.drawable.ic_indent_out) { binding.editor.indentSelection(false) },
            PaletteCmd("sel_bracket", "Select to Matching Bracket", "Edit", R.drawable.ic_symbol) { binding.editor.selectToMatchingBracket() },
            PaletteCmd("jump_bracket", "Jump to Matching Bracket", "Edit", R.drawable.ic_symbol) { binding.editor.jumpToMatchingBracket() },
            PaletteCmd("match_tag", "Select to Matching Tag", "Edit", R.drawable.ic_tag) { binding.editor.selectToMatchingTag() },
            PaletteCmd("last_edit", "Jump to Last Edit", "Edit", R.drawable.ic_last_edit) { binding.editor.jumpToLastEdit() },
            PaletteCmd("bm_toggle", "Toggle Bookmark", "Edit", R.drawable.ic_bookmark) { binding.editor.toggleBookmark() },
            PaletteCmd("bm_next", "Next Bookmark", "Edit", R.drawable.ic_bookmark) { binding.editor.nextBookmark() },
            PaletteCmd("bm_clear", "Clear Bookmarks", "Edit", R.drawable.ic_bookmark) { binding.editor.clearBookmarks() },
            PaletteCmd("next_empty", "Next Empty Line", "Edit", R.drawable.ic_arrow_down) { binding.editor.jumpToEmptyLine(true) },
            PaletteCmd("prev_empty", "Previous Empty Line", "Edit", R.drawable.ic_arrow_up) { binding.editor.jumpToEmptyLine(false) },
            PaletteCmd("line_above", "Insert Line Above", "Edit", R.drawable.ic_arrow_up) { binding.editor.insertLine(false) },
            PaletteCmd("line_below", "Insert Line Below", "Edit", R.drawable.ic_arrow_down) { binding.editor.insertLine(true) },
            PaletteCmd("sort_asc", "Sort Lines A-Z", "Edit", R.drawable.ic_sort_asc) { binding.editor.sortLines(true) },
            PaletteCmd("sort_desc", "Sort Lines Z-A", "Edit", R.drawable.ic_sort_desc) { binding.editor.sortLines(false) },
            PaletteCmd("dedup", "Remove Duplicate Lines", "Edit", R.drawable.ic_dedup) { binding.editor.removeDuplicateLines() },
            PaletteCmd("reverse", "Reverse Lines", "Edit", R.drawable.ic_reverse) { binding.editor.reverseLines() },
            PaletteCmd("tabs_spaces", "Tabs to Spaces", "Edit", R.drawable.ic_convert) { binding.editor.convertIndentation(true) },
            PaletteCmd("spaces_tabs", "Spaces to Tabs", "Edit", R.drawable.ic_convert) { binding.editor.convertIndentation(false) },
            PaletteCmd("to_lf", "Convert to LF", "Edit", R.drawable.ic_convert) { binding.editor.convertLineEndings(false) },
            PaletteCmd("to_crlf", "Convert to CRLF", "Edit", R.drawable.ic_convert) { binding.editor.convertLineEndings(true) },
            PaletteCmd("rm_blank", "Remove Blank Lines", "Edit", R.drawable.ic_dedup) { binding.editor.removeBlankLines() },
            PaletteCmd("toggle_case", "Toggle Case", "Edit", R.drawable.ic_toggle_case) { binding.editor.toggleCase() },
            PaletteCmd("snake_camel", "snake_case \u2194 camelCase", "Edit", R.drawable.ic_toggle_case) { binding.editor.convertSnakeCamel() },
            PaletteCmd("sel_string", "Select String Content", "Edit", R.drawable.ic_quotes) { binding.editor.selectStringContent() },
            // ---- v7: terminal switching + new editor features ----
            PaletteCmd("term_here", "Open Terminal Here", "Terminal", R.drawable.ic_terminal) { openTerminalHere() },
            PaletteCmd("term_back", "Back to Terminal", "Terminal", R.drawable.ic_terminal) { backToTerminal() },
            PaletteCmd("split_screen", "Split Screen (Editor + Terminal)", "Terminal", R.drawable.ic_terminal) { openSplitScreen() },
            PaletteCmd("run_terminal", "Run in Terminal (and Return)", "Terminal", R.drawable.ic_run) { runInTerminalAndReturn() },
            PaletteCmd("run_config", "Run Configuration", "Terminal", R.drawable.ic_run) { showRunConfigDialog() },
            PaletteCmd("send_terminal", "Send Selection to Terminal", "Terminal", R.drawable.ic_terminal) { sendSelectionToTerminal() },
            PaletteCmd("file_bookmarks", "File Bookmarks", "Go", R.drawable.ic_bookmark) { showFileBookmarksDialog() },
            PaletteCmd("bookmark_file", "Bookmark This File", "Go", R.drawable.ic_bookmark) {
                val tab = if (activeTabIndex in openTabs.indices) openTabs[activeTabIndex] else null
                val path = tab?.path
                if (path != null) {
                    addFileBookmark(path)
                    Toast.makeText(this, "Bookmarked: " + tab.name, Toast.LENGTH_SHORT).show()
                }
            },
            PaletteCmd("github", "GitHub: Clone / Sync", "Git", R.drawable.ic_git_branch) { com.nobidev.nobicode.github.GithubSheet(this).show() },
            PaletteCmd("plugins", "Plugins", "Tools", R.drawable.ic_palette) { startActivity(Intent(this, com.nobidev.nobicode.plugins.PluginActivity::class.java)) },
            PaletteCmd("ai_chat", "AI Assistant", "Tools", R.drawable.ic_code) { openAiChat() },
            PaletteCmd("js_console", "JavaScript Console", "Tools", R.drawable.ic_code) { startActivity(Intent(this, com.nobidev.nobicode.js.JsConsoleActivity::class.java)) },
            PaletteCmd("lsp", "LSP Servers / Problems", "Tools", R.drawable.ic_code) { showLspDialog() },
            PaletteCmd("follow_folder", "Follow Terminal Folder: " + if (prefs.followTerminalFolder) "On" else "Off", "Terminal", R.drawable.ic_folder_open) {
                prefs.followTerminalFolder = !prefs.followTerminalFolder
                Toast.makeText(this, "Follow terminal folder: " + if (prefs.followTerminalFolder) "On" else "Off", Toast.LENGTH_SHORT).show()
            },
            PaletteCmd("output_strip", "Last Run Output", "Terminal", R.drawable.ic_terminal) { refreshOutputStrip() },
            PaletteCmd("vim_mode", "Vim Mode: " + if (prefs.vimMode) "On" else "Off", "Tools", R.drawable.ic_code) {
                prefs.vimMode = !prefs.vimMode
                binding.editor.vimMode = prefs.vimMode
                Toast.makeText(this, "Vim mode: " + if (prefs.vimMode) "On (normal mode — h/j/k/l, i to insert, Esc to exit insert)" else "Off", Toast.LENGTH_LONG).show()
            },
            PaletteCmd("compare", "Compare with Another File", "File", R.drawable.ic_code) { showComparePicker() },
            PaletteCmd("editor_font", "Editor Font", "Tools", R.drawable.ic_palette) { showV7FontPicker() },
            PaletteCmd("density", "Layout Density: " + prefs.density.replaceFirstChar { it.uppercase() }, "Tools", R.drawable.ic_palette) {
                prefs.density = if (prefs.density == "compact") "comfortable" else "compact"
                applyPreferences()
                Toast.makeText(this, "Density: " + prefs.density, Toast.LENGTH_SHORT).show()
            },
            PaletteCmd("clip_history", "Clipboard History", "Tools", R.drawable.ic_code) { showV7ClipboardHistory() },
            PaletteCmd("time_theme", "Time-aware Theme: " + if (prefs.timeAwareTheme) "On" else "Off", "Tools", R.drawable.ic_palette) {
                prefs.timeAwareTheme = !prefs.timeAwareTheme
                Toast.makeText(this, "Time-aware theme: " + if (prefs.timeAwareTheme) "On" else "Off", Toast.LENGTH_SHORT).show()
            },
            // ---- Moved here from 3-dot menu (menu cleanup): File actions ----
            PaletteCmd("close_right", "Close Tabs to Right", "File", R.drawable.ic_close_tabs) { closeTabsToRight() },
            PaletteCmd("close_all", "Close All Tabs", "File", R.drawable.ic_close_tabs) { closeAllTabs() },
            PaletteCmd("tab_left", "Move Tab Left", "File", R.drawable.ic_arrow_left) { moveTab(-1) },
            PaletteCmd("tab_right", "Move Tab Right", "File", R.drawable.ic_arrow_right) { moveTab(1) },
            PaletteCmd("share_file", "Share File", "File", R.drawable.ic_share) { shareCurrentFile() },
            PaletteCmd("copy_path", "Copy File Path", "File", R.drawable.ic_copy_path) { copyFilePath() },
            // ---- Moved here from 3-dot menu (menu cleanup): View actions ----
            PaletteCmd("html_preview", "Toggle HTML Preview", "View", R.drawable.ic_file) { toggleHtmlPreview() }
        )
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_command_palette, null)
        dialog.setContentView(view)
        val searchInput: EditText = view.findViewById(R.id.paletteSearch)
        val list: RecyclerView = view.findViewById(R.id.paletteList)
        val emptyText: TextView = view.findViewById(R.id.paletteEmpty)
        list.layoutManager = LinearLayoutManager(this)
        val recentIds = prefs.paletteHistory.toMutableList()
        fun pushRecent(id: String) {
            recentIds.remove(id)
            recentIds.add(0, id)
            while (recentIds.size > 8) recentIds.removeAt(recentIds.size - 1)
            prefs.paletteHistory = recentIds
        }
        fun runCmd(cmd: PaletteCmd) {
            pushRecent(cmd.id)
            dialog.dismiss()
            cmd.run()
        }
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<PaletteCmd> = emptyList()
            fun submit(newItems: List<PaletteCmd>) {
                items = newItems
                notifyDataSetChanged()
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_command, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val c = items[position]
                holder.itemView.findViewById<ImageView>(R.id.cmdIcon).setImageResource(c.icon)
                holder.itemView.findViewById<TextView>(R.id.cmdTitle).text = c.title
                holder.itemView.findViewById<TextView>(R.id.cmdHint).text = c.hint
                holder.itemView.setOnClickListener { runCmd(c) }
            }
            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        // Fuzzy match: query ke saare chars title me isi order me aane chahiye.
        fun fuzzyMatch(title: String, q: String): Boolean {
            var ti = 0
            for (qc in q) {
                ti = title.indexOf(qc, ti, ignoreCase = true)
                if (ti < 0) return false
                ti++
            }
            return true
        }
        fun refreshFilter() {
            val q = searchInput.text.toString().trim()
            val filtered = if (q.isEmpty()) {
                val recents = recentIds.mapNotNull { id -> all.find { it.id == id } }
                recents + all.filter { c -> recents.none { it.id == c.id } }
            } else {
                all.filter { fuzzyMatch(it.title, q) }
            }
            adapter.submit(filtered)
            emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                adapter.items.firstOrNull()?.let { runCmd(it) }
                true
            } else false
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshFilter()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        refreshFilter()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        searchInput.post { searchInput.requestFocus() }
    }

    /** Quick Switcher: Swipe down on tab bar to open quick switcher. */
    private fun setupQuickSwitcherSwipe() {
        var downY = 0f
        binding.tabContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - downY
                    // Swipe down > 50px opens quick switcher
                    if (dy > 50 * resources.displayMetrics.density) {
                        showQuickSwitcher()
                        downY = event.y // Prevent repeat
                    }
                }
            }
            false // Don't consume, let other handlers work
        }
    }

    /** Quick Switcher: Track file access for MRU list. */
    private fun trackRecentFile(tab: EditorTab) {
        val item = QuickSwitchItem(
            name = tab.name,
            path = tab.path,
            isOpen = true,
            tabIndex = openTabs.indexOf(tab)
        )
        // Remove existing entry for this file
        recentFiles.removeAll { it.path == tab.path && it.name == tab.name }
        // Add to front (most recent)
        recentFiles.add(0, item)
        // Keep max 20
        if (recentFiles.size > 20) {
            recentFiles.subList(20, recentFiles.size).clear()
        }
    }

    /** Quick Switcher: Show bottom sheet with recent files + search. */
    private fun showQuickSwitcher() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_quick_switcher, null)
        dialog.setContentView(view)

        val searchBox = view.findViewById<android.widget.EditText>(R.id.qsSearch)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.qsList)
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        // Build list: open tabs first (MRU), then recent closed files
        fun buildList(filter: String = ""): List<QuickSwitchItem> {
            val result = ArrayList<QuickSwitchItem>()
            val f = filter.lowercase().trim()
            
            // Open tabs in MRU order
            for (recent in recentFiles) {
                if (recent.tabIndex in openTabs.indices) {
                    val tab = openTabs[recent.tabIndex]
                    // Verify it's still the same tab
                    if (tab.name == recent.name) {
                        if (f.isEmpty() || tab.name.lowercase().contains(f) || 
                            (tab.path?.lowercase()?.contains(f) == true)) {
                            result.add(recent.copy(isOpen = true, tabIndex = recent.tabIndex))
                        }
                    }
                }
            }
            
            // Add other open tabs not in recent list
            for ((idx, tab) in openTabs.withIndex()) {
                if (result.none { it.tabIndex == idx }) {
                    if (f.isEmpty() || tab.name.lowercase().contains(f) ||
                        (tab.path?.lowercase()?.contains(f) == true)) {
                        result.add(QuickSwitchItem(tab.name, tab.path, true, idx))
                    }
                }
            }
            return result
        }

        val adapter = QuickSwitcherAdapter(buildList()) { item ->
            dialog.dismiss()
            if (item.tabIndex in openTabs.indices) {
                switchTab(item.tabIndex)
            }
        }
        recycler.adapter = adapter

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.updateItems(buildList(s?.toString() ?: ""))
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Focus search and show keyboard
        searchBox.requestFocus()
        dialog.setOnShowListener {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) 
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()
    }


    // ============ v11 follow-up: Editor tools (22 features) ============
    // Har tool Editor tools menu (toolbar overflow) se khulta hai, aur
    // kuch automatic hain: per-language symbols (tab switch par),
    // recent-files recording (tab switch par), large-file fast mode
    // (tab switch par), lint squiggles (edit ke 500 ms baad, background
    // thread par compute), drawer swipe aur editor swipe/tap gestures.
    // Koi tool UI thread par heavy kaam nahi karta: file walk, diff,
    // lint, decode sab Dispatchers.IO/Default par; list results capped.

    private data class FollowupTool(val id: String, val title: String, val run: () -> Unit)

    private val followupLintHandler = Handler(Looper.getMainLooper())
    private var followupLintRunnable: Runnable? = null
    private var followupLintIssues: List<FollowupLint.Issue> = emptyList()
    private val followupLintSpans = ArrayList<android.text.style.UnderlineSpan>()
    private var followupLastFastTabId: String? = null

    private fun currentFileExtension(): String {
        return openTabs.getOrNull(activeTabIndex)?.name
            ?.substringAfterLast('.', "")?.lowercase() ?: ""
    }

    private fun followupAllTools(): List<FollowupTool> {
        return listOf(
            FollowupTool("quick_switch", "Quick file switcher") { showFollowupQuickSwitcher() },
            FollowupTool("recent", "Recent files") { showFollowupRecent() },
            FollowupTool("hex", "Hex color picker") { showFollowupHexPicker() },
            FollowupTool("templates", "File templates") { showFollowupTemplates() },
            FollowupTool("image_preview", "Image path preview") { showFollowupImagePreview() },
            FollowupTool("sort_filter", "File browser sort + filter") { showFollowupSortFilter() },
            FollowupTool("workspaces", "Workspaces") { showFollowupWorkspaces() },
            FollowupTool("cheat_sheets", "Offline cheat sheets") { showFollowupCheatSheets() },
            FollowupTool("diff", "File compare / diff view") { showFollowupDiffPicker() },
            FollowupTool("find_replace", "Find & replace with preview") { showFollowupFindReplacePreview() },
            FollowupTool("split", "Split editor panes") { showFollowupSplit() },
            FollowupTool("terminal_folder", "Open terminal in file's folder") { openTerminalHere() },
            FollowupTool("lint", "Offline lint issues") { showFollowupLintDialog() },
            FollowupTool("encoding", "Encoding + line ending") { showFollowupEncodingDialog() },
            FollowupTool("share", "Share file out") { showFollowupShare() },
            FollowupTool("file_info", "File info") { showFollowupFileInfo() },
            FollowupTool("send_terminal", "Send selection to terminal") { showFollowupSendToTerminal() },
            FollowupTool("save_clipboard", "Save clipboard text as new file") { showFollowupSaveClipboard() },
            FollowupTool("tour", "Editor tour") { showFollowupTour(force = true) },
            FollowupTool("custom_toolbar", "Customise toolbar") { showFollowupToolbarCustomiser() },
            FollowupTool("fast_mode", "Large file fast mode") { showFollowupFastModeInfo() }
        )
    }

    private fun showFollowupMenu() {
        val all = followupAllTools()
        val byId = all.associateBy { it.id }
        val order = FollowupStore.toolbarOrder(this)
        val disabled = FollowupStore.toolbarDisabled(this)
        val visible = ArrayList<FollowupTool>()
        for (id in order) {
            val tool = byId[id]
            if (tool != null && id !in disabled) visible.add(tool)
        }
        for (tool in all) {
            if (tool.id !in order && tool.id !in disabled) visible.add(tool)
        }
        if (visible.isEmpty()) {
            Toast.makeText(this, "All tools are disabled — open Customise toolbar", Toast.LENGTH_LONG).show()
            showFollowupToolbarCustomiser()
            return
        }
        val titles = visible.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.followup_menu_title)
            .setItems(titles) { _, which -> visible[which].run() }
            .setNeutralButton(R.string.followup_custom_toolbar_title) { _, _ -> showFollowupToolbarCustomiser() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupFollowup() {
        try {
            binding.welcomeRecentBtn.setOnClickListener { showFollowupRecent() }
        } catch (e: Exception) {
        }
        renderFollowupToolbar()

        // (21) Tour: sirf pehli baar, editor khulne ke thodi der baad.
        if (!FollowupStore.isTourShown(this)) {
            binding.root.postDelayed({ showFollowupTour(force = false) }, 800)
        }

        // (9a) Drawer file list me swipe: left = next file tab, right = previous.
        val swipeHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean = false

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    submitDrawerList(folderBackStack.isNotEmpty())
                    if (openTabs.isEmpty()) return
                    val next = if (direction == ItemTouchHelper.LEFT) {
                        (activeTabIndex + 1) % openTabs.size
                    } else {
                        (activeTabIndex - 1 + openTabs.size) % openTabs.size
                    }
                    switchTab(next)
                }
            }
        )
        try {
            swipeHelper.attachToRecyclerView(binding.fileRecyclerView)
        } catch (e: Exception) {
        }

        // (9b) Editor gestures + (5) hex tap: fling = line delete (confirm +
        // Snackbar undo), tap on #RRGGBB = hex picker. Listener kabhi event
        // consume nahi karta, isliye normal typing/selection chalta rehta hai.
        val density = resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        binding.editor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val dt = System.currentTimeMillis() - downTime
                    val ed = binding.editor
                    if (dt < 600 && kotlin.math.abs(dx) > 160 * density &&
                        kotlin.math.abs(dy) < 60 * density
                    ) {
                        showFollowupDeleteLineConfirm(downX, downY)
                    } else if (dt < 500 && kotlin.math.abs(dx) < 24 &&
                        kotlin.math.abs(dy) < 24
                    ) {
                        try {
                            val offset = ed.getOffsetForPosition(downX, downY)
                            val text = ed.text
                            if (text != null && offset in 0..text.length) {
                                val found = FollowupHex.findAt(text, offset)
                                if (found != null) showFollowupHexPicker(found)
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            false
        }

        // (18) Lint: har edit ke 500 ms baad, background thread par compute.
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleFollowupLint()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun showFollowupDeleteLineConfirm(x: Float, y: Float) {
        val ed = binding.editor
        val text = ed.text ?: return
        val offset = try {
            ed.getOffsetForPosition(x, y).coerceIn(0, text.length)
        } catch (e: Exception) {
            return
        }
        val full = text.toString()
        val lineStart = full.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let {
            if (it < 0) 0 else it + 1
        }
        var lineEnd = full.indexOf('\n', offset)
        if (lineEnd < 0) lineEnd = full.length else lineEnd += 1
        if (lineStart >= lineEnd) return
        val deleted = full.substring(lineStart, lineEnd)
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete line?")
            .setMessage(deleted.trim().take(120))
            .setPositiveButton("Delete") { _, _ ->
                ed.text?.delete(lineStart, lineEnd)
                Snackbar.make(binding.root, "Line deleted", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        ed.text?.insert(lineStart, deleted)
                        ed.setSelection((lineStart + deleted.length).coerceAtMost(ed.length()))
                    }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun scheduleFollowupLint() {
        followupLintRunnable?.let { followupLintHandler.removeCallbacks(it) }
        val snapshot = binding.editor.text?.toString() ?: return
        val ext = currentFileExtension()
        val runnable = Runnable {
            lifecycleScope.launch(Dispatchers.Default) {
                val issues = FollowupLint.lint(snapshot, ext)
                withContext(Dispatchers.Main) {
                    followupLintIssues = issues
                    applyFollowupLintSpans()
                }
            }
        }
        followupLintRunnable = runnable
        followupLintHandler.postDelayed(runnable, 500)
    }

    private fun applyFollowupLintSpans() {
        val editable = binding.editor.text ?: return
        for (span in followupLintSpans) {
            try {
                editable.removeSpan(span)
            } catch (e: Exception) {
            }
        }
        followupLintSpans.clear()
        if (followupLintIssues.isEmpty()) return
        val full = editable.toString()
        val lineStarts = ArrayList<Int>()
        lineStarts.add(0)
        for (i in full.indices) {
            if (full[i] == '\n') lineStarts.add(i + 1)
        }
        for (issue in followupLintIssues.take(50)) {
            val idx = issue.line - 1
            if (idx !in lineStarts.indices) continue
            val start = lineStarts[idx]
            var end = if (idx + 1 < lineStarts.size) lineStarts[idx + 1] - 1 else full.length
            if (end <= start) continue
            val span = android.text.style.UnderlineSpan()
            try {
                editable.setSpan(span, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                followupLintSpans.add(span)
            } catch (e: Exception) {
            }
        }
    }

    private fun showFollowupLintDialog() {
        if (followupLintIssues.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Offline lint")
                .setMessage("No lint issues found in this file.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val items = followupLintIssues.map { "Ln ${it.line}: ${it.message}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Offline lint (${followupLintIssues.size} issues)")
            .setItems(items) { dialog, which ->
                val line = followupLintIssues[which].line
                binding.editor.gotoLine(line)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // (2) Large file fast mode: badi file par highlight buffer tight hota
    // hai (sirf visible range + 40 lines), chhoti par wapas 120 lines.
    private fun applyFollowupFastMode(tab: EditorTab) {
        val lines = if (tab.content.isEmpty()) 1 else tab.content.count { it == '\n' } + 1
        val large = lines > 5000 || tab.content.length > 300000
        binding.editor.applyFastMode(large)
        if (large && followupLastFastTabId != tab.id) {
            followupLastFastTabId = tab.id
            Toast.makeText(
                this,
                "Fast mode on: large file, highlight limited to visible area",
                Toast.LENGTH_LONG
            ).show()
        } else if (!large) {
            followupLastFastTabId = null
        }
    }

    private fun showFollowupFastModeInfo() {
        val tab = openTabs.getOrNull(activeTabIndex) ?: run {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        applyFollowupFastMode(tab)
        val lines = if (tab.content.isEmpty()) 1 else tab.content.count { it == '\n' } + 1
        val large = lines > 5000 || tab.content.length > 300000
        Toast.makeText(
            this,
            if (large) "Fast mode on for this file" else "Fast mode off — this file is small",
            Toast.LENGTH_SHORT
        ).show()
    }

    // (17) Custom toolbar row: stored order ke pehle 8 enabled tools.
    private fun renderFollowupToolbar() {
        try {
            val container = binding.followupToolbarContainer
            container.removeAllViews()
            val byId = followupAllTools().associateBy { it.id }
            val disabled = FollowupStore.toolbarDisabled(this)
            val shown = FollowupStore.toolbarOrder(this)
                .mapNotNull { byId[it] }
                .filter { it.id !in disabled }
                .take(8)
            binding.followupToolbar.visibility =
                if (shown.isEmpty()) View.GONE else View.VISIBLE
            val density = resources.displayMetrics.density
            for (tool in shown) {
                val btn = android.widget.Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = tool.title
                    textSize = 13f
                    setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                }
                btn.setOnClickListener { tool.run() }
                container.addView(
                    btn,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        } catch (e: Exception) {
        }
    }

    private inner class FollowupToolbarViewHolder(
        val row: LinearLayout,
        val titleView: TextView,
        val upBtn: android.widget.Button,
        val downBtn: android.widget.Button,
        val toggle: android.widget.Switch
    ) : RecyclerView.ViewHolder(row)

    private fun showFollowupToolbarCustomiser() {
        val ids = FollowupStore.toolbarOrder(this).toMutableList()
        val disabled = FollowupStore.toolbarDisabled(this).toMutableSet()
        val titles = followupAllTools().associate { it.id to it.title }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * resources.displayMetrics.density).toInt()
            )
        }
        col.addView(list)
        val adapter = object : RecyclerView.Adapter<FollowupToolbarViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FollowupToolbarViewHolder {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val titleView = TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 15f
                }
                val upBtn = android.widget.Button(this@MainActivity).apply { text = "↑" }
                val downBtn = android.widget.Button(this@MainActivity).apply { text = "↓" }
                val toggle = android.widget.Switch(this@MainActivity)
                row.addView(titleView)
                row.addView(upBtn)
                row.addView(downBtn)
                row.addView(toggle)
                return FollowupToolbarViewHolder(row, titleView, upBtn, downBtn, toggle)
            }

            override fun getItemCount(): Int = ids.size

            override fun onBindViewHolder(holder: FollowupToolbarViewHolder, position: Int) {
                val id = ids[position]
                holder.titleView.text = titles[id] ?: id
                holder.toggle.setOnCheckedChangeListener(null)
                holder.toggle.isChecked = id !in disabled
                holder.toggle.setOnCheckedChangeListener { _, checked ->
                    if (checked) disabled.remove(id) else disabled.add(id)
                    FollowupStore.setToolbarDisabled(this@MainActivity, disabled)
                    renderFollowupToolbar()
                }
                holder.upBtn.setOnClickListener {
                    if (position > 0) {
                        val moved = ids.removeAt(position)
                        ids.add(position - 1, moved)
                        FollowupStore.setToolbarOrder(this@MainActivity, ids)
                        notifyDataSetChanged()
                        renderFollowupToolbar()
                    }
                }
                holder.downBtn.setOnClickListener {
                    if (position < ids.size - 1) {
                        val moved = ids.removeAt(position)
                        ids.add(position + 1, moved)
                        FollowupStore.setToolbarOrder(this@MainActivity, ids)
                        notifyDataSetChanged()
                        renderFollowupToolbar()
                    }
                }
            }
        }
        list.adapter = adapter
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.followup_custom_toolbar_title)
            .setView(col)
            .setPositiveButton("Done") { _, _ ->
                FollowupStore.setToolbarOrder(this, ids)
                FollowupStore.setToolbarDisabled(this, disabled)
                renderFollowupToolbar()
            }
            .show()
    }

    // (1) Quick file switcher: current folder ke saare files (max 500,
    // background thread par walk), fuzzy type-ahead filter (200 ms
    // debounce), tap par file khulti hai. Folder na ho to khule tabs.
    private fun showFollowupQuickSwitcher() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        val search = EditText(this).apply {
            hint = "Type to filter files…"
        }
        val listView = android.widget.ListView(this)
        col.addView(search)
        col.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (420 * resources.displayMetrics.density).toInt()
            )
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Quick file switcher")
            .setView(col)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        data class QItem(val name: String, val relPath: String, val doc: DocumentFile)
        val full = ArrayList<QItem>()
        val shown = ArrayList<QItem>()
        val display = ArrayList<String>()
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_list_item_1, display
        )
        listView.adapter = adapter
        fun refresh(query: String) {
            shown.clear()
            display.clear()
            val filtered = if (query.isBlank()) {
                full.take(100)
            } else {
                full.mapNotNull { item ->
                    val score = FollowupFuzzy.scoreFile(query, item.name, item.relPath)
                    if (score < 0) null else item to score
                }.sortedByDescending { it.second }.map { it.first }.take(50)
            }
            shown.addAll(filtered)
            display.addAll(filtered.map { "${it.name} — ${it.relPath}" })
            adapter.notifyDataSetChanged()
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in shown.indices) {
                dialog.dismiss()
                openFileInTab(shown[position].doc)
            }
        }
        val filterHandler = Handler(Looper.getMainLooper())
        var filterRunnable: Runnable? = null
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterRunnable?.let { filterHandler.removeCallbacks(it) }
                val q = s?.toString() ?: ""
                val r = Runnable { refresh(q) }
                filterRunnable = r
                filterHandler.postDelayed(r, 200)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        dialog.show()
        search.requestFocus()
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val out = ArrayList<QItem>()
                val folder = currentFolder
                if (folder != null) {
                    fun walk(dir: DocumentFile, prefix: String, depth: Int) {
                        if (out.size >= 500 || depth > 4) return
                        val children = try {
                            dir.listFiles()
                        } catch (e: Exception) {
                            return
                        }
                        for (child in children) {
                            if (out.size >= 500) return
                            val name = child.name ?: continue
                            if (prefs.hideHiddenFiles && name.startsWith(".")) continue
                            try {
                                if (child.isDirectory) {
                                    walk(child, "$prefix$name/", depth + 1)
                                } else if (child.isFile) {
                                    out.add(QItem(name, "$prefix$name", child))
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                    try {
                        walk(folder, "", 0)
                    } catch (e: Exception) {
                    }
                }
                if (out.isEmpty()) {
                    for (tab in openTabs) {
                        val uri = tab.uri ?: continue
                        try {
                            val doc = DocumentFile.fromSingleUri(this@MainActivity, uri)
                            if (doc != null && doc.isFile) {
                                out.add(QItem(tab.name, tab.name, doc))
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
                out
            }
            full.clear()
            full.addAll(items)
            refresh(search.text?.toString() ?: "")
        }
    }

    // (4) Recent files: har tab switch par persist hoti list, ek tap par wapas.
    private fun showFollowupRecent() {
        val items = FollowupStore.recentFiles(this)
        if (items.isEmpty()) {
            Toast.makeText(this, "No recent files yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { "${it.name} — ${relativeTimeAgo(it.time)}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Recent files")
            .setItems(labels) { dialog, which ->
                val uri = try {
                    android.net.Uri.parse(items[which].uri)
                } catch (e: Exception) {
                    null
                }
                if (uri != null) {
                    openFileFromUri(uri)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // (5) Hex color picker: caret par #RRGGBB ho to preview + presets +
    // custom hex; Apply par document me hex replace hota hai.
    private fun showFollowupHexPicker(foundIn: FollowupHex.Found? = null) {
        val ed = binding.editor
        val text = ed.text ?: return
        val found = foundIn ?: run {
            val offset = ed.selectionStart.coerceIn(0, text.length)
            FollowupHex.findAt(text, offset)
        }
        if (found == null) {
            Toast.makeText(this, "No #RRGGBB color at caret", Toast.LENGTH_SHORT).show()
            return
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * resources.displayMetrics.density).toInt()
            )
            try {
                setBackgroundColor(android.graphics.Color.parseColor(found.hex))
            } catch (e: Exception) {
            }
        }
        val input = EditText(this).apply {
            setText(found.hex)
            hint = "#RRGGBB"
        }
        col.addView(preview)
        col.addView(input)
        for (preset in FollowupHex.presets) {
            val btn = android.widget.Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                this.text = preset
                try {
                    setBackgroundColor(android.graphics.Color.parseColor(preset))
                    setTextColor(android.graphics.Color.WHITE)
                } catch (e: Exception) {
                }
            }
            btn.setOnClickListener {
                input.setText(preset)
                try {
                    preview.setBackgroundColor(android.graphics.Color.parseColor(preset))
                } catch (e: Exception) {
                }
            }
            col.addView(btn)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Hex color picker")
            .setView(col)
            .setPositiveButton("Apply") { _, _ ->
                val hex = FollowupHex.normalize(input.text.toString())
                if (!FollowupHex.isValid(hex)) {
                    Toast.makeText(this, "Invalid hex color", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val editable = binding.editor.text ?: return@setPositiveButton
                val start = found.start.coerceIn(0, editable.length)
                val end = found.end.coerceIn(start, editable.length)
                editable.replace(start, end, hex)
                binding.editor.setSelection((start + hex.length).coerceAtMost(editable.length))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // (6) File templates: template chuno, sahi extension wali nayi file khulti hai.
    private fun showFollowupTemplates() {
        val templates = FollowupTemplates.all
        val labels = templates.map { "${it.label} (${it.fileName})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("New file from template")
            .setItems(labels) { dialog, which ->
                openTemplateTab(templates[which].fileName, templates[which].content)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openTemplateTab(name: String, content: String) {
        val newTab = EditorTab(name = name, content = content, diskContent = "", isModified = true)
        openTabs.add(newTab)
        switchTab(openTabs.size - 1)
        saveSessionState()
    }

    // (7) Image path preview: caret wali line ka image path resolve karke
    // background thread par decode, fail par graceful message.
    private fun showFollowupImagePreview() {
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val offset = ed.selectionStart.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let {
            if (it < 0) 0 else it + 1
        }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val match = Regex("([A-Za-z0-9_./-]+\\.(png|jpg|jpeg|gif|webp|bmp))")
            .find(line)
        if (match == null) {
            Toast.makeText(this, "No image path on this line", Toast.LENGTH_SHORT).show()
            return
        }
        val path = match.groupValues[1]
        lifecycleScope.launch {
            val doc = withContext(Dispatchers.IO) {
                try {
                    if (path.startsWith("/")) {
                        val file = java.io.File(path)
                        if (file.isFile) DocumentFile.fromFile(file) else null
                    } else {
                        var found: DocumentFile? = null
                        for (base in listOfNotNull(currentFolder, rootDocumentFile)) {
                            found = findDocumentByRelativePath(base, path)
                            if (found != null) break
                        }
                        found
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (doc == null) {
                Toast.makeText(this@MainActivity, "Image not found: $path", Toast.LENGTH_LONG).show()
                return@launch
            }
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    if (doc.length() > 8 * 1024 * 1024) return@withContext null
                    contentResolver.openInputStream(doc.uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap == null) {
                Toast.makeText(this@MainActivity, "Could not load image: $path", Toast.LENGTH_LONG).show()
                return@launch
            }
            val iv = android.widget.ImageView(this@MainActivity).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
            }
            val sv = android.widget.HorizontalScrollView(this@MainActivity).apply {
                addView(android.widget.ScrollView(this@MainActivity).apply { addView(iv) })
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(doc.name ?: path)
                .setView(sv)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    // (10) File browser sort + filter. Date/size ke liye DocumentFile se
    // metadata liya jata hai (FileItem me wo fields nahi hain); choice
    // persist hoti hai aur name/type sort agle folder load par bhi lagta hai.
    private fun showFollowupSortFilter() {
        val sortOptions = arrayOf("Name", "Date", "Size", "Type")
        val sortKeys = arrayOf("name", "date", "size", "type")
        val sortChecked = sortKeys.indexOf(FollowupStore.browserSort(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort files by")
            .setSingleChoiceItems(sortOptions, sortChecked) { _, _ -> }
            .setPositiveButton("Next") { dialog, _ ->
                val listView = (dialog as AlertDialog).listView
                val sortPos = listView.checkedItemPosition.coerceAtLeast(0)
                val sortKey = sortKeys[sortPos]
                showFollowupTypeFilter(sortKey)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFollowupTypeFilter(sortKey: String) {
        val filterOptions = arrayOf("All files", "Code", "Images", "Documents", "Other")
        val filterKeys = arrayOf("all", "code", "image", "doc", "other")
        val filterChecked = filterKeys.indexOf(FollowupStore.browserTypeFilter(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Show only")
            .setSingleChoiceItems(filterOptions, filterChecked) { _, _ -> }
            .setPositiveButton("Apply") { dialog, _ ->
                val listView = (dialog as AlertDialog).listView
                val filterPos = listView.checkedItemPosition.coerceAtLeast(0)
                val filterKey = filterKeys[filterPos]
                FollowupStore.setBrowserSort(this, sortKey)
                FollowupStore.setBrowserTypeFilter(this, filterKey)
                applyFollowupBrowserSortFilterNow(sortKey, filterKey)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyFollowupBrowserSortFilterNow(sortKey: String, filterKey: String) {
        val folder = currentFolder ?: run {
            Toast.makeText(this, "No folder open", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val sorted = withContext(Dispatchers.IO) {
                val children = try {
                    folder.listFiles()
                } catch (e: Exception) {
                    emptyArray()
                }
                val dirs = ArrayList<DocumentFile>()
                val files = ArrayList<Triple<DocumentFile, Long, Long>>()
                for (child in children) {
                    val name = child.name ?: continue
                    if (prefs.hideHiddenFiles && name.startsWith(".")) continue
                    try {
                        if (child.isDirectory) {
                            dirs.add(child)
                        } else if (child.isFile) {
                            if (filterKey != "all" && FollowupStore.typeGroup(name) != filterKey) continue
                            files.add(Triple(child, child.lastModified(), child.length()))
                        }
                    } catch (e: Exception) {
                    }
                }
                val sortedDirs = dirs.sortedBy { it.name?.lowercase() ?: "" }
                val sortedFiles = when (sortKey) {
                    "date" -> files.sortedByDescending { it.second }
                    "size" -> files.sortedByDescending { it.third }
                    "type" -> files.sortedWith(
                        compareBy(
                            { it.first.name?.substringAfterLast('.', "")?.lowercase() ?: "" },
                            { it.first.name?.lowercase() ?: "" }
                        )
                    )
                    else -> files.sortedBy { it.first.name?.lowercase() ?: "" }
                }
                val items = ArrayList<FileItem>()
                for (dir in sortedDirs) {
                    items.add(FileItem(dir.name ?: "", dir.uri, isDirectory = true))
                }
                for ((doc, _, _) in sortedFiles) {
                    items.add(FileItem(doc.name ?: "", doc.uri, isDirectory = false))
                }
                items
            }
            lastDrawerBase = filterHiddenFiles(sorted)
            submitDrawerList(folderBackStack.isNotEmpty())
            Toast.makeText(this@MainActivity, "Browser sorted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFollowupBrowserSortFilter(items: List<FileItem>): List<FileItem> {
        val filterKey = FollowupStore.browserTypeFilter(this)
        val sortKey = FollowupStore.browserSort(this)
        val filtered = if (filterKey == "all") {
            items
        } else {
            items.filter { it.isDirectory || FollowupStore.typeGroup(it.name) == filterKey }
        }
        return when (sortKey) {
            "name" -> filtered.sortedWith(
                compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
            "type" -> filtered.sortedWith(
                compareByDescending<FileItem> { it.isDirectory }
                    .thenBy { it.name.substringAfterLast('.', "").lowercase() }
                    .thenBy { it.name.lowercase() }
            )
            else -> filtered
        }
    }

    // (11) Workspaces: kai folders, ek tap me switch; list persist hoti hai.
    private fun showFollowupWorkspaces() {
        val spaces = FollowupStore.workspaces(this)
        val labels = spaces.map { it.name }.toMutableList()
        labels.add("Add current folder as workspace")
        MaterialAlertDialogBuilder(this)
            .setTitle("Workspaces")
            .setItems(labels.toTypedArray()) { dialog, which ->
                if (which == spaces.size) {
                    val folder = currentFolder ?: run {
                        Toast.makeText(this, "No folder open", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    val input = EditText(this).apply {
                        setText(folder.name ?: "Workspace")
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Workspace name")
                        .setView(input)
                        .setPositiveButton("Add") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotEmpty()) {
                                FollowupStore.addWorkspace(this, name, folder.uri.toString())
                                Toast.makeText(this, "Workspace added: $name", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    val space = spaces[which]
                    val doc = try {
                        DocumentFile.fromTreeUri(this, android.net.Uri.parse(space.uri))
                    } catch (e: Exception) {
                        null
                    }
                    if (doc == null || !doc.exists()) {
                        Toast.makeText(this, "Workspace folder not found", Toast.LENGTH_LONG).show()
                        return@setItems
                    }
                    rootDocumentFile = doc
                    currentFolder = doc
                    folderBackStack.clear()
                    folderCache.evictAll()
                    loadCurrentFolderContentsFast()
                    Toast.makeText(this, "Workspace: ${space.name}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNeutralButton("Remove workspace…") { _, _ ->
                if (spaces.isEmpty()) {
                    Toast.makeText(this, "No workspaces saved", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                val names = spaces.map { it.name }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Remove workspace")
                    .setItems(names) { _, which ->
                        FollowupStore.removeWorkspace(this, spaces[which].uri)
                        Toast.makeText(this, "Workspace removed", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // (12) Offline cheat sheets: bundled text, koi network nahi.
    private fun showFollowupCheatSheets() {
        val languages = FollowupCheatSheets.languages()
        MaterialAlertDialogBuilder(this)
            .setTitle("Cheat sheets (offline)")
            .setItems(languages.toTypedArray()) { _, which ->
                val lang = languages[which]
                val textView = TextView(this).apply {
                    text = FollowupCheatSheets.sheets[lang] ?: ""
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 13f
                    setPadding(32, 24, 32, 24)
                }
                val scroll = android.widget.ScrollView(this).apply { addView(textView) }
                MaterialAlertDialogBuilder(this)
                    .setTitle("$lang cheat sheet")
                    .setView(scroll)
                    .setPositiveButton("Close", null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // (13) File compare: pehle background thread par diff count nikalta
    // hai, phir existing CompareActivity side-by-side diff dikhata hai.
    // Disk wali files compare hoti hain (CompareActivity bhi disk se padhta hai).
    private fun showFollowupDiffPicker() {
        val current = openTabs.getOrNull(activeTabIndex) ?: run {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val currentUri = current.uri?.toString() ?: run {
            Toast.makeText(this, "Save the current file first", Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = LinkedHashMap<String, String>()
        for ((i, tab) in openTabs.withIndex()) {
            if (i == activeTabIndex) continue
            val u = tab.uri?.toString() ?: continue
            candidates[u] = tab.name
        }
        try {
            for (item in lastDrawerBase) {
                if (item.isDirectory || item.isParent) continue
                val u = item.uri.toString()
                if (u != currentUri) candidates.putIfAbsent(u, item.name)
            }
        } catch (e: Exception) {
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Open a second file first", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = candidates.keys.toList()
        val names = candidates.values.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Compare with")
            .setItems(names) { dialog, which ->
                val otherUri = uris[which]
                val otherName = names[which]
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.Default) {
                        try {
                            val linesA = contentResolver.openInputStream(
                                android.net.Uri.parse(currentUri)
                            )?.bufferedReader()?.readLines() ?: emptyList()
                            val linesB = contentResolver.openInputStream(
                                android.net.Uri.parse(otherUri)
                            )?.bufferedReader()?.readLines() ?: emptyList()
                            FollowupDiff.diff(linesA, linesB)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (result == null) {
                        Toast.makeText(this@MainActivity, "Could not read one of the files", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val message = if (result.tooLarge) {
                        "Files are too large for line highlights — open plain side-by-side view?"
                    } else if (result.differingLines == 0) {
                        "Files are identical. Open diff view anyway?"
                    } else {
                        "${result.differingLines} differing line(s). Open diff view?"
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Diff preview")
                        .setMessage(message)
                        .setPositiveButton("Open diff") { _, _ ->
                            val intent = Intent(
                                this@MainActivity,
                                com.nobidev.nobicode.compare.CompareActivity::class.java
                            )
                            intent.putExtra("compare_a_uri", currentUri)
                            intent.putExtra("compare_a_name", current.name)
                            intent.putExtra("compare_b_uri", otherUri)
                            intent.putExtra("compare_b_name", otherName)
                            startActivity(intent)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // (14) In-file find & replace with preview: regex support, matches ki
    // list (line + snippet), Replace one / Replace all. Replacement literal
    // hai (regex group expansion nahi), matches cap 200.
    private fun showFollowupFindReplacePreview() {
        data class FMatch(val line: Int, val start: Int, val end: Int, val snippet: String)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        val patternInput = EditText(this).apply { hint = "Search pattern" }
        val replaceInput = EditText(this).apply { hint = "Replacement" }
        val regexBox = android.widget.CheckBox(this).apply { text = "Use regex" }
        val caseBox = android.widget.CheckBox(this).apply { text = "Case sensitive" }
        val countView = TextView(this).apply { text = "No matches yet" }
        val listView = android.widget.ListView(this)
        val findBtn = android.widget.Button(this).apply { text = "Find matches" }
        val replaceOneBtn = android.widget.Button(this).apply { text = "Replace one" }
        val replaceAllBtn = android.widget.Button(this).apply { text = "Replace all" }
        col.addView(patternInput)
        col.addView(replaceInput)
        col.addView(regexBox)
        col.addView(caseBox)
        col.addView(findBtn)
        col.addView(countView)
        col.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (300 * resources.displayMetrics.density).toInt()
            )
        )
        col.addView(replaceOneBtn)
        col.addView(replaceAllBtn)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Find & replace with preview")
            .setView(col)
            .setNegativeButton("Close", null)
            .create()
        var matches = listOf<FMatch>()
        var matchIndex = 0
        val display = ArrayList<String>()
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_list_item_1, display
        )
        listView.adapter = adapter
        fun refreshList() {
            display.clear()
            display.addAll(matches.map { "Ln ${it.line}: ${it.snippet}" })
            adapter.notifyDataSetChanged()
            countView.text = if (matches.isEmpty()) "No matches" else "${matches.size} match(es)"
        }
        findBtn.setOnClickListener {
            val pattern = patternInput.text.toString()
            if (pattern.isEmpty()) {
                Toast.makeText(this, "Enter a search pattern", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val snapshot = binding.editor.text?.toString() ?: ""
            val useRegex = regexBox.isChecked
            val caseSensitive = caseBox.isChecked
            lifecycleScope.launch(Dispatchers.Default) {
                val found = ArrayList<FMatch>()
                try {
                    val regex = if (useRegex) {
                        if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                    } else {
                        val escaped = Regex.escape(pattern)
                        if (caseSensitive) Regex(escaped) else Regex(escaped, RegexOption.IGNORE_CASE)
                    }
                    val lineStarts = ArrayList<Int>()
                    lineStarts.add(0)
                    for (i in snapshot.indices) {
                        if (snapshot[i] == '\n') lineStarts.add(i + 1)
                    }
                    fun lineOf(offset: Int): Int {
                        var lo = 0
                        var hi = lineStarts.size - 1
                        while (lo < hi) {
                            val mid = (lo + hi + 1) / 2
                            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
                        }
                        return lo + 1
                    }
                    for (m in regex.findAll(snapshot)) {
                        if (found.size >= 200) break
                        val line = lineOf(m.range.first)
                        val ls = lineStarts[line - 1]
                        var le = snapshot.indexOf('\n', m.range.first)
                        if (le < 0) le = snapshot.length
                        found.add(
                            FMatch(line, m.range.first, m.range.last + 1, snapshot.substring(ls, le).trim().take(100))
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Invalid pattern", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    matches = found
                    matchIndex = 0
                    refreshList()
                }
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in matches.indices) {
                matchIndex = position
                binding.editor.gotoLine(matches[position].line)
            }
        }
        replaceOneBtn.setOnClickListener {
            if (matches.isEmpty()) {
                Toast.makeText(this, "Tap Find matches first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val replacement = replaceInput.text.toString()
            val match = matches[matchIndex % matches.size]
            val editable = binding.editor.text ?: return@setOnClickListener
            if (match.start in 0..editable.length && match.end in match.start..editable.length) {
                editable.replace(match.start, match.end, replacement)
                binding.editor.setSelection((match.start + replacement.length).coerceAtMost(editable.length))
                matches = emptyList()
                refreshList()
                Toast.makeText(this, "Replaced one — tap Find again to refresh", Toast.LENGTH_SHORT).show()
            }
        }
        replaceAllBtn.setOnClickListener {
            if (matches.isEmpty()) {
                Toast.makeText(this, "Tap Find matches first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val replacement = replaceInput.text.toString()
            val editable = binding.editor.text ?: return@setOnClickListener
            var count = 0
            for (match in matches.sortedByDescending { it.start }) {
                if (match.start in 0..editable.length && match.end in match.start..editable.length) {
                    editable.replace(match.start, match.end, replacement)
                    count++
                }
            }
            matches = emptyList()
            refreshList()
            Toast.makeText(this, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    // (15) Split editor panes: do saved files, alag activity me side by
    // side (landscape) / stacked (portrait), dono independently editable.
    private fun showFollowupSplit() {
        val current = openTabs.getOrNull(activeTabIndex) ?: run {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val currentUri = current.uri?.toString() ?: run {
            Toast.makeText(this, "Save the current file first", Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = openTabs.filterIndexed { i, tab ->
            i != activeTabIndex && tab.uri != null
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Open and save a second file first", Toast.LENGTH_SHORT).show()
            return
        }
        val names = candidates.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Split with")
            .setItems(names) { dialog, which ->
                val other = candidates[which]
                val intent = Intent(this, FollowupSplitActivity::class.java)
                intent.putExtra("split_a_uri", currentUri)
                intent.putExtra("split_a_name", current.name)
                intent.putExtra("split_b_uri", other.uri.toString())
                intent.putExtra("split_b_name", other.name)
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // (3) Editor -> terminal: selection (ya current line) clipboard ke
    // zariye terminal tak; terminal file ke folder me khulta hai. Is
    // codebase me terminal session me seedha command bhejne ka koi API
    // nahi hai, isliye clipboard hi honest bridge hai — fake file channel
    // ka daava nahi kiya gaya hai.
    private fun showFollowupSendToTerminal() {
        val ed = binding.editor
        val text = ed.text?.toString() ?: return
        val start = ed.selectionStart.coerceIn(0, text.length)
        val end = ed.selectionEnd.coerceIn(0, text.length)
        val toSend = if (start != end) {
            text.substring(minOf(start, end), maxOf(start, end))
        } else {
            val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let {
                if (it < 0) 0 else it + 1
            }
            val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            text.substring(lineStart, lineEnd)
        }
        if (toSend.isBlank()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NobiCode", toSend))
        openTerminalHere()
        Toast.makeText(this, "Copied to clipboard — paste in terminal", Toast.LENGTH_LONG).show()
    }

    // (3, reverse) Terminal -> editor: terminal se copy kiya text clipboard
    // se nayi file ke roop me editor me khulta hai, phir Save As se save hota hai.
    private fun showFollowupSaveClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (clipText.isNullOrBlank()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        openNewUntitledTab(clipText)
        Toast.makeText(this, "Clipboard text opened as a new file", Toast.LENGTH_SHORT).show()
    }

    // (19) Encoding + line ending: choice persist hoti hai, editor text ka
    // line ending turant badalta hai, aur save/open path usi encoding se
    // likhta/padhta hai (saveLocalTabContent aur openFileInTab ke hooks dekho).
    private fun showFollowupEncodingDialog() {
        val tab = openTabs.getOrNull(activeTabIndex) ?: run {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val uriKey = tab.uri?.toString()
        val encodings = FollowupEncoding.encodings.toTypedArray()
        val encChecked = encodings.indexOf(FollowupStore.encodingFor(this, uriKey)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("File encoding")
            .setSingleChoiceItems(encodings, encChecked) { _, _ -> }
            .setPositiveButton("Next") { dialog, _ ->
                val listView = (dialog as AlertDialog).listView
                val encPos = listView.checkedItemPosition.coerceAtLeast(0)
                val chosenEnc = encodings[encPos]
                val endings = arrayOf("LF", "CRLF")
                val leChecked = if (FollowupStore.lineEndingFor(this, uriKey) == "CRLF") 1 else 0
                MaterialAlertDialogBuilder(this)
                    .setTitle("Line ending")
                    .setSingleChoiceItems(endings, leChecked) { _, _ -> }
                    .setPositiveButton("Apply") { dialog2, _ ->
                        val listView2 = (dialog2 as AlertDialog).listView
                        val lePos = listView2.checkedItemPosition.coerceAtLeast(0)
                        val chosenLe = endings[lePos]
                        FollowupStore.setEncoding(this, uriKey, chosenEnc)
                        FollowupStore.setLineEnding(this, uriKey, chosenLe)
                        val editable = binding.editor.text
                        if (editable != null) {
                            val converted = FollowupEncoding.convertLineEndings(
                                editable.toString(), chosenLe == "CRLF"
                            )
                            if (converted != editable.toString()) {
                                editable.replace(0, editable.length, converted)
                            }
                        }
                        updateEncodingStatus()
                        Toast.makeText(
                            this, "Encoding $chosenEnc, $chosenLe — applies on save", Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // (20) Share file out: saved file ka SAF uri share intent se, untitled
    // file ka text share intent se.
    private fun showFollowupShare() {
        val tab = openTabs.getOrNull(activeTabIndex) ?: run {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        if (tab.uri != null) {
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_STREAM, tab.uri)
            intent.putExtra(Intent.EXTRA_SUBJECT, tab.name)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, binding.editor.text?.toString() ?: "")
            intent.putExtra(Intent.EXTRA_SUBJECT, tab.name)
        }
        startActivity(Intent.createChooser(intent, "Share file"))
    }

    // (21) Editor tour: steps dialog sequence; Skip/Done par dobara nahi.
    private fun showFollowupTour(force: Boolean) {
        if (!force && FollowupStore.isTourShown(this)) return
        fun showStep(index: Int) {
            if (index >= FollowupTour.steps.size) {
                FollowupStore.setTourShown(this)
                return
            }
            val step = FollowupTour.steps[index]
            val last = index == FollowupTour.steps.size - 1
            MaterialAlertDialogBuilder(this)
                .setTitle(step.title)
                .setMessage(step.body)
                .setPositiveButton(if (last) "Done" else "Next") { _, _ ->
                    if (last) FollowupStore.setTourShown(this) else showStep(index + 1)
                }
                .setNegativeButton("Skip") { _, _ -> FollowupStore.setTourShown(this) }
                .setCancelable(false)
                .show()
        }
        showStep(0)
    }

    // (22) File info: size, modified date, permissions, path + Copy path.
    private fun showFollowupFileInfo() {
        val tab = openTabs.getOrNull(activeTabIndex) ?: run {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val doc = try {
            tab.uri?.let { DocumentFile.fromSingleUri(this, it) }
        } catch (e: Exception) {
            null
        }
        val sizeBytes: Long = try {
            doc?.length() ?: binding.editor.text?.toString()?.toByteArray()?.size?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
        val sizeText = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        }
        val modifiedText = try {
            val m = doc?.lastModified() ?: 0L
            if (m > 0) {
                SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(m))
            } else {
                "Unsaved"
            }
        } catch (e: Exception) {
            "Unknown"
        }
        val permsText = if (doc == null) {
            "Unsaved file"
        } else {
            val read = if (doc.canRead()) "Read" else "No read"
            val write = if (doc.canWrite()) "Write" else "No write"
            "$read, $write"
        }
        val pathText = tab.path ?: tab.uri?.toString() ?: "Unsaved file"
        val message = "Name: ${tab.name}\nSize: $sizeText\nModified: $modifiedText\n" +
            "Permissions: $permsText\nPath: $pathText"
        MaterialAlertDialogBuilder(this)
            .setTitle("File info")
            .setMessage(message)
            .setPositiveButton("Copy path") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("File path", pathText))
                Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // Save hook (19): line ending save par lagu hoti hai (encoding byte
    // write saveLocalTabContent me hota hai).
    private fun applyFollowupSaveText(tab: EditorTab, text: String): String {
        val ending = FollowupStore.lineEndingFor(this, tab.uri?.toString())
        return FollowupEncoding.convertLineEndings(text, ending == "CRLF")
    }

}
