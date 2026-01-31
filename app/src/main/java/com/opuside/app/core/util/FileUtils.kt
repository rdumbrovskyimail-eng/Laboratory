package com.opuside.app.core.util

import com.opuside.app.core.database.entity.CachedFileEntity
import kotlinx.datetime.Clock

/**
 * Утилиты для работы с файлами.
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

/**
 * Создает CachedFileEntity из данных GitHub файла.
 */
fun createCachedFile(
    filePath: String,
    content: String,
    repoOwner: String,
    repoName: String,
    branch: String,
    sha: String
): CachedFileEntity {
    val fileName = filePath.substringAfterLast('/')
    
    return CachedFileEntity(
        filePath = filePath,
        fileName = fileName,
        content = content,
        sizeBytes = content.toByteArray().size,
        language = detectLanguage(fileName),
        addedAt = Clock.System.now(),
        repoOwner = repoOwner,
        repoName = repoName,
        branch = branch,
        sha = sha,
        isEncrypted = false,
        encryptionIv = null
    )
}