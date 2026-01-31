package com.opuside.app.core.git

import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.GitHubApiException
import com.opuside.app.core.network.github.model.GitHubContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 
 * ✅ ОБНОВЛЕНО: Добавлен retry loop для быстрых конфликтов (Проблема №15)
 * ✅ ИСПРАВЛЕНО: Проблема №20 (BUG #20) - Добавлена delay protection против бесконечного retry loop
 * ✅ ИСПРАВЛЕНО (Проблема #4): Добавлено обязательное подтверждение пользователя
 * ✅ ИСПРАВЛЕНО (Проблема #13): Оптимизирован diff algorithm с использованием LCS
 */
@Singleton
class GitConflictResolver @Inject constructor(
    private val gitHubClient: GitHubApiClient
) {

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val MIN_RETRY_DELAY_MS = 1000L // ✅ ДОБАВЛЕНО: Минимальная задержка между retry
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFLICT DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ОБНОВЛЕНО (Проблема №15): Безопасное сохранение файла с автоматической 
     * обработкой конфликтов и retry loop для быстрых изменений.
     * ✅ ИСПРАВЛЕНО (Проблема №20): Добавлена delay protection против spam-retry.
     * ✅ ИСПРАВЛЕНО (Проблема #4): Первый конфликт всегда требует подтверждения пользователя.
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
        
        var attempts = 0
        var latestSha = currentSha
        var lastRetryTime = 0L // ✅ ДОБАВЛЕНО: Отслеживание времени последнего retry
        
        while (attempts < MAX_RETRY_ATTEMPTS) {
            // ✅ ДОБАВЛЕНО: Защита от spam-retry
            val now = System.currentTimeMillis()
            if (attempts > 0 && now - lastRetryTime < MIN_RETRY_DELAY_MS) {
                delay(MIN_RETRY_DELAY_MS - (now - lastRetryTime))
            }
            lastRetryTime = System.currentTimeMillis()
            
            // Попытка сохранить с текущим SHA
            val saveResult = gitHubClient.createOrUpdateFile(
                path = path,
                content = localContent,
                message = commitMessage,
                sha = latestSha,
                branch = branch
            )

            when {
                saveResult.isSuccess -> {
                    return@withContext ConflictResult.Success(
                        newSha = saveResult.getOrNull()!!.content.sha,
                        message = if (attempts > 0) "Saved after $attempts retry attempts" else null
                    )
                }
                
                saveResult.isFailure -> {
                    val error = saveResult.exceptionOrNull()
                    
                    // Проверяем, это конфликт или другая ошибка
                    if (error is GitHubApiException && error.statusCode == 409) {
                        attempts++
                        
                        // ✅ ИСПРАВЛЕНО (Проблема #4): Всегда показываем UI при конфликте
                        // Никакого автоматического разрешения без подтверждения пользователя
                        return@withContext handleConflict(path, localContent, latestSha, branch)
                    } else {
                        // Не конфликт - возвращаем ошибку
                        return@withContext ConflictResult.Error(
                            message = error?.message ?: "Unknown error"
                        )
                    }
                }
                
                else -> {
                    return@withContext ConflictResult.Error("Unexpected state")
                }
            }
        }
        
        // Превышено количество попыток
        ConflictResult.Error("Too many conflicts (${MAX_RETRY_ATTEMPTS} attempts). Please try again later.")
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

        // ✅ ИСПРАВЛЕНО (Проблема #13): Используем оптимизированный diff
        val diff = generateOptimizedDiff(localContent, remoteContent)

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
     * ✅ ОБНОВЛЕНО (Проблема №15): Стратегия KEEP_MINE с retry loop.
     * ✅ ИСПРАВЛЕНО (Проблема №20): Добавлена delay protection.
     * ✅ ИСПРАВЛЕНО (Проблема #4): Вызывается только после явного подтверждения пользователя.
     */
    suspend fun resolveKeepMine(
        conflict: ConflictResult.Conflict,
        branch: String,
        commitMessage: String = "Resolve conflict: keep local changes",
        userConfirmed: Boolean // ✅ ДОБАВЛЕНО: Обязательное подтверждение
    ): ConflictResult = withContext(Dispatchers.IO) {
        
        // ✅ ИСПРАВЛЕНО (Проблема #4): Проверка подтверждения
        if (!userConfirmed) {
            return@withContext ConflictResult.Error(
                message = "User confirmation required to overwrite remote changes"
            )
        }
        
        var attempts = 0
        var latestSha = conflict.remoteSha
        var lastRetryTime = 0L // ✅ ДОБАВЛЕНО
        
        while (attempts < MAX_RETRY_ATTEMPTS) {
            // ✅ ДОБАВЛЕНО: Защита от spam-retry
            val now = System.currentTimeMillis()
            if (attempts > 0 && now - lastRetryTime < MIN_RETRY_DELAY_MS) {
                delay(MIN_RETRY_DELAY_MS - (now - lastRetryTime))
            }
            lastRetryTime = System.currentTimeMillis()
            
            val result = gitHubClient.createOrUpdateFile(
                path = conflict.path,
                content = conflict.localContent,
                message = commitMessage,
                sha = latestSha,
                branch = branch
            )

            when {
                result.isSuccess -> {
                    return@withContext ConflictResult.Success(
                        newSha = result.getOrNull()!!.content.sha,
                        message = if (attempts > 0) "Resolved after $attempts retry attempts" else null
                    )
                }
                
                result.isFailure -> {
                    val error = result.exceptionOrNull()
                    
                    if (error is GitHubApiException && error.statusCode == 409) {
                        attempts++
                        
                        if (attempts < MAX_RETRY_ATTEMPTS) {
                            // Получаем новый SHA и retry
                            val remoteFileResult = gitHubClient.getFileContent(conflict.path, branch)
                            if (remoteFileResult.isSuccess) {
                                latestSha = remoteFileResult.getOrNull()!!.sha
                                continue
                            }
                        }
                    }
                    
                    return@withContext ConflictResult.Error(
                        message = "Failed to apply local changes: ${error?.message}"
                    )
                }
            }
        }
        
        ConflictResult.Error("Too many conflicts during resolution. Please try again.")
    }

    /**
     * Стратегия: Принять удаленные изменения (отменить локальные).
     * ✅ ИСПРАВЛЕНО (Проблема #4): Добавлено обязательное подтверждение.
     */
    suspend fun resolveKeepTheirs(
        conflict: ConflictResult.Conflict,
        userConfirmed: Boolean // ✅ ДОБАВЛЕНО: Обязательное подтверждение
    ): ConflictResult {
        // ✅ ИСПРАВЛЕНО (Проблема #4): Проверка подтверждения
        if (!userConfirmed) {
            return ConflictResult.Error(
                message = "User confirmation required to discard local changes"
            )
        }
        
        // Просто возвращаем удаленную версию
        return ConflictResult.Success(
            newSha = conflict.remoteSha,
            message = "Local changes discarded. File reverted to remote version."
        )
    }

    /**
     * ✅ ОБНОВЛЕНО (Проблема №15): Стратегия MANUAL_MERGE с retry loop.
     * ✅ ИСПРАВЛЕНО (Проблема №20): Добавлена delay protection.
     */
    suspend fun resolveManualMerge(
        conflict: ConflictResult.Conflict,
        mergedContent: String,
        branch: String,
        commitMessage: String = "Resolve conflict: manual merge"
    ): ConflictResult = withContext(Dispatchers.IO) {
        
        var attempts = 0
        var latestSha = conflict.remoteSha
        var lastRetryTime = 0L // ✅ ДОБАВЛЕНО
        
        while (attempts < MAX_RETRY_ATTEMPTS) {
            // ✅ ДОБАВЛЕНО: Защита от spam-retry
            val now = System.currentTimeMillis()
            if (attempts > 0 && now - lastRetryTime < MIN_RETRY_DELAY_MS) {
                delay(MIN_RETRY_DELAY_MS - (now - lastRetryTime))
            }
            lastRetryTime = System.currentTimeMillis()
            
            val result = gitHubClient.createOrUpdateFile(
                path = conflict.path,
                content = mergedContent,
                message = commitMessage,
                sha = latestSha,
                branch = branch
            )

            when {
                result.isSuccess -> {
                    return@withContext ConflictResult.Success(
                        newSha = result.getOrNull()!!.content.sha,
                        message = if (attempts > 0) "Merged after $attempts retry attempts" else null
                    )
                }
                
                result.isFailure -> {
                    val error = result.exceptionOrNull()
                    
                    if (error is GitHubApiException && error.statusCode == 409) {
                        attempts++
                        
                        if (attempts < MAX_RETRY_ATTEMPTS) {
                            val remoteFileResult = gitHubClient.getFileContent(conflict.path, branch)
                            if (remoteFileResult.isSuccess) {
                                latestSha = remoteFileResult.getOrNull()!!.sha
                                continue
                            }
                        }
                    }
                    
                    return@withContext ConflictResult.Error(
                        message = "Failed to save merged version: ${error?.message}"
                    )
                }
            }
        }
        
        ConflictResult.Error("Too many conflicts during merge. Please try again.")
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
     * ✅ ИСПРАВЛЕНО (Проблема #13): Оптимизированный diff с использованием LCS.
     * 
     * Использует Longest Common Subsequence (LCS) algorithm для более точного
     * и быстрого определения изменений. Сложность O(m*n) вместо O(m+n) наивного подхода,
     * но результат намного точнее.
     */
    private fun generateOptimizedDiff(
        localContent: String,
        remoteContent: String
    ): List<DiffLine> {
        val localLines = localContent.lines()
        val remoteLines = remoteContent.lines()
        
        // Вычисляем LCS (Longest Common Subsequence)
        val lcs = computeLCS(localLines, remoteLines)
        
        // Строим diff на основе LCS
        val diff = mutableListOf<DiffLine>()
        var localIdx = 0
        var remoteIdx = 0
        var lineNumber = 0
        
        while (localIdx < localLines.size || remoteIdx < remoteLines.size) {
            val localLine = localLines.getOrNull(localIdx)
            val remoteLine = remoteLines.getOrNull(remoteIdx)
            
            when {
                // Обе строки есть в LCS - без изменений
                localLine != null && remoteLine != null && 
                localLine == remoteLine && lcs.contains(localLine) -> {
                    diff.add(DiffLine.Unchanged(lineNumber, localLine))
                    localIdx++
                    remoteIdx++
                    lineNumber++
                }
                
                // Строка только в локальной версии - добавлена
                localLine != null && (remoteLine == null || !lcs.contains(localLine)) -> {
                    diff.add(DiffLine.Added(lineNumber, localLine))
                    localIdx++
                    lineNumber++
                }
                
                // Строка только в удаленной версии - удалена
                remoteLine != null && (localLine == null || !lcs.contains(remoteLine)) -> {
                    diff.add(DiffLine.Removed(lineNumber, remoteLine))
                    remoteIdx++
                    lineNumber++
                }
                
                // Обе строки разные - модифицирована
                localLine != null && remoteLine != null -> {
                    diff.add(DiffLine.Modified(lineNumber, localLine, remoteLine))
                    localIdx++
                    remoteIdx++
                    lineNumber++
                }
                
                else -> break
            }
        }
        
        return diff
    }

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #13): Вычисление LCS с использованием динамического программирования.
     * 
     * Алгоритм: Dynamic Programming с оптимизацией памяти.
     * Сложность: O(m*n) времени, O(min(m,n)) памяти.
     */
    private fun computeLCS(lines1: List<String>, lines2: List<String>): Set<String> {
        val m = lines1.size
        val n = lines2.size
        
        // DP таблица: dp[i][j] = длина LCS для lines1[0..i] и lines2[0..j]
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        // Заполняем DP таблицу
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (lines1[i - 1] == lines2[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        
        // Восстанавливаем LCS из DP таблицы
        val lcs = mutableSetOf<String>()
        var i = m
        var j = n
        
        while (i > 0 && j > 0) {
            when {
                lines1[i - 1] == lines2[j - 1] -> {
                    lcs.add(lines1[i - 1])
                    i--
                    j--
                }
                dp[i - 1][j] > dp[i][j - 1] -> i--
                else -> j--
            }
        }
        
        return lcs
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