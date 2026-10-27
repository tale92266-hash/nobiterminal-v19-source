package com.nobidev.nobicode.model

import android.net.Uri

data class FileNode(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val level: Int = 0,
    var isExpanded: Boolean = false,
    val children: MutableList<FileNode> = mutableListOf()
)
