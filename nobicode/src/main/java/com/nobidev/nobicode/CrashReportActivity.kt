package com.nobidev.nobicode

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class CrashReportActivity : AppCompatActivity() {

    private var crashFile: File? = null
    private var crashText: String = "No crash info available"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        // Full device-info report was saved to this file by NobiCodeApplication
        // (reading from file is binder-safe and never truncated, unlike a big intent extra)
        val path = intent.getStringExtra("crash_file_path")
        if (path != null) {
            try {
                val f = File(path)
                if (f.exists()) {
                    crashFile = f
                    crashText = f.readText()
                }
            } catch (_: Exception) { }
        }
        if (crashFile == null) {
            // Fallback: show at least the crash summary
            crashText = intent.getStringExtra("crash_summary") ?: crashText
        }

        findViewById<TextView>(R.id.crashLogView).text = crashText

        findViewById<Button>(R.id.shareCrashBtn).setOnClickListener {
            shareCrashReport()
        }

        findViewById<Button>(R.id.restartAppBtn).setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        }
    }

    private fun shareCrashReport() {
        val file = crashFile
        if (file != null && file.exists()) {
            // Share as a real .txt attachment with ALL content (Sam's request)
            try {
                val uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "NobiIDE Crash Report")
                    putExtra(Intent.EXTRA_TEXT, crashText)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share crash report via"))
                return
            } catch (e: Exception) {
                Toast.makeText(this, "File share failed, sharing as text", Toast.LENGTH_SHORT).show()
            }
        }
        // Fallback: plain text share
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NobiIDE Crash Report")
            putExtra(Intent.EXTRA_TEXT, crashText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share crash report via"))
    }
}
