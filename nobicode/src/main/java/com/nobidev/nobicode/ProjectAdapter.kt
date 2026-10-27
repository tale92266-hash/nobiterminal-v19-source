package com.nobidev.nobicode

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ProjectAdapter(
    private var projects: List<Project>,
    private val onProjectClick: (Project) -> Unit,
    private val onProjectMenu: (Project, View) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.projName)
        val path: TextView = view.findViewById(R.id.projPath)
        val lastOpened: TextView = view.findViewById(R.id.projLastOpened)
        val menu: ImageView = view.findViewById(R.id.projMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val project = projects[position]
        holder.name.text = project.name
        holder.path.text = project.path
        
        val timeStr = DateUtils.getRelativeTimeSpanString(
            project.lastOpened,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        holder.lastOpened.text = "Opened $timeStr"
        
        holder.itemView.setOnClickListener { onProjectClick(project) }
        holder.menu.setOnClickListener { onProjectMenu(project, holder.menu) }
    }

    override fun getItemCount() = projects.size

    fun updateProjects(newProjects: List<Project>) {
        projects = newProjects
        notifyDataSetChanged()
    }
}
