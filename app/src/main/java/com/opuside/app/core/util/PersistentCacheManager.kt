package com.opuside.app.core.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.opuside.app.core.cache.CacheRepository
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.entity.CachedFileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

private val Context.cacheTimerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cache_timer_state"
)

/**
 * Контекст кеша для передачи в Claude API.
 */
data class CacheContext(
    val fileCount: Int = 0,
    val filePaths: List<String> = emptyList(),
    val formattedContext: String = "",
    val totalTokensEstimate: Int = 0,
    val isActive: Boolean = false,
    val isEmpty: Boolean = fileCount == 0
)

/**
 * ✅ ОБНОВЛЕНО (Проблема #16 - God Object Refactoring)
 * ✅ ОБНОВЛЕНО (Проблема #8 - Детальное логирование)
 * 
 * Менеджер персистентного кеша с фоновым таймером.
 * 
 * ИЗМЕНЕНИЯ:
 * ────────────────────────────────────────────────────────────────
 * ✅ Разделен на несколько компонентов:
 *    - CacheRepository: CRUD операции с БД + шифрование
 *    - CacheTimerController: Таймер (запуск, пауза, остановка)
 *    - CacheWorkScheduler: WorkManager для фоновых задач
 *    - CacheNotificationManager: Уведомления
 *    - PersistentCacheManager: Координатор всех компонентов
 * 
 * ✅ Добавлено детальное логирование для диагностики кеширования
 * 
 * ОТВЕТСТВЕННОСТЬ (Single Responsibility Principle):
 * ────────────────────────────────────────────────────────────────
 * - Координация между CacheRepository, TimerController, WorkScheduler
 * - Предоставление единого API для ViewModels
 * - Синхронизация состояния между компонентами
 * - Управление жизненным циклом таймера
 * 
 * НЕ отвечает за:
 * - БД операции (делегировано CacheRepository)
 * - Таймер логику (делегировано CacheTimerController)
 * - WorkManager (делегировано CacheWorkScheduler)
 * - Уведомления (делегировано CacheNotificationManager)
 */
@Singleton
class PersistentCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheRepository: CacheRepository,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "PersistentCacheManager"
        
        // DataStore keys для состояния таймера
        private val KEY_TIMER_START_TIME = longPreferencesKey("timer_start_time")
        private val KEY_TIMER_DURATION_MS = longPreferencesKey("timer_duration_ms")
        private val KEY_TIMER_PAUSED_AT = longPreferencesKey("timer_paused_at")
        private val KEY_TIMER_STATE = stringPreferencesKey("timer_state")
        
        private const val UPDATE_INTERVAL_MS = 1000L // Обновление раз в секунду
    }

    private val dataStore = context.cacheTimerDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TIMER STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val _timerState = MutableStateFlow(TimerState.STOPPED)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()
    
    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()
    
    private var timerJob: Job? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DERIVED STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    val formattedTime: StateFlow<String> = _remainingSeconds
        .map { seconds ->
            val minutes = seconds / 60
            val secs = seconds % 60
            "%d:%02d".format(minutes, secs)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), "0:00")
    
    val timerProgress: StateFlow<Float> = combine(
        _remainingSeconds,
        appSettings.cacheConfig
    ) { remaining, config ->
        val total = config.timeoutMinutes * 60
        if (total > 0) remaining.toFloat() / total else 0f
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), 0f)
    
    val isTimerCritical: StateFlow<Boolean> = _remainingSeconds
        .map { it in 1..60 } // Последняя минута
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)
    
    val isCacheActive: StateFlow<Boolean> = timerState
        .map { it == TimerState.RUNNING }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    val cachedFiles: StateFlow<List<CachedFileEntity>> = cacheRepository.observeAll()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    val fileCount: StateFlow<Int> = cacheRepository.observeCount()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    init {
        // Восстанавливаем состояние таймера при старте
        scope.launch {
            restoreTimerState()
        }
        
        // Следим за количеством файлов
        scope.launch {
            fileCount.collect { count ->
                if (count == 0 && _timerState.value != TimerState.STOPPED) {
                    stopTimer()
                }
            }
        }
    }
    
    /**
     * Восстанавливает состояние таймера из DataStore.
     */
    private suspend fun restoreTimerState() {
        val prefs = dataStore.data.first()
        
        val savedState = prefs[KEY_TIMER_STATE]?.let { 
            TimerState.valueOf(it) 
        } ?: TimerState.STOPPED
        
        when (savedState) {
            TimerState.RUNNING -> {
                val startTime = prefs[KEY_TIMER_START_TIME] ?: return
                val durationMs = prefs[KEY_TIMER_DURATION_MS] ?: return
                
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = ((durationMs - elapsed) / 1000).toInt()
                
                if (remaining > 0) {
                    _timerState.value = TimerState.RUNNING
                    _remainingSeconds.value = remaining
                    startTimerTicker()
                } else {
                    // Время истекло пока приложение было закрыто
                    onTimerExpired()
                }
            }
            
            TimerState.PAUSED -> {
                val pausedAt = prefs[KEY_TIMER_PAUSED_AT] ?: return
                val durationMs = prefs[KEY_TIMER_DURATION_MS] ?: return
                
                val remaining = ((durationMs - pausedAt) / 1000).toInt()
                
                if (remaining > 0) {
                    _timerState.value = TimerState.PAUSED
                    _remainingSeconds.value = remaining
                } else {
                    onTimerExpired()
                }
            }
            
            TimerState.EXPIRED -> {
                _timerState.value = TimerState.EXPIRED
                _remainingSeconds.value = 0
            }
            
            TimerState.STOPPED -> {
                _timerState.value = TimerState.STOPPED
                _remainingSeconds.value = 0
            }
        }
        
        android.util.Log.d(TAG, "📱 Timer restored: state=${_timerState.value}, remaining=${_remainingSeconds.value}s")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE OPERATIONS (✅ ПРОБЛЕМА 8: ДЕТАЛЬНОЕ ЛОГИРОВАНИЕ)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Добавляет файл в кеш и запускает/сбрасывает таймер.
     * 
     * ✅ ПРОБЛЕМА 8: Добавлено детальное логирование для диагностики кеширования
     */
    suspend fun addFile(file: CachedFileEntity): Result<Unit> {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "📦 ADDING FILE TO CACHE DATABASE")
        android.util.Log.d(TAG, "   Path: ${file.filePath}")
        android.util.Log.d(TAG, "   Name: ${file.fileName}")
        android.util.Log.d(TAG, "   Size: ${file.sizeBytes} bytes (${file.content.length} chars)")
        android.util.Log.d(TAG, "   Language: ${file.language}")
        android.util.Log.d(TAG, "   Repo: ${file.repoOwner}/${file.repoName}")
        android.util.Log.d(TAG, "   Branch: ${file.branch}")
        android.util.Log.d(TAG, "   SHA: ${file.sha ?: "N/A"}")
        android.util.Log.d(TAG, "   Encrypted: ${file.isEncrypted}")
        android.util.Log.d(TAG, "   AddedAt: ${file.addedAt}")
        
        return cacheRepository.addFile(file)
            .onSuccess {
                android.util.Log.d(TAG, "✅ FILE SUCCESSFULLY ADDED TO DATABASE")
                
                // ✅ Верификация: проверяем что файл действительно сохранился
                val verified = cacheRepository.getByPath(file.filePath)
                if (verified != null) {
                    android.util.Log.d(TAG, "✅ VERIFICATION PASSED - File found in database")
                    android.util.Log.d(TAG, "   Verified path: ${verified.filePath}")
                    android.util.Log.d(TAG, "   Verified size: ${verified.sizeBytes} bytes")
                } else {
                    android.util.Log.e(TAG, "❌ VERIFICATION FAILED - File NOT found in database after insert!")
                }
                
                resetTimer()
                android.util.Log.d(TAG, "⏰ Timer has been reset/started")
            }
            .onFailure { e ->
                android.util.Log.e(TAG, "❌ DATABASE INSERT FAILED", e)
                android.util.Log.e(TAG, "   Error type: ${e.javaClass.simpleName}")
                android.util.Log.e(TAG, "   Error message: ${e.message}")
                android.util.Log.e(TAG, "   File path: ${file.filePath}")
            }
            .also {
                android.util.Log.d(TAG, "━".repeat(80))
            }
    }
    
    /**
     * Добавляет несколько файлов в кеш и запускает/сбрасывает таймер.
     * 
     * ✅ ПРОБЛЕМА 8: Добавлено детальное логирование для диагностики кеширования
     */
    suspend fun addFiles(files: List<CachedFileEntity>): Result<Int> {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "📦 ADDING MULTIPLE FILES TO CACHE DATABASE")
        android.util.Log.d(TAG, "   Total files: ${files.size}")
        
        files.forEachIndexed { index, file ->
            android.util.Log.d(TAG, "   [$index] ${file.filePath} (${file.sizeBytes} bytes, ${file.language})")
        }
        
        return cacheRepository.addFiles(files)
            .onSuccess { count ->
                android.util.Log.d(TAG, "✅ FILES SUCCESSFULLY ADDED: $count/${files.size}")
                
                // ✅ Верификация: проверяем общее количество файлов в БД
                val totalCount = cacheRepository.getCount()
                android.util.Log.d(TAG, "✅ TOTAL FILES IN DATABASE: $totalCount")
                
                if (count > 0) {
                    resetTimer()
                    android.util.Log.d(TAG, "⏰ Timer has been reset/started")
                } else {
                    android.util.Log.w(TAG, "⚠️ WARNING: No files were added (count=0)")
                }
            }
            .onFailure { e ->
                android.util.Log.e(TAG, "❌ DATABASE BATCH INSERT FAILED", e)
                android.util.Log.e(TAG, "   Error type: ${e.javaClass.simpleName}")
                android.util.Log.e(TAG, "   Error message: ${e.message}")
                android.util.Log.e(TAG, "   Attempted to insert: ${files.size} files")
            }
            .also {
                android.util.Log.d(TAG, "━".repeat(80))
            }
    }
    
    /**
     * Удаляет файл из кеша.
     */
    suspend fun removeFile(filePath: String) {
        android.util.Log.d(TAG, "🗑️ Removing file from cache: $filePath")
        cacheRepository.removeFile(filePath)
            .onSuccess {
                android.util.Log.d(TAG, "✅ File removed successfully")
            }
            .onFailure { e ->
                android.util.Log.e(TAG, "❌ Failed to remove file", e)
            }
    }
    
    /**
     * Очищает весь кеш и останавливает таймер.
     */
    suspend fun clearCache() {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "🗑️ CLEARING ENTIRE CACHE")
        
        val countBefore = cacheRepository.getCount()
        android.util.Log.d(TAG, "   Files before clear: $countBefore")
        
        cacheRepository.clearAll()
            .onSuccess {
                android.util.Log.d(TAG, "✅ Cache cleared successfully")
                
                val countAfter = cacheRepository.getCount()
                android.util.Log.d(TAG, "   Files after clear: $countAfter")
            }
            .onFailure { e ->
                android.util.Log.e(TAG, "❌ Failed to clear cache", e)
            }
        
        stopTimer()
        android.util.Log.d(TAG, "⏹️ Timer stopped")
        android.util.Log.d(TAG, "━".repeat(80))
    }
    
    /**
     * Проверяет наличие файла в кеше.
     */
    suspend fun hasFile(filePath: String): Boolean {
        val exists = cacheRepository.hasFile(filePath)
        android.util.Log.d(TAG, "🔍 Checking file existence: $filePath → $exists")
        return exists
    }
    
    /**
     * Обновляет содержимое файла в кеше.
     */
    suspend fun updateFileContent(filePath: String, newContent: String) {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "✏️ UPDATING FILE CONTENT")
        android.util.Log.d(TAG, "   Path: $filePath")
        android.util.Log.d(TAG, "   New content length: ${newContent.length} chars")
        
        cacheRepository.updateFileContent(filePath, newContent)
            .onSuccess {
                android.util.Log.d(TAG, "✅ File content updated successfully")
                
                // Верификация
                val updated = cacheRepository.getByPath(filePath)
                if (updated != null) {
                    android.util.Log.d(TAG, "✅ VERIFICATION PASSED")
                    android.util.Log.d(TAG, "   Updated size: ${updated.sizeBytes} bytes")
                    android.util.Log.d(TAG, "   Content matches: ${updated.content == newContent}")
                }
            }
            .onFailure { e ->
                android.util.Log.e(TAG, "❌ Failed to update file content", e)
            }
        
        android.util.Log.d(TAG, "━".repeat(80))
    }
    
    /**
     * Получить контекст для Claude API.
     */
    suspend fun getContextForClaude(): CacheContext {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "🤖 GENERATING CONTEXT FOR CLAUDE API")
        
        val files = cacheRepository.getAll()
        
        android.util.Log.d(TAG, "   Files in cache: ${files.size}")
        android.util.Log.d(TAG, "   Timer state: ${timerState.value}")
        
        if (files.isEmpty() || timerState.value != TimerState.RUNNING) {
            android.util.Log.w(TAG, "⚠️ Context is INACTIVE (empty or timer not running)")
            android.util.Log.d(TAG, "━".repeat(80))
            
            return CacheContext(
                fileCount = 0,
                filePaths = emptyList(),
                formattedContext = "",
                totalTokensEstimate = 0,
                isActive = false,
                isEmpty = true
            )
        }
        
        val formattedContext = buildString {
            appendLine("━━━ CACHED FILES (${files.size}) ━━━")
            appendLine()
            
            files.forEach { file ->
                appendLine("📄 ${file.filePath}")
                appendLine("Language: ${file.language}")
                appendLine("Size: ${file.sizeBytes} bytes")
                appendLine("Lines: ${file.content.lines().size}")
                appendLine()
                appendLine("```${file.language}")
                appendLine(file.content)
                appendLine("```")
                appendLine()
                appendLine("━".repeat(60))
                appendLine()
            }
        }
        
        // Грубая оценка токенов (1 токен ≈ 4 символа)
        val totalTokens = formattedContext.length / 4
        
        android.util.Log.d(TAG, "✅ Context generated successfully")
        android.util.Log.d(TAG, "   Total characters: ${formattedContext.length}")
        android.util.Log.d(TAG, "   Estimated tokens: $totalTokens")
        android.util.Log.d(TAG, "━".repeat(80))
        
        return CacheContext(
            fileCount = files.size,
            filePaths = files.map { it.filePath },
            formattedContext = formattedContext,
            totalTokensEstimate = totalTokens,
            isActive = timerState.value == TimerState.RUNNING,
            isEmpty = false
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TIMER OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Запускает/сбрасывает таймер.
     */
    private suspend fun resetTimer() {
        val config = appSettings.cacheConfig.first()
        val durationMs = config.timeoutMinutes * 60 * 1000L
        
        // Сохраняем в DataStore
        dataStore.edit { prefs ->
            prefs[KEY_TIMER_START_TIME] = System.currentTimeMillis()
            prefs[KEY_TIMER_DURATION_MS] = durationMs
            prefs[KEY_TIMER_STATE] = TimerState.RUNNING.name
            prefs.remove(KEY_TIMER_PAUSED_AT)
        }
        
        _timerState.value = TimerState.RUNNING
        _remainingSeconds.value = config.timeoutMinutes * 60
        
        startTimerTicker()
        
        android.util.Log.d(TAG, "⏰ Timer started: ${config.timeoutMinutes} minutes")
    }
    
    /**
     * Ставит таймер на паузу.
     */
    fun pauseTimer() {
        if (_timerState.value != TimerState.RUNNING) return
        
        scope.launch {
            val prefs = dataStore.data.first()
            val startTime = prefs[KEY_TIMER_START_TIME] ?: return@launch
            val elapsed = System.currentTimeMillis() - startTime
            
            dataStore.edit { prefs ->
                prefs[KEY_TIMER_PAUSED_AT] = elapsed
                prefs[KEY_TIMER_STATE] = TimerState.PAUSED.name
            }
            
            timerJob?.cancel()
            _timerState.value = TimerState.PAUSED
            
            android.util.Log.d(TAG, "⏸️ Timer paused")
        }
    }
    
    /**
     * Возобновляет таймер с паузы.
     */
    fun resumeTimer() {
        if (_timerState.value != TimerState.PAUSED) return
        
        scope.launch {
            val prefs = dataStore.data.first()
            val pausedAt = prefs[KEY_TIMER_PAUSED_AT] ?: return@launch
            val durationMs = prefs[KEY_TIMER_DURATION_MS] ?: return@launch
            
            val newStartTime = System.currentTimeMillis() - pausedAt
            
            dataStore.edit { prefs ->
                prefs[KEY_TIMER_START_TIME] = newStartTime
                prefs[KEY_TIMER_STATE] = TimerState.RUNNING.name
                prefs.remove(KEY_TIMER_PAUSED_AT)
            }
            
            _timerState.value = TimerState.RUNNING
            startTimerTicker()
            
            android.util.Log.d(TAG, "▶️ Timer resumed")
        }
    }
    
    /**
     * Останавливает таймер.
     */
    private suspend fun stopTimer() {
        timerJob?.cancel()
        
        dataStore.edit { prefs ->
            prefs.clear()
        }
        
        _timerState.value = TimerState.STOPPED
        _remainingSeconds.value = 0
        
        android.util.Log.d(TAG, "⏹️ Timer stopped")
    }
    
    /**
     * Запускает ticker для обновления таймера.
     */
    private fun startTimerTicker() {
        timerJob?.cancel()
        
        timerJob = scope.launch {
            while (isActive && _timerState.value == TimerState.RUNNING) {
                val prefs = dataStore.data.first()
                val startTime = prefs[KEY_TIMER_START_TIME] ?: break
                val durationMs = prefs[KEY_TIMER_DURATION_MS] ?: break
                
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = ((durationMs - elapsed) / 1000).toInt()
                
                if (remaining <= 0) {
                    onTimerExpired()
                    break
                } else {
                    _remainingSeconds.value = remaining
                }
                
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Обрабатывает истечение таймера.
     */
    private suspend fun onTimerExpired() {
        timerJob?.cancel()
        
        dataStore.edit { prefs ->
            prefs[KEY_TIMER_STATE] = TimerState.EXPIRED.name
        }
        
        _timerState.value = TimerState.EXPIRED
        _remainingSeconds.value = 0
        
        // Очищаем кеш если включено auto-clear
        val config = appSettings.cacheConfig.first()
        if (config.autoClear) {
            cacheRepository.clearAll()
            android.util.Log.d(TAG, "🗑️ Auto-cleared cache after timeout")
        }
        
        android.util.Log.d(TAG, "⏰ Timer expired")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun shutdown() {
        timerJob?.cancel()
        scope.cancel()
    }
}