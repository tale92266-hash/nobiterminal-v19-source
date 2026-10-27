package com.nobidev.nobicode.followup

/**
 * v11 follow-up (18): simple OFFLINE lint. No network, no language server.
 * Deliberately conservative so it does not cry wolf: it only reports
 * unbalanced brackets (strings and comments skipped), trailing whitespace,
 * mixed tab/space indentation in Python, and unclosed HTML tags. Callers
 * run it debounced on a background thread and cap the issues shown.
 */
object FollowupLint {

    data class Issue(val line: Int, val message: String)

    private val voidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )

    fun lint(text: String, ext: String): List<Issue> {
        val issues = ArrayList<Issue>()
        val lines = text.split("\n")
        if (ext == "html" || ext == "htm" || ext == "xml") {
            lintHtml(lines, issues)
        } else {
            lintBrackets(text, ext, issues)
        }
        for ((idx, line) in lines.withIndex()) {
            if (issues.size >= 100) break
            if (line.endsWith(" ") || line.endsWith("\t")) {
                issues.add(Issue(idx + 1, "Trailing whitespace"))
            }
            if (ext == "py" && line.startsWith(" ") && line.contains("\t")) {
                issues.add(Issue(idx + 1, "Mixed tabs and spaces in indentation"))
            }
        }
        return issues
    }

    private fun lintBrackets(text: String, ext: String, issues: MutableList<Issue>) {
        val isPython = ext == "py"
        val stack = ArrayDeque<Pair<Char, Int>>()
        var inSingle = false
        var inDouble = false
        var inLineComment = false
        var inBlockComment = false
        var escaped = false
        var line = 1
        var i = 0
        fun matching(open: Char, close: Char): Boolean {
            return (open == '(' && close == ')') || (open == '[' && close == ']') ||
                (open == '{' && close == '}')
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                line++
                inLineComment = false
                i++
                continue
            }
            if (inLineComment) {
                i++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (inSingle || inDouble) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\' && !isPython) {
                    escaped = true
                } else if (inSingle && c == '\'') {
                    inSingle = false
                } else if (inDouble && c == '"') {
                    inDouble = false
                }
                i++
                continue
            }
            if (!isPython && c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }
            if (!isPython && c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                inBlockComment = true
                i += 2
                continue
            }
            if (isPython && c == '#') {
                inLineComment = true
                i++
                continue
            }
            when (c) {
                '\'' -> inSingle = true
                '"' -> inDouble = true
                '(', '[', '{' -> stack.addLast(c to line)
                ')', ']', '}' -> {
                    val open = stack.removeLastOrNull()
                    if (open == null) {
                        if (issues.size < 100) {
                            issues.add(Issue(line, "Unmatched closing '$c'"))
                        }
                    } else if (!matching(open.first, c)) {
                        if (issues.size < 100) {
                            issues.add(
                                Issue(line, "Mismatched bracket: '${open.first}' closed by '$c'")
                            )
                        }
                    }
                }
            }
            i++
        }
        for ((open, openLine) in stack) {
            if (issues.size >= 100) break
            issues.add(Issue(openLine, "Unclosed '$open'"))
        }
    }

    private fun lintHtml(lines: List<String>, issues: MutableList<Issue>) {
        val tagRegex = Regex("</?([A-Za-z][A-Za-z0-9-]*)[^>]*?/?>")
        val stack = ArrayDeque<Pair<String, Int>>()
        for ((idx, rawLine) in lines.withIndex()) {
            if (issues.size >= 100) break
            val line = rawLine.replace(Regex("<!--.*?-->"), "")
            for (m in tagRegex.findAll(line)) {
                val whole = m.value
                val name = m.groupValues[1].lowercase()
                if (name in voidTags || whole.endsWith("/>")) continue
                if (whole.startsWith("</")) {
                    val open = stack.removeLastOrNull()
                    if (open == null) {
                        issues.add(Issue(idx + 1, "Closing tag </$name> without an opener"))
                    } else if (open.first != name) {
                        issues.add(
                            Issue(idx + 1, "Mismatched tag: <${open.first}> closed by </$name>")
                        )
                    }
                } else {
                    stack.addLast(name to idx + 1)
                }
            }
        }
        for ((name, openLine) in stack) {
            if (issues.size >= 100) break
            issues.add(Issue(openLine, "Unclosed tag <$name>"))
        }
    }
}
