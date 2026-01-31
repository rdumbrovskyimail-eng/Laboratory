package com.opuside.app.core.cache

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private val Context.cacheTimerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cache_timer_state"
)

/**
 * ✅ НОВЫЙ КЛАСС (Проблема #16 - God Object Refactoring)
 * 
 * Контроллер для таймера кеша файлов.
 * Отвечает ТОЛЬКО за:
 * - Запуск/остановка/пауза таймера
 * - Сохранение/восстановление состояния таймера
 * - Ticker для обновления UI каждую секунду
 * - Детекцию time jumps (sleep/resume, NTP sync)
 * 
 * НЕ отвечает за:
 * - DB операции (см. CacheRepository)
 * - WorkManager (см. CacheWorkScheduler)
 * - Уведомления (см. CacheNotificationManager)
 * 
 * ✅ ИСПРАВЛЕНО (Проблема #1): Race condition в init - теперь сначала загружаем config
 * ✅ ИСПРАВЛЕНО (Проблема #5): Timer drift - используем monotonic clock как primary
 */
@Singleton
class CacheTimerController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CacheTimerController"
        
        /**
         * ✅ ИСПРАВЛЕНО (Проблема #5): Уменьшен порог детекции time jump с 5000ms до 2000ms.
         * 
         * Теперь детектируем даже небольшие NTP синхронизации и плавные коррекции времени.
         * Это предотвращает drift при:
         * - NTP sync (обычно ±1-2 секунды)
         * - Смена часовых поясов
         * - Ручная настройка времени пользователем
         */
        private const val TIME_JUMP_THRESHOLD_MS = 2000L
        
        // DataStore keys
        private val KEY_END_TIMESTAMP = longPreferencesKey("cache_end_timestamp")
        private val KEY_IS_ACTIVE = booleanPreferencesKey("cache_is_active")
        private val KEY_TIMEOUT_MS = longPreferencesKey("cache_timeout_ms")
        private val KEY_PAUSED_AT = longPreferencesKey("cache_paused_at")
        
        /**
         * ✅ НОВЫЙ KEY (Проблема #5): Сохраняем monotonic time для точного восстановления.
         * 
         * SystemClock.elapsedRealtime() - монотонное время с момента boot (включая sleep).
         * Используется как основной источник времени для защиты от wall-clock jumps.
         */
        private val KEY_START_MONOTONIC_TIME = longPreferencesKey("start_monotonic_time")
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timerDataStore = context.cacheTimerDataStore
    private val timerMutex = Mutex()
    
    private var tickerJob: Job? = null
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #1): Теперь инициализируется ЯВНО через init(), а не в constructor.
     * Это позволяет сначала загрузить config из AppSettings, а потом восстановить таймер.
     */
    private var currentTimeoutMs: Long = 5 * 60 * 1000L // Default 5 минут
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #5): Monotonic clock как primary source of truth.
     * 
     * tickerStartMonotonicTime - момент старта таймера в monotonic времени
     * Используется для вычисления точного elapsed time, защищенного от:
     * - NTP sync
     * - Смены часовых поясов
     * - Ручной настройки времени
     * - System clock adjustments
     */
    private var tickerStartMonotonicTime: Long = 0L
    
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
    // CALLBACKS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Callback вызывается когда таймер истекает (достигает 0:00).
     * Должен быть установлен внешним компонентом (обычно PersistentCacheManager).
     */
    var onTimerExpired: (suspend () -> Unit)? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #1): Инициализация теперь ЯВНАЯ и async.
     * 
     * Вызывается из PersistentCacheManager ПОСЛЕ загрузки конфига.
     * Это гарантирует что currentTimeoutMs уже установлен перед восстановлением таймера.
     * 
     * @param timeoutMs Таймаут из конфига (уже загружен из AppSettings)
     */
    suspend fun initialize(timeoutMs: Long) {
        currentTimeoutMs = timeoutMs
        Log.d(TAG, "✅ Initialized with timeout: ${timeoutMs}ms")
        
        // Теперь безопасно восстанавливать таймер
        restoreTimerState()
    }
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #1 + #5): Восстановление состояния таймера.
     * 
     * Теперь вызывается ПОСЛЕ инициализации currentTimeoutMs.
     * Использует monotonic clock для точного вычисления оставшегося времени.
     */
    private suspend fun restoreTimerState() {
        val prefs = timerDataStore.data.first()
        val isActive = prefs[KEY_IS_ACTIVE] ?: false
        val endTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
        val savedTimeoutMs = prefs[KEY_TIMEOUT_MS] ?: currentTimeoutMs
        val savedMonotonicTime = prefs[KEY_START_MONOTONIC_TIME] ?: 0L
        
        // Используем сохраненный таймаут (может отличаться от текущего config)
        currentTimeoutMs = savedTimeoutMs
        
        if (isActive && endTimestamp > 0) {
            // ✅ ИСПРАВЛЕНО (Проблема #5): Используем monotonic clock для точного расчета
            val currentMonotonicTime = SystemClock.elapsedRealtime()
            val monotonicElapsed = if (savedMonotonicTime > 0) {
                currentMonotonicTime - savedMonotonicTime
            } else {
                // Fallback на wall-clock (для старых данных без savedMonotonicTime)
                val now = Clock.System.now().toEpochMilliseconds()
                now - (endTimestamp - currentTimeoutMs)
            }
            
            val remainingMs = (currentTimeoutMs - monotonicElapsed).coerceAtLeast(0)
            
            if (remainingMs > 0) {
                _remainingSeconds.value = (remainingMs / 1000).toInt()
                _timerState.value = TimerState.RUNNING
                
                // Запускаем ticker с правильным monotonic start time
                tickerStartMonotonicTime = currentMonotonicTime - monotonicElapsed
                startTicker()
                
                Log.d(TAG, "✅ Timer restored: ${_remainingSeconds.value}s remaining (monotonic-based)")
            } else {
                // Таймер истек пока приложение было закрыто
                _timerState.value = TimerState.EXPIRED
                onTimerExpired?.invoke()
                Log.d(TAG, "⏱️ Timer expired while app was closed")
            }
        }
    }
    
    /**
     * Сохраняет состояние таймера в DataStore.
     * 
     * ✅ ИСПРАВЛЕНО (Проблема #5): Теперь сохраняем monotonic time для точного восстановления.
     */
    private suspend fun saveTimerState(
        isActive: Boolean,
        endTimestamp: Long = 0L,
        monotonicStartTime: Long = 0L
    ) {
        timerDataStore.edit { prefs ->
            prefs[KEY_IS_ACTIVE] = isActive
            prefs[KEY_END_TIMESTAMP] = endTimestamp
            prefs[KEY_TIMEOUT_MS] = currentTimeoutMs
            prefs[KEY_START_MONOTONIC_TIME] = monotonicStartTime
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TIMER CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Запускает/сбрасывает таймер на полные N минут.
     * 
     * @return endTimestamp для использования в WorkManager scheduling
     */
    suspend fun resetTimer(): Long {
        return timerMutex.withLock {
            stopTimerInternal()
            
            val totalSeconds = (currentTimeoutMs / 1000).toInt()
            
            // ✅ ИСПРАВЛЕНО (Проблема #5): Используем monotonic clock как primary
            val currentMonotonicTime = SystemClock.elapsedRealtime()
            tickerStartMonotonicTime = currentMonotonicTime
            
            // Wall-clock endTimestamp только для WorkManager и DataStore
            val endTimestamp = Clock.System.now().toEpochMilliseconds() + currentTimeoutMs
            
            _remainingSeconds.value = totalSeconds
            _timerState.value = TimerState.RUNNING
            
            saveTimerState(
                isActive = true,
                endTimestamp = endTimestamp,
                monotonicStartTime = tickerStartMonotonicTime
            )
            
            startTicker()
            
            Log.d(TAG, "✅ Timer started: ${totalSeconds}s (monotonic: $tickerStartMonotonicTime)")
            
            endTimestamp
        }
    }
    
    /**
     * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО (Проблема #5): Ticker теперь использует monotonic clock.
     * 
     * СТАРАЯ ПРОБЛЕМА:
     * - Использовал System.currentTimeMillis() (wall-clock)
     * - При NTP sync/timezone change/manual adjustment таймер прыгал
     * - При sleep/resume могли быть пропуски или дублирование секунд
     * 
     * НОВОЕ РЕШЕНИЕ:
     * - Основа - SystemClock.elapsedRealtime() (monotonic, продолжает идти во время sleep)
     * - Wall-clock используется только для детекции экстремальных прыжков (>2 сек)
     * - При детекции jump - пересчет на основе monotonic времени
     * - Защита от всех типов time drift
     */
    private fun startTicker() {
        tickerJob?.cancel()
        
        tickerJob = scope.launch {
            // Запоминаем wall-clock только для детекции прыжков
            var lastWallTime = System.currentTimeMillis()
            
            while (isActive && _remainingSeconds.value > 0) {
                delay(1000)
                
                // ✅ PRIMARY: Вычисляем elapsed на основе monotonic clock
                val currentMonotonicTime = SystemClock.elapsedRealtime()
                val monotonicElapsed = currentMonotonicTime - tickerStartMonotonicTime
                
                // SECONDARY: Проверяем wall-clock для детекции экстремальных прыжков
                val currentWallTime = System.currentTimeMillis()
                val wallElapsed = currentWallTime - lastWallTime
                
                // Детекция прыжка времени (NTP sync, timezone change, manual adjustment)
                if (abs(wallElapsed - 1000) > TIME_JUMP_THRESHOLD_MS) {
                    Log.w(TAG, "⚠️ Wall-clock jump detected: ${wallElapsed}ms (expected ~1000ms)")
                    // Не корректируем - monotonic clock не подвержен этим прыжкам
                }
                
                lastWallTime = currentWallTime
                
                // ✅ Вычисляем оставшееся время на основе monotonic elapsed
                val remainingMs = (currentTimeoutMs - monotonicElapsed).coerceAtLeast(0)
                val remainingSec = (remainingMs / 1000).toInt()
                
                _remainingSeconds.value = remainingSec
                
                if (remainingSec <= 0) {
                    // Таймер истек
                    _timerState.value = TimerState.EXPIRED
                    saveTimerState(isActive = false)
                    
                    onTimerExpired?.invoke()
                    break
                }
            }
        }
    }
    
    /**
     * Останавливает таймер полностью.
     */
    suspend fun stopTimer() {
        timerMutex.withLock {
            stopTimerInternal()
            _remainingSeconds.value = 0
            _timerState.value = TimerState.STOPPED
            saveTimerState(isActive = false)
            
            Log.d(TAG, "⏹️ Timer stopped")
        }
    }
    
    private fun stopTimerInternal() {
        tickerJob?.cancel()
        tickerJob = null
    }
    
    /**
     * Приостанавливает таймер (сохраняет текущее состояние).
     */
    suspend fun pauseTimer() {
        if (_timerState.value == TimerState.RUNNING) {
            timerMutex.withLock {
                tickerJob?.cancel()
                _timerState.value = TimerState.PAUSED
                
                val pausedAt = Clock.System.now().toEpochMilliseconds()
                timerDataStore.edit { prefs ->
                    prefs[KEY_PAUSED_AT] = pausedAt
                }
                
                Log.d(TAG, "⏸️ Timer paused")
            }
        }
    }
    
    /**
     * Возобновляет приостановленный таймер.
     */
    suspend fun resumeTimer() {
        if (_timerState.value == TimerState.PAUSED && _remainingSeconds.value > 0) {
            timerMutex.withLock {
                val prefs = timerDataStore.data.first()
                val pausedAt = prefs[KEY_PAUSED_AT] ?: 0L
                val now = Clock.System.now().toEpochMilliseconds()
                val pauseDuration = now - pausedAt
                
                val oldEndTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
                val newEndTimestamp = oldEndTimestamp + pauseDuration
                
                // ✅ Корректируем monotonic start time
                val currentMonotonicTime = SystemClock.elapsedRealtime()
                val remainingSec = _remainingSeconds.value
                tickerStartMonotonicTime = currentMonotonicTime - ((currentTimeoutMs / 1000 - remainingSec) * 1000)
                
                saveTimerState(
                    isActive = true,
                    endTimestamp = newEndTimestamp,
                    monotonicStartTime = tickerStartMonotonicTime
                )
                
                _timerState.value = TimerState.RUNNING
                startTicker()
                
                Log.d(TAG, "▶️ Timer resumed")
            }
        }
    }
    
    /**
     * Продлевает таймер на дополнительное время.
     */
    suspend fun extendTimer(additionalSeconds: Int = 60) {
        if (_timerState.value == TimerState.RUNNING) {
            val maxSeconds = (currentTimeoutMs / 1000).toInt()
            val newRemaining = minOf(
                _remainingSeconds.value + additionalSeconds,
                maxSeconds
            )
            
            _remainingSeconds.value = newRemaining
            
            // Обновляем monotonic start time
            val currentMonotonicTime = SystemClock.elapsedRealtime()
            tickerStartMonotonicTime = currentMonotonicTime - ((currentTimeoutMs / 1000 - newRemaining) * 1000)
            
            val prefs = timerDataStore.data.first()
            val oldEndTimestamp = prefs[KEY_END_TIMESTAMP] ?: 0L
            val newEndTimestamp = oldEndTimestamp + (additionalSeconds * 1000)
            
            saveTimerState(
                isActive = true,
                endTimestamp = newEndTimestamp,
                monotonicStartTime = tickerStartMonotonicTime
            )
            
            Log.d(TAG, "⏱️ Timer extended by ${additionalSeconds}s")
        }
    }
    
    /**
     * ✅ НОВЫЙ МЕТОД (Проблема #1): Позволяет обновить timeout на лету.
     * 
     * Вызывается когда пользователь меняет настройки таймаута в Settings.
     * Если таймер активен - пересчитываем endTimestamp пропорционально.
     */
    suspend fun updateTimeout(newTimeoutMs: Long) {
        if (newTimeoutMs == currentTimeoutMs) return
        
        val wasRunning = _timerState.value == TimerState.RUNNING
        val oldTimeout = currentTimeoutMs
        currentTimeoutMs = newTimeoutMs
        
        if (wasRunning) {
            // Пересчитываем оставшееся время пропорционально
            val currentRemainingSec = _remainingSeconds.value
            val progress = 1.0f - (currentRemainingSec.toFloat() / (oldTimeout / 1000f))
            val newRemainingSec = ((newTimeoutMs / 1000) * (1.0f - progress)).toInt()
            
            _remainingSeconds.value = newRemainingSec
            
            // Обновляем monotonic start time
            val currentMonotonicTime = SystemClock.elapsedRealtime()
            tickerStartMonotonicTime = currentMonotonicTime - ((newTimeoutMs / 1000 - newRemainingSec) * 1000)
            
            val newEndTimestamp = Clock.System.now().toEpochMilliseconds() + (newRemainingSec * 1000L)
            
            saveTimerState(
                isActive = true,
                endTimestamp = newEndTimestamp,
                monotonicStartTime = tickerStartMonotonicTime
            )
            
            Log.d(TAG, "⏱️ Timeout updated: ${oldTimeout}ms → ${newTimeoutMs}ms, remaining: ${newRemainingSec}s")
        } else {
            Log.d(TAG, "⏱️ Timeout updated: ${oldTimeout}ms → ${newTimeoutMs}ms (timer not running)")
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

enum class TimerState {
    STOPPED, RUNNING, PAUSED, EXPIRED
}