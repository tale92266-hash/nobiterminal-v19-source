package com.nobidev.nobicode.model

/** Saved FTP/SFTP server connection. */
data class RemoteConnection(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var protocol: String = "SFTP", // FTP or SFTP
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var password: String = ""
)

/** A file/folder on the remote server. */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0
)

