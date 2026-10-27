package com.nobidev.nobicode.git

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.nobidev.nobicode.MainActivity
import com.nobidev.nobicode.R
import kotlinx.coroutines.launch
import java.io.File

/** Bottom sheet for Git source control: status, commit, push/pull, branches, clone. */
class GitSheet(private val activity: MainActivity) {

    private var gitUser: String = ""
    private var gitPass: String = ""

    fun show() {
        val repoDir = activity.getRepoDirForGit()
        if (repoDir == null) {
            Toast.makeText(activity, "Open a folder first", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_git, null)
        dialog.setContentView(view)

        val repoName: TextView = view.findViewById(R.id.gitRepoName)
        val branchChip: TextView = view.findViewById(R.id.gitBranchChip)
        val changesLabel: TextView = view.findViewById(R.id.gitChangesLabel)
        val changesList: RecyclerView = view.findViewById(R.id.gitChangesList)
        val commitMsg: TextInputEditText = view.findViewById(R.id.gitCommitMsg)
        val commitBtn: MaterialButton = view.findViewById(R.id.gitCommitBtn)
        val amendCheck: com.google.android.material.checkbox.MaterialCheckBox = view.findViewById(R.id.gitAmendCheck)
        val pullBtn: MaterialButton = view.findViewById(R.id.gitPullBtn)
        val pushBtn: MaterialButton = view.findViewById(R.id.gitPushBtn)
        val cloneSection: View = view.findViewById(R.id.gitCloneSection)
        val cloneUrl: TextInputEditText = view.findViewById(R.id.gitCloneUrl)
        val cloneBtn: MaterialButton = view.findViewById(R.id.gitCloneBtn)

        repoName.text = repoDir.name
        changesList.layoutManager = LinearLayoutManager(activity)
        val adapter = GitChangeAdapter()
        changesList.adapter = adapter

        var currentBranch = "main"

        fun refresh() {
            activity.lifecycleScope.launch {
                val result = GitManager.status(repoDir)
                result.onSuccess { info ->
                    currentBranch = info.branch
                    branchChip.text = info.branch
                    val n = info.changes.size
                    changesLabel.text = if (n == 0) "Changes - working tree clean" else "Changes ($n)"
                    adapter.submit(info.changes)
                    val isRepo = true
                    cloneSection.visibility = View.GONE
                    commitBtn.isEnabled = n > 0
                }.onFailure {
                    // Not a repo (or unreadable) -> show clone UI
                    branchChip.text = "-"
                    changesLabel.text = "Changes"
                    adapter.submit(emptyList())
                    cloneSection.visibility = View.VISIBLE
                    commitBtn.isEnabled = false
                }
            }
        }

        branchChip.setOnClickListener { showBranchDialog(repoDir) { refresh() } }

        commitBtn.setOnClickListener {
            val msg = commitMsg.text?.toString()?.trim().orEmpty()
            if (msg.isEmpty()) {
                commitMsg.error = "Enter a commit message"
                return@setOnClickListener
            }
            commitBtn.isEnabled = false
            activity.lifecycleScope.launch {
                val stageRes = GitManager.stageAll(repoDir)
                if (stageRes.isFailure) {
                    Toast.makeText(activity, "Stage failed", Toast.LENGTH_SHORT).show()
                    commitBtn.isEnabled = true
                    return@launch
                }
                GitManager.commit(repoDir, msg, gitUser.ifBlank { "NobiCode" }, amendCheck.isChecked)
                    .onSuccess {
                        Toast.makeText(activity, "Committed", Toast.LENGTH_SHORT).show()
                        commitMsg.setText("")
                    }
                    .onFailure { e ->
                        Toast.makeText(activity, "Commit failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                commitBtn.isEnabled = true
                refresh()
            }
        }

        pullBtn.setOnClickListener {
            askCredentials("Pull") { u, p ->
                activity.lifecycleScope.launch {
                    Toast.makeText(activity, "Pulling...", Toast.LENGTH_SHORT).show()
                    GitManager.pull(repoDir, u, p)
                        .onSuccess { Toast.makeText(activity, "Pull: $it", Toast.LENGTH_SHORT).show() }
                        .onFailure { e -> Toast.makeText(activity, "Pull failed: ${e.message}", Toast.LENGTH_LONG).show() }
                    refresh()
                }
            }
        }

        pushBtn.setOnClickListener {
            askCredentials("Push") { u, p ->
                activity.lifecycleScope.launch {
                    Toast.makeText(activity, "Pushing...", Toast.LENGTH_SHORT).show()
                    GitManager.push(repoDir, u, p)
                        .onSuccess { Toast.makeText(activity, "Pushed", Toast.LENGTH_SHORT).show() }
                        .onFailure { e -> Toast.makeText(activity, "Push failed: ${e.message}", Toast.LENGTH_LONG).show() }
                    refresh()
                }
            }
        }

        cloneBtn.setOnClickListener {
            val url = cloneUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                cloneUrl.error = "Enter repository URL"
                return@setOnClickListener
            }
            askCredentials("Clone") { u, p ->
                cloneBtn.isEnabled = false
                activity.lifecycleScope.launch {
                    Toast.makeText(activity, "Cloning...", Toast.LENGTH_SHORT).show()
                    // Clone into a subfolder named after the repo
                    val repoNameFromUrl = url.substringAfterLast("/").removeSuffix(".git").ifBlank { "repo" }
                    val target = File(repoDir, repoNameFromUrl)
                    GitManager.clone(url, target, u, p)
                        .onSuccess {
                            Toast.makeText(activity, "Cloned into ${target.name}", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                        .onFailure { e ->
                            Toast.makeText(activity, "Clone failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    cloneBtn.isEnabled = true
                }
            }
        }

        refresh()
        dialog.show()
    }

    private fun showBranchDialog(repoDir: File, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            val branches = GitManager.branches(repoDir).getOrElse {
                Toast.makeText(activity, "Could not list branches", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val items = (branches + "+ Create new branch").toTypedArray()
            MaterialAlertDialogBuilder(activity)
                .setTitle("Branches")
                .setItems(items) { _, which ->
                    if (which < branches.size) {
                        activity.lifecycleScope.launch {
                            GitManager.checkout(repoDir, branches[which])
                                .onSuccess { Toast.makeText(activity, "Switched to ${branches[which]}", Toast.LENGTH_SHORT).show(); onDone() }
                                .onFailure { e -> Toast.makeText(activity, "Checkout failed: ${e.message}", Toast.LENGTH_LONG).show() }
                        }
                    } else {
                        val input = EditText(activity)
                        input.hint = "New branch name"
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("Create branch")
                            .setView(input)
                            .setPositiveButton("Create") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    activity.lifecycleScope.launch {
                                        GitManager.createBranch(repoDir, name)
                                            .onSuccess { Toast.makeText(activity, "Created $name", Toast.LENGTH_SHORT).show(); onDone() }
                                            .onFailure { e -> Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show() }
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                .show()
        }
    }

    private fun askCredentials(title: String, onOk: (String, String) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_credentials, null)
        val userEt: EditText = view.findViewById(R.id.credUser)
        val passEt: EditText = view.findViewById(R.id.credPass)
        userEt.setText(gitUser)
        MaterialAlertDialogBuilder(activity)
            .setTitle("$title - credentials")
            .setMessage("Use a personal access token as password for GitHub/GitLab.")
            .setView(view)
            .setPositiveButton("OK") { _, _ ->
                gitUser = userEt.text.toString().trim()
                gitPass = passEt.text.toString()
                onOk(gitUser, gitPass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class GitChangeAdapter : RecyclerView.Adapter<GitChangeAdapter.VH>() {
        private val items = mutableListOf<GitChange>()
        fun submit(list: List<GitChange>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(p: ViewGroup, v: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_git_change, p, false)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])
        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val badge: TextView = v.findViewById(R.id.changeBadge)
            private val path: TextView = v.findViewById(R.id.changePath)
            fun bind(c: GitChange) {
                badge.text = c.type.toString()
                path.text = c.path
                val color = when (c.type) {
                    'A' -> 0xFF4CAF50.toInt()
                    'M' -> 0xFFFF9800.toInt()
                    'D' -> 0xFFF44336.toInt()
                    else -> 0xFF9E9E9E.toInt()
                }
                badge.background.setTint(color)
            }
        }
    }
}

