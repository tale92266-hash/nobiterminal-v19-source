package com.nobidev.nobicode.editor

object Languages {

    val KOTLIN = Language(
        id = "kotlin", displayName = "Kotlin",
        extensions = listOf("kt", "kts"),
        keywords = listOf(
            "package","import","class","interface","object","fun","val","var",
            "if","else","when","for","while","do","return","break","continue",
            "throw","try","catch","finally","is","in","as","by","where","init",
            "constructor","companion","data","sealed","enum","abstract","open",
            "override","private","public","protected","internal","lateinit",
            "const","suspend","inline","noinline","crossinline","reified",
            "operator","infix","tailrec","external","annotation","actual",
            "expect","true","false","null","this","super","it"
        ),
        types = listOf("Int","Long","Short","Byte","Float","Double","Boolean",
            "Char","String","Unit","Any","Nothing","List","MutableList","Map",
            "MutableMap","Set","MutableSet","Array","Pair","Triple"),
        lineComment = "//",
        stringDelims = listOf('"', '\''),
        supportsAnnotations = true,
    )

    val JAVA = Language(
        id = "java", displayName = "Java", extensions = listOf("java"),
        keywords = listOf("package","import","class","interface","enum",
            "public","private","protected","static","final","abstract",
            "synchronized","volatile","transient","if","else","switch",
            "case","default","for","while","do","return","break","continue",
            "throw","throws","try","catch","finally","new","this","super",
            "extends","implements","instanceof","true","false","null","void",
            "assert","native","strictfp"),
        types = listOf("int","long","short","byte","float","double","boolean",
            "char","String","Integer","Long","Short","Byte","Float","Double",
            "Boolean","Character","Object","List","Map","Set","ArrayList","HashMap"),
        lineComment = "//",
        stringDelims = listOf('"', '\''),
        supportsAnnotations = true,
    )

    val XML = Language(
        id = "xml", displayName = "XML",
        extensions = listOf("xml","html","xhtml"),
        stringDelims = listOf('"', '\''),
        supportsNumbers = false,
    )

    val JSON = Language(
        id = "json", displayName = "JSON", extensions = listOf("json"),
        keywords = listOf("true","false","null"),
        stringDelims = listOf('"'),
    )

    val MD = Language(
        id = "markdown", displayName = "Markdown",
        extensions = listOf("md","markdown"),
        stringDelims = listOf('"', '\''),
        supportsNumbers = false,
    )

    val HTML = Language(
        id = "html", displayName = "HTML", extensions = listOf("html","htm"),
        stringDelims = listOf('"', '\''),
        supportsNumbers = false,
    )

    val CSS = Language(
        id = "css", displayName = "CSS", extensions = listOf("css"),
        stringDelims = listOf('"', '\''),
    )

    val JS = Language(
        id = "javascript", displayName = "JavaScript",
        extensions = listOf("js","mjs","cjs"),
        keywords = listOf("var","let","const","function","return","if","else",
            "for","while","do","switch","case","default","break","continue",
            "new","this","super","class","extends","import","export","from",
            "async","await","try","catch","finally","throw","typeof",
            "instanceof","in","of","true","false","null","undefined","delete",
            "void","yield"),
        lineComment = "//",
        stringDelims = listOf('"', '\'', '`'),
    )

    val PY = Language(
        id = "python", displayName = "Python", extensions = listOf("py","pyw"),
        keywords = listOf("def","class","return","if","elif","else","for","while",
            "break","continue","pass","import","from","as","try","except",
            "finally","raise","with","lambda","yield","global","nonlocal",
            "assert","del","in","is","not","and","or","True","False","None",
            "async","await"),
        types = listOf("int","str","float","bool","list","dict","set","tuple"),
        lineComment = "#",
        stringDelims = listOf('"', '\''),
    )

    val SH = Language(
        id = "shell", displayName = "Shell",
        extensions = listOf("sh","bash","zsh"),
        keywords = listOf("if","then","else","elif","fi","for","while","do",
            "done","case","esac","function","return","echo","export","local",
            "readonly","cd","pwd","exit"),
        lineComment = "#",
        stringDelims = listOf('"', '\''),
    )

    val PLAIN = Language(
        id = "plain", displayName = "Plain Text",
        extensions = listOf("txt","log","ini","conf"),
        stringDelims = emptyList(),
        supportsNumbers = false,
    )

    val ALL = listOf(KOTLIN, JAVA, XML, JSON, MD, HTML, CSS, JS, PY, SH, PLAIN)
    val DEFAULT = PLAIN

    fun byExt(ext: String?): Language {
        if (ext.isNullOrBlank()) return DEFAULT
        val e = ext.lowercase().removePrefix(".")
        return ALL.firstOrNull { e in it.extensions } ?: DEFAULT
    }

    fun byName(name: String): Language =
        byExt(name.substringAfterLast('.', ""))
}
