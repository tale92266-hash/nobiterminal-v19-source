package com.nobidev.nobicode.model

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.nobidev.nobicode.R

data class MenuItemData(val title: String, val iconRes: Int)

class MenuActionAdapter(context: Context, items: List<MenuItemData>) :
    ArrayAdapter<MenuItemData>(context, R.layout.dialog_menu_item, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.dialog_menu_item, parent, false)
        val item = getItem(position)
        if (item != null) {
            view.findViewById<ImageView>(R.id.menuItemIcon).setImageResource(item.iconRes)
            view.findViewById<TextView>(R.id.menuItemText).text = item.title
        }
        return view
    }
}
