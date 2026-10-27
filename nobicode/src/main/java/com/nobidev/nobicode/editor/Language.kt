package com.nobidev.nobicode.editor

data class Language(
    val id: String,
    val displayName: String,
    val extensions: List<String>,
    val keywords: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val lineComment: String? = null,
    val stringDelims: List<Char> = listOf('"'),
    val supportsNumbers: Boolean = true,
    val supportsAnnotations: Boolean = false,
) {
    val keywordRegex: Regex? =
        if (keywords.isEmpty()) null
        else Regex("\\b(" + keywords.joinToString("|") { Regex.escape(it) } + ")\\b")

    val typeRegex: Regex? =
        if (types.isEmpty()) null
        else Regex("\\b(" + types.joinToString("|") { Regex.escape(it) } + ")\\b")

    val numberRegex: Regex? =
        if (supportsNumbers) Regex("\\b(0[xX][0-9a-fA-F]+|\\d+\\.?\\d*[fFdDlL]?)\\b")
        else null

    val annotationRegex: Regex? =
        if (supportsAnnotations) Regex("@[A-Za-z_][A-Za-z0-9_]*") else null

    // NOTE: nullable — skip when empty (this was a crash source earlier)
    val stringRegex: Regex? =
        if (stringDelims.isEmpty()) null
        else {
            val d = stringDelims.joinToString("") { Regex.escape(it.toString()) }
            Regex("[$d][^$d\\n]*[$d]")
        }

    val lineCommentRegex: Regex? =
        lineComment?.let { Regex(Regex.escape(it) + "[^\\n]*") }
}
