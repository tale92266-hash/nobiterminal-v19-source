package com.nobidev.nobicode.followup

/**
 * v11 follow-up (12): offline cheat sheets bundled as text. No network
 * call is ever made; the sheets are short on purpose so they render
 * instantly in a scrollable dialog.
 */
object FollowupCheatSheets {

    val sheets: Map<String, String> = mapOf(
        "Python" to
            "Variables: x = 10 | name = \"Sam\"\n" +
            "Print: print(\"Hello\", x)\n" +
            "If: if x > 5: ... elif ...: ... else: ...\n" +
            "Loop: for item in items: ... | while x > 0: ...\n" +
            "Function: def greet(name): return \"Hi \" + name\n" +
            "List: items = [1, 2, 3] | items.append(4)\n" +
            "Dict: user = {\"name\": \"Sam\", \"age\": 20}\n" +
            "String: text.upper() | text.strip() | text.split(\",\")\n" +
            "File: with open(\"a.txt\") as f: data = f.read()\n" +
            "Try: try: ... except Exception as e: ...\n" +
            "Class: class Dog: def __init__(self, name): ...\n" +
            "Import: import os | from math import sqrt\n",
        "HTML" to
            "Page: <!DOCTYPE html> <html> <head> <body>\n" +
            "Heading: <h1>Title</h1> ... <h6>\n" +
            "Text: <p>Paragraph</p> <b>Bold</b> <i>Italic</i>\n" +
            "Link: <a href=\"https://example.com\">Text</a>\n" +
            "Image: <img src=\"pic.png\" alt=\"Photo\">\n" +
            "List: <ul><li>Item</li></ul> | <ol> for numbers\n" +
            "Table: <table><tr><td>Cell</td></tr></table>\n" +
            "Form: <form><input type=\"text\"><button>Go</button></form>\n" +
            "Div: <div class=\"box\">...</div>\n" +
            "Comment: <!-- note -->\n",
        "Java" to
            "Class: public class Main { ... }\n" +
            "Main: public static void main(String[] args) { ... }\n" +
            "Print: System.out.println(\"Hello\");\n" +
            "Variables: int n = 5; String s = \"Hi\"; boolean ok = true;\n" +
            "If: if (n > 0) { ... } else { ... }\n" +
            "Loop: for (int i = 0; i < 10; i++) { ... }\n" +
            "Method: int add(int a, int b) { return a + b; }\n" +
            "List: ArrayList<String> list = new ArrayList<>();\n" +
            "Try: try { ... } catch (Exception e) { ... }\n" +
            "Comment: // line | /* block */\n",
        "Kotlin" to
            "Function: fun greet(name: String): String { ... }\n" +
            "Main: fun main() { println(\"Hello\") }\n" +
            "Variables: val fixed = 5 | var changeable = 5\n" +
            "Null: String? | value?.length | value ?: \"default\"\n" +
            "If: val out = if (x > 0) \"pos\" else \"neg\"\n" +
            "When: when (x) { 1 -> \"one\" else -> \"other\" }\n" +
            "Loop: for (item in list) { ... } | repeat(3) { ... }\n" +
            "List: listOf(1, 2) | mutableListOf<String>()\n" +
            "Class: class User(val name: String)\n" +
            "Lambda: list.map { it * 2 } | list.filter { it > 0 }\n"
    )

    fun languages(): List<String> = sheets.keys.toList()
}
