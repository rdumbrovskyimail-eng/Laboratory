package com.opuside.app.core.git

import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.GitHubApiException
import com.opuside.app.core.network.github.model.GitHubContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-уровневая система разрешения Git конфликтов.
 * 
 * Сценарий:
 * 1. Пользователь редактирует файл в OpusIDE
 * 2. Кто-то другой (или он сам с компьютера) пушит изменения
 * 3. При попытке сохранить -> 409 Conflict
 * 4. Этот класс показывает UI для разрешения
 * 
 * Поддерживаемые стратегии:
 * - KEEP_MINE: перезаписать удаленную версию (force push)
 * - KEEP_THEIRS: отменить локальные изменения
 * - MANUAL_MERGE: 3-way diff для ручного слияния
 * - SAVE_AS_COPY: сохранить как новый файл
 */
@Singleton
class GitConflictResolver @Inject constructor(
    private val gitHubClient: GitHubApiClient
) {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFLICT DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Безопасное сохранение файла с автоматической обработкой конфликтов.
     * 
     * @return ConflictResult с информацией о результате
     */
    suspend fun saveFileWithConflictHandling(
        path: String,
        localContent: String,
        currentSha: String,
        branch: String,
        commitMessage: String
    ): ConflictResult = withContext(Dispatchers.IO) {
        
        // Попытка №1: Обычное сохранение
        val saveResult = gitHubClient.createOrUpdateFile(
            path = path,
            content = localContent,
            message = commitMessage,
            sha = currentSha,
            branch = branch
        )

        when {
            saveResult.isSuccess -> {
                ConflictResult.Success(
                    newSha = saveResult.getOrNull()!!.content.sha
                )
            }
            
            saveResult.isFailure -> {
                val error = saveResult.exceptionOrNull()
                
                // Проверяем, это конфликт или другая ошибка
                if (error is GitHubApiException && error.statusCode == 409) {
                    handleConflict(path, localContent, currentSha, branch)
                } else {
                    ConflictResult.Error(
                        message = error?.message ?: "Unknown error"
                    )
                }
            }
            
            else -> ConflictResult.Error("Unexpected state")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFLICT HANDLING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Обрабатывает обнаруженный конфликт.
     * 
     * 1. Загружает актуальную версию с сервера
     * 2. Сравнивает с локальной
     * 3. Возвращает данные для UI
     */
    private suspend fun handleConflict(
        path: String,
        localContent: String,
        outdatedSha: String,
        branch: String
    ): ConflictResult {
        
        // Получаем актуальную версию файла с сервера
        val remoteFileResult = gitHubClient.getFileContent(path, branch)
        
        if (remoteFileResult.isFailure) {
            return ConflictResult.Error(
                message = "Failed to fetch remote version: ${remoteFileResult.exceptionOrNull()?.message}"
            )
        }

        val remoteFile = remoteFileResult.getOrNull()!!
        val remoteContentResult = gitHubClient.getFileContentDecoded(path, branch)
        
        if (remoteContentResult.isFailure) {
            return ConflictResult.Error(
                message = "Failed to decode remote content"
            )
        }

        val remoteContent = remoteContentResult.getOrNull()!!

        // Генерируем diff
        val diff = generateDiff(localContent, remoteContent)

        return ConflictResult.Conflict(
            path = path,
            localContent = localContent,
            remoteContent = remoteContent,
            remoteSha = remoteFile.sha,
            diff = diff,
            conflictedLines = findConflictedLines(diff)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFLICT RESOLUTION STRATEGIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Стратегия: Оставить мои изменения (force push).
     */
    suspend fun resolveKeepMine(
        conflict: ConflictResult.Conflict,
        branch: String,
        commitMessage: String = "Resolve conflict: keep local changes"
    ): ConflictResult = withContext(Dispatchers.IO) {
        
        val result = gitHubClient.createOrUpdateFile(
            path = conflict.path,
            content = conflict.localContent,
            message = commitMessage,
            sha = conflict.remoteSha, // Используем актуальный SHA!
            branch = branch
        )

        if (result.isSuccess) {
            ConflictResult.Success(
                newSha = result.getOrNull()!!.content.sha
            )
        } else {
            ConflictResult.Error(
                message = "Failed to apply local changes: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    /**
     * Стратегия: Принять удаленные изменения (отменить локальные).
     */
    suspend fun resolveKeepTheirs(
        conflict: ConflictResult.Conflict
    ): ConflictResult {
        // Просто возвращаем удаленную версию
        return ConflictResult.Success(
            newSha = conflict.remoteSha,
            message = "Local changes discarded. File reverted to remote version."
        )
    }

    /**
     * Стратегия: Ручное слияние.
     * Пользователь редактирует в UI, затем сохраняет результат.
     */
    suspend fun resolveManualMerge(
        conflict: ConflictResult.Conflict,
        mergedContent: String,
        branch: String,
        commitMessage: String = "Resolve conflict: manual merge"
    ): ConflictResult = withContext(Dispatchers.IO) {
        
        val result = gitHubClient.createOrUpdateFile(
            path = conflict.path,
            content = mergedContent,
            message = commitMessage,
            sha = conflict.remoteSha,
            branch = branch
        )

        if (result.isSuccess) {
            ConflictResult.Success(
                newSha = result.getOrNull()!!.content.sha
            )
        } else {
            ConflictResult.Error(
                message = "Failed to save merged version: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    /**
     * Стратегия: Сохранить как копию (новый файл).
     */
    suspend fun resolveSaveAsCopy(
        conflict: ConflictResult.Conflict,
        branch: String,
        commitMessage: String = "Save conflicted version as copy"
    ): ConflictResult = withContext(Dispatchers.IO) {
        
        val copyPath = generateCopyPath(conflict.path)
        
        val result = gitHubClient.createOrUpdateFile(
            path = copyPath,
            content = conflict.localContent,
            message = commitMessage,
            sha = null, // Новый файл
            branch = branch
        )

        if (result.isSuccess) {
            ConflictResult.Success(
                newSha = result.getOrNull()!!.content.sha,
                message = "Local changes saved as: $copyPath"
            )
        } else {
            ConflictResult.Error(
                message = "Failed to create copy: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DIFF GENERATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Генерирует unified diff между двумя версиями.
     * Использует Myers' diff algorithm (упрощенная версия).
     */
    private fun generateDiff(
        localContent: String,
        remoteContent: String
    ): List<DiffLine> {
        val localLines = localContent.lines()
        val remoteLines = remoteContent.lines()
        
        val diff = mutableListOf<DiffLine>()
        
        // Простая построчная сверка (в продакшене используйте java-diff-utils)
        val maxLines = maxOf(localLines.size, remoteLines.size)
        
        for (i in 0 until maxLines) {
            val localLine = localLines.getOrNull(i)
            val remoteLine = remoteLines.getOrNull(i)
            
            when {
                localLine == remoteLine -> {
                    diff.add(DiffLine.Unchanged(i, localLine ?: ""))
                }
                localLine != null && remoteLine == null -> {
                    diff.add(DiffLine.Added(i, localLine))
                }
                localLine == null && remoteLine != null -> {
                    diff.add(DiffLine.Removed(i, remoteLine))
                }
                else -> {
                    diff.add(DiffLine.Modified(i, localLine!!, remoteLine!!))
                }
            }
        }
        
        return diff
    }

    /**
     * Находит строки с конфликтами.
     */
    private fun findConflictedLines(diff: List<DiffLine>): List<Int> {
        return diff
            .filterIsInstance<DiffLine.Modified>()
            .map { it.lineNumber }
    }

    /**
     * Генерирует имя для копии файла.
     * Example: "MainActivity.kt" -> "MainActivity.conflict-2026-01-29.kt"
     */
    private fun generateCopyPath(originalPath: String): String {
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        
        val lastSlash = originalPath.lastIndexOf('/')
        val path = if (lastSlash >= 0) originalPath.substring(0, lastSlash + 1) else ""
        val fileName = if (lastSlash >= 0) originalPath.substring(lastSlash + 1) else originalPath
        
        val dotIndex = fileName.lastIndexOf('.')
        val name = if (dotIndex >= 0) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex >= 0) fileName.substring(dotIndex) else ""
        
        return "${path}${name}.conflict-${timestamp}${ext}"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RESULT TYPES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Результат операции сохранения/разрешения конфликта.
 */
sealed class ConflictResult {
    /**
     * Успешное сохранение без конфликтов.
     */
    data class Success(
        val newSha: String,
        val message: String? = null
    ) : ConflictResult()

    /**
     * Обнаружен конфликт - требуется действие пользователя.
     */
    data class Conflict(
        val path: String,
        val localContent: String,
        val remoteContent: String,
        val remoteSha: String,
        val diff: List<DiffLine>,
        val conflictedLines: List<Int>
    ) : ConflictResult()

    /**
     * Ошибка при сохранении.
     */
    data class Error(
        val message: String
    ) : ConflictResult()
}

/**
 * Строка в diff.
 */
sealed class DiffLine {
    abstract val lineNumber: Int

    data class Unchanged(
        override val lineNumber: Int,
        val content: String
    ) : DiffLine()

    data class Added(
        override val lineNumber: Int,
        val content: String
    ) : DiffLine()

    data class Removed(
        override val lineNumber: Int,
        val content: String
    ) : DiffLine()

    data class Modified(
        override val lineNumber: Int,
        val localContent: String,
        val remoteContent: String
    ) : DiffLine()
}

/**
 * Стратегии разрешения конфликтов.
 */
enum class ConflictStrategy {
    KEEP_MINE,      // Оставить локальные изменения
    KEEP_THEIRS,    // Принять удаленные изменения
    MANUAL_MERGE,   // Ручное слияние
    SAVE_AS_COPY    // Сохранить как копию
}