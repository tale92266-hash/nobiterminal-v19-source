package com.nobidev.nobicode.followup

/**
 * v11 follow-up (8): per-language symbols row. The editor's symbols bar
 * rebuilds from this list whenever the active file changes, so Python
 * files get Python tokens, HTML files get tags, and so on. The generic
 * list is the bar the editor already had, kept as the fallback.
 */
object FollowupSymbols {

    private val generic = listOf(
        "Tab", "(", ")", "{", "}", "[", "]", ";", ":", ",", ".",
        "\"", "'", "=", "+", "-", "*", "/", "<", ">", "!", "?", "&", "|",
        "_", "#", "$", "@", "\\", "~", "^", "%"
    )

    private val python = listOf(
        "Tab", "def ", "class ", ":", "(", ")", "[", "]", "#", "\"\"\"",
        "f\"", "import ", "from ", "return ", "if ", "elif ", "else:",
        "for ", "while ", "print(", "self", "=", "==", "->", "None"
    )

    private val html = listOf(
        "Tab", "<", ">", "/", "=", "\"", "<div>", "</div>", "<p>",
        "</p>", "<span>", "</span>", "class=", "id=", "<!--", "-->",
        "<a href=\"\">", "<img src=\"\">", "<ul>", "<li>"
    )

    private val java = listOf(
        "Tab", "(", ")", "{", "}", "[", "]", ";", "public ", "private ",
        "static ", "void ", "class ", "new ", "return ", "if ", "for ",
        "System.out.println(", "\"", "import ", "extends ", "this."
    )

    private val kotlin = listOf(
        "Tab", "(", ")", "{", "}", "[", "]", "fun ", "val ", "var ",
        "class ", "return ", "if ", "when ", "for ", "println(",
        "\"", "?.", "?:", "->", "import ", "data class ", "object "
    )

    fun symbolsFor(ext: String): List<String> {
        return when (ext.lowercase()) {
            "py" -> python
            "html", "htm", "xml" -> html
            "java" -> java
            "kt", "kts" -> kotlin
            else -> generic
        }
    }
}
