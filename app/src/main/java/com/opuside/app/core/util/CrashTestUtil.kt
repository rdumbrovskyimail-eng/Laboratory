package com.opuside.app.core.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 🧪 Утилита для тестирования системы перехвата крашей
 */
object CrashTestUtil {
    
    /**
     * Вызывает краш для тестирования системы логирования
     */
    fun triggerTestCrash() {
        throw RuntimeException("🔥 TEST CRASH - This is intentional for testing crash logger")
    }
    
    /**
     * Вызывает краш с задержкой
     */
    fun triggerDelayedCrash(delayMs: Long = 3000) {
        Thread {
            Thread.sleep(delayMs)
            throw RuntimeException("🔥 DELAYED TEST CRASH - Triggered after ${delayMs}ms")
        }.start()
    }
    
    /**
     * Вызывает NullPointerException
     */
    fun triggerNPE() {
        val nullString: String? = null
        @Suppress("UNUSED_VARIABLE")
        val length = nullString!!.length
    }
    
    /**
     * Вызывает OutOfMemoryError
     */
    fun triggerOOM() {
        val list = mutableListOf<ByteArray>()
        while (true) {
            list.add(ByteArray(1024 * 1024)) // 1 MB
        }
    }
    
    /**
     * Открывает последний краш-лог в текстовом редакторе
     */
    fun openLatestCrashLog(context: Context) {
        val crashLogger = CrashLogger.getInstance() ?: return
        val latestLog = crashLogger.getLatestCrashLog() ?: return
        
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                latestLog
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(Intent.createChooser(intent, "Open crash log"))
        } catch (e: Exception) {
            android.util.Log.e("CrashTestUtil", "Failed to open crash log", e)
        }
    }
    
    /**
     * Шарит последний краш-лог
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
     * Выводит содержимое последнего краш-лога в logcat
     */
    fun printLatestCrashLog() {
        val crashLogger = CrashLogger.getInstance() ?: return
        val latestLog = crashLogger.getLatestCrashLog() ?: return
        
        android.util.Log.i("CrashTestUtil", "=" * 80)
        android.util.Log.i("CrashTestUtil", "LATEST CRASH LOG: ${latestLog.name}")
        android.util.Log.i("CrashTestUtil", "=" * 80)
        
        try {
            latestLog.readLines().forEach { line ->
                android.util.Log.i("CrashTestUtil", line)
            }
        } catch (e: Exception) {
            android.util.Log.e("CrashTestUtil", "Failed to read crash log", e)
        }
        
        android.util.Log.i("CrashTestUtil", "=" * 80)
    }
    
    /**
     * Получает статистику по краш-логам
     */
    fun getCrashStats(): String {
        val crashLogger = CrashLogger.getInstance() ?: return "CrashLogger not initialized"
        val logs = crashLogger.getCrashLogs()
        
        return buildString {
            appendLine("Crash Logs Statistics:")
            appendLine("Total crashes: ${logs.size}")
            
            if (logs.isNotEmpty()) {
                appendLine("Latest crash: ${logs.first().name}")
                appendLine("Oldest crash: ${logs.last().name}")
                
                val totalSize = logs.sumOf { it.length() }
                appendLine("Total size: ${totalSize / 1024} KB")
                appendLine("Location: ${crashLogger.getCrashLogDirectory()}")
            }
        }
    }
}

private operator fun String.times(count: Int): String = repeat(count)