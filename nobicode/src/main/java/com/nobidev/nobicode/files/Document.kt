package com.nobidev.nobicode.files

import android.net.Uri
import com.nobidev.nobicode.editor.Language
import java.util.UUID

data class Document(
    val id: String = UUID.randomUUID().toString(),
    var uri: Uri?,
    var displayName: String,
    var content: String,
    var language: Language,
    var savedContent: String = content,
    var contentLoaded: Boolean = true,
) {
    val isDirty: Boolean get() = content != savedContent
    val label: String get() = if (isDirty) "$displayName •" else displayName
    fun markSaved() { savedContent = content }
}
