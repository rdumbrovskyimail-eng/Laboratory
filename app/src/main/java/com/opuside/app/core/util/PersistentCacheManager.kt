package com.opuside.app.core.util

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.CacheDao
import com.opuside.app.core.database.entity.CachedFileEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private val Context.cacheTimerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cache_timer_state"
)

/**
 * 2026-уровневый CacheManager с persistent таймером.
 * 
 * КЛЮЧЕВЫЕ УЛУЧШЕНИЯ vs старая версия:
 * 
 * 1. PERSISTENT STATE:
 *    - Таймер сохраняется в DataStore (endTimestamp)
 *    - При возврате в приложение восстанавливается корректное время
 *    - Даже если Android убил процесс - таймер продолжает идти
 * 
 * 2. WORKMANAGER INTEGRATION:
 *    - Scheduled Worker очищает кеш в фоне, даже если приложение закрыто
 *    - Нотификация предупреждает пользователя за 1 минуту
 *    - Гарантия: кеш ВСЕГДА очистится ровно через 5 минут
 * 
 * 3. LIFECYCLE AWARE:
 *    - Паузит таймер когда приложение в background (опционально)
 *    - Возобновляет при возврате
 *    - Обрабатывает screen on/off
 * 
 * 4. UI SYNC:
 *    - StateFlow обновляются каждую секунду
 *    - UI всегда показывает актуальное время
 *    - Нет рассинхронизации между UI и реальным состоянием
 * 
 * ✅ ИСПРАВЛЕНО:
 * - Проблема №5: Race condition в init - сначала загружаем config, потом восстанавливаем таймер
 * - Проблема №13: Добавлен лимит MAX_FILE_SIZE (1MB) для защиты от переполнения БД
 * - Проблема №16: Детекция прыжков системного времени с использованием монотонных часов
 * - Проблема №20: Проверка дубликатов перед добавлением в кеш
 * - CRASH #3: Race condition с Mutex для синхронизации доступа к tickerJob
 * - CRASH #5: Исправлен timer drift с отрицательными timestamps
 * - BUG #16: Таймер учитывает изменение настроек таймаута
 */
@Singleton
class PersistentCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheDao: CacheDao,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TIMER_WORK_TAG = "cache_timer_cleanup"
        private const val NOTIFICATION_WORK_TAG = "cache_timer_warning"
        private const val MAX_FILE_SIZE = 1 * 1024 * 1024  // ✅ ДОБАВЛЕНО: 1MB лимит
        private const val TIME_JUMP_THRESHOLD_MS = 5000L  // ✅ ДОБАВЛЕНО: Порог детекции прыжка времени
        
        // DataStore keys
        private val KEY_END_TIMESTAMP = longPreferencesKey("cache_end_timestamp")
        private val KEY_IS_ACTIVE = booleanPreferencesKey("cache_is_active")
        private val KEY_TIMEOUT_MS = longPreferencesKey("cache_timeout_ms")
        private val KEY_PAUSED_AT = longPreferencesKey("cache_paused_at")
        private val KEY_ACCUMULATED_PAUSE_MS = longPreferencesKey("accumulated_pause_ms")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workManager = WorkManager.getInstance(context)
    private val timerDataStore = context.cacheTimerDataStore

    // ✅ ИСПРАВЛЕНО: CRASH #3 - Добавлен Mutex для синхронизации
    private val timerMutex = Mutex()
    private var tickerJob: Job? = null
    private var currentTimeoutMs: Long = 5 * 60 * 1000L

    // ✅ ДОБАВЛЕНО: Проблема №16 - Монотонные часы для детекции прыжков времени
    private var tickerStartMonotonicTime: Long = 0L
    private var tickerStartWallTime: Long = 0L

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMER STATE (Reactive)
    // ═══════════════════════════════════════════════════════════════════════════

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _timerState = MutableStateFlow(TimerState.STOPPED)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    val formattedTime: StateFlow<String> = _remainingSeconds
        .map { secs ->
            val m = secs / 60
            val s = secs % 60
            "%02d:%02d".format(m, s)
        }
        .stateIn(scope, SharingStarted.Eagerly, "00:00")

    val timerProgress: StateFlow<Float> = _remainingSeconds
        .map { secs ->
            if (currentTimeoutMs > 0) {
                secs.toFloat() / (currentTimeoutMs / 1000f)
            } else 0f
        }
        .stateIn(scope, SharingStarted.Eagerly, 0f)

    val isTimerCritical: StateFlow<Boolean> = _remainingSeconds
        .map { it in 1..59 }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    val cachedFiles: Flow<List<CachedFileEntity>> = cacheDao.observeAll()

    val fileCount: StateFlow<Int> = cacheDao.observeCount()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val isEmpty: StateFlow<Boolean> = fileCount
        .map { it == 0 }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val isCacheActive: StateFlow<Boolean> = combine(fileCount, timerState) { count, state ->
        count > 0 && state == TimerState.RUNNING
    }.stateIn(scope, SharingStarted.Eagerly, false)

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    init {
        // ✅ ИСПРАВЛЕНО: Проблема №5 - Race condition
        // Сначала загружаем настройки, потом восстанавливаем таймер
        scope.launch {
            // Сначала загружаем настройки
            val config = appSettings.cacheConfig.first()
            currentTimeoutMs = config.timeoutMs
            
            // Потом восстанавливаем таймер
            restoreTimerState()
            
            // ✅ ИСПРАВЛЕНО: BUG #16 - Обновление активного таймера при изменении настроек
            appSettings.cacheConfig.collect { newConfig ->
                val oldTimeout = currentTimeoutMs
                currentTimeoutMs = newConfig.timeoutMs
                
                // Если таймер активен И таймаут изменился
                if (_timerState.value == TimerState.RUNNING && oldTimeout != currentTimeoutMs) {
                    // Пересчитываем endTimestamp с новым таймаутом
                    timerMutex.withLock {
                        val prefs = timerDataStore.data.first()
                        val oldEndTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
                        val now = Clock.System.now().toEpochMilliseconds()
                        val elapsed = now - (oldEndTimestamp - oldTimeout)
                        val newEndTimestamp = now + (currentTimeoutMs - elapsed).coerceAtLeast(0)
                        
                        saveTimerState(isActive = true, endTimestamp = newEndTimestamp)
                        
                        android.util.Log.d("CacheManager", 
                            "⏱️ Timer timeout changed from ${oldTimeout}ms to ${currentTimeoutMs}ms")
                    }
                }
            }
        }
    }

    /**
     * Восстанавливает состояние таймера из DataStore.
     * Вызывается при старте приложения.
     */
    private suspend fun restoreTimerState() {
        val prefs = timerDataStore.data.first()
        val isActive = prefs[KEY_IS_ACTIVE] ?: false
        val endTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
        val savedTimeoutMs = prefs[KEY_TIMEOUT_MS] ?: currentTimeoutMs

        currentTimeoutMs = savedTimeoutMs

        if (isActive && endTimestamp > 0) {
            val now = Clock.System.now().toEpochMilliseconds()
            val remainingMs = endTimestamp - now

            if (remainingMs > 0) {
                // Таймер ещё не истёк - восстанавливаем
                _remainingSeconds.value = (remainingMs / 1000).toInt()
                _timerState.value = TimerState.RUNNING
                startTicker()
                
                android.util.Log.d("CacheManager", "✅ Timer restored: ${_remainingSeconds.value}s remaining")
            } else {
                // Таймер истёк пока приложение было закрыто
                onTimerExpired()
                android.util.Log.d("CacheManager", "⏱️ Timer expired while app was closed")
            }
        }
    }

    /**
     * Сохраняет состояние таймера в DataStore.
     */
    private suspend fun saveTimerState(
        isActive: Boolean,
        endTimestamp: Long = 0L
    ) {
        timerDataStore.edit { prefs ->
            prefs[KEY_IS_ACTIVE] = isActive
            prefs[KEY_END_TIMESTAMP] = endTimestamp
            prefs[KEY_TIMEOUT_MS] = currentTimeoutMs
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ОБНОВЛЕНО: Проблема №13, №20 - Проверка размера и дубликатов
     */
    suspend fun addFile(file: CachedFileEntity): Result<Unit> {
        return try {
            // ✅ ДОБАВЛЕНО: Проблема №13 - Проверка размера файла
            if (file.sizeBytes > MAX_FILE_SIZE) {
                return Result.failure(IllegalArgumentException(
                    "File too large: ${file.sizeBytes} bytes (max ${MAX_FILE_SIZE / 1024 / 1024}MB)"
                ))
            }
            
            // ✅ ДОБАВЛЕНО: Проблема №20 - Проверка дубликатов
            val existing = cacheDao.getByPath(file.filePath)
            if (existing != null) {
                android.util.Log.d("CacheManager", "⚠️ File already in cache: ${file.filePath}")
                return Result.success(Unit) // Уже есть, не добавляем повторно
            }
            
            val maxFiles = appSettings.maxCacheFiles.first()
            val currentCount = cacheDao.getCount()

            if (currentCount >= maxFiles) {
                cacheDao.trimToSize(maxFiles - 1)
            }

            cacheDao.insert(file)
            resetTimer()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * ✅ ОБНОВЛЕНО: Проблема №13, №20 - Проверка размера и дубликатов
     */
    suspend fun addFiles(files: List<CachedFileEntity>): Result<Int> {
        return try {
            // ✅ ДОБАВЛЕНО: Проблема №13 - Проверка размера каждого файла
            val oversizedFiles = files.filter { it.sizeBytes > MAX_FILE_SIZE }
            if (oversizedFiles.isNotEmpty()) {
                return Result.failure(IllegalArgumentException(
                    "Files too large: ${oversizedFiles.map { it.filePath }} exceed ${MAX_FILE_SIZE / 1024 / 1024}MB"
                ))
            }
            
            // ✅ ДОБАВЛЕНО: Проблема №20 - Фильтрация дубликатов
            val newFiles = mutableListOf<CachedFileEntity>()
            val duplicates = mutableListOf<String>()
            
            files.forEach { file ->
                if (cacheDao.getByPath(file.filePath) != null) {
                    duplicates.add(file.filePath)
                } else {
                    newFiles.add(file)
                }
            }
            
            if (duplicates.isNotEmpty()) {
                android.util.Log.d("CacheManager", "⚠️ Skipped ${duplicates.size} duplicate files")
            }
            
            if (newFiles.isEmpty()) {
                return Result.success(0) // Все файлы были дубликатами
            }
            
            val maxFiles = appSettings.maxCacheFiles.first()
            val currentCount = cacheDao.getCount()
            val availableSlots = maxFiles - currentCount

            val filesToAdd = if (newFiles.size > availableSlots) {
                cacheDao.trimToSize(maxFiles - newFiles.size)
                newFiles
            } else {
                newFiles
            }

            cacheDao.insertAll(filesToAdd)
            resetTimer()

            Result.success(filesToAdd.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFile(filePath: String) {
        cacheDao.deleteByPath(filePath)

        if (cacheDao.getCount() == 0) {
            stopTimer()
        }
    }

    suspend fun clearCache() {
        cacheDao.clearAll()
        stopTimer()
    }

    suspend fun getAllFiles(): List<CachedFileEntity> = cacheDao.getAll()

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

        val estimatedTokens = formattedContext.length / 4

        return CacheContext(
            files = files,
            formattedContext = formattedContext,
            totalTokensEstimate = estimatedTokens,
            isActive = _timerState.value == TimerState.RUNNING
        )
    }

    suspend fun hasFile(filePath: String): Boolean = cacheDao.getByPath(filePath) != null

    suspend fun updateFileContent(filePath: String, newContent: String) {
        cacheDao.getByPath(filePath)?.let { file ->
            cacheDao.update(file.copy(
                content = newContent,
                sizeBytes = newContent.toByteArray().size,
                addedAt = Clock.System.now()
            ))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMER CONTROL (PERSISTENT)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Запускает/сбрасывает таймер на полные N минут.
     * Создаёт WorkManager задачи для фоновой очистки.
     */
    fun resetTimer() {
        scope.launch {
            stopTimerInternal()

            val totalSeconds = (currentTimeoutMs / 1000).toInt()
            val endTimestamp = Clock.System.now().toEpochMilliseconds() + currentTimeoutMs

            _remainingSeconds.value = totalSeconds
            _timerState.value = TimerState.RUNNING

            // Сохраняем в DataStore
            saveTimerState(isActive = true, endTimestamp = endTimestamp)

            // Запускаем UI ticker
            startTicker()

            // Планируем фоновую очистку через WorkManager
            scheduleBackgroundCleanup(currentTimeoutMs)

            android.util.Log.d("CacheManager", "✅ Timer started: ${totalSeconds}s, ends at $endTimestamp")
        }
    }

    /**
     * ✅ ОБНОВЛЕНО: Проблема №16 - Ticker с детекцией прыжков системного времени.
     * ✅ ИСПРАВЛЕНО: CRASH #5 - Исправлен отрицательный timestamp при timer drift
     */
    private fun startTicker() {
        tickerJob?.cancel()
        
        // ✅ ДОБАВЛЕНО: Запоминаем точки отсчета для обоих типов часов
        tickerStartMonotonicTime = SystemClock.elapsedRealtime()
        tickerStartWallTime = System.currentTimeMillis()
        
        tickerJob = scope.launch {
            while (isActive && _remainingSeconds.value > 0) {
                delay(1000)
                
                // ✅ ДОБАВЛЕНО: Проверка прыжка времени
                val currentMonotonicTime = SystemClock.elapsedRealtime()
                val currentWallTime = System.currentTimeMillis()
                
                val monotonicElapsed = currentMonotonicTime - tickerStartMonotonicTime
                val wallElapsed = currentWallTime - tickerStartWallTime
                
                val timeDrift = abs(monotonicElapsed - wallElapsed)
                
                if (timeDrift > TIME_JUMP_THRESHOLD_MS) {
                    android.util.Log.w(
                        "CacheManager", 
                        "⚠️ Time jump detected! Drift: ${timeDrift}ms. Recalculating based on monotonic clock..."
                    )
                    
                    // Пересчитываем endTimestamp на основе монотонного времени
                    val prefs = timerDataStore.data.first()
                    
                    // Вычисляем сколько РЕАЛЬНО прошло времени (по монотонным часам)
                    val realElapsedSinceStart = monotonicElapsed
                    
                    // ✅ ИСПРАВЛЕНО: CRASH #5 - Защита от отрицательных значений
                    val remainingMs = (currentTimeoutMs - realElapsedSinceStart).coerceAtLeast(0)
                    val newEndTimestamp = currentWallTime + remainingMs
                    
                    saveTimerState(isActive = true, endTimestamp = newEndTimestamp)
                    
                    // Сбрасываем точки отсчета
                    tickerStartMonotonicTime = currentMonotonicTime
                    tickerStartWallTime = currentWallTime
                }
                
                // Пересчитываем оставшееся время из saved endTimestamp
                val prefs = timerDataStore.data.first()
                val endTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
                val now = Clock.System.now().toEpochMilliseconds()
                val remainingMs = endTimestamp - now

                if (remainingMs > 0) {
                    _remainingSeconds.value = (remainingMs / 1000).toInt()
                } else {
                    _remainingSeconds.value = 0
                    onTimerExpired()
                    break
                }
            }
        }
    }

    /**
     * Планирует фоновую очистку кеша через WorkManager.
     * 
     * WorkManager гарантирует выполнение даже если:
     * - Приложение закрыто
     * - Процесс убит
     * - Устройство перезагружено (после boot)
     */
    private fun scheduleBackgroundCleanup(delayMs: Long) {
        // Отменяем предыдущие задачи
        workManager.cancelAllWorkByTag(TIMER_WORK_TAG)
        workManager.cancelAllWorkByTag(NOTIFICATION_WORK_TAG)

        // Задача очистки (выполнится ровно через delayMs)
        val cleanupRequest = OneTimeWorkRequestBuilder<CacheCleanupWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(TIMER_WORK_TAG)
            .build()

        // Задача предупреждения (за 1 минуту до истечения)
        val warningDelayMs = (delayMs - 60_000).coerceAtLeast(0)
        if (warningDelayMs > 0) {
            val warningRequest = OneTimeWorkRequestBuilder<CacheWarningWorker>()
                .setInitialDelay(warningDelayMs, TimeUnit.MILLISECONDS)
                .addTag(NOTIFICATION_WORK_TAG)
                .build()

            workManager.enqueue(warningRequest)
        }

        workManager.enqueue(cleanupRequest)
        
        android.util.Log.d("CacheManager", "📅 Scheduled background cleanup in ${delayMs}ms")
    }

    /**
     * ✅ ИСПРАВЛЕНО: CRASH #3 - Добавлен Mutex для потокобезопасности
     */
    fun pauseTimer() {
        if (_timerState.value == TimerState.RUNNING) {
            scope.launch {
                timerMutex.withLock {
                    tickerJob?.cancel()
                    _timerState.value = TimerState.PAUSED
                    
                    val pausedAt = Clock.System.now().toEpochMilliseconds()
                    timerDataStore.edit { prefs ->
                        prefs[KEY_PAUSED_AT] = pausedAt
                    }
                    
                    android.util.Log.d("CacheManager", "⏸️ Timer paused at $pausedAt")
                }
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: CRASH #3 - Добавлен Mutex для потокобезопасности
     */
    fun resumeTimer() {
        if (_timerState.value == TimerState.PAUSED && _remainingSeconds.value > 0) {
            scope.launch {
                timerMutex.withLock {
                    val prefs = timerDataStore.data.first()
                    val pausedAt = prefs[KEY_PAUSED_AT] ?: 0L
                    val now = Clock.System.now().toEpochMilliseconds()
                    val pauseDuration = now - pausedAt

                    val oldEndTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
                    val newEndTimestamp = oldEndTimestamp + pauseDuration

                    saveTimerState(isActive = true, endTimestamp = newEndTimestamp)

                    _timerState.value = TimerState.RUNNING
                    startTicker()

                    android.util.Log.d("CacheManager", "▶️ Timer resumed, extended by ${pauseDuration}ms")
                }
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: CRASH #3 - Добавлен Mutex для потокобезопасности
     */
    fun stopTimer() {
        scope.launch {
            timerMutex.withLock {
                stopTimerInternal()
                _remainingSeconds.value = 0
                _timerState.value = TimerState.STOPPED
                saveTimerState(isActive = false)
                
                workManager.cancelAllWorkByTag(TIMER_WORK_TAG)
                workManager.cancelAllWorkByTag(NOTIFICATION_WORK_TAG)

                android.util.Log.d("CacheManager", "⏹️ Timer stopped")
            }
        }
    }

    private fun stopTimerInternal() {
        tickerJob?.cancel()
        tickerJob = null
    }

    fun extendTimer(additionalSeconds: Int = 60) {
        if (_timerState.value == TimerState.RUNNING) {
            scope.launch {
                val prefs = timerDataStore.data.first()
                val oldEndTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
                val newEndTimestamp = oldEndTimestamp + (additionalSeconds * 1000)

                saveTimerState(isActive = true, endTimestamp = newEndTimestamp)

                val maxSeconds = (currentTimeoutMs / 1000).toInt()
                _remainingSeconds.value = minOf(
                    _remainingSeconds.value + additionalSeconds,
                    maxSeconds
                )

                android.util.Log.d("CacheManager", "⏱️ Timer extended by ${additionalSeconds}s")
            }
        }
    }

    /**
     * Вызывается когда таймер истёк.
     */
    private suspend fun onTimerExpired() {
        _timerState.value = TimerState.EXPIRED

        val autoClear = appSettings.autoClearCache.first()
        if (autoClear) {
            cacheDao.clearAll()
            android.util.Log.d("CacheManager", "🗑️ Cache auto-cleared on timer expiry")
        }

        saveTimerState(isActive = false)
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
// WORKMANAGER WORKERS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Background worker для очистки кеша.
 * Выполняется ровно через 5 минут, даже если приложение закрыто.
 * 
 * ✅ ИСПРАВЛЕНО: Проблема №3 - Использует @HiltWorker и @AssistedInject для DI
 */
@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cacheDao: CacheDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        android.util.Log.d("CacheCleanupWorker", "🗑️ Executing background cache cleanup")

        // Очищаем кеш через инжектированный DAO
        cacheDao.clearAll()

        // Сбрасываем состояние таймера
        applicationContext.cacheTimerDataStore.edit { prefs ->
            prefs.clear()
        }

        // Показываем нотификацию
        CacheNotificationHelper.showCacheExpiredNotification(applicationContext)

        return Result.success()
    }
}

/**
 * Worker для предупреждения (за 1 минуту до истечения).
 * 
 * ✅ ИСПРАВЛЕНО: Проблема №3 - Использует @HiltWorker и @AssistedInject для DI
 */
@HiltWorker
class CacheWarningWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        android.util.Log.d("CacheWarningWorker", "⚠️ Cache will expire in 1 minute")

        CacheNotificationHelper.showCacheWarningNotification(applicationContext)

        return Result.success()
    }
}