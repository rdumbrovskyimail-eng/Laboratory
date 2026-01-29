package com.opuside.app.core.util

import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.CacheDao
import com.opuside.app.core.database.entity.CachedFileEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Менеджер кеша файлов с точным 5-минутным таймером.
 * 
 * КЛЮЧЕВАЯ ЛОГИКА:
 * 1. Пользователь выбирает файлы для кеша (до 20 шт)
 * 2. Файлы кешируются, запускается таймер 5 минут
 * 3. Пока таймер идёт — Claude API использует закешированный контекст (экономия токенов!)
 * 4. Таймер истёк → кеш очищается → нужно заново выбрать файлы
 * 5. Любое добавление файла СБРАСЫВАЕТ таймер на 5 минут
 * 
 * API НЕ сканирует весь проект! Только файлы из кеша.
 */
@Singleton
class CacheManager @Inject constructor(
    private val cacheDao: CacheDao,
    private val appSettings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var timerJob: Job? = null
    private var currentTimeoutMs: Long = 5 * 60 * 1000L // Default 5 минут

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMER STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()
    
    private val _timerState = MutableStateFlow(TimerState.STOPPED)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    /** Форматированное время MM:SS */
    val formattedTime: StateFlow<String> = _remainingSeconds
        .map { secs ->
            val m = secs / 60
            val s = secs % 60
            "%02d:%02d".format(m, s)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), "00:00")

    /** Прогресс таймера 0.0 - 1.0 */
    val timerProgress: StateFlow<Float> = _remainingSeconds
        .map { secs ->
            if (currentTimeoutMs > 0) {
                secs.toFloat() / (currentTimeoutMs / 1000f)
            } else 0f
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0f)

    /** Таймер в критической зоне (< 1 минуты) */
    val isTimerCritical: StateFlow<Boolean> = _remainingSeconds
        .map { it in 1..59 }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Список кешированных файлов (reactive) */
    val cachedFiles: Flow<List<CachedFileEntity>> = cacheDao.observeAll()

    /** Количество файлов в кеше */
    val fileCount: StateFlow<Int> = cacheDao.observeCount()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0)

    /** Кеш пуст */
    val isEmpty: StateFlow<Boolean> = fileCount
        .map { it == 0 }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), true)

    /** Кеш активен (есть файлы И таймер идёт) */
    val isCacheActive: StateFlow<Boolean> = combine(fileCount, timerState) { count, state ->
        count > 0 && state == TimerState.RUNNING
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), false)

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    init {
        // Подписываемся на изменения настроек кеша
        scope.launch {
            appSettings.cacheConfig.collect { config ->
                currentTimeoutMs = config.timeoutMs
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Добавить файл в кеш.
     * - Проверяет лимит
     * - СБРАСЫВАЕТ таймер на полные 5 минут
     */
    suspend fun addFile(file: CachedFileEntity): Result<Unit> {
        return try {
            val maxFiles = appSettings.maxCacheFiles.first()
            val currentCount = cacheDao.getCount()
            
            if (currentCount >= maxFiles) {
                // Удаляем самый старый файл
                cacheDao.trimToSize(maxFiles - 1)
            }
            
            cacheDao.insert(file)
            
            // ВАЖНО: Сбрасываем таймер при добавлении
            resetTimer()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Добавить несколько файлов.
     */
    suspend fun addFiles(files: List<CachedFileEntity>): Result<Int> {
        return try {
            val maxFiles = appSettings.maxCacheFiles.first()
            val currentCount = cacheDao.getCount()
            val availableSlots = maxFiles - currentCount
            
            val filesToAdd = if (files.size > availableSlots) {
                // Удаляем старые, чтобы влезли новые
                cacheDao.trimToSize(maxFiles - files.size)
                files
            } else {
                files
            }
            
            cacheDao.insertAll(filesToAdd)
            resetTimer()
            
            Result.success(filesToAdd.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Удалить файл из кеша.
     */
    suspend fun removeFile(filePath: String) {
        cacheDao.deleteByPath(filePath)
        
        // Если кеш опустел — останавливаем таймер
        if (cacheDao.getCount() == 0) {
            stopTimer()
        }
    }

    /**
     * Очистить весь кеш.
     */
    suspend fun clearCache() {
        cacheDao.clearAll()
        stopTimer()
    }

    /**
     * Получить все файлы из кеша (snapshot).
     */
    suspend fun getAllFiles(): List<CachedFileEntity> = cacheDao.getAll()

    /**
     * Получить контекст для Claude API.
     * Возвращает отформатированный текст всех файлов в кеше.
     */
    suspend fun getContextForClaude(): CacheContext {
        val files = cacheDao.getAll()
        
        if (files.isEmpty()) {
            return CacheContext(
                files = emptyList(),
                formattedContext = "",
                totalTokensEstimate = 0,
                isActive = false
            )
        }

        val formattedContext = buildString {
            appendLine("=== CACHED FILES CONTEXT (${files.size} files) ===")
            appendLine("⏱ Cache expires in: ${formattedTime.value}")
            appendLine()
            
            files.forEachIndexed { index, file ->
                appendLine("━━━ FILE ${index + 1}/${files.size}: ${file.filePath} ━━━")
                appendLine("Language: ${file.language} | Size: ${file.sizeBytes} bytes")
                appendLine("```${file.language}")
                appendLine(file.content)
                appendLine("```")
                appendLine()
            }
        }

        // Грубая оценка токенов: ~4 символа = 1 токен
        val estimatedTokens = formattedContext.length / 4

        return CacheContext(
            files = files,
            formattedContext = formattedContext,
            totalTokensEstimate = estimatedTokens,
            isActive = _timerState.value == TimerState.RUNNING
        )
    }

    /**
     * Проверить, есть ли файл в кеше.
     */
    suspend fun hasFile(filePath: String): Boolean = cacheDao.getByPath(filePath) != null

    /**
     * Обновить содержимое файла в кеше (после редактирования).
     */
    suspend fun updateFileContent(filePath: String, newContent: String) {
        cacheDao.getByPath(filePath)?.let { file ->
            cacheDao.update(file.copy(
                content = newContent,
                sizeBytes = newContent.toByteArray().size,
                addedAt = Clock.System.now() // Обновляем время
            ))
        }
        // НЕ сбрасываем таймер при обновлении — только при добавлении!
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMER CONTROL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Запустить/сбросить таймер на полные 5 минут.
     */
    fun resetTimer() {
        stopTimerInternal()
        
        val totalSeconds = (currentTimeoutMs / 1000).toInt()
        _remainingSeconds.value = totalSeconds
        _timerState.value = TimerState.RUNNING
        
        timerJob = scope.launch {
            while (_remainingSeconds.value > 0 && isActive) {
                delay(1000)
                _remainingSeconds.value = _remainingSeconds.value - 1
            }
            
            if (_remainingSeconds.value == 0) {
                onTimerExpired()
            }
        }
    }

    /**
     * Приостановить таймер (например, при уходе из приложения).
     */
    fun pauseTimer() {
        if (_timerState.value == TimerState.RUNNING) {
            timerJob?.cancel()
            _timerState.value = TimerState.PAUSED
        }
    }

    /**
     * Возобновить таймер.
     */
    fun resumeTimer() {
        if (_timerState.value == TimerState.PAUSED && _remainingSeconds.value > 0) {
            _timerState.value = TimerState.RUNNING
            
            timerJob = scope.launch {
                while (_remainingSeconds.value > 0 && isActive) {
                    delay(1000)
                    _remainingSeconds.value = _remainingSeconds.value - 1
                }
                
                if (_remainingSeconds.value == 0) {
                    onTimerExpired()
                }
            }
        }
    }

    /**
     * Остановить таймер.
     */
    fun stopTimer() {
        stopTimerInternal()
        _remainingSeconds.value = 0
        _timerState.value = TimerState.STOPPED
    }

    private fun stopTimerInternal() {
        timerJob?.cancel()
        timerJob = null
    }

    /**
     * Добавить время к таймеру (бонус за активность).
     */
    fun extendTimer(additionalSeconds: Int = 60) {
        if (_timerState.value == TimerState.RUNNING) {
            val maxSeconds = (currentTimeoutMs / 1000).toInt()
            _remainingSeconds.value = minOf(
                _remainingSeconds.value + additionalSeconds,
                maxSeconds
            )
        }
    }

    /**
     * Вызывается когда таймер истёк.
     */
    private suspend fun onTimerExpired() {
        _timerState.value = TimerState.EXPIRED
        
        // Проверяем настройку автоочистки
        val autoClear = appSettings.autoClearCache.first()
        if (autoClear) {
            cacheDao.clearAll()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    fun cleanup() {
        stopTimerInternal()
        scope.cancel()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════════════

enum class TimerState {
    STOPPED,  // Таймер не запущен
    RUNNING,  // Таймер идёт
    PAUSED,   // Таймер приостановлен
    EXPIRED   // Таймер истёк
}

data class CacheContext(
    val files: List<CachedFileEntity>,
    val formattedContext: String,
    val totalTokensEstimate: Int,
    val isActive: Boolean
) {
    val fileCount: Int get() = files.size
    val isEmpty: Boolean get() = files.isEmpty()
    val filePaths: List<String> get() = files.map { it.filePath }
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Создать CachedFileEntity из данных.
 */
fun createCachedFile(
    filePath: String,
    content: String,
    repoOwner: String,
    repoName: String,
    branch: String = "main",
    sha: String? = null
): CachedFileEntity {
    val fileName = filePath.substringAfterLast("/")
    val language = detectLanguage(fileName)
    
    return CachedFileEntity(
        filePath = filePath,
        fileName = fileName,
        content = content,
        language = language,
        sizeBytes = content.toByteArray().size,
        repoOwner = repoOwner,
        repoName = repoName,
        branch = branch,
        sha = sha
    )
}

/**
 * Определить язык по расширению файла.
 */
fun detectLanguage(fileName: String): String {
    val ext = fileName.substringAfterLast(".", "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "md", "markdown" -> "markdown"
        "gradle" -> "gradle"
        "properties" -> "properties"
        "txt" -> "text"
        "html", "htm" -> "html"
        "css" -> "css"
        "js" -> "javascript"
        "ts" -> "typescript"
        "py" -> "python"
        "sh", "bash" -> "shell"
        "sql" -> "sql"
        "pro" -> "proguard"
        "toml" -> "toml"
        "gitignore" -> "gitignore"
        else -> "text"
    }
}
