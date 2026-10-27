package com.nobidev.nobicode.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

data class FileEntry(
    val docId: String,
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
)

object SafFs {

    fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun list(context: Context, tree: Uri, parentId: String): List<FileEntry> {
        val out = mutableListOf<FileEntry>()
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: "(unnamed)"
                    val mime = c.getString(2) ?: ""
                    val child = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    out.add(FileEntry(id, name, child,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    fun read(context: Context, uri: Uri): String = try {
        context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""
    } catch (_: Throwable) { "" }

    fun write(context: Context, uri: Uri, text: String): Boolean = try {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        true
    } catch (_: Throwable) { false }

    fun name(uri: Uri): String = try {
        DocumentsContract.getDocumentId(uri).substringAfterLast('/').substringAfterLast(':')
    } catch (_: Throwable) { "file" }

    fun createFile(context: Context, tree: Uri, parentId: String, name: String, mime: String = "text/plain"): Uri? = try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
    } catch (_: Throwable) { null }

    fun createFolder(context: Context, tree: Uri, parentId: String, name: String): Uri? =
        createFile(context, tree, parentId, name, Document.MIME_TYPE_DIR)

    fun delete(context: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (_: Throwable) { false }

    fun rename(context: Context, uri: Uri, name: String): Uri? = try {
        DocumentsContract.renameDocument(context.contentResolver, uri, name)
    } catch (_: Throwable) { null }

    fun duplicate(context: Context, tree: Uri, parentId: String, source: FileEntry, newName: String): Uri? {
        if (source.isDirectory) return null
        val created = createFile(context, tree, parentId, newName, mimeForName(source.name)) ?: return null
        return if (write(context, created, read(context, source.uri))) created else null
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "kt","kts" -> "text/x-kotlin"
        "java" -> "text/x-java-source"
        "xml" -> "text/xml"
        "json" -> "application/json"
        "html","htm" -> "text/html"
        "css" -> "text/css"
        "js","mjs","ts" -> "text/javascript"
        "py" -> "text/x-python"
        "sh","bash" -> "text/x-shellscript"
        else -> "text/plain"
    }

    fun parentOf(docId: String): String? {
        val slash = docId.lastIndexOf('/')
        if (slash >= 0) return docId.substring(0, slash)
        val colon = docId.indexOf(':')
        if (colon < 0) return null
        val root = docId.substring(0, colon + 1)
        return if (root == docId) null else root
    }
}
