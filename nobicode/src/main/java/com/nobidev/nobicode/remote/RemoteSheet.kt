package com.nobidev.nobicode.remote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nobidev.nobicode.MainActivity
import com.nobidev.nobicode.R
import com.nobidev.nobicode.model.RemoteConnection
import kotlinx.coroutines.launch

/** Bottom sheet: saved FTP/SFTP connections, add/edit/delete, connect. */
class RemoteSheet(private val activity: MainActivity) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_remote, null)
        dialog.setContentView(view)

        val statusText: TextView = view.findViewById(R.id.remoteStatusText)
        val list: RecyclerView = view.findViewById(R.id.remoteConnList)
        val addBtn: MaterialButton = view.findViewById(R.id.remoteAddBtn)
        val disconnectBtn: MaterialButton = view.findViewById(R.id.remoteDisconnectBtn)

        list.layoutManager = LinearLayoutManager(activity)
        val conns = RemotePrefs.load(activity)
        lateinit var adapter: ConnAdapter
        adapter = ConnAdapter(conns,
            onConnect = { conn ->
                dialog.dismiss()
                activity.lifecycleScope.launch {
                    Toast.makeText(activity, "Connecting...", Toast.LENGTH_SHORT).show()
                    RemoteManager.connect(conn)
                        .onSuccess {
                            Toast.makeText(activity, "Connected to ${conn.name}", Toast.LENGTH_SHORT).show()
                            activity.enterRemoteMode(conn)
                        }
                        .onFailure { e ->
                            Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            },
            onEdit = { conn ->
                showEditDialog(conn) {
                    RemotePrefs.save(activity, conns)
                    adapter.refresh()
                }
            },
            onDelete = { conn ->
                conns.remove(conn)
                RemotePrefs.save(activity, conns)
                adapter.refresh()
            }
        )
        list.adapter = adapter

        fun refreshStatus() {
            val active = RemoteManager.activeConnection
            if (RemoteManager.isConnected && active != null) {
                statusText.text = "Connected: ${active.name} (${active.username}@${active.host})"
                disconnectBtn.visibility = View.VISIBLE
            } else {
                statusText.text = "Not connected"
                disconnectBtn.visibility = View.GONE
            }
            adapter.refresh()
        }

        disconnectBtn.setOnClickListener {
            RemoteManager.disconnect()
            activity.exitRemoteMode()
            refreshStatus()
            Toast.makeText(activity, "Disconnected", Toast.LENGTH_SHORT).show()
        }

        // reload wrapper for add
        refreshStatus()
        dialog.setOnDismissListener { }
        dialog.show()

        // hook add to reload list after
        addBtn.setOnClickListener {
            showEditDialog(null) {
                conns.clear()
                conns.addAll(RemotePrefs.load(activity))
                adapter.refresh()
            }
        }
    }

    private fun showEditDialog(existing: RemoteConnection?, onSaved: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_remote_edit, null)
        val nameEt: EditText = view.findViewById(R.id.reName)
        val protoSp: Spinner = view.findViewById(R.id.reProtocol)
        val hostEt: EditText = view.findViewById(R.id.reHost)
        val portEt: EditText = view.findViewById(R.id.rePort)
        val userEt: EditText = view.findViewById(R.id.reUser)
        val passEt: EditText = view.findViewById(R.id.rePass)

        val protocols = arrayOf("SFTP", "FTP")
        protoSp.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, protocols)

        if (existing != null) {
            nameEt.setText(existing.name)
            protoSp.setSelection(if (existing.protocol == "FTP") 1 else 0)
            hostEt.setText(existing.host)
            portEt.setText(existing.port.toString())
            userEt.setText(existing.username)
            passEt.setText(existing.password)
        } else {
            portEt.setText("22")
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) "Add connection" else "Edit connection")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEt.text.toString().trim().ifBlank { hostEt.text.toString().trim() }
                val host = hostEt.text.toString().trim()
                if (host.isEmpty()) {
                    Toast.makeText(activity, "Host required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val protocol = protocols[protoSp.selectedItemPosition]
                val port = portEt.text.toString().toIntOrNull()
                    ?: if (protocol == "FTP") 21 else 22
                val conns = RemotePrefs.load(activity)
                if (existing != null) {
                    val idx = conns.indexOfFirst { it.id == existing.id }
                    if (idx != -1) {
                        conns[idx] = existing.copy(
                            name = name, protocol = protocol, host = host,
                            port = port, username = userEt.text.toString().trim(),
                            password = passEt.text.toString()
                        )
                    }
                } else {
                    conns.add(
                        RemoteConnection(
                            name = name, protocol = protocol, host = host,
                            port = port, username = userEt.text.toString().trim(),
                            password = passEt.text.toString()
                        )
                    )
                }
                RemotePrefs.save(activity, conns)
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class ConnAdapter(
        private val items: MutableList<RemoteConnection>,
        private val onConnect: (RemoteConnection) -> Unit,
        private val onEdit: (RemoteConnection) -> Unit,
        private val onDelete: (RemoteConnection) -> Unit
    ) : RecyclerView.Adapter<ConnAdapter.VH>() {

        fun refresh() { notifyDataSetChanged() }

        override fun onCreateViewHolder(p: ViewGroup, v: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_remote_conn, p, false)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])
        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val name: TextView = v.findViewById(R.id.connName)
            private val detail: TextView = v.findViewById(R.id.connDetail)
            private val badge: TextView = v.findViewById(R.id.connActiveBadge)
            fun bind(c: RemoteConnection) {
                name.text = c.name.ifBlank { c.host }
                detail.text = "${c.protocol} - ${c.username}@${c.host}:${c.port}"
                val isActive = RemoteManager.activeConnection?.id == c.id && RemoteManager.isConnected
                badge.visibility = if (isActive) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onConnect(c) }
                itemView.setOnLongClickListener {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(c.name.ifBlank { c.host })
                        .setItems(arrayOf("Edit", "Delete")) { _, which ->
                            if (which == 0) onEdit(c) else onDelete(c)
                        }
                        .show()
                    true
                }
            }
        }
    }
}

