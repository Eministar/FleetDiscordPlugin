package dev.emin.fleetrichpresence.bridge

import java.util.Locale

data class LargeImageAsset(
    val key: String,
    val text: String,
)

object FileTypeAssets {
    private val extensionMap = mapOf(
        "kt" to LargeImageAsset("kotlin", "Kotlin"),
        "kts" to LargeImageAsset("kotlin", "Kotlin"),
        "java" to LargeImageAsset("java", "Java"),
        "groovy" to LargeImageAsset("groovy", "Groovy"),
        "gradle" to LargeImageAsset("gradle", "Gradle"),
        "js" to LargeImageAsset("javascript", "JavaScript"),
        "mjs" to LargeImageAsset("javascript", "JavaScript"),
        "cjs" to LargeImageAsset("javascript", "JavaScript"),
        "ts" to LargeImageAsset("typescript", "TypeScript"),
        "tsx" to LargeImageAsset("react", "React / TypeScript"),
        "jsx" to LargeImageAsset("react", "React / JavaScript"),
        "py" to LargeImageAsset("python", "Python"),
        "rs" to LargeImageAsset("rust", "Rust"),
        "go" to LargeImageAsset("go", "Go"),
        "cs" to LargeImageAsset("csharp", "C#"),
        "cpp" to LargeImageAsset("cpp", "C++"),
        "cc" to LargeImageAsset("cpp", "C++"),
        "cxx" to LargeImageAsset("cpp", "C++"),
        "c" to LargeImageAsset("c", "C"),
        "h" to LargeImageAsset("c", "C / Header"),
        "hpp" to LargeImageAsset("cpp", "C++ / Header"),
        "swift" to LargeImageAsset("swift", "Swift"),
        "php" to LargeImageAsset("php", "PHP"),
        "rb" to LargeImageAsset("ruby", "Ruby"),
        "lua" to LargeImageAsset("lua", "Lua"),
        "html" to LargeImageAsset("html", "HTML"),
        "htm" to LargeImageAsset("html", "HTML"),
        "css" to LargeImageAsset("css", "CSS"),
        "scss" to LargeImageAsset("sass", "Sass / SCSS"),
        "sass" to LargeImageAsset("sass", "Sass"),
        "json" to LargeImageAsset("json", "JSON"),
        "yml" to LargeImageAsset("yaml", "YAML"),
        "yaml" to LargeImageAsset("yaml", "YAML"),
        "xml" to LargeImageAsset("xml", "XML"),
        "sql" to LargeImageAsset("database", "SQL"),
        "md" to LargeImageAsset("markdown", "Markdown"),
        "dockerfile" to LargeImageAsset("docker", "Docker"),
        "sh" to LargeImageAsset("terminal", "Shell"),
        "ps1" to LargeImageAsset("powershell", "PowerShell"),
    )

    fun resolve(fileName: String?): LargeImageAsset? {
        if (fileName.isNullOrBlank()) {
            return null
        }

        val lowerCaseFileName = fileName.lowercase(Locale.ROOT)
        if (lowerCaseFileName == "dockerfile") {
            return extensionMap["dockerfile"]
        }

        val extension = lowerCaseFileName.substringAfterLast('.', "")
        if (extension.isBlank()) {
            return null
        }

        return extensionMap[extension]
    }

    fun languageName(fileName: String?): String? = resolve(fileName)?.text
}
