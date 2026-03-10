package com.opuside.app.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.opuside.app.MainActivity
import com.opuside.app.R

object WorkflowNotificationManager {

    private const val CHANNEL_SUCCESS_ID = "wf_success"
    private const val CHANNEL_FAILURE_ID = "wf_failure"
    const val         CHANNEL_MONITOR_ID = "wf_monitor"

    private const val NOTIF_SUCCESS_ID = 1001
    private const val NOTIF_FAILURE_ID = 1002
    const val         NOTIF_MONITOR_ID = 1000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Тихий канал монитора — только иконка в шторке
        NotificationChannel(
            CHANNEL_MONITOR_ID,
            "Мониторинг воркфлоу",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Постоянная иконка пока сервис активен"
            setShowBadge(false)
        }.also { nm.createNotificationChannel(it) }

        // Канал успеха — со звуком
        NotificationChannel(
            CHANNEL_SUCCESS_ID,
            "Билд успешен",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.sound_success}"),
                attr
            )
        }.also { nm.createNotificationChannel(it) }

        // Канал ошибки — со звуком
        NotificationChannel(
            CHANNEL_FAILURE_ID,
            "Билд упал",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.sound_failure}"),
                attr
            )
        }.also { nm.createNotificationChannel(it) }
    }

    fun notifySuccess(context: Context, workflowName: String, duration: String) {
        playSound(context, R.raw.sound_success)
        send(
            context   = context,
            channelId = CHANNEL_SUCCESS_ID,
            notifId   = NOTIF_SUCCESS_ID,
            title     = "✅ Билд успешен!",
            body      = "$workflowName завершён за $duration",
            colorArgb = 0xFF10B981.toInt()
        )
    }

    fun notifyFailure(context: Context, workflowName: String) {
        playSound(context, R.raw.sound_failure)
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
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            context, notifId, intent,
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

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notif)
    }

    private fun playSound(context: Context, resId: Int) {
        try {
            MediaPlayer.create(context, resId)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        } catch (_: Exception) {}
    }
}