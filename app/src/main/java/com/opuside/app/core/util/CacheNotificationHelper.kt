package com.opuside.app.core.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opuside.app.MainActivity
import com.opuside.app.R

/**
 * Helper для Cache-уведомлений.
 */
object CacheNotificationHelper {
    private const val CHANNEL_ID = "cache_timer_channel"
    private const val WARNING_NOTIFICATION_ID = 1001
    private const val EXPIRED_NOTIFICATION_ID = 1002

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cache Timer",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about cache timer expiry"
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Проверяет есть ли разрешение на показ уведомлений
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // До Android 13 разрешение не требуется
        }
    }

    fun showCacheWarningNotification(context: Context) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⏱️ Cache Expiring Soon")
            .setContentText("Your cached files will expire in 1 minute")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (hasNotificationPermission(context)) {
            NotificationManagerCompat.from(context)
                .notify(WARNING_NOTIFICATION_ID, notification)
        }
    }

    fun showCacheExpiredNotification(context: Context) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🗑️ Cache Cleared")
            .setContentText("Your cached files have expired and been cleared")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (hasNotificationPermission(context)) {
            NotificationManagerCompat.from(context)
                .notify(EXPIRED_NOTIFICATION_ID, notification)
        }
    }
}