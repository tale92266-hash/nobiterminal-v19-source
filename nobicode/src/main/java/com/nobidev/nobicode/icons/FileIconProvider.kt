package com.nobidev.nobicode.icons

import com.nobidev.nobicode.R
import java.util.Locale

object FileIconProvider {

    fun getFileIcon(fileName: String): Int {
        val lower = fileName.lowercase(Locale.ROOT)

        // Exact Filename matches
        when (lower) {
            "dockerfile", "docker-compose.yml", "docker-compose.yaml" -> return R.drawable.docker
            ".gitignore", ".gitattributes", ".gitmodules" -> return R.drawable.file_type_git
            "package.json", "package-lock.json" -> return R.drawable.file_type_npm
            "tsconfig.json" -> return R.drawable.file_type_typescript
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" -> return R.drawable.file_type_gradle
            "gradlew", "gradlew.bat" -> return R.drawable.file_type_gradle
            "gradle.properties" -> return R.drawable.file_type_properties
            "libs.versions.toml" -> return R.drawable.file_type_toml
            "androidmanifest.xml" -> return R.drawable.file_type_android
            "proguard-rules.pro" -> return R.drawable.file_type_proguard
            "tailwind.config.js", "tailwind.config.ts" -> return R.drawable.file_type_tailwind
            "vite.config.js", "vite.config.ts" -> return R.drawable.file_type_vite
            "next.config.js", "next.config.mjs" -> return R.drawable.file_type_next
            "readme.md", "readme.txt", "readme" -> return R.drawable.file_type_markdown
            "changelog.md", "changelog" -> return R.drawable.changelog
            "license", "license.md", "license.txt" -> return R.drawable.certificate
            ".eslintrc", ".eslintrc.js", ".eslintrc.json" -> return R.drawable.file_type_eslint
            ".prettierrc", ".prettierrc.json" -> return R.drawable.file_type_prettier
            ".editorconfig" -> return R.drawable.editorconfig
            ".env", ".env.local", ".env.production", ".env.development" -> return R.drawable.file_type_env
        }

        // Extension matches
        val ext = lower.substringAfterLast('.', "")
        return when (ext) {
            "kt" -> R.drawable.file_type_kotlin
            "kts" -> R.drawable.file_type_kotlin_script
            "java" -> R.drawable.file_type_java
            "py", "pyw", "pyi" -> R.drawable.file_type_python
            "js", "mjs", "cjs" -> R.drawable.file_type_js
            "jsx" -> R.drawable.file_type_react
            "ts" -> R.drawable.file_type_typescript
            "tsx" -> R.drawable.file_type_react_ts
            "c", "h" -> R.drawable.c
            "cpp", "cc", "cxx", "hpp", "hxx" -> R.drawable.file_type_cpp
            "cs" -> R.drawable.csharp
            "html", "htm", "hfml", "xhtml" -> R.drawable.file_type_html
            "css" -> R.drawable.file_type_css
            "scss", "sass" -> R.drawable.folder_sass
            "json" -> R.drawable.file_type_json
            "xml" -> R.drawable.file_type_android
            "md", "markdown" -> R.drawable.file_type_markdown
            "sh", "bash", "zsh" -> R.drawable.sh
            "bat", "cmd" -> R.drawable.sh
            "sql", "db", "sqlite" -> R.drawable.database
            "dart" -> R.drawable.dart
            "rs" -> R.drawable.folder_rust
            "go" -> R.drawable.folder_src
            "php" -> R.drawable.dinophp
            "svg" -> R.drawable.file_type_svg
            "toml" -> R.drawable.file_type_toml
            "properties" -> R.drawable.file_type_properties
            "yaml", "yml" -> R.drawable.folder_husky
            "txt", "log", "text" -> R.drawable.file_type_text
            "pdf" -> R.drawable.ic_file_pdf
            "zip", "tar", "gz", "7z", "rar" -> R.drawable.folder_archive
            "mp3", "wav", "ogg", "flac" -> R.drawable.audio
            "exe" -> R.drawable.exe
            "dll" -> R.drawable.dll
            "astro" -> R.drawable.astro
            "clj", "cljs" -> R.drawable.clojure
            "ex", "exs" -> R.drawable.elixir
            "elm" -> R.drawable.elm
            "erl" -> R.drawable.erlang
            "coffee" -> R.drawable.coffee
            "d" -> R.drawable.d
            "diff", "patch" -> R.drawable.diff
            "drawio" -> R.drawable.drawio
            "epub" -> R.drawable.epub
            "lua" -> R.drawable.folder_moon
            else -> R.drawable.file_type_text
        }
    }

    fun getFolderIcon(folderName: String, isExpanded: Boolean = false): Int {
        val lower = folderName.lowercase(Locale.ROOT)
        return when (lower) {
            "android" -> R.drawable.folder_android
            "api", "apis", "endpoint", "endpoints" -> R.drawable.folder_api
            "app", "apps" -> R.drawable.folder_app
            "archive", "archives", "backup", "backups" -> R.drawable.folder_archive
            "components", "component", "widgets" -> R.drawable.folder_components
            "core", "kernel" -> R.drawable.folder_core
            "database", "db", "models", "entities", "schemas", "sql" -> R.drawable.folder_database
            "dist", "build", "out", "target", "release", "output" -> R.drawable.folder_dist
            "docs", "documentation", "doc", "guides" -> R.drawable.folder_docs
            "element", "elements" -> R.drawable.folder_element
            "examples", "example", "samples", "demo" -> R.drawable.folder_examples
            "helper", "helpers", "utils", "util", "tools" -> R.drawable.folder_helper
            "include", "includes", "inc" -> R.drawable.folder_include
            "java" -> R.drawable.folder_java
            "js", "javascript" -> R.drawable.folder_javascript
            "kotlin", "kt" -> R.drawable.folder_kotlin
            "layout", "layouts", "views", "view", "screens", "pages" -> R.drawable.folder_layout
            "messages", "locales", "i18n", "lang" -> R.drawable.folder_messages
            "middleware", "middlewares" -> R.drawable.folder_middleware
            "mock", "mocks", "stubs", "fixtures" -> R.drawable.folder_mock
            "node_modules" -> R.drawable.folder_node
            "res", "resources", "resource", "assets", "static", "public" -> R.drawable.folder_resource
            "server", "backend", "service", "services" -> R.drawable.folder_server
            "src", "source", "sources" -> R.drawable.folder_src
            "store", "redux", "state" -> R.drawable.folder_store
            ".git", ".github", ".gitlab", ".circleci", "ci", "workflows" -> R.drawable.folder_ci
            "routes", "router" -> R.drawable.folder_resolver
            else -> if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder
        }
    }

    fun getLanguageName(fileName: String): String {
        val ext = fileName.lowercase(Locale.ROOT).substringAfterLast('.', "")
        return when (ext) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "py", "pyw" -> "Python"
            "js", "mjs" -> "JavaScript"
            "ts" -> "TypeScript"
            "jsx", "tsx" -> "React"
            "c", "h" -> "C"
            "cpp", "hpp" -> "C++"
            "cs" -> "C#"
            "html", "htm", "hfml" -> "HTML"
            "css" -> "CSS"
            "scss", "sass" -> "SCSS"
            "json" -> "JSON"
            "xml" -> "XML"
            "md" -> "Markdown"
            "sh", "bash" -> "Shell"
            "sql" -> "SQL"
            "dart" -> "Dart"
            "rs" -> "Rust"
            "go" -> "Go"
            "php" -> "PHP"
            "yaml", "yml" -> "YAML"
            "toml" -> "TOML"
            else -> "Plain Text"
        }
    }
}
