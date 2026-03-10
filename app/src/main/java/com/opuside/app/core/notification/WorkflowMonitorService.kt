package com.opuside.app.core.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.opuside.app.R
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.WorkflowRun
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class WorkflowMonitorService : Service() {

    @Inject
    lateinit var gitHubApiClient: GitHubApiClient

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefs by lazy {
        getSharedPreferences("workflow_monitor_state", Context.MODE_PRIVATE)
    }

    private var lastSeenId: Long
        get() = prefs.getLong("last_seen_id", -1L)
        set(v) { prefs.edit().putLong("last_seen_id", v).apply() }

    private var wasActive: Boolean
        get() = prefs.getBoolean("was_active", false)
        set(v) { prefs.edit().putBoolean("was_active", v).apply() }

    private var lastNotifiedId: Long
        get() = prefs.getLong("last_notified_id", -1L)
        set(v) { prefs.edit().putLong("last_notified_id", v).apply() }

    companion object {
        private const val TAG = "WorkflowMonitor"

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
        WorkflowNotificationManager.createChannels(this)
        startForeground(
            WorkflowNotificationManager.NOTIF_MONITOR_ID,
            buildMonitorNotif("🔄 Ожидание воркфлоу...")
        )
        startScanning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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

                    val latest = workflows?.firstOrNull()

                    if (latest != null) {
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
                        val isRunning = latest.status == "in_progress" || latest.status == "queued"
                        val isCompleted = latest.status == "completed"
                        val alreadyNotified = latest.id == lastNotifiedId

                        when {
                            isNewWorkflow -> {
                                val isFirstLaunch = lastSeenId == -1L
                                lastSeenId = latest.id

                                if (isFirstLaunch) {
                                    wasActive = isRunning
                                    Log.d(TAG, "First launch, seeding with: ${latest.name} (${latest.id}), active=$isRunning")
                                } else if (isRunning) {
                                    wasActive = true
                                    Log.d(TAG, "New active workflow: ${latest.name} (${latest.id})")
                                } else if (isCompleted && !alreadyNotified) {
                                    wasActive = false
                                    Log.d(TAG, "New workflow already completed: ${latest.name} (${latest.id})")
                                    sendNotification(latest)
                                }
                            }

                            isRunning -> {
                                wasActive = true
                            }

                            isCompleted && wasActive && !alreadyNotified -> {
                                wasActive = false
                                Log.d(TAG, "Workflow completed: ${latest.name} (${latest.id}), conclusion=${latest.conclusion}")
                                sendNotification(latest)
                            }

                            else -> { /* no-op */ }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Scan error: ${e.message}")
                }

                delay(5_000)
            }
        }
    }

    private fun sendNotification(run: WorkflowRun) {
        lastNotifiedId = run.id
        val name = run.name ?: "Workflow #${run.id}"
        val duration = calcDuration(run.runStartedAt, run.updatedAt)

        when (run.conclusion) {
            "success" -> WorkflowNotificationManager.notifySuccess(
                context      = applicationContext,
                workflowName = name,
                duration     = duration
            )
            "failure" -> WorkflowNotificationManager.notifyFailure(
                context      = applicationContext,
                workflowName = name
            )
            else -> Log.d(TAG, "Ignoring conclusion: ${run.conclusion}")
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