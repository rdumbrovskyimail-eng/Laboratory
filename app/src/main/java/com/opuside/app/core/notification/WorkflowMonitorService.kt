package com.opuside.app.core.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.opuside.app.R
import com.opuside.app.core.network.github.GitHubApiClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class WorkflowMonitorService : Service() {

    @Inject
    lateinit var gitHubApiClient: GitHubApiClient

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Персистентное состояние (переживает START_STICKY рестарт) ──────────
    private val prefs by lazy {
        getSharedPreferences("workflow_monitor_state", Context.MODE_PRIVATE)
    }

    /**
     * ID последнего workflow который мы видели.
     * -1L = ещё не видели ни одного.
     */
    private var lastSeenId: Long
        get() = prefs.getLong("last_seen_id", -1L)
        set(v) { prefs.edit().putLong("last_seen_id", v).apply() }

    /**
     * true  = workflow был активен (in_progress/queued) в предыдущей итерации
     *         → ждём завершения, потом уведомим
     * false = уже уведомили об этом workflow (или впервые увидели его уже completed)
     *         → не уведомляем повторно
     */
    private var wasActive: Boolean
        get() = prefs.getBoolean("was_active", false)
        set(v) { prefs.edit().putBoolean("was_active", v).apply() }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, WorkflowMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkflowMonitorService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Гарантируем наличие каналов — на Android 8+ без них уведомления молча пропадают
        WorkflowNotificationManager.createChannels(this)
        startForeground(
            WorkflowNotificationManager.NOTIF_MONITOR_ID,
            buildMonitorNotif("🔄 Ожидание воркфлоу...")
        )
        startScanning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startScanning() {
        scope.launch {
            while (isActive) {
                try {
                    val workflows = gitHubApiClient
                        .getWorkflowRuns(perPage = 10)
                        .getOrNull()
                        ?.workflowRuns

                    // GitHub API отдаёт runs отсортированными DESC по created_at.
                    // firstOrNull() = самый свежий запуск.
                    val latest = workflows?.firstOrNull()

                    if (latest != null) {

                        // ── Обновляем foreground-уведомление монитора ──────
                        updateMonitorNotif(
                            when {
                                latest.status == "queued"      -> "⏳ В очереди: ${latest.name}"
                                latest.status == "in_progress" -> "⚙️ Собирается: ${latest.name}"
                                latest.conclusion == "success" -> "✅ Успешно: ${latest.name}"
                                latest.conclusion == "failure" -> "❌ Ошибка: ${latest.name}"
                                else                           -> "🔄 Мониторинг воркфлоу..."
                            }
                        )

                        val isNewWorkflow = latest.id != lastSeenId
                        val isActive = latest.status == "in_progress" || latest.status == "queued"
                        val isCompleted = latest.status == "completed"

                        when {
                            // Новый workflow который мы раньше не видели
                            isNewWorkflow -> {
                                lastSeenId = latest.id
                                // Если он уже completed при первом обнаружении —
                                // wasActive = false → уведомление НЕ отправляем.
                                // Workflow завершился пока сервис не работал, слать его несвежо.
                                // Если он ещё активен — wasActive = true, будем ждать завершения.
                                wasActive = isActive
                            }

                            // Тот же workflow — всё ещё активен
                            isActive -> {
                                wasActive = true
                            }

                            // Тот же workflow — только что завершился,
                            // и мы видели его активным (wasActive == true)
                            isCompleted && wasActive -> {
                                wasActive = false // сбрасываем, чтобы не уведомлять повторно
                                val name = latest.name ?: "Workflow #${latest.id}"
                                val duration = calcDuration(latest.runStartedAt, latest.updatedAt)
                                when (latest.conclusion) {
                                    "success" -> WorkflowNotificationManager.notifySuccess(
                                        context      = applicationContext,
                                        workflowName = name,
                                        duration     = duration
                                    )
                                    "failure" -> WorkflowNotificationManager.notifyFailure(
                                        context      = applicationContext,
                                        workflowName = name
                                    )
                                    // "cancelled", "skipped" и т.д. — тихо игнорируем
                                }
                            }

                            // Тот же workflow, уже completed, wasActive == false
                            // (повторные итерации после уведомления) → ничего не делаем
                            else -> { /* no-op */ }
                        }
                    }
                } catch (_: Exception) {
                    // Сеть недоступна или API ошибка — ждём следующей итерации
                }

                delay(5_000)
            }
        }
    }

    private fun calcDuration(startTime: String?, endTime: String): String {
        return try {
            if (startTime == null) return ""
            val sec = java.time.Duration.between(
                java.time.Instant.parse(startTime),
                java.time.Instant.parse(endTime)
            ).seconds
            if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
        } catch (_: Exception) { "" }
    }

    private fun buildMonitorNotif(text: String): Notification =
        NotificationCompat.Builder(this, WorkflowNotificationManager.CHANNEL_MONITOR_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GitHub Actions Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateMonitorNotif(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(WorkflowNotificationManager.NOTIF_MONITOR_ID, buildMonitorNotif(text))
    }
}
