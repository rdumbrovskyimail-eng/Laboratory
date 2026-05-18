package com.opuside.app.feature.pipeline.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground Service для Pipeline.
 *
 * Запускается из PipelineViewModel.start(), убивается из stop()/reset() или
 * автоматически когда пайплайн перешёл в DONE/FATAL/CANCELLED.
 *
 * Удерживает PARTIAL_WAKE_LOCK чтобы CPU не засыпал когда экран заблокирован.
 * Показывает уведомление с прогрессом, обновляется через updateNotification().
 */
class PipelineForegroundService : Service() {

    companion object {
        private const val TAG = "PipelineFgService"
        const val CHANNEL_ID = "pipeline_foreground"
        const val CHANNEL_NAME = "Pipeline Execution"
        const val NOTIFICATION_ID = 4242
        const val WAKE_LOCK_TAG = "OpusIDE::PipelineWakeLock"

        // Actions
        const val ACTION_START = "com.opuside.pipeline.START"
        const val ACTION_STOP = "com.opuside.pipeline.STOP"
        const val ACTION_UPDATE = "com.opuside.pipeline.UPDATE"

        // Extras
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_PROGRESS = "extra_progress"        // 0..100, -1 = indeterminate
        const val EXTRA_TOTAL = "extra_total"              // для счётчика "5/10"
        const val EXTRA_COMPLETED = "extra_completed"

        /**
         * Запустить сервис. Безопасно вызывать многократно — повторный START
         * только обновит уведомление.
         */
        fun start(context: Context, title: String = "Pipeline запущен", text: String = "Подготовка...") {
            val intent = Intent(context, PipelineForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        /**
         * Обновить уведомление без остановки сервиса.
         */
        fun update(
            context: Context,
            title: String,
            text: String,
            completed: Int = 0,
            total: Int = 0,
            indeterminate: Boolean = false
        ) {
            val intent = Intent(context, PipelineForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_COMPLETED, completed)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_PROGRESS, if (indeterminate) -1 else if (total > 0) (completed * 100 / total) else 0)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update notification: ${e.message}")
            }
        }

        /**
         * Остановить сервис. Освобождает WakeLock и убирает уведомление.
         */
        fun stop(context: Context) {
            val intent = Intent(context, PipelineForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop service: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var isStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_UPDATE -> handleUpdate(intent)
            ACTION_STOP -> handleStop()
            else -> {
                // Системный рестарт (START_STICKY) — поднимаем минимальное уведомление
                if (!isStarted) {
                    handleStart(null)
                }
            }
        }
        // START_NOT_STICKY: если процесс убили — НЕ перезапускать с null intent.
        // Pipeline не имеет смысла продолжать без своего ViewModel state.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent?) {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Pipeline запущен"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Выполнение задач..."
        val notification = buildNotification(title, text, completed = 0, total = 0, indeterminate = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            acquireWakeLock()
            isStarted = true
            Log.i(TAG, "Service started in foreground")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun handleUpdate(intent: Intent) {
        if (!isStarted) {
            // Если update пришёл до START — стартуем
            handleStart(intent)
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Pipeline"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val completed = intent.getIntExtra(EXTRA_COMPLETED, 0)
        val total = intent.getIntExtra(EXTRA_TOTAL, 0)
        val progress = intent.getIntExtra(EXTRA_PROGRESS, -1)
        val indeterminate = progress < 0

        val notification = buildNotification(title, text, completed, total, indeterminate)
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    private fun handleStop() {
        Log.i(TAG, "Stopping service")
        releaseWakeLock()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        isStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        releaseWakeLock()
        isStarted = false
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WAKE LOCK
    // ═══════════════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            // Таймаут 60 минут — страховка от бесконечного зависания.
            // Pipeline сам отпустит lock через stop().
            lock.acquire(60 * 60 * 1000L)
            wakeLock = lock
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        } catch (e: Exception) {
            Log.w(TAG, "releaseWakeLock failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ═══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW   // LOW = без звука, но видим
            ).apply {
                description = "Прогресс выполнения Pipeline в фоне"
                setShowBadge(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create channel", e)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        completed: Int,
        total: Int,
        indeterminate: Boolean
    ): Notification {
        // Intent на открытие приложения по тапу
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = if (openIntent != null) {
            PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        // Intent на остановку
        val stopIntent = Intent(this, PipelineForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.stat_sys_download

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stopPending)

        if (openPending != null) {
            builder.setContentIntent(openPending)
            builder.addAction(android.R.drawable.ic_menu_view, "Открыть", openPending)
        }

        // Progress bar
        when {
            indeterminate -> builder.setProgress(0, 0, true)
            total > 0 -> builder.setProgress(total, completed, false)
            else -> { /* без прогресса */ }
        }

        return builder.build()
    }
}