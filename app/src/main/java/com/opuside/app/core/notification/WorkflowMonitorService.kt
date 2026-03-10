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

    private var lastWorkflowId: Long? = null
    private var lastConclusion: String? = null

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

                    val last = workflows?.lastOrNull()

                    if (last != null) {

                        // Обновляем текст в постоянном уведомлении
                        val statusText = when {
                            last.status == "queued"      -> "⏳ В очереди: ${last.name}"
                            last.status == "in_progress" -> "⚙️ Собирается: ${last.name}"
                            last.conclusion == "success" -> "✅ Успешно: ${last.name}"
                            last.conclusion == "failure" -> "❌ Ошибка: ${last.name}"
                            else                         -> "🔄 Мониторинг воркфлоу..."
                        }
                        updateMonitorNotif(statusText)

                        // Новый воркфлоу — сбрасываем память
                        if (last.id != lastWorkflowId) {
                            lastWorkflowId = last.id
                            lastConclusion = if (last.status == "completed") last.conclusion else null
                        }

                        // Воркфлоу активен — сбрасываем чтобы поймать завершение
                        if (last.status == "in_progress" || last.status == "queued") {
                            lastConclusion = null
                        }

                        // Воркфлоу только что завершился и мы ещё не уведомляли
                        if (last.status == "completed" && last.conclusion != lastConclusion) {
                            lastConclusion = last.conclusion
                            val name = last.name ?: "Workflow #${last.id}"
                            when (last.conclusion) {
                                "success" -> {
                                    val duration = calcDuration(
                                        last.runStartedAt,
                                        last.updatedAt
                                    )
                                    WorkflowNotificationManager.notifySuccess(
                                        context      = applicationContext,
                                        workflowName = name,
                                        duration     = duration
                                    )
                                }
                                "failure" -> {
                                    WorkflowNotificationManager.notifyFailure(
                                        context      = applicationContext,
                                        workflowName = name
                                    )
                                }
                            }
                        }
                    }

                } catch (_: Exception) {
                    // Сеть недоступна — ждём следующей итерации
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
            if (sec > 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
        } catch (_: Exception) { "" }
    }

    private fun buildMonitorNotif(text: String): Notification {
        return NotificationCompat.Builder(this, WorkflowNotificationManager.CHANNEL_MONITOR_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GitHub Actions Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateMonitorNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            WorkflowNotificationManager.NOTIF_MONITOR_ID,
            buildMonitorNotif(text)
        )
    }
}