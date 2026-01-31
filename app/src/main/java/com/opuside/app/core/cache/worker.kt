package com.opuside.app.core.cache

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.opuside.app.core.database.dao.CacheDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * ✅ ОБНОВЛЕНО: Worker для фоновой очистки кеша.
 * 
 * Теперь использует CacheNotificationManager для уведомлений.
 */
@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cacheDao: CacheDao,
    private val notificationManager: CacheNotificationManager
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        android.util.Log.d("CacheCleanupWorker", "🗑️ Executing background cache cleanup")
        
        try {
            // Очищаем БД
            cacheDao.clearAll()
            
            // Очищаем состояние таймера в DataStore
            applicationContext.cacheTimerDataStore.edit { prefs ->
                prefs.clear()
            }
            
            // Показываем уведомление
            notificationManager.showCacheExpiredNotification()
            
            return Result.success()
        } catch (e: Exception) {
            android.util.Log.e("CacheCleanupWorker", "❌ Cleanup failed", e)
            return Result.failure()
        }
    }
}

/**
 * ✅ ОБНОВЛЕНО: Worker для предупреждения (за 1 минуту до истечения).
 * 
 * Теперь использует CacheNotificationManager.
 */
@HiltWorker
class CacheWarningWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notificationManager: CacheNotificationManager
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        android.util.Log.d("CacheWarningWorker", "⚠️ Cache will expire in 1 minute")
        
        try {
            notificationManager.showCacheWarningNotification()
            return Result.success()
        } catch (e: Exception) {
            android.util.Log.e("CacheWarningWorker", "❌ Warning notification failed", e)
            return Result.failure()
        }
    }
}

// Extension для доступа к DataStore (нужно для CacheCleanupWorker)
private val Context.cacheTimerDataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> 
    get() = TODO("Используйте тот же DataStore что и в CacheTimerController")