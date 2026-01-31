package com.opuside.app.core.cache

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.opuside.app.core.database.dao.CacheDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

// DataStore для состояния таймера
private val Context.cacheTimerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cache_timer_state"
)

@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cacheDao: CacheDao,
    private val notificationManager: CacheNotificationManager
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        android.util.Log.d("CacheCleanupWorker", "🗑️ Executing background cache cleanup")
        
        return try {
            // Очищаем БД
            cacheDao.clearAll()
            
            // Очищаем состояние таймера в DataStore
            applicationContext.cacheTimerDataStore.edit { prefs ->
                prefs.clear()
            }
            
            // Показываем уведомление
            notificationManager.showCacheExpiredNotification()
            
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("CacheCleanupWorker", "❌ Cleanup failed", e)
            Result.failure()
        }
    }
}

@HiltWorker
class CacheWarningWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notificationManager: CacheNotificationManager
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        android.util.Log.d("CacheWarningWorker", "⚠️ Cache will expire in 1 minute")
        
        return try {
            notificationManager.showCacheWarningNotification()
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("CacheWarningWorker", "❌ Warning notification failed", e)
            Result.failure()
        }
    }
}