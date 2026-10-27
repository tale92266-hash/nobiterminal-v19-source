package com.nobidev.nobicode.remote

import com.nobidev.nobicode.model.RemoteConnection
import com.nobidev.nobicode.model.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Vector

/**
 * FTP/SFTP client. One active connection at a time.
 * All operations run on Dispatchers.IO and return Result.
 */
object RemoteManager {

    private var ftp: FTPClient? = null
    private var sftpSession: Session? = null
    private var sftpChannel: ChannelSftp? = null
    private var active: RemoteConnection? = null

    val isConnected: Boolean
        get() = active != null && (ftp?.isConnected == true || sftpChannel?.isConnected == true)

    val activeConnection: RemoteConnection? get() = active

    fun disconnect() {
        try { ftp?.logout() } catch (e: Exception) { }
        try { ftp?.disconnect() } catch (e: Exception) { }
        ftp = null
        try { sftpChannel?.disconnect() } catch (e: Exception) { }
        try { sftpSession?.disconnect() } catch (e: Exception) { }
        sftpChannel = null
        sftpSession = null
        active = null
    }

    suspend fun connect(conn: RemoteConnection): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()
            if (conn.protocol == "FTP") {
                val client = FTPClient()
                client.connect(conn.host, conn.port)
                if (!client.login(conn.username, conn.password)) {
                    client.disconnect()
                    throw Exception("Login failed")
                }
                client.enterLocalPassiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                ftp = client
            } else {
                val jsch = JSch()
                val session = jsch.getSession(conn.username, conn.host, conn.port)
                session.setPassword(conn.password)
                val config = java.util.Properties()
                config["StrictHostKeyChecking"] = "no"
                session.setConfig(config)
                session.connect(15000)
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect(15000)
                sftpSession = session
                sftpChannel = channel
            }
            active = conn
            Unit
        }
    }

    private fun join(parent: String, name: String): String {
        val p = parent.trimEnd('/')
        return if (p.isEmpty()) "/$name" else "$p/$name"
    }

    suspend fun listDir(path: String): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val out = mutableListOf<RemoteFile>()
            val f = ftp
            val s = sftpChannel
            when {
                f != null && f.isConnected -> {
                    val files = f.listFiles(path)
                    for (rf in files) {
                        if (rf.name == "." || rf.name == "..") continue
                        out.add(RemoteFile(rf.name, join(path, rf.name), rf.isDirectory, rf.size))
                    }
                }
                s != null && s.isConnected -> {
                    @Suppress("UNCHECKED_CAST")
                    val entries = s.ls(path) as Vector<ChannelSftp.LsEntry>
                    for (e in entries) {
                        if (e.filename == "." || e.filename == "..") continue
                        out.add(RemoteFile(e.filename, join(path, e.filename), e.attrs.isDir, e.attrs.size))
                    }
                }
                else -> throw Exception("Not connected")
            }
            out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }
    }

    suspend fun download(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                local.parentFile?.mkdirs()
                val f = ftp
                val s = sftpChannel
                when {
                    f != null && f.isConnected -> {
                        FileOutputStream(local).use { out ->
                            if (!f.retrieveFile(remotePath, out)) throw Exception("Download failed")
                        }
                    }
                    s != null && s.isConnected -> {
                        FileOutputStream(local).use { out ->
                            s.get(remotePath, out)
                        }
                    }
                    else -> throw Exception("Not connected")
                }
                Unit
            }
        }

    suspend fun upload(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = ftp
                val s = sftpChannel
                when {
                    f != null && f.isConnected -> {
                        FileInputStream(local).use { inp ->
                            if (!f.storeFile(remotePath, inp)) throw Exception("Upload failed")
                        }
                    }
                    s != null && s.isConnected -> {
                        FileInputStream(local).use { inp ->
                            s.put(inp, remotePath)
                        }
                    }
                    else -> throw Exception("Not connected")
                }
                Unit
            }
        }
}

