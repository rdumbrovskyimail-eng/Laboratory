package com.opuside.app.core.util

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 🔥 Упрощённый CrashLogger - работает ВСЕГДА, даже при мгновенных крашах
 */
class CrashLogger private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var instance: CrashLogger? = null
        
        fun init(context: Context): CrashLogger {
            return instance ?: synchronized(this) {
                instance ?: CrashLogger(context.applicationContext).also {
                    instance = it
                    // Сразу устанавливаем обработчик
                    it.setupUncaughtExceptionHandler()
                }
            }
        }
        
        fun getInstance(): CrashLogger? = instance
    }
    
    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // СИНХРОННАЯ запись - без корутин!
                writeCrashLogSync(throwable, thread)
            } catch (e: Exception) {
                android.util.Log.e("CrashLogger", "❌ Write failed", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        
        android.util.Log.i("CrashLogger", "✅ CrashLogger initialized")
    }
    
    /**
     * СИНХРОННАЯ запись - работает мгновенно
     */
    private fun writeCrashLogSync(throwable: Throwable, thread: Thread) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        
        // Пробуем 3 разных пути
        val paths = listOf(
            File(Environment.getExternalStorageDirectory(), "log"),
            File(Environment.getExternalStorageDirectory(), "Download/OpusIDE_Crashes"),
            File(context.filesDir, "crashes")
        )
        
        paths.forEach { dir ->
            try {
                if (!dir.exists()) dir.mkdirs()
                
                val crashFile = File(dir, "crash_$timestamp.txt")
                
                crashFile.writeText(buildString {
                    appendLine("=" * 80)
                    appendLine("🔥 CRASH REPORT - OpusIDE")
                    appendLine("=" * 80)
                    appendLine()
                    appendLine("Timestamp: $timestamp")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Location: ${crashFile.absolutePath}")
                    appendLine()
                    appendLine("-" * 80)
                    appendLine("EXCEPTION:")
                    appendLine("-" * 80)
                    appendLine(throwable.stackTraceToString())
                    appendLine()
                    appendLine("-" * 80)
                    appendLine("LOGCAT DUMP:")
                    appendLine("-" * 80)
                    
                    // Быстрый logcat dump
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500"))
                        process.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { appendLine(it) }
                        }
                    } catch (e: Exception) {
                        appendLine("Logcat failed: ${e.message}")
                    }
                    
                    appendLine("-" * 80)
                    appendLine("END OF CRASH REPORT")
                    appendLine("=" * 80)
                })
                
                android.util.Log.e("CrashLogger", "✅ SAVED: ${crashFile.absolutePath} (${crashFile.length()} bytes)")
                
            } catch (e: Exception) {
                android.util.Log.e("CrashLogger", "❌ Failed to write to ${dir.absolutePath}", e)
            }
        }
    }
    
    fun startLogging() {
        android.util.Log.i("CrashLogger", "📁 Crash logs will be saved to:")
        android.util.Log.i("CrashLogger", "  1. /storage/emulated/0/log/")
        android.util.Log.i("CrashLogger", "  2. /storage/emulated/0/Download/OpusIDE_Crashes/")
        android.util.Log.i("CrashLogger", "  3. ${context.filesDir}/crashes/")
    }
    
    fun getCrashLogs(): List<File> = emptyList()
    fun getLatestCrashLog(): File? = null
    fun getCrashLogDirectory(): String = "/storage/emulated/0/log/"
    fun cleanOldLogs(keepCount: Int = 10) {}
    fun stopLogging() {}
}

private operator fun String.times(count: Int): String = repeat(count)