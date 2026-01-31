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
 * 🔴 ПРОБЛЕМЫ:
 * - Проблема №1: Race condition в init - restoreTimerState() вызывается до загрузки config
 * - Проблема №5: Timer drift при sleep/resume - использует wall-clock вместо monotonic
 * - Проблема №7: WorkManager duplicate enqueue - не проверяет существующие задачи
 * - Проблема №11: God Object - слишком много ответственностей в одном классе (29KB)
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
        private const val MAX_FILE_SIZE = 1 * 1024 * 1024
        private const val TIME_JUMP_THRESHOLD_MS = 5000L
        
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

    private val timerMutex = Mutex()
    private var tickerJob: Job? = null
    private var currentTimeoutMs: Long = 5 * 60 * 1000L

    // 🔴 ПРОБЛЕМА #5: Timer Drift - Использование wall-clock времени
    // При sleep/resume устройства wall-clock может прыгать, но мы его не детектируем должным образом
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
        // 🔴 ПРОБЛЕМА #1: Race Condition в init (строки 93-107)
        // restoreTimerState() вызывается ДО загрузки config из appSettings
        // Если таймер был активен и сохранен с другим timeout, произойдет рассинхронизация
        scope.launch {
            // 🔴 Вызываем восстановление ДО загрузки настроек
            restoreTimerState()
            
            // Config загружается ПОСЛЕ, но currentTimeoutMs уже использован в restoreTimerState()
            appSettings.cacheConfig.collect { config ->
                currentTimeoutMs = config.timeoutMs
            }
        }
    }

    /**
     * Восстанавливает состояние таймера из DataStore.
     * Вызывается при старте приложения.
     * 
     * 🔴 ПРОБЛЕМА #1: Использует currentTimeoutMs до его инициализации из appSettings
     */
    private suspend fun restoreTimerState() {
        val prefs = timerDataStore.data.first()
        val isActive = prefs[KEY_IS_ACTIVE] ?: false
        val endTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
        val savedTimeoutMs = prefs[KEY_TIMEOUT_MS] ?: currentTimeoutMs

        // 🔴 currentTimeoutMs еще не загружен из appSettings!
        currentTimeoutMs = savedTimeoutMs

        if (isActive && endTimestamp > 0) {
            val now = Clock.System.now().toEpochMilliseconds()
            val remainingMs = endTimestamp - now

            if (remainingMs > 0) {
                _remainingSeconds.value = (remainingMs / 1000).toInt()
                _timerState.value = TimerState.RUNNING
                startTicker()
                
                android.util.Log.d("CacheManager", "✅ Timer restored: ${_remainingSeconds.value}s remaining")
            } else {
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

    suspend fun addFile(file: CachedFileEntity): Result<Unit> {
        return try {
            if (file.sizeBytes > MAX_FILE_SIZE) {
                return Result.failure(IllegalArgumentException(
                    "File too large: ${file.sizeBytes} bytes (max ${MAX_FILE_SIZE / 1024 / 1024}MB)"
                ))
            }
            
            val existing = cacheDao.getByPath(file.filePath)
            if (existing != null) {
                android.util.Log.d("CacheManager", "⚠️ File already in cache: ${file.filePath}")
                return Result.success(Unit)
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

    suspend fun addFiles(files: List<CachedFileEntity>): Result<Int> {
        return try {
            val oversizedFiles = files.filter { it.sizeBytes > MAX_FILE_SIZE }
            if (oversizedFiles.isNotEmpty()) {
                return Result.failure(IllegalArgumentException(
                    "Files too large: ${oversizedFiles.map { it.filePath }} exceed ${MAX_FILE_SIZE / 1024 / 1024}MB"
                ))
            }
            
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
                return Result.success(0)
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

            saveTimerState(isActive = true, endTimestamp = endTimestamp)

            startTicker()

            scheduleBackgroundCleanup(currentTimeoutMs)

            android.util.Log.d("CacheManager", "✅ Timer started: ${totalSeconds}s, ends at $endTimestamp")
        }
    }

    /**
     * 🔴 ПРОБЛЕМА #5: Timer Drift при Sleep/Resume (строки 179-207)
     * 
     * Использует System.currentTimeMillis() (wall-clock) для детекции прыжков времени.
     * При переходе в сон/пробуждении устройства wall-clock может прыгать из-за NTP sync,
     * смены часовых поясов, ручной настройки времени.
     * 
     * SystemClock.elapsedRealtime() (monotonic clock) продолжает идти во время сна,
     * но здесь используется только для вычисления drift, а не как основной источник времени.
     * 
     * При сильном drift (>5 сек) пересчитываем endTimestamp, но:
     * - Может случиться ложное срабатывание при NTP sync (±1-2 сек)
     * - Может НЕ сработать если wall-clock скорректировался плавно
     * - Не учитывает что монотонное время ОСТАНАВЛИВАЕТСЯ в некоторых режимах сна
     */
    private fun startTicker() {
        tickerJob?.cancel()
        
        // 🔴 Запоминаем оба типа часов, но используем wall-clock как основной
        tickerStartMonotonicTime = SystemClock.elapsedRealtime()
        tickerStartWallTime = System.currentTimeMillis()
        
        tickerJob = scope.launch {
            while (isActive && _remainingSeconds.value > 0) {
                delay(1000)
                
                // 🔴 Проверка drift между монотонным и wall-clock временем
                val currentMonotonicTime = SystemClock.elapsedRealtime()
                val currentWallTime = System.currentTimeMillis()
                
                val monotonicElapsed = currentMonotonicTime - tickerStartMonotonicTime
                val wallElapsed = currentWallTime - tickerStartWallTime
                
                val timeDrift = abs(monotonicElapsed - wallElapsed)
                
                // 🔴 Детекция прыжка только если drift > 5 секунд
                // Проблемы:
                // 1. NTP sync может дать ±2 сек - не детектируем
                // 2. Плавная коррекция времени (adjtime) - не детектируем
                // 3. SystemClock.elapsedRealtime() ОСТАНАВЛИВАЕТСЯ в некоторых режимах deep sleep
                if (timeDrift > TIME_JUMP_THRESHOLD_MS) {
                    android.util.Log.w(
                        "CacheManager", 
                        "⚠️ Time jump detected! Drift: ${timeDrift}ms. Recalculating based on monotonic clock..."
                    )
                    
                    val prefs = timerDataStore.data.first()
                    
                    // 🔴 Пересчет на основе монотонного времени, но:
                    // - Монотонные часы могут остановиться в deep sleep
                    // - realElapsedSinceStart может быть меньше реального времени сна
                    val realElapsedSinceStart = monotonicElapsed
                    
                    val remainingMs = (currentTimeoutMs - realElapsedSinceStart).coerceAtLeast(0)
                    val newEndTimestamp = currentWallTime + remainingMs
                    
                    saveTimerState(isActive = true, endTimestamp = newEndTimestamp)
                    
                    // Сбрасываем точки отсчета
                    tickerStartMonotonicTime = currentMonotonicTime
                    tickerStartWallTime = currentWallTime
                }
                
                // 🔴 Основной расчет через saved endTimestamp (wall-clock based)
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
     * 
     * 🔴 ПРОБЛЕМА #7: WorkManager Duplicate Enqueue (строки 249-273)
     * Не проверяет существующие задачи перед добавлением новых.
     * При быстрых вызовах resetTimer() может создать несколько одинаковых задач.
     */
    private fun scheduleBackgroundCleanup(delayMs: Long) {
        // 🔴 cancelAllWorkByTag() асинхронный - не ждем завершения
        // Следующий enqueue() может выполниться ДО завершения отмены
        workManager.cancelAllWorkByTag(TIMER_WORK_TAG)
        workManager.cancelAllWorkByTag(NOTIFICATION_WORK_TAG)

        // 🔴 Нет проверки: может быть уже запланирована задача с тем же тегом
        // Если cancelAllWorkByTag() еще не завершился, создастся дубликат
        val cleanupRequest = OneTimeWorkRequestBuilder<CacheCleanupWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(TIMER_WORK_TAG)
            .build()

        val warningDelayMs = (delayMs - 60_000).coerceAtLeast(0)
        if (warningDelayMs > 0) {
            // 🔴 Та же проблема - может создать дубликат warning задачи
            val warningRequest = OneTimeWorkRequestBuilder<CacheWarningWorker>()
                .setInitialDelay(warningDelayMs, TimeUnit.MILLISECONDS)
                .addTag(NOTIFICATION_WORK_TAG)
                .build()

            workManager.enqueue(warningRequest)
        }

        // 🔴 enqueue() не проверяет uniqueWork - могут быть дубликаты
        // Правильно использовать enqueueUniqueWork() с REPLACE или UPDATE
        workManager.enqueue(cleanupRequest)
        
        android.util.Log.d("CacheManager", "📅 Scheduled background cleanup in ${delayMs}ms")
    }

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
 */
@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cacheDao: CacheDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        android.util.Log.d("CacheCleanupWorker", "🗑️ Executing background cache cleanup")

        cacheDao.clearAll()

        applicationContext.cacheTimerDataStore.edit { prefs ->
            prefs.clear()
        }

        CacheNotificationHelper.showCacheExpiredNotification(applicationContext)

        return Result.success()
    }
}

/**
 * Worker для предупреждения (за 1 минуту до истечения).
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

// ═══════════════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════════════

enum class TimerState {
    STOPPED, RUNNING, PAUSED, EXPIRED
}

data class CacheContext(
    val files: List<CachedFileEntity>,
    val formattedContext: String,
    val totalTokensEstimate: Int,
    val isActive: Boolean
)

// Placeholder для notification helper
object CacheNotificationHelper {
    fun showCacheExpiredNotification(context: Context) {
        // Показывает нотификацию "Cache expired"
    }
    
    fun showCacheWarningNotification(context: Context) {
        // Показывает нотификацию "Cache expires in 1 minute"
    }
}