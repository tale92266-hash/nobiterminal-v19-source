package com.nobidev.nobicode.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Har save par file ka snapshot — VS Code "Local History" jaisa.
 * App-private storage me: filesDir/fileHistory/<uriHash>/<timestamp>.txt
 */
object LocalHistory {
    const val MAX_VERSIONS = 30
    const val MAX_BYTES = 2_000_000

    data class Version(val time: Long, val size: Long, val file: File)

    private fun dirFor(context: Context, uriKey: String): File {
        val key = Integer.toUnsignedString(uriKey.hashCode(), 16)
        return File(File(context.filesDir, "fileHistory"), key).apply { mkdirs() }
    }

    /** Save ke baad snapshot lo (duplicate content skip, purane prune). */
    suspend fun record(context: Context, uriKey: String, content: String) = withContext(Dispatchers.IO) {
        try {
            if (content.length > MAX_BYTES) return@withContext
            val dir = dirFor(context, uriKey)
            val latest = dir.listFiles()
                ?.maxByOrNull { it.nameWithoutExtension.toLongOrNull() ?: 0L }
            if (latest != null && latest.length() == content.length.toLong() && latest.readText() == content) {
                return@withContext
            }
            File(dir, "${System.currentTimeMillis()}.txt").writeText(content)
            val all = dir.listFiles()
                ?.sortedBy { it.nameWithoutExtension.toLongOrNull() ?: 0L }
                ?: return@withContext
            if (all.size > MAX_VERSIONS) {
                all.take(all.size - MAX_VERSIONS).forEach { it.delete() }
            }
        } catch (_: Exception) {
        }
    }

    /** Naye se purane tak versions. */
    suspend fun versions(context: Context, uriKey: String): List<Version> = withContext(Dispatchers.IO) {
        try {
            dirFor(context, uriKey).listFiles()
                ?.mapNotNull { f ->
                    val t = f.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
                    Version(t, f.length(), f)
                }
                ?.sortedByDescending { it.time }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun read(v: Version): String? = withContext(Dispatchers.IO) {
        try {
            v.file.readText()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun delete(v: Version) = withContext(Dispatchers.IO) {
        try {
            v.file.delete()
        } catch (_: Exception) {
        }
    }
}
