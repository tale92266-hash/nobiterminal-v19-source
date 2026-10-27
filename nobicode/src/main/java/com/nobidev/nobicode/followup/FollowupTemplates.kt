package com.nobidev.nobicode.followup

/**
 * v11 follow-up (6): one-tap file templates. Tapping a template opens a
 * new editor tab with that file name (so language detection works) and
 * the template text inside; the user saves it wherever they want with
 * Save As. No network, no new files on disk until the user saves.
 */
object FollowupTemplates {

    data class Template(val label: String, val fileName: String, val content: String)

    val all: List<Template> = listOf(
        Template(
            "HTML page", "index.html",
            "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>New page</title>\n</head>\n<body>\n    <h1>Hello</h1>\n" +
                "    <p>Start writing here.</p>\n</body>\n</html>\n"
        ),
        Template(
            "Python script", "main.py",
            "def main():\n    print(\"Hello\")\n\n\nif __name__ == \"__main__\":\n    main()\n"
        ),
        Template(
            "Java class", "Main.java",
            "public class Main {\n    public static void main(String[] args) {\n" +
                "        System.out.println(\"Hello\");\n    }\n}\n"
        ),
        Template(
            "Kotlin file", "Main.kt",
            "fun main() {\n    println(\"Hello\")\n}\n"
        )
    )
}
