package com.opuside.app.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.opuside.app.MainActivity
import com.opuside.app.R

object WorkflowNotificationManager {

    private const val CHANNEL_VERSION = 2

    private const val CHANNEL_SUCCESS_ID = "wf_success_v$CHANNEL_VERSION"
    private const val CHANNEL_FAILURE_ID = "wf_failure_v$CHANNEL_VERSION"
    const val         CHANNEL_MONITOR_ID = "wf_monitor_v$CHANNEL_VERSION"

    private val DEPRECATED_CHANNELS = listOf(
        "wf_success", "wf_failure", "wf_monitor",
        "wf_success_v1", "wf_failure_v1", "wf_monitor_v1",
    )

    const val NOTIF_MONITOR_ID = 1000
    const val NOTIF_SUCCESS_ID = 1001
    const val NOTIF_FAILURE_ID = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        DEPRECATED_CHANNELS.forEach { id ->
            try { nm.deleteNotificationChannel(id) } catch (_: Exception) {}
        }

        NotificationChannel(
            CHANNEL_MONITOR_ID,
            "Мониторинг воркфлоу",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Постоянная иконка пока сервис активен"
            setShowBadge(false)
        }.also { nm.createNotificationChannel(it) }

        NotificationChannel(
            CHANNEL_SUCCESS_ID,
            "Билд успешен",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.sound_success}"),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        }.also { nm.createNotificationChannel(it) }

        NotificationChannel(
            CHANNEL_FAILURE_ID,
            "Билд упал",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.sound_failure}"),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        }.also { nm.createNotificationChannel(it) }
    }

    fun notifySuccess(context: Context, workflowName: String, duration: String) {
        send(
            context   = context,
            channelId = CHANNEL_SUCCESS_ID,
            notifId   = NOTIF_SUCCESS_ID,
            title     = "✅ Билд успешен!",
            body      = if (duration.isNotEmpty()) "$workflowName завершён за $duration"
                        else workflowName,
            colorArgb = 0xFF10B981.toInt()
        )
    }

    fun notifyFailure(context: Context, workflowName: String) {
        send(
            context   = context,
            channelId = CHANNEL_FAILURE_ID,
            notifId   = NOTIF_FAILURE_ID,
            title     = "❌ Билд упал!",
            body      = "$workflowName завершился с ошибкой",
            colorArgb = 0xFFEF4444.toInt()
        )
    }

    private fun send(
        context: Context,
        channelId: String,
        notifId: Int,
        title: String,
        body: String,
        colorArgb: Int
    ) {
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setColor(colorArgb)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notifId, notif)
    }
}