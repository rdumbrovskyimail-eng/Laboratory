package com.opuside.app.core.notification

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object ApkInstallManager {

    private const val TAG = "ApkInstallManager"

    fun downloadAndInstall(context: Context, url: String, fileName: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Удаляем старый файл если есть
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Обновление OpusIDE")
            .setDescription("Скачиваем новый APK...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )

        val downloadId = dm.enqueue(request)
        Log.d(TAG, "Download started: $downloadId")

        // Слушаем завершение скачивания
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                ctx.unregisterReceiver(this)

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusCol)
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        installApk(ctx, file)
                    } else {
                        Log.e(TAG, "Download failed, status=$status")
                    }
                }
                cursor.close()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            Log.d(TAG, "Install dialog opened")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open installer", e)
        }
    }
}