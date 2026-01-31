package com.opuside.app.core.cache

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ✅ НОВЫЙ КЛАСС (Проблема #16 - God Object Refactoring)
 * 
 * Планировщик фоновых задач для кеша через WorkManager.
 * Отвечает ТОЛЬКО за:
 * - Планирование background cleanup (очистка кеша)
 * - Планирование warning notifications (за 1 минуту до истечения)
 * - Отмену запланированных задач
 * 
 * НЕ отвечает за:
 * - DB операции (см. CacheRepository)
 * - Таймер (см. CacheTimerController)
 * - Показ уведомлений (см. CacheNotificationManager)
 * 
 * ✅ ИСПРАВЛЕНО (Проблема #9): WorkManager дублирование - используем enqueueUniqueWork
 */
@Singleton
class CacheWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CacheWorkScheduler"
        
        /**
         * ✅ ИСПРАВЛЕНО (Проблема #9): Уникальные имена для работ.
         * 
         * Используется в enqueueUniqueWork() для предотвращения дубликатов.
         * Каждое имя уникально идентифицирует тип работы.
         */
        private const val CLEANUP_WORK_NAME = "cache_cleanup_work"
        private const val WARNING_WORK_NAME = "cache_warning_work"
    }
    
    private val workManager = WorkManager.getInstance(context)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCHEDULING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #9): Планирует cleanup с REPLACE policy.
     * 
     * СТАРАЯ ПРОБЛЕМА:
     * - Использовал enqueue() без проверки дубликатов
     * - cancelAllWorkByTag() асинхронный - следующий enqueue() выполнялся ДО отмены
     * - Результат: несколько одинаковых задач в очереди
     * 
     * НОВОЕ РЕШЕНИЕ:
     * - Используем enqueueUniqueWork() с ExistingWorkPolicy.REPLACE
     * - WorkManager автоматически отменяет старую задачу перед добавлением новой
     * - Гарантия: всегда только ОДНА задача каждого типа в очереди
     * - Атомарная операция - нет race condition
     * 
     * @param delayMs Задержка до выполнения cleanup (обычно 5 минут)
     */
    fun scheduleCleanup(delayMs: Long) {
        val cleanupRequest = OneTimeWorkRequestBuilder<CacheCleanupWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false) // Выполнять даже при низком заряде
                    .setRequiresStorageNotLow(false) // Выполнять даже при низкой памяти
                    .build()
            )
            .build()
        
        // ✅ КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: REPLACE policy
        // Старая задача автоматически отменяется, новая ставится на ее место
        workManager.enqueueUniqueWork(
            CLEANUP_WORK_NAME,
            ExistingWorkPolicy.REPLACE, // ← Ключевое изменение!
            cleanupRequest
        )
        
        Log.d(TAG, "📅 Scheduled cache cleanup in ${delayMs}ms (REPLACE policy)")
    }
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #9): Планирует warning с REPLACE policy.
     * 
     * Аналогично scheduleCleanup - используем enqueueUniqueWork для предотвращения дубликатов.
     * 
     * @param delayMs Задержка до показа warning (обычно 4 минуты = timeout - 1 минута)
     */
    fun scheduleWarning(delayMs: Long) {
        if (delayMs <= 0) {
            Log.d(TAG, "⚠️ Warning delay is <= 0, skipping")
            return
        }
        
        val warningRequest = OneTimeWorkRequestBuilder<CacheWarningWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .build()
        
        // ✅ REPLACE policy - автоматическая отмена старого warning
        workManager.enqueueUniqueWork(
            WARNING_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            warningRequest
        )
        
        Log.d(TAG, "⚠️ Scheduled cache warning in ${delayMs}ms (REPLACE policy)")
    }
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #9): Отмена теперь использует cancelUniqueWork.
     * 
     * СТАРАЯ ПРОБЛЕМА:
     * - cancelAllWorkByTag() асинхронная - могла не успеть выполниться
     * - Следующий enqueue() мог добавить задачу ДО завершения отмены
     * 
     * НОВОЕ РЕШЕНИЕ:
     * - cancelUniqueWork() более надежная - отменяет по имени работы
     * - Синхронизирована с enqueueUniqueWork()
     * - Гарантия: работа будет отменена корректно
     */
    fun cancelAll() {
        workManager.cancelUniqueWork(CLEANUP_WORK_NAME)
        workManager.cancelUniqueWork(WARNING_WORK_NAME)
        
        Log.d(TAG, "🛑 All cache background tasks cancelled")
    }
    
    /**
     * Отменяет только cleanup задачу.
     */
    fun cancelCleanup() {
        workManager.cancelUniqueWork(CLEANUP_WORK_NAME)
        Log.d(TAG, "🛑 Cache cleanup task cancelled")
    }
    
    /**
     * Отменяет только warning задачу.
     */
    fun cancelWarning() {
        workManager.cancelUniqueWork(WARNING_WORK_NAME)
        Log.d(TAG, "🛑 Cache warning task cancelled")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // WORK STATUS MONITORING (ОПЦИОНАЛЬНО)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Проверяет, запланирована ли cleanup задача.
     * Полезно для дебага и мониторинга.
     */
    fun isCleanupScheduled(): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork(CLEANUP_WORK_NAME).get()
        return workInfos.any { !it.state.isFinished }
    }
    
    /**
     * Проверяет, запланирована ли warning задача.
     */
    fun isWarningScheduled(): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork(WARNING_WORK_NAME).get()
        return workInfos.any { !it.state.isFinished }
    }
}