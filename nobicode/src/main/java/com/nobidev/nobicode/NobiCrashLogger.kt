package com.nobidev.nobicode

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash logger for the Nobi Code editor.
 *
 * Ported from the standalone app's NobiCodeApplication: inside NobiTerminal
 * the host application class already exists, so the handler is installed
 * from the editor's MainActivity instead of an Application subclass.
 * Behaviour is unchanged: a full report is saved (Downloads, internal
 * crash_logs and the NobiIDE folder) and the shareable CrashReportActivity
 * is launched.
 */
object NobiCrashLogger {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            var savedCrashPath: String? = null
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val timeStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val fileName = "NobiCode_crash_$timeStamp.txt"

                val report = buildString {
                    append("=========================================\n")
                    append("          NOBICODE CRASH REPORT          \n")
                    append("=========================================\n")
                    append("Date & Time : $timeStamp\n")
                    append("Device Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    append("Android Ver : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                    append("Thread Name : ${thread.name} (id=${thread.id})\n")
                    append("Exception   : ${throwable.javaClass.name}\n")
                    append("Message     : ${throwable.message}\n")
                    append("=========================================\n")
                    append("STACKTRACE:\n\n")
                    append(stackTrace)
                    append("\n=========================================\n")
                }

                saveToDownloads(appContext, fileName, report)

                val internalDir = File(appContext.filesDir, "crash_logs")
                if (!internalDir.exists()) internalDir.mkdirs()
                val internalFile = File(internalDir, fileName)
                internalFile.writeText(report)
                savedCrashPath = internalFile.absolutePath

                // Sam's request: also save to /storage/emulated/0/NobiIDE/crash_logs/ for easy access
                try {
                    val nobiDir = File("/storage/emulated/0/NobiIDE/crash_logs")
                    if (!nobiDir.exists()) nobiDir.mkdirs()
                    val nobiFile = File(nobiDir, fileName)
                    nobiFile.writeText(report)
                    Log.e("NobiCrashLogger", "Crash saved to NobiIDE folder: ${nobiFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e("NobiCrashLogger", "Failed to save to NobiIDE folder", e)
                }

                Log.e("NobiCrashLogger", "Crash saved to Download: $fileName")
            } catch (e: Exception) {
                Log.e("NobiCrashLogger", "Failed to write crash log", e)
            } finally {
                try {
                    // Launch shareable crash reporter activity (Sam's request)
                    // Full device-info report is already saved to file; pass its path so the
                    // reporter can display AND share the complete .txt (binder-safe, no truncation).
                    val intent = Intent(appContext, CrashReportActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra("crash_file_path", savedCrashPath)
                        putExtra("crash_summary", "${throwable.javaClass.name}: ${throwable.message}")
                    }
                    appContext.startActivity(intent)
                    try { Thread.sleep(800) } catch (_: InterruptedException) {}
                } catch (e: Exception) {
                    Log.e("NobiCrashLogger", "Failed to launch crash reporter", e)
                }
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun saveToDownloads(context: Context, fileName: String, content: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                    }
                }
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                val file = File(downloadDir, fileName)
                FileOutputStream(file).use { fos ->
                    fos.write(content.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            }
        } catch (e: Exception) {
            Log.e("NobiCrashLogger", "Failed to save file to Downloads", e)
        }
    }
}
