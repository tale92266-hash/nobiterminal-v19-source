package com.nobidev.nobicode

import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.nobidev.nobicode.icons.FileIconProvider
import com.nobidev.nobicode.model.FileItem

class FileListAdapter(
    private var items: List<FileItem> = emptyList(),
    private var highlightUri: Uri? = null,
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit,
    // v6 tree sidebar: tap on a directory's arrow expands/collapses it inline
    // (row tap still navigates, as before). Null = no tree behavior.
    private val onArrowClick: ((FileItem) -> Unit)? = null
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    fun submitList(newItems: List<FileItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setHighlightUri(uri: Uri?) {
        if (highlightUri == uri) return
        highlightUri = uri
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_row, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileIcon: ImageView = itemView.findViewById(R.id.fileIcon)
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val folderArrow: ImageView = itemView.findViewById(R.id.folderArrow)
        // Layout's own start padding (18dp) — depth indent adds on top of it.
        // Captured once: bind() runs on recycled holders, so reading the
        // live padding here would compound the indent on every rebind.
        private val baseStartPadding: Int = itemView.paddingStart

        fun bind(item: FileItem) {
            fileName.text = item.name

            // v6 tree sidebar: indent by depth; expanded dirs point down.
            // (Named indentDensity: bind() already declares `density` below
            // for the highlight margins — a second `density` would not compile.)
            val indentDensity = itemView.resources.displayMetrics.density
            itemView.setPaddingRelative(
                baseStartPadding + (item.depth * 18 * indentDensity).toInt(),
                itemView.paddingTop,
                itemView.paddingEnd,
                itemView.paddingBottom
            )

            if (item.isParent) {
                fileIcon.setImageResource(R.drawable.ic_folder_parent)
                fileIcon.colorFilter = null
                folderArrow.visibility = View.GONE
                fileName.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary))
            } else if (item.isDirectory) {
                fileIcon.setImageResource(FileIconProvider.getFolderIcon(item.name, false))
                fileIcon.colorFilter = null
                folderArrow.visibility = View.VISIBLE
                folderArrow.rotation = if (item.isExpanded) 90f else 0f
                folderArrow.setOnClickListener { onArrowClick?.invoke(item) }
                fileName.setTextColor(ContextCompat.getColor(itemView.context, R.color.on_surface))
            } else {
                fileIcon.setImageResource(FileIconProvider.getFileIcon(item.name))
                fileIcon.colorFilter = null
                // v6 fix: recycled dir holders used to leak a visible arrow
                // onto file rows (the file branch never hid it).
                folderArrow.visibility = View.GONE
                fileName.setTextColor(ContextCompat.getColor(itemView.context, R.color.on_surface))
            }

            // Highlight the file currently open in the editor: floating rounded pill
            val lp = itemView.layoutParams as? ViewGroup.MarginLayoutParams
            val density = itemView.resources.displayMetrics.density
            if (!item.isParent && !item.isDirectory && item.uri == highlightUri) {
                itemView.setBackgroundResource(R.drawable.bg_file_row_selected)
                val mH = (10 * density).toInt()
                val mV = (2 * density).toInt()
                lp?.setMargins(mH, mV, mH, mV)
                fileName.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary))
            } else {
                val outValue = TypedValue()
                itemView.context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, outValue, true
                )
                itemView.setBackgroundResource(outValue.resourceId)
                lp?.setMargins(0, 0, 0, 0)
            }

            itemView.setOnClickListener {
                onItemClick(item)
            }

            itemView.setOnLongClickListener {
                if (!item.isParent) {
                    onItemLongClick(item)
                }
                true
            }
        }
    }
}
