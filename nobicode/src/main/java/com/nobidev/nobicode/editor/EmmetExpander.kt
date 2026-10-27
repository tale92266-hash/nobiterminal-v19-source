package com.nobidev.nobicode.editor

/**
 * Emmet abbreviation expander (subset).
 *
 * Supported:
 *   tag            div            -> <div></div>
 *   #id .class     div#main.box   -> <div id="main" class="box"></div>
 *   > child        div>p          -> <div><p></p></div>
 *   + sibling      div+p          -> <div></div><p></p>
 *   ^ climb        div>p^span     -> <div><p></p></div><span></span>
 *   *N multiply    ul>li*3        -> <ul><li></li> x3 </ul>
 *   $ numbering    li.i$*3        -> class="i1","i2","i3"
 *   {text}         p{hi}          -> <p>hi</p>
 *   [attrs]        a[href=#]      -> <a href="#"></a>
 *   !              HTML5 boilerplate
 */
object EmmetExpander {

    private val voidElements = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )

    data class Node(
        val tag: String,
        val id: String?,
        val classes: List<String>,
        val attrs: Map<String, String>,
        val text: String?,
        val count: Int,
        var parent: Node? = null,
        val children: MutableList<Node> = mutableListOf(),
        val isRaw: Boolean = false
    ) {
        fun toHtml(indent: String, index: Int = 1): String {
            if (isRaw) return (text ?: "") + "\n"
            val sb = StringBuilder()
            for (i in 1..count) {
                val idx = if (count > 1) i else index
                val t = tag.replace("$", idx.toString())
                val idAttr = id?.let { " id=\"" + it.replace("$", idx.toString()) + "\"" } ?: ""
                val classAttr = if (classes.isNotEmpty())
                    " class=\"" + classes.joinToString(" ") { it.replace("$", idx.toString()) } + "\""
                else ""
                val attrStr = attrs.entries.joinToString("") { (k, v) ->
                    " " + k + "=\"" + v.replace("$", idx.toString()) + "\""
                }
                val openTag = "<" + t + idAttr + classAttr + attrStr + ">"
                if (t.lowercase() in voidElements) {
                    sb.append(indent).append(openTag).append("\n")
                } else if (children.isEmpty() && text == null) {
                    sb.append(indent).append(openTag).append("</").append(t).append(">\n")
                } else if (children.isEmpty()) {
                    sb.append(indent).append(openTag)
                        .append(text!!.replace("$", idx.toString()))
                        .append("</").append(t).append(">\n")
                } else {
                    sb.append(indent).append(openTag).append("\n")
                    for (child in children) sb.append(child.toHtml(indent + "    ", idx))
                    sb.append(indent).append("</").append(t).append(">\n")
                }
            }
            return sb.toString()
        }
    }

    /** Expand [abbr] with [baseIndent]; null if not a valid abbreviation. */
    fun expand(abbr: String, baseIndent: String = ""): String? {
        if (abbr.isBlank()) return null
        val parser = Parser(abbr.trim())
        val roots = parser.parse() ?: return null
        val sb = StringBuilder()
        for (n in roots) sb.append(n.toHtml(baseIndent))
        var html = sb.toString()
        // Drop the trailing newline; keep inner structure.
        if (html.endsWith("\n")) html = html.dropLast(1)
        return html.ifBlank { null }
    }

    private class Parser(val input: String) {
        var pos = 0

        fun parse(): List<Node>? {
            val roots = mutableListOf<Node>()
            var last: Node? = null

            var node = parseElement() ?: return null
            roots.add(node)
            last = node

            while (pos < input.length) {
                when (input[pos]) {
                    '>' -> {
                        pos++
                        val child = parseElement() ?: return null
                        child.parent = last
                        last!!.children.add(child)
                        last = child
                    }
                    '+' -> {
                        pos++
                        val sib = parseElement() ?: return null
                        val parent = last?.parent
                        if (parent == null) roots.add(sib) else parent.children.add(sib)
                        sib.parent = parent
                        last = sib
                    }
                    '^' -> {
                        pos++
                        last = last?.parent
                        val sib = parseElement() ?: return null
                        val grandparent = last?.parent
                        if (grandparent == null) roots.add(sib) else grandparent.children.add(sib)
                        sib.parent = grandparent
                        last = sib
                    }
                    else -> break
                }
            }
            return if (pos == input.length) roots else null
        }

        private fun parseElement(): Node? {
            if (pos < input.length && input[pos] == '!') {
                pos++
                return boilerplate()
            }
            val tagStart = pos
            while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '-' || input[pos] == ':')) pos++
            var tag: String? = if (pos > tagStart) input.substring(tagStart, pos) else null

            var id: String? = null
            val classes = mutableListOf<String>()
            val attrs = mutableMapOf<String, String>()
            var text: String? = null
            var count = 1

            while (pos < input.length) {
                when (input[pos]) {
                    '#' -> {
                        pos++
                        val s = pos
                        while (pos < input.length && isNameChar(input[pos])) pos++
                        if (pos == s) return null
                        id = input.substring(s, pos)
                    }
                    '.' -> {
                        pos++
                        val s = pos
                        while (pos < input.length && isNameChar(input[pos])) pos++
                        if (pos == s) return null
                        classes.add(input.substring(s, pos))
                    }
                    '[' -> {
                        pos++
                        while (pos < input.length && input[pos] != ']') {
                            while (pos < input.length && input[pos] == ' ') pos++
                            if (pos < input.length && input[pos] == ']') break
                            val ns = pos
                            while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '-' || input[pos] == ':')) pos++
                            if (pos == ns) return null
                            val name = input.substring(ns, pos)
                            var value = ""
                            if (pos < input.length && input[pos] == '=') {
                                pos++
                                if (pos < input.length && (input[pos] == '"' || input[pos] == '\'')) {
                                    val q = input[pos++]
                                    val vs = pos
                                    while (pos < input.length && input[pos] != q) pos++
                                    value = input.substring(vs, pos)
                                    if (pos < input.length) pos++
                                } else {
                                    val vs = pos
                                    while (pos < input.length && !input[pos].isWhitespace() && input[pos] != ']') pos++
                                    value = input.substring(vs, pos)
                                }
                            }
                            attrs[name] = value
                        }
                        if (pos >= input.length || input[pos] != ']') return null
                        pos++
                    }
                    '{' -> {
                        pos++
                        val s = pos
                        var depth = 1
                        while (pos < input.length && depth > 0) {
                            if (input[pos] == '{') depth++
                            else if (input[pos] == '}') depth--
                            pos++
                        }
                        if (depth != 0) return null
                        text = input.substring(s, pos - 1)
                    }
                    '*' -> {
                        pos++
                        val s = pos
                        while (pos < input.length && input[pos].isDigit()) pos++
                        if (pos == s) return null
                        count = input.substring(s, pos).toIntOrNull() ?: return null
                        if (count < 1 || count > 50) return null
                    }
                    else -> break
                }
            }

            if (tag == null) {
                if (id == null && classes.isEmpty() && attrs.isEmpty()) return null
                tag = "div"
            }
            return Node(tag, id, classes, attrs, text, count)
        }

        private fun isNameChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '-' || c == '_' || c == '$'

        private fun boilerplate(): Node {
            // ! -> full HTML5 shell; represented as raw text node
            val html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>Document</title>\n</head>\n<body>\n    \n</body>\n</html>"
            return Node("__raw__", null, emptyList(), emptyMap(), html, 1, isRaw = true)
        }
    }
}
