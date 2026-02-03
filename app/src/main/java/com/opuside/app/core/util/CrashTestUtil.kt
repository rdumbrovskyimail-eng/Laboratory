package com.opuside.app.core.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 🧪 Утилита для тестирования системы логирования
 */
object CrashTestUtil {
    
    /**
     * 💥 Вызывает тестовый краш
     * Автоматически сохранится через CrashLogger
     */
    fun triggerTestCrash() {
        throw RuntimeException("🔥 TEST CRASH - This is intentional for testing crash logger")
    }
    
    /**
     * 📝 Сохраняет текущие ошибки LogCat
     * Возвращает путь к сохраненному файлу или null если ошибка
     */
    fun saveLogCatErrors(context: Context): File? {
        val crashLogger = CrashLogger.getInstance() ?: return null
        return crashLogger.saveLogCatErrors()
    }
    
    /**
     * 📂 Открывает список всех логов в встроенном viewer
     * Используется для навигации к LogViewerScreen
     */
    fun openLogViewer(context: Context, onNavigateToViewer: () -> Unit) {
        onNavigateToViewer()
    }
    
    /**
     * 📤 Шарит последний краш-лог
     */
    fun shareLatestCrashLog(context: Context) {
        val crashLogger = CrashLogger.getInstance() ?: return
        val latestLog = crashLogger.getLatestCrashLog() ?: return
        
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                latestLog
            )
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Crash Log - ${latestLog.name}")
                putExtra(Intent.EXTRA_TEXT, "Crash log from OpusIDE application")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(intent, "Share crash log"))
        } catch (e: Exception) {
            android.util.Log.e("CrashTestUtil", "Failed to share crash log", e)
        }
    }
    
    /**
     * 📊 Получает статистику по логам
     */
    fun getLogStats(): LogStats? {
        val crashLogger = CrashLogger.getInstance() ?: return null
        return crashLogger.getStats()
    }
}
