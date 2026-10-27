package com.nobidev.nobicode.followup

/**
 * v11 follow-up (21): first-run editor tour. Shown once, the first time
 * the editor opens; Skip or finishing sets the flag so it never appears
 * again on its own (the user can still reopen it from Editor tools).
 */
object FollowupTour {

    data class Step(val title: String, val body: String)

    val steps: List<Step> = listOf(
        Step(
            "Welcome to the editor",
            "Open a folder from the drawer, then tap any file to edit it. " +
                "Use the toolbar menu for Save, Find, and more."
        ),
        Step(
            "Quick file switcher",
            "Open Editor tools, then Quick file switcher, and type a few " +
                "letters of any file name to jump straight to it."
        ),
        Step(
            "Symbols row",
            "The row above the footer inserts brackets and language " +
                "symbols at the caret. It changes with the file language."
        ),
        Step(
            "Editor tools",
            "Editor tools in the toolbar menu holds the rest: recent " +
                "files, templates, cheat sheets, file compare, split " +
                "editor, lint, sharing, and file info."
        ),
        Step(
            "Terminal in this folder",
            "Editor tools, then Open terminal in file's folder, opens " +
                "the terminal in the folder of the file you are editing."
        ),
        Step(
            "You are set",
            "That is the whole tour. Open Editor tools any time to " +
                "revisit these, and customise the tools row from " +
                "Customise toolbar."
        )
    )
}
