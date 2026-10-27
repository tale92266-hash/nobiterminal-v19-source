package com.nobidev.nobicode.model

import android.net.Uri

data class FileItem(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val isParent: Boolean = false,
    // v6 tree sidebar: indent level in the flattened drawer list, and whether
    // this directory is currently expanded inline. Defaults keep every
    // existing construction site valid.
    val depth: Int = 0,
    val isExpanded: Boolean = false
)
