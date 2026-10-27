package com.nobidev.nobicode.followup

import java.nio.charset.Charset

/**
 * v11 follow-up (19): encoding + line-ending handling. The chosen charset
 * is used both when writing (save path) and when reading (open path), so
 * a file saved as UTF-16LE reopens as UTF-16LE instead of mojibake.
 * Line endings are normalised at save time, so edits typed with plain
 * newlines still save in the chosen style.
 */
object FollowupEncoding {

    val encodings: List<String> = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "ISO-8859-1")

    fun charsetFor(name: String): Charset {
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    fun detectLineEnding(text: String): String {
        return if (text.contains("\r\n")) "CRLF" else "LF"
    }

    fun convertLineEndings(text: String, toCRLF: Boolean): String {
        val lf = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (toCRLF) lf.replace("\n", "\r\n") else lf
    }
}
