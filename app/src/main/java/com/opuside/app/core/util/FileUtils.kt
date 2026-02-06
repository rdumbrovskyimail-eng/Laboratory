package com.opuside.app.core.util

import com.opuside.app.core.database.entity.CachedFileEntity
import kotlinx.datetime.Clock

/**
 * Утилиты для работы с файлами.
 * 
 * ✅ ОБНОВЛЕНО (Проблема #8): Добавлено детальное логирование
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
 * 
 * ✅ ПРОБЛЕМА 8: Добавлено детальное логирование для диагностики
 */
fun createCachedFile(
    filePath: String,
    content: String,
    repoOwner: String,
    repoName: String,
    branch: String,
    sha: String
): CachedFileEntity {
    android.util.Log.d("FileUtils", "━".repeat(80))
    android.util.Log.d("FileUtils", "🔨 CREATING CachedFileEntity")
    android.util.Log.d("FileUtils", "   INPUT PARAMETERS:")
    android.util.Log.d("FileUtils", "   • filePath: $filePath")
    android.util.Log.d("FileUtils", "   • repoOwner: $repoOwner")
    android.util.Log.d("FileUtils", "   • repoName: $repoName")
    android.util.Log.d("FileUtils", "   • branch: $branch")
    android.util.Log.d("FileUtils", "   • sha: $sha")
    android.util.Log.d("FileUtils", "   • content length: ${content.length} chars")
    
    val contentSizeBytes = content.toByteArray().size
    android.util.Log.d("FileUtils", "   • content size: $contentSizeBytes bytes")
    
    val fileName = filePath.substringAfterLast('/')
    android.util.Log.d("FileUtils", "   EXTRACTED DATA:")
    android.util.Log.d("FileUtils", "   • fileName: $fileName")
    
    val detectedLanguage = detectLanguage(fileName)
    android.util.Log.d("FileUtils", "   • detected language: $detectedLanguage")
    
    val currentTime = Clock.System.now()
    android.util.Log.d("FileUtils", "   • timestamp: $currentTime")
    
    val entity = CachedFileEntity(
        filePath = filePath,
        fileName = fileName,
        content = content,
        sizeBytes = contentSizeBytes,
        language = detectedLanguage,
        addedAt = currentTime,
        repoOwner = repoOwner,
        repoName = repoName,
        branch = branch,
        sha = sha,
        isEncrypted = false,
        encryptionIv = null
    )
    
    android.util.Log.d("FileUtils", "   FINAL ENTITY:")
    android.util.Log.d("FileUtils", "   • Full path: ${entity.filePath}")
    android.util.Log.d("FileUtils", "   • Size: ${entity.sizeBytes} bytes")
    android.util.Log.d("FileUtils", "   • Language: ${entity.language}")
    android.util.Log.d("FileUtils", "   • Repository: ${entity.repoOwner}/${entity.repoName}")
    android.util.Log.d("FileUtils", "   • Branch: ${entity.branch}")
    android.util.Log.d("FileUtils", "   • SHA: ${entity.sha}")
    android.util.Log.d("FileUtils", "   • Encrypted: ${entity.isEncrypted}")
    android.util.Log.d("FileUtils", "✅ CachedFileEntity created successfully")
    android.util.Log.d("FileUtils", "━".repeat(80))
    
    return entity
}