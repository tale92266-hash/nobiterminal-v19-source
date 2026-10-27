package com.nobidev.nobicode

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Project(
    val name: String,
    val path: String,
    val lastOpened: Long = System.currentTimeMillis()
)

object ProjectManager {
    private const val PREFS = "nobi_projects"
    private const val KEY_PROJECTS = "projects"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun getProjects(context: Context): MutableList<Project> {
        val json = prefs(context).getString(KEY_PROJECTS, "[]") ?: "[]"
        val list = mutableListOf<Project>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Project(
                    name = obj.optString("name"),
                    path = obj.optString("path"),
                    lastOpened = obj.optLong("lastOpened", 0)
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Sort by last opened (recent first)
        return list.sortedByDescending { it.lastOpened }.toMutableList()
    }

    fun addProject(context: Context, name: String, path: String) {
        val projects = getProjects(context)
        // Remove existing with same path
        projects.removeAll { it.path == path }
        // Add to front
        projects.add(0, Project(name, path, System.currentTimeMillis()))
        saveProjects(context, projects)
    }

    fun removeProject(context: Context, path: String) {
        val projects = getProjects(context)
        projects.removeAll { it.path == path }
        saveProjects(context, projects)
    }

    fun renameProject(context: Context, path: String, newName: String) {
        val projects = getProjects(context)
        val idx = projects.indexOfFirst { it.path == path }
        if (idx >= 0) {
            projects[idx] = projects[idx].copy(name = newName)
            saveProjects(context, projects)
        }
    }

    fun updateLastOpened(context: Context, path: String) {
        val projects = getProjects(context)
        val idx = projects.indexOfFirst { it.path == path }
        if (idx >= 0) {
            projects[idx] = projects[idx].copy(lastOpened = System.currentTimeMillis())
            saveProjects(context, projects)
        }
    }

    private fun saveProjects(context: Context, projects: List<Project>) {
        val arr = JSONArray()
        for (p in projects) {
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("path", p.path)
            obj.put("lastOpened", p.lastOpened)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_PROJECTS, arr.toString()).apply()
    }
}
