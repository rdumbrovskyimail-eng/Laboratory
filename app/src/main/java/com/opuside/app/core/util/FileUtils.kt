package com.opuside.app.core.util

/**
 * ✅ ОЧИЩЕНО: Утилиты для работы с файлами
 * 
 * Система локального кеша удалена - теперь Claude работает напрямую с GitHub API.
 */

/**
 * Определяет язык программирования по расширению файла.
 */
fun detectLanguage(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "kt" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "gradle", "kts" -> "gradle"
        "json" -> "json"
        "md" -> "markdown"
        "txt" -> "text"
        "properties" -> "properties"
        "yml", "yaml" -> "yaml"
        "sh" -> "bash"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "cpp", "cc", "cxx" -> "cpp"
        "c", "h" -> "c"
        "cs" -> "csharp"
        "html", "htm" -> "html"
        "css" -> "css"
        "sql" -> "sql"
        "swift" -> "swift"
        "rs" -> "rust"
        "go" -> "go"
        else -> "text"
    }
}
