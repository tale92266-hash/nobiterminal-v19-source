package com.nobidev.nobicode.plugins

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nobidev.nobicode.R
import java.io.File

/**
 * Plugin store UI: installed plugins with enable/disable, import from a
 * plugin.json/.zip file, delete. No network store: plugins are JSON
 * manifests by design (no code execution), installed from files the user
 * picks. A remote plugin catalogue is pending (needs Sam's call on where
 * it should live).
 */
class PluginActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var importBtn: Button
    private var plugins: List<PluginManager.Plugin> = emptyList()

    companion object {
        private const val REQ_IMPORT = 7001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)
        title = "Plugins"
        list = findViewById(R.id.pluginList)
        emptyText = findViewById(R.id.pluginEmpty)
        importBtn = findViewById(R.id.pluginImportBtn)
        list.layoutManager = LinearLayoutManager(this)
        PluginManager.installSamplesIfEmpty(this)
        PluginManager.refreshThemes(this)
        refresh()
        importBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/zip"))
            startActivityForResult(intent, REQ_IMPORT)
        }
    }

    private fun refresh() {
        plugins = PluginManager.list(this)
        emptyText.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = PluginAdapter(plugins,
            onToggle = { plugin, enabled ->
                PluginManager.setEnabled(this, plugin, enabled)
                Toast.makeText(this, plugin.name + if (enabled) " enabled" else " disabled", Toast.LENGTH_SHORT).show()
            },
            onDelete = { plugin ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete " + plugin.name + "?")
                    .setPositiveButton("Delete") { _, _ ->
                        if (PluginManager.delete(this, plugin)) refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val tmp = File(cacheDir, "plugin_import_" + System.currentTimeMillis())
            contentResolver.openInputStream(uri)?.use { inp ->
                tmp.outputStream().use { o -> inp.copyTo(o) }
            }
            // A bare plugin.json has no extension hint; sniff the magic bytes.
            var isZip = tmp.name.endsWith(".zip", ignoreCase = true)
            if (!isZip) {
                try {
                    tmp.inputStream().use { inp ->
                        val b0 = inp.read()
                        val b1 = inp.read()
                        isZip = (b0 == 0x50 && b1 == 0x4B)
                    }
                } catch (e: Exception) {
                }
            }
            val result = PluginManager.install(this, tmp)
            result.onSuccess { id ->
                Toast.makeText(this, "Installed plugin: $id", Toast.LENGTH_SHORT).show()
                PluginManager.refreshThemes(this)
                refresh()
            }.onFailure { e ->
                Toast.makeText(this, "Install failed: " + e.message, Toast.LENGTH_LONG).show()
            }
            tmp.delete()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private inner class PluginAdapter(
        private val items: List<PluginManager.Plugin>,
        private val onToggle: (PluginManager.Plugin, Boolean) -> Unit,
        private val onDelete: (PluginManager.Plugin) -> Unit
    ) : RecyclerView.Adapter<PluginAdapter.VH>() {

        override fun onCreateViewHolder(p: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_plugin, p, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])
        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val name: TextView = v.findViewById(R.id.pluginName)
            private val detail: TextView = v.findViewById(R.id.pluginDetail)
            private val toggle: Switch = v.findViewById(R.id.pluginToggle)
            private val deleteBtn: Button = v.findViewById(R.id.pluginDeleteBtn)
            fun bind(p: PluginManager.Plugin) {
                name.text = p.name
                detail.text = p.type + " · v" + p.version + " · " + p.id
                toggle.isChecked = p.enabled
                toggle.setOnCheckedChangeListener { _, checked -> onToggle(p, checked) }
                deleteBtn.setOnClickListener { onDelete(p) }
            }
        }
    }
}
