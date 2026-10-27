package com.nobidev.nobicode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import com.nobidev.nobicode.icons.FileIconProvider

sealed class BrowserItem {
    object BackItem : BrowserItem()
    data class FileItem(val doc: DocumentFile) : BrowserItem()
}

class BrowserAdapter(
    private var items: List<BrowserItem>,
    private val onFolderClick: (DocumentFile) -> Unit,
    private val onFileClick: (DocumentFile) -> Unit,
    private val onBackClick: () -> Unit = {}
) : RecyclerView.Adapter<BrowserAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.bfIcon)
        val name: TextView = view.findViewById(R.id.bfName)
        val arrow: ImageView = view.findViewById(R.id.bfArrow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browser_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = items[position]) {
            is BrowserItem.BackItem -> {
                holder.icon.setImageResource(R.drawable.ic_arrow_back)
                holder.name.text = ".."
                holder.arrow.visibility = View.GONE
                holder.itemView.setOnClickListener { onBackClick() }
            }
            is BrowserItem.FileItem -> {
                val file = item.doc
                val name = file.name ?: "Unknown"
                holder.name.text = name
                if (file.isDirectory) {
                    holder.icon.setImageResource(FileIconProvider.getFolderIcon(name, false))
                    holder.arrow.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener { onFolderClick(file) }
                } else {
                    holder.icon.setImageResource(FileIconProvider.getFileIcon(name))
                    holder.arrow.visibility = View.GONE
                    holder.itemView.setOnClickListener { onFileClick(file) }
                }
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<BrowserItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
