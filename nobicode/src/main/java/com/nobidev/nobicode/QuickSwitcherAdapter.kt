package com.nobidev.nobicode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nobidev.nobicode.icons.FileIconProvider

data class QuickSwitchItem(
    val name: String,
    val path: String?,
    val isOpen: Boolean,
    val tabIndex: Int = -1
)

class QuickSwitcherAdapter(
    private var items: List<QuickSwitchItem>,
    private val onItemClick: (QuickSwitchItem) -> Unit
) : RecyclerView.Adapter<QuickSwitcherAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.qsIcon)
        val name: TextView = view.findViewById(R.id.qsName)
        val path: TextView = view.findViewById(R.id.qsPath)
        val badge: TextView = view.findViewById(R.id.qsBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_switcher, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(FileIconProvider.getFileIcon(item.name))
        holder.name.text = item.name
        holder.path.text = item.path ?: ""
        holder.path.visibility = if (item.path.isNullOrEmpty()) View.GONE else View.VISIBLE
        holder.badge.visibility = if (item.isOpen) View.VISIBLE else View.GONE
        
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<QuickSwitchItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
