package com.nobidev.nobicode.model

import android.net.Uri

data class EditorTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    var uri: Uri? = null,
    var name: String = "Untitled",
    var path: String? = null,
    var content: String = "",
    var diskContent: String = "",
    var isModified: Boolean = false,
    var isDeleted: Boolean = false,
    var cursorPosition: Int = 0,
    var scrollPosition: Int = 0,
    var isPinned: Boolean = false,
    /** v7 (41): remote file backing — server path + connection id, if any. */
    var remotePath: String? = null,
    var remoteConnId: String? = null
)
