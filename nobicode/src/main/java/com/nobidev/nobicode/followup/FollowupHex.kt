package com.nobidev.nobicode.followup

/**
 * v11 follow-up (5): #RRGGBB handling. Finds the hex token under the
 * caret so a tap on a color in code opens the picker, and validates a
 * typed replacement before it is written back into the document.
 */
object FollowupHex {

    private val hexRegex = Regex("#([0-9A-Fa-f]{6})\\b")

    data class Found(val start: Int, val end: Int, val hex: String)

    /** Hex token containing [offset], or null when the caret is not on one. */
    fun findAt(text: CharSequence, offset: Int): Found? {
        for (m in hexRegex.findAll(text)) {
            if (offset in m.range) {
                return Found(m.range.first, m.range.last + 1, m.value)
            }
        }
        return null
    }

    fun isValid(hex: String): Boolean {
        return hexRegex.matches(hex.trim())
    }

    fun normalize(hex: String): String = hex.trim().uppercase()

    val presets: List<String> = listOf(
        "#FF0000", "#FF9800", "#FFEB3B", "#4CAF50", "#2196F3",
        "#8B5CF6", "#E91E63", "#00BCD4", "#795548", "#000000",
        "#FFFFFF", "#9E9E9E"
    )
}
