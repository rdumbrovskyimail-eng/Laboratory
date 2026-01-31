package com.opuside.app.core.cache

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opuside.app.MainActivity
import com.opuside.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ✅ НОВЫЙ КЛАСС (Проблема #16 - God Object Refactoring)
 * 
 * Менеджер уведомлений для кеша файлов.
 * Отвечает ТОЛЬКО за:
 * - Создание notification channels
 * - Показ warning уведомлений (за 1 минуту до истечения)
 * - Показ expired уведомлений (кеш истек)
 * - Отмену уведомлений
 * 
 * НЕ отвечает за:
 * - DB операции (см. CacheRepository)
 * - Таймер (см. CacheTimerController)
 * - WorkManager (см. CacheWorkScheduler)
 */
@Singleton
class CacheNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CacheNotificationMgr"
        
        private const val CHANNEL_ID = "cache_timer_channel"
        private const val CHANNEL_NAME = "Cache Timer Notifications"
        private const val CHANNEL_DESCRIPTION = "Notifications about cache expiration"
        
        private const val NOTIFICATION_ID_WARNING = 1001
        private const val NOTIFICATION_ID_EXPIRED = 1002
    }
    
    private val notificationManager = NotificationManagerCompat.from(context)
    
    init {
        createNotificationChannel()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CHANNEL SETUP
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Создает notification channel для Android O+.
     * Вызывается автоматически при инициализации.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                enableLights(true)
            }
            
            val systemNotificationManager = 
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            systemNotificationManager.createNotificationChannel(channel)
            
            android.util.Log.d(TAG, "✅ Notification channel created")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Показывает предупреждение о скором истечении кеша (за 1 минуту).
     * 
     * Notification:
     * - Title: "Cache expiring soon"
     * - Text: "Your cached files will expire in 1 minute"
     * - Action: Tap to open app and extend timer
     * - Priority: HIGH (показывается как heads-up на Android)
     */
    fun showCacheWarningNotification() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("action", "extend_cache_timer")
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification) // Замените на ваш icon
                .setContentTitle("⚠️ Cache expiring soon")
                .setContentText("Your cached files will expire in 1 minute")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Your cached files will expire in 1 minute. Tap to open OpusIDE and extend the timer.")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true) // Автоматически скрывается при тапе
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0, 300, 200, 300)) // Vibration pattern
                .build()
            
            notificationManager.notify(NOTIFICATION_ID_WARNING, notification)
            
            android.util.Log.d(TAG, "⚠️ Cache warning notification shown")
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "❌ Notification permission denied", e)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to show warning notification", e)
        }
    }
    
    /**
     * Показывает уведомление об истечении кеша.
     * 
     * Notification:
     * - Title: "Cache expired"
     * - Text: "Your cached files have been cleared"
     * - Action: Tap to open app and add new files
     * - Priority: DEFAULT
     */
    fun showCacheExpiredNotification() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("action", "open_cache_screen")
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification) // Замените на ваш icon
                .setContentTitle("🗑️ Cache expired")
                .setContentText("Your cached files have been cleared")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Your cached files have expired and been automatically cleared. Tap to open OpusIDE and add new files.")
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID_EXPIRED, notification)
            
            android.util.Log.d(TAG, "🗑️ Cache expired notification shown")
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "❌ Notification permission denied", e)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to show expired notification", e)
        }
    }
    
    /**
     * Отменяет все уведомления, связанные с кешем.
     */
    fun cancelAllNotifications() {
        try {
            notificationManager.cancel(NOTIFICATION_ID_WARNING)
            notificationManager.cancel(NOTIFICATION_ID_EXPIRED)
            
            android.util.Log.d(TAG, "🔕 All cache notifications cancelled")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to cancel notifications", e)
        }
    }
    
    /**
     * Отменяет только warning уведомление.
     */
    fun cancelWarningNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID_WARNING)
            android.util.Log.d(TAG, "🔕 Warning notification cancelled")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to cancel warning notification", e)
        }
    }
    
    /**
     * Отменяет только expired уведомление.
     */
    fun cancelExpiredNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID_EXPIRED)
            android.util.Log.d(TAG, "🔕 Expired notification cancelled")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to cancel expired notification", e)
        }
    }
}