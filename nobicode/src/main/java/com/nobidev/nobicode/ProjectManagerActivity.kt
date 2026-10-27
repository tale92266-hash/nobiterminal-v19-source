package com.nobidev.nobicode

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.RecyclerView
import androidx.documentfile.provider.DocumentFile

class ProjectManagerActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var adapter: ProjectAdapter
    private var browserMode = false
    private var currentProject: Project? = null
    private val folderStack = ArrayDeque<DocumentFile>()
    private lateinit var browserAdapter: BrowserAdapter

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistable permission
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Get folder name
            val name = getFolderName(uri)
            // Save as project
            ProjectManager.addProject(this, name, uri.toString())
            // Open in MainActivity
            openProject(uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.pmToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.pmList)
        emptyView = findViewById(R.id.pmEmpty)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = ProjectAdapter(
            emptyList(),
            onProjectClick = { project ->
                ProjectManager.updateLastOpened(this, project.path)
                enterBrowserMode(project)
            },
            onProjectMenu = { project, view ->
                showProjectMenu(project, view)
            }
        )
        recycler.adapter = adapter
        browserAdapter = BrowserAdapter(
            emptyList(),
            onFolderClick = { folder ->
                folderStack.add(folder)
                showBrowser()
            },
            onFileClick = { file ->
                currentProject?.let { openFileInEditor(it, file) }
            },
            onBackClick = {
                if (folderStack.size > 1) {
                    folderStack.removeLast()
                    showBrowser()
                }
            }
        )

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.pm_add_folder -> {
                    folderPicker.launch(null)
                    true
                }
                else -> false
            }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val projects = ProjectManager.getProjects(this)
        adapter.updateProjects(projects)
        emptyView.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (projects.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openProject(uriString: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("project_uri", uriString)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun getFolderName(uri: Uri): String {
        // Try to get display name from DocumentsContract
        var name = "Project"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx) ?: "Project"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Fallback: last path segment
        if (name == "Project") {
            name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Project"
        }
        return name
    }

    private fun showProjectMenu(project: Project, view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("Rename")
        popup.menu.add("Remove")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Rename" -> showRenameDialog(project)
                "Remove" -> {
                    ProjectManager.removeProject(this, project.path)
                    refreshList()
                    Toast.makeText(this, "Project removed", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        popup.show()
    }

    private fun showRenameDialog(project: Project) {
        val input = EditText(this).apply {
            setText(project.name)
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Project")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    ProjectManager.renameProject(this, project.path, newName)
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun enterBrowserMode(project: Project) {
        try {
            val uri = Uri.parse(project.path)
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root == null || !root.exists()) {
                Toast.makeText(this, "Folder not accessible", Toast.LENGTH_SHORT).show()
                return
            }
            browserMode = true
            currentProject = project
            folderStack.clear()
            folderStack.add(root)
            findViewById<MaterialToolbar>(R.id.pmToolbar).title = project.name
            showBrowser()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private var browserLoadId = 0
    private fun showBrowser() {
        val current = folderStack.lastOrNull() ?: return
        recycler.adapter = browserAdapter
        // Add button is now in toolbar, no need to hide
        // Clear immediately for fast feel
        browserAdapter.updateItems(emptyList())
        emptyView.visibility = View.GONE
        val loadId = ++browserLoadId
        // Load in background - cancel if user navigated away
        Thread {
            val items = mutableListOf<BrowserItem>()
            try {
                // Add ".." back item if not at root
                if (folderStack.size > 1) {
                    items.add(BrowserItem.BackItem)
                }
                val files = current.listFiles()
                // Pre-compute names once to avoid repeated DocumentFile calls
                val fileInfos = files.mapNotNull { f ->
                    val name = f.name ?: return@mapNotNull null
                    Triple(f, name, f.isDirectory)
                }
                val folders = fileInfos.filter { it.third }.sortedBy { it.second.lowercase() }
                val fileItems = fileInfos.filter { !it.third }.sortedBy { it.second.lowercase() }
                for ((doc, _, _) in folders) items.add(BrowserItem.FileItem(doc))
                for ((doc, _, _) in fileItems) items.add(BrowserItem.FileItem(doc))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Only update if this is still the current load
            if (loadId == browserLoadId) {
                runOnUiThread {
                    browserAdapter.updateItems(items)
                    emptyView.visibility = if (items.isEmpty() || (items.size == 1 && items[0] is BrowserItem.BackItem)) View.VISIBLE else View.GONE
                }
            }
        }.start()
    }

    private fun exitBrowserMode() {
        browserMode = false
        currentProject = null
        folderStack.clear()
        findViewById<MaterialToolbar>(R.id.pmToolbar).title = "Projects"
        // Add button is now in toolbar
        recycler.adapter = adapter
        refreshList()
    }

    override fun onBackPressed() {
        if (browserMode) {
            if (folderStack.size > 1) {
                folderStack.removeLast()
                showBrowser()
            } else {
                exitBrowserMode()
            }
        } else {
            super.onBackPressed()
        }
    }

    private fun openFileInEditor(project: Project, file: DocumentFile) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("project_uri", project.path)
            putExtra("open_file_uri", file.uri.toString())
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_PROJECT_URI = "project_uri"
    }
}
