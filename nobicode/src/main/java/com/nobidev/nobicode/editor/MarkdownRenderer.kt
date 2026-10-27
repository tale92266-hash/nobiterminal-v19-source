package com.nobidev.nobicode.editor

/** Minimal Markdown to HTML renderer for preview. */
object MarkdownRenderer {

    fun toHtml(markdown: String): String {
        val lines = markdown.split("\n")
        val sb = StringBuilder()
        var inCodeBlock = false
        var inList = false
        var listType = ""

        fun closeList() {
            if (inList) { sb.append("</").append(listType).append(">\n"); inList = false }
        }

        for (rawLine in lines) {
            val line = rawLine
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) { sb.append("</code></pre>\n"); inCodeBlock = false }
                else { closeList(); sb.append("<pre><code>"); inCodeBlock = true }
                continue
            }
            if (inCodeBlock) { sb.append(escapeHtml(line)).append("\n"); continue }

            if (trimmed.isEmpty()) { closeList(); continue }

            val header = Regex("^(#{1,6})\\s+(.*)").find(trimmed)
            if (header != null) {
                closeList()
                val level = header.groupValues[1].length
                sb.append("<h").append(level).append(">")
                    .append(inline(header.groupValues[2])).append("</h").append(level).append(">\n")
                continue
            }
            if (Regex("^([-*_]\\s*){3,}$").matches(trimmed)) {
                closeList(); sb.append("<hr>\n"); continue
            }
            if (trimmed.startsWith(">")) {
                closeList()
                sb.append("<blockquote>").append(inline(trimmed.substring(1).trim())).append("</blockquote>\n")
                continue
            }
            val ul = Regex("^[-*+]\\s+(.*)").find(trimmed)
            val ol = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
            if (ul != null || ol != null) {
                val type = if (ul != null) "ul" else "ol"
                val content = (ul ?: ol)!!.groupValues.last()
                if (!inList || listType != type) {
                    closeList(); sb.append("<").append(type).append(">\n")
                    inList = true; listType = type
                }
                sb.append("<li>").append(inline(content)).append("</li>\n")
                continue
            }
            closeList()
            sb.append("<p>").append(inline(trimmed)).append("</p>\n")
        }
        if (inCodeBlock) sb.append("</code></pre>\n")
        closeList()
        return sb.toString()
    }

    private fun inline(text: String): String {
        var t = escapeHtml(text)
        t = t.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*"), "<strong><em>$1</em></strong>")
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        t = t.replace(Regex("__(.+?)__"), "<strong>$1</strong>")
        t = t.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        t = t.replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
        t = t.replace(Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)"), "<img alt=\"$1\" src=\"$2\">")
        t = t.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        return t
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun wrapHtml(body: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>" +
        "body{background:#1A1B26;color:#C0CAF5;font-family:monospace;padding:16px;line-height:1.6;}" +
        "h1,h2,h3{color:#8B7CF6;}" +
        "code{background:#24283B;padding:2px 6px;border-radius:4px;}" +
        "pre{background:#24283B;padding:12px;border-radius:8px;overflow-x:auto;}" +
        "pre code{background:none;padding:0;}" +
        "blockquote{border-left:3px solid #8B7CF6;margin:8px 0;padding:8px 16px;background:#24283B;}" +
        "a{color:#7DCFFF;}hr{border-color:#3B4261;}" +
        "img{max-width:100%;}" +
        "</style></head><body>" + body + "</body></html>"
}
