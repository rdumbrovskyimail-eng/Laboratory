package com.opuside.app.core.service

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
import com.opuside.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * STREAMING FOREGROUND SERVICE v1.0
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Защищает SSE стрим от убийства Android'ом при сворачивании приложения.
 *
 * ПРОБЛЕМА:
 * - Claude Extended Thinking + 120K output = до 75 минут стриминга
 * - Android Doze убивает приложение через ~10 минут в фоне
 * - WakeLock + Foreground Service = процесс живёт пока стрим идёт
 *
 * ИСПОЛЬЗОВАНИЕ:
 * 1. AnalyzerViewModel вызывает StreamingForegroundService.start(context)
 *    ПЕРЕД началом стриминга
 * 2. Service показывает уведомление "Claude is generating..."
 * 3. Уведомление обновляется с прогрессом (токены, время)
 * 4. AnalyzerViewModel вызывает StreamingForegroundService.stop(context)
 *    после завершения стриминга
 * 5. WakeLock автоматически освобождается
 *
 * БЕЗОПАСНОСТЬ:
 * - Partial WakeLock (CPU only, экран может быть выключен)
 * - Таймаут WakeLock = 100 минут (failsafe)
 * - Автостоп если не обновлять прогресс 5 минут
 */
@AndroidEntryPoint
class StreamingForegroundService : Service() {

    companion object {
        private const val TAG = "StreamingFGService"
        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 42
        private const val WAKELOCK_TAG = "OpusIDE:StreamingWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 100 * 60 * 1000L // 100 минут
        private const val AUTO_STOP_NO_UPDATE_MS = 5 * 60 * 1000L // 5 минут без обновлений → стоп

        private const val ACTION_START = "com.opuside.app.STREAMING_START"
        private const val ACTION_STOP = "com.opuside.app.STREAMING_STOP"
        private const val ACTION_UPDATE = "com.opuside.app.STREAMING_UPDATE"

        private const val EXTRA_MODEL_NAME = "model_name"
        private const val EXTRA_PROGRESS_TEXT = "progress_text"
        private const val EXTRA_TOKENS = "tokens"
        private const val EXTRA_ELAPSED_SEC = "elapsed_sec"

        // ══════════════════════════════════════════════════════════════
        // PUBLIC API — вызывается из AnalyzerViewModel
        // ══════════════════════════════════════════════════════════════

        fun start(context: Context, modelName: String = "Claude") {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateProgress(
            context: Context,
            progressText: String,
            tokens: Int = 0,
            elapsedSec: Int = 0
        ) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_PROGRESS_TEXT, progressText)
                putExtra(EXTRA_TOKENS, tokens)
                putExtra(EXTRA_ELAPSED_SEC, elapsedSec)
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var modelName = "Claude"
    private var lastUpdateTime = 0L
    private var autoStopJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "Claude"
                startStreaming()
            }
            ACTION_STOP -> {
                stopStreaming()
            }
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_PROGRESS_TEXT) ?: ""
                val tokens = intent.getIntExtra(EXTRA_TOKENS, 0)
                val elapsed = intent.getIntExtra(EXTRA_ELAPSED_SEC, 0)
                updateNotification(text, tokens, elapsed)
            }
        }
        return START_STICKY // Перезапускается если Android убьёт
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        autoStopJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════
    // STREAMING CONTROL
    // ══════════════════════════════════════════════════════════════════

    private fun startStreaming() {
        Log.i(TAG, "Starting foreground streaming for $modelName")

        // 1. Foreground notification
        startForeground(NOTIFICATION_ID, buildNotification(
            title = "$modelName is generating...",
            text = "Preparing response...",
            ongoing = true
        ))

        // 2. WakeLock — CPU stays on
        acquireWakeLock()

        // 3. Auto-stop watchdog
        lastUpdateTime = System.currentTimeMillis()
        startAutoStopWatchdog()
    }

    private fun stopStreaming() {
        Log.i(TAG, "Stopping foreground streaming")
        autoStopJob?.cancel()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateNotification(text: String, tokens: Int, elapsedSec: Int) {
        lastUpdateTime = System.currentTimeMillis()

        val timeStr = formatElapsed(elapsedSec)
        val tokStr = if (tokens > 0) "${"%,d".format(tokens)} tok" else ""
        val subtitle = listOf(timeStr, tokStr).filter { it.isNotEmpty() }.joinToString(" • ")

        val notification = buildNotification(
            title = "$modelName is generating...",
            text = if (text.isNotBlank()) text else subtitle,
            subtext = subtitle,
            ongoing = true
        )

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    // ══════════════════════════════════════════════════════════════════
    // WAKELOCK
    // ══════════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(WAKELOCK_TIMEOUT_MS) // Auto-release after 100 min
        }
        Log.i(TAG, "WakeLock acquired (timeout=${WAKELOCK_TIMEOUT_MS / 60000}min)")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    // ══════════════════════════════════════════════════════════════════
    // AUTO-STOP WATCHDOG
    // ══════════════════════════════════════════════════════════════════

    private fun startAutoStopWatchdog() {
        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            while (isActive) {
                delay(30_000) // Проверяем каждые 30 секунд
                val timeSinceUpdate = System.currentTimeMillis() - lastUpdateTime
                if (timeSinceUpdate > AUTO_STOP_NO_UPDATE_MS) {
                    Log.w(TAG, "No updates for ${timeSinceUpdate / 1000}s — auto-stopping")
                    stopStreaming()
                    break
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ══════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Claude Streaming",
                NotificationManager.IMPORTANCE_LOW // Без звука
            ).apply {
                description = "Показывает прогресс генерации Claude"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        subtext: String? = null,
        ongoing: Boolean = true
    ): Notification {
        // Intent чтоб по клику вернуться в приложение
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(this, StreamingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .apply { if (subtext != null) setSubText(subtext) }
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Замени на свою иконку
            .setOngoing(ongoing)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun formatElapsed(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}