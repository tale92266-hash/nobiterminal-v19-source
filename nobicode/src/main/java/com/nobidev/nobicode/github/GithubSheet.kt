package com.nobidev.nobicode.github

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.nobidev.nobicode.MainActivity
import com.nobidev.nobicode.R
import com.nobidev.nobicode.git.GitManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub: clone a repo into the editor and sync it (pull).
 * Clone uses JGit over https; an optional personal access token is asked
 * each time and is NEVER persisted. Repo info (stars/description) comes
 * from the public GitHub API without any key. Private repos need a token
 * with repo scope, entered at clone time.
 */
class GithubSheet(private val activity: MainActivity) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_github, null)
        dialog.setContentView(view)

        val urlEt: EditText = view.findViewById(R.id.ghUrl)
        val tokenEt: EditText = view.findViewById(R.id.ghToken)
        val infoText: TextView = view.findViewById(R.id.ghInfo)
        val cloneBtn: MaterialButton = view.findViewById(R.id.ghCloneBtn)
        val syncBtn: MaterialButton = view.findViewById(R.id.ghSyncBtn)

        cloneBtn.setOnClickListener {
            val url = urlEt.text.toString().trim()
            if (url.isEmpty()) {
                urlEt.error = "Enter a repository URL"
                return@setOnClickListener
            }
            val token = tokenEt.text.toString().trim()
            cloneBtn.isEnabled = false
            infoText.text = "Cloning…"
            activity.lifecycleScope.launch {
                val repoInfo = fetchRepoInfo(url)
                if (repoInfo != null) infoText.text = repoInfo
                val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
                    .ifBlank { "repo" }
                val target = File(activity.filesDir, "github/" + name)
                val result = GitManager.clone(
                    url, target,
                    if (token.isNotBlank()) "x-access-token" else "",
                    token
                )
                result.onSuccess {
                    infoText.text = "Cloned to " + target.absolutePath
                    Toast.makeText(activity, "Cloned " + name, Toast.LENGTH_SHORT).show()
                    activity.openGithubFolder(DocumentFile.fromFile(target))
                    dialog.dismiss()
                }.onFailure { e ->
                    infoText.text = "Clone failed: " + e.message
                    cloneBtn.isEnabled = true
                }
            }
        }

        syncBtn.setOnClickListener {
            val token = tokenEt.text.toString().trim()
            syncBtn.isEnabled = false
            infoText.text = "Syncing…"
            activity.lifecycleScope.launch {
                val dir = activity.currentGithubDir()
                if (dir == null) {
                    infoText.text = "Open a cloned GitHub folder first"
                    syncBtn.isEnabled = true
                    return@launch
                }
                val result = GitManager.pull(
                    dir,
                    if (token.isNotBlank()) "x-access-token" else "",
                    token
                )
                result.onSuccess { msg ->
                    infoText.text = "Synced: " + msg
                    Toast.makeText(activity, "Synced", Toast.LENGTH_SHORT).show()
                    activity.refreshCurrentFolder()
                }.onFailure { e ->
                    infoText.text = "Sync failed: " + e.message
                }
                syncBtn.isEnabled = true
            }
        }

        dialog.show()
    }

    /** "owner/repo" from a github.com URL, or null. */
    private fun ownerRepo(url: String): String? {
        val m = Regex("github\\.com[:/]([^/]+/[^/]+?)(?:\\.git)?/?$").find(url.trim())
        return m?.groupValues?.get(1)
    }

    private suspend fun fetchRepoInfo(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val orr = ownerRepo(url) ?: return@withContext null
            val conn = URL("https://api.github.com/repos/" + orr).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "NobiCode")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val stars = json.optInt("stargazers_count", -1)
            val desc = json.optString("description", "")
            val starPart = if (stars >= 0) "★ $stars" else ""
            listOf(json.optString("full_name", orr), starPart, desc)
                .filter { it.isNotBlank() }.joinToString(" — ")
        } catch (e: Exception) {
            null
        }
    }
}
