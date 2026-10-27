package com.nobidev.nobicode.git

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Status of a single changed file. */
data class GitChange(val path: String, val type: Char) // M, A, D, ?

data class GitStatusInfo(
    val branch: String,
    val changes: List<GitChange>,
    val ahead: Int = 0,
    val behind: Int = 0
)

/**
 * JGit wrapper. All operations run on Dispatchers.IO and return Result.
 * Works with SAF tree URIs by resolving primary-storage paths to java.io.File.
 */
object GitManager {

    /** Resolve a SAF tree URI (primary storage) to a java.io.File. Null if not resolvable. */
    fun resolveToFile(uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri) // e.g. "primary:NobiIDE"
            val split = docId.split(":")
            if (split[0].equals("primary", ignoreCase = true)) {
                val rel = if (split.size > 1) split[1] else ""
                File(Environment.getExternalStorageDirectory(), rel)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun isRepo(dir: File): Boolean {
        return try {
            File(dir, ".git").let { it.exists() && it.isDirectory } ||
                RepositoryCache_isGitDir(File(dir, ".git"))
        } catch (e: Exception) {
            false
        }
    }

    private fun RepositoryCache_isGitDir(gitDir: File): Boolean {
        return try {
            FileRepositoryBuilder().apply {
                setGitDir(gitDir)
                readEnvironment()
            }.build().use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun open(dir: File): Git {
        return Git.open(dir)
    }

    suspend fun status(dir: File): Result<GitStatusInfo> = withContext(Dispatchers.IO) {
        runCatching {
            open(dir).use { git ->
                val repo = git.repository
                val branch = try { repo.branch } catch (e: Exception) { "HEAD" }
                val st = git.status().call()
                val changes = mutableListOf<GitChange>()
                st.added.forEach { changes.add(GitChange(it, 'A')) }
                st.changed.forEach { changes.add(GitChange(it, 'M')) }
                st.removed.forEach { changes.add(GitChange(it, 'D')) }
                st.missing.forEach { changes.add(GitChange(it, 'D')) }
                st.untracked.forEach { changes.add(GitChange(it, '?')) }
                st.modified.forEach { if (changes.none { c -> c.path == it }) changes.add(GitChange(it, 'M')) }
                changes.sortBy { it.path }
                GitStatusInfo(branch, changes)
            }
        }
    }

    /** Document URI (single file, primary storage) ko java.io.File me badlo. */
    fun resolveDocToFile(uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            if (split[0].equals("primary", ignoreCase = true)) {
                val rel = if (split.size > 1) split[1] else ""
                File(Environment.getExternalStorageDirectory(), rel)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** HEAD vs current text ki ek diff hunk: type = INSERT/DELETE/REPLACE, lines 0-based. */
    data class DiffHunk(val type: String, val beginB: Int, val endB: Int)

    /**
     * HEAD commit me file ka version vs diya gaya current text - line diff.
     * repoRelPath: repo root se relative path ('/' separator).
     */
    suspend fun diffAgainstHead(repoDir: File, repoRelPath: String, currentText: String): Result<List<DiffHunk>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (currentText.length > 1_000_000) return@runCatching emptyList()
                Git.open(repoDir).use { git ->
                    val repo = git.repository
                    val headId = repo.resolve("HEAD") ?: return@runCatching emptyList()
                    val headText: String? = RevWalk(repo).use { walk ->
                        val commit = walk.parseCommit(headId)
                        TreeWalk(repo).use { tw ->
                            tw.addTree(commit.tree)
                            tw.isRecursive = true
                            tw.filter = PathFilter.create(repoRelPath)
                            if (!tw.next()) return@use null
                            repo.open(tw.getObjectId(0)).bytes.toString(Charsets.UTF_8)
                        }
                    }
                    // Nayi file (HEAD me nahi): saari lines added.
                    if (headText == null) {
                        val b0 = RawText(currentText.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                        val n = b0.size()
                        return@runCatching if (n > 0) listOf(DiffHunk("INSERT", 0, n)) else emptyList()
                    }
                    val a = RawText(headText.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                    val b = RawText(currentText.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                    if (a.size() > 20000 || b.size() > 20000) return@runCatching emptyList()
                    val algorithm = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
                    val editList = algorithm.diff(RawTextComparator.DEFAULT, a, b)
                    editList.map { e -> DiffHunk(e.type.name, e.beginB, e.endB) }
                }
            }
        }

    /** Commit history: naya sabse pehle. */
    data class CommitInfo(
        val hash: String,
        val shortHash: String,
        val message: String,
        val author: String,
        val time: Long
    )

    suspend fun logEntries(dir: File, max: Int = 100): Result<List<CommitInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    git.log().setMaxCount(max).call().map { c ->
                        CommitInfo(
                            hash = c.name,
                            shortHash = c.name.take(7),
                            message = c.fullMessage.trim(),
                            author = c.authorIdent?.name ?: "?",
                            time = c.commitTime * 1000L
                        )
                    }
                }
            }
        }

    /** Stash entry: list me index ke saath. */
    data class StashInfo(val index: Int, val message: String, val time: Long)

    /** Working tree changes ko stash me daalo. */
    suspend fun stashSave(dir: File, message: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    val commit = git.stashCreate()
                        .setWorkingDirectoryMessage(message.ifBlank { "WIP" })
                        .call() ?: throw IllegalStateException("No changes to stash")
                    commit.name.take(7)
                }
            }
        }

    /** Stash list: naya sabse pehle (index 0). */
    suspend fun stashList(dir: File): Result<List<StashInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    git.stashList().call().mapIndexed { i, c ->
                        StashInfo(
                            i,
                            c.fullMessage.trim().ifEmpty { "WIP" },
                            c.commitTime * 1000L
                        )
                    }
                }
            }
        }

    /** Stash apply karo (list me rehta hai). */
    suspend fun stashApply(dir: File, index: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    git.stashApply().setStashRef("stash@{$index}").call()
                    Unit
                }
            }
        }

    /** Stash delete karo. */
    suspend fun stashDrop(dir: File, index: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    git.stashDrop().setStashRef(index).call()
                    Unit
                }
            }
        }

    /** Ek commit ko current branch par cherry-pick karo. */
    suspend fun cherryPick(dir: File, hash: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    val result = git.cherryPick()
                        .include(git.repository.resolve(hash)
                            ?: throw IllegalArgumentException("Commit nahi mila"))
                        .call()
                    if (result.status != org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.OK) {
                        throw IllegalStateException("Cherry-pick fail: " + result.status)
                    }
                    Unit
                }
            }
        }

    /** Ek commit ko revert karo (naya commit banata hai). */
    suspend fun revertCommit(dir: File, hash: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    // JGit 6.7: RevertCommand.call() returns the new
                    // RevCommit (null when the revert fails); the
                    // RevertResult type only exists in newer JGit.
                    val newCommit = git.revert()
                        .include(git.repository.resolve(hash)
                            ?: throw IllegalArgumentException("Commit nahi mila"))
                        .call()
                    if (newCommit == null) {
                        throw IllegalStateException("Revert fail")
                    }
                    Unit
                }
            }
        }

    /** Unified diff (3 context lines) HEAD vs current text. */
    suspend fun unifiedDiff(repoDir: File, repoRelPath: String, currentText: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (currentText.length > 1_000_000) return@runCatching ""
                Git.open(repoDir).use { git ->
                    val repo = git.repository
                    val headId = repo.resolve("HEAD") ?: return@runCatching ""
                    val headText: String? = RevWalk(repo).use { walk ->
                        val commit = walk.parseCommit(headId)
                        TreeWalk(repo).use { tw ->
                            tw.addTree(commit.tree)
                            tw.isRecursive = true
                            tw.filter = PathFilter.create(repoRelPath)
                            if (!tw.next()) return@use null
                            repo.open(tw.getObjectId(0)).bytes.toString(Charsets.UTF_8)
                        }
                    }
                    val a = RawText((headText ?: "").replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                    val b = RawText(currentText.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                    if (a.size() > 20000 || b.size() > 20000) return@runCatching ""
                    val out = ByteArrayOutputStream()
                    val df = DiffFormatter(out)
                    try {
                        df.setRepository(repo)
                        df.setContext(3)
                        val alg = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
                        val edits = alg.diff(RawTextComparator.DEFAULT, a, b)
                        df.format(edits, a, b)
                    } finally {
                        df.close()
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }

    data class BlameInfo(
        val shortHash: String,
        val author: String,
        val timeMillis: Long,
        val message: String
    )

    /** INV-155: ek line ka blame (HEAD version par) — commit/author/time/message. */
    suspend fun blameLine(repoDir: File, repoRelPath: String, line0: Int): Result<BlameInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                Git.open(repoDir).use { git ->
                    val result = git.blame().setFilePath(repoRelPath).call()
                        ?: return@runCatching null
                    if (line0 < 0 || line0 >= result.resultContents.size()) return@runCatching null
                    val commit = result.getSourceCommit(line0) ?: return@runCatching null
                    val author = result.getSourceAuthor(line0)
                    BlameInfo(
                        shortHash = commit.name.take(7),
                        author = author?.name ?: commit.authorIdent?.name ?: "?",
                        timeMillis = (author?.`when`?.time ?: commit.commitTime * 1000L),
                        message = commit.shortMessage ?: ""
                    )
                }
            }
        }

    /** Do text ka unified diff (bina repo ke). */
    fun diffTexts(oldText: String, newText: String): String {
        return try {
            if (oldText.length > 1_000_000 || newText.length > 1_000_000) return ""
            val a = RawText(oldText.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            val b = RawText(newText.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            if (a.size() > 20000 || b.size() > 20000) return ""
            val out = ByteArrayOutputStream()
            val df = DiffFormatter(out)
            try {
                df.setContext(3)
                val alg = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
                val edits = alg.diff(RawTextComparator.DEFAULT, a, b)
                df.format(edits, a, b)
            } finally {
                df.close()
            }
            out.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            ""
        }
    }

    /** Kisi file ko chhune wale commits (naya sabse pehle). */
    suspend fun fileLog(dir: File, repoRelPath: String, max: Int = 50): Result<List<CommitInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    git.log().addPath(repoRelPath).setMaxCount(max).call().map { c ->
                        CommitInfo(
                            hash = c.name,
                            shortHash = c.name.take(7),
                            message = c.fullMessage.trim(),
                            author = c.authorIdent?.name ?: "?",
                            time = c.commitTime * 1000L
                        )
                    }
                }
            }
        }

    /** Kisi commit par file ka content (null = file us commit me nahi thi). */
    suspend fun blobTextAtCommit(dir: File, hash: String, repoRelPath: String): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    val repo = git.repository
                    val commitId = repo.resolve(hash) ?: return@runCatching null
                    RevWalk(repo).use { walk ->
                        val commit = walk.parseCommit(commitId)
                        TreeWalk(repo).use { tw ->
                            tw.addTree(commit.tree)
                            tw.isRecursive = true
                            tw.filter = PathFilter.create(repoRelPath)
                            if (!tw.next()) return@runCatching null
                            repo.open(tw.getObjectId(0)).bytes.toString(Charsets.UTF_8)
                        }
                    }
                }
            }
        }

    /** Ek file ke saare changes HEAD se discard karo (working tree wapas). */
    suspend fun discardFileChanges(dir: File, repoRelPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    git.checkout().setStartPoint("HEAD").addPath(repoRelPath).call()
                    Unit
                }
            }
        }

    suspend fun stageAll(dir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            open(dir).use { git ->
                git.add().addFilepattern(".").call()
            }
            Unit
        }
    }

    suspend fun commit(
        dir: File,
        message: String,
        author: String,
        amend: Boolean = false
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    val cleanAuthor = author.ifBlank { "NobiCode" }
                    val rev = git.commit()
                        .setMessage(message)
                        .setAuthor(cleanAuthor, "$cleanAuthor@nobicod.e")
                        .setCommitter(cleanAuthor, "$cleanAuthor@nobicod.e")
                        .setAmend(amend)
                        .call()
                    rev.name
                }
            }
        }

    private fun creds(user: String, pass: String) =
        UsernamePasswordCredentialsProvider(user, pass)

    suspend fun push(dir: File, user: String, pass: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    val results = git.push()
                        .setCredentialsProvider(creds(user, pass))
                        .call()
                    results.joinToString("; ") { it.messages }
                }
            }
        }

    suspend fun pull(dir: File, user: String, pass: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    val result = git.pull()
                        .setCredentialsProvider(creds(user, pass))
                        .call()
                    result.mergeResult?.mergeStatus?.toString() ?: "OK"
                }
            }
        }

    suspend fun branches(dir: File): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            open(dir).use { git ->
                git.branchList().call().map {
                    Repository.shortenRefName(it.name)
                }.sorted()
            }
        }
    }

    suspend fun checkout(dir: File, branch: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    git.checkout().setName(branch).call()
                }
                Unit
            }
        }

    suspend fun createBranch(dir: File, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                open(dir).use { git ->
                    git.branchCreate().setName(name).call()
                    git.checkout().setName(name).call()
                }
                Unit
            }
        }

    suspend fun clone(url: String, localDir: File, user: String, pass: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(localDir)
                if (user.isNotBlank()) {
                    builder.setCredentialsProvider(creds(user, pass))
                }
                builder.call().use { }
                Unit
            }
        }

    suspend fun init(dir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Git.init().setDirectory(dir).call().use { }
            Unit
        }
    }
}

