package com.opuside.app.core.util

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * 🔥 Автоматический перехватчик крашей с записью logcat в файл
 * 
 * Логи сохраняются в: /storage/emulated/0/log/
 */
class CrashLogger private constructor(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private var logcatJob: Job? = null
    
    // 🔥 ПУТЬ: /storage/emulated/0/log/
    private val crashLogDir: File by lazy {
        File(Environment.getExternalStorageDirectory(), "log").apply {
            if (!exists()) {
                val created = mkdirs()
                android.util.Log.d("CrashLogger", "📁 Create log dir: $absolutePath - success: $created")
            }
            android.util.Log.d("CrashLogger", "📁 Crash log directory: $absolutePath (exists: ${exists()}, canWrite: ${canWrite()})")
        }
    }
    
    companion object {
        @Volatile
        private var instance: CrashLogger? = null
        
        fun init(context: Context): CrashLogger {
            return instance ?: synchronized(this) {
                instance ?: CrashLogger(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        fun getInstance(): CrashLogger? = instance
    }
    
    /**
     * Запускает непрерывный мониторинг logcat
     */
    fun startLogging() {
        // Устанавливаем обработчик необработанных исключений
        setupUncaughtExceptionHandler()
        
        // Запускаем фоновый сбор логов
        startLogcatCapture()
        
        android.util.Log.i("CrashLogger", "🚀 Crash logging started")
        android.util.Log.i("CrashLogger", "📁 Logs location: ${crashLogDir.absolutePath}")
        android.util.Log.i("CrashLogger", "📁 Directory exists: ${crashLogDir.exists()}, canWrite: ${crashLogDir.canWrite()}")
    }
    
    /**
     * Настройка перехватчика необработанных исключений
     */
    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Записываем краш в файл
                writeCrashLog(throwable, thread)
            } catch (e: Exception) {
                android.util.Log.e("CrashLogger", "❌ Failed to write crash log", e)
                e.printStackTrace()
            } finally {
                // Вызываем дефолтный обработчик
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
    
    /**
     * Запускает фоновый процесс захвата logcat
     */
    private fun startLogcatCapture() {
        logcatJob?.cancel()
        logcatJob = scope.launch {
            try {
                // Очищаем предыдущие логи
                Runtime.getRuntime().exec("logcat -c")
                
                // Запускаем непрерывный захват
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat",
                        "-v", "threadtime",
                        "*:V"
                    )
                )
                
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                val logBuffer = mutableListOf<String>()
                val maxBufferSize = 5000
                
                bufferedReader.useLines { lines ->
                    lines.forEach { line ->
                        logBuffer.add(line)
                        
                        if (logBuffer.size > maxBufferSize) {
                            logBuffer.removeAt(0)
                        }
                        
                        if (logBuffer.size % 100 == 0) {
                            saveBufferToTemp(logBuffer)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CrashLogger", "Error in logcat capture", e)
            }
        }
    }
    
    /**
     * Сохраняет буфер логов во временный файл
     */
    private fun saveBufferToTemp(buffer: List<String>) {
        try {
            val tempFile = File(crashLogDir, "temp_logcat.txt")
            tempFile.writeText(buffer.joinToString("\n"))
        } catch (e: Exception) {
            android.util.Log.e("CrashLogger", "Failed to save temp buffer", e)
        }
    }
    
    /**
     * Записывает полный лог краша
     */
    private fun writeCrashLog(throwable: Throwable, thread: Thread) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            
            // Проверяем доступность директории
            if (!crashLogDir.exists()) {
                crashLogDir.mkdirs()
            }
            
            val crashFile = File(crashLogDir, "crash_$timestamp.txt")
            
            android.util.Log.e("CrashLogger", "🔥 Writing crash to: ${crashFile.absolutePath}")
            
            crashFile.bufferedWriter().use { writer ->
                writer.write("=" * 80)
                writer.newLine()
                writer.write("🔥 CRASH REPORT - OpusIDE")
                writer.newLine()
                writer.write("=" * 80)
                writer.newLine()
                writer.newLine()
                
                writer.write("Timestamp: $timestamp")
                writer.newLine()
                writer.write("Thread: ${thread.name}")
                writer.newLine()
                writer.write("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                writer.newLine()
                writer.write("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                writer.newLine()
                try {
                    writer.write("App Version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
                    writer.newLine()
                } catch (e: Exception) {
                    writer.write("App Version: Unknown")
                    writer.newLine()
                }
                writer.write("Crash Log Location: ${crashFile.absolutePath}")
                writer.newLine()
                writer.newLine()
                
                writer.write("-" * 80)
                writer.newLine()
                writer.write("EXCEPTION STACK TRACE:")
                writer.newLine()
                writer.write("-" * 80)
                writer.newLine()
                writer.write(throwable.stackTraceToString())
                writer.newLine()
                writer.newLine()
                
                writer.write("-" * 80)
                writer.newLine()
                writer.write("LOGCAT BEFORE CRASH:")
                writer.newLine()
                writer.write("-" * 80)
                writer.newLine()
                
                val tempFile = File(crashLogDir, "temp_logcat.txt")
                if (tempFile.exists()) {
                    tempFile.readLines().forEach { line ->
                        writer.write(line)
                        writer.newLine()
                    }
                } else {
                    captureImmediateLogcat(writer)
                }
                
                writer.write("-" * 80)
                writer.newLine()
                writer.write("END OF CRASH REPORT")
                writer.newLine()
                writer.write("=" * 80)
            }
            
            android.util.Log.e("CrashLogger", "━".repeat(80))
            android.util.Log.e("CrashLogger", "🔥 CRASH LOG SAVED!")
            android.util.Log.e("CrashLogger", "📁 ${crashFile.absolutePath}")
            android.util.Log.e("CrashLogger", "📊 File size: ${crashFile.length()} bytes")
            android.util.Log.e("CrashLogger", "✅ File exists: ${crashFile.exists()}")
            android.util.Log.e("CrashLogger", "━".repeat(80))
            
        } catch (e: Exception) {
            android.util.Log.e("CrashLogger", "❌ CRITICAL: Failed to write crash log", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Моментальный захват logcat при краше
     */
    private fun captureImmediateLogcat(writer: java.io.BufferedWriter) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat",
                    "-d",
                    "-v", "threadtime",
                    "-t", "1000",
                    "*:V"
                )
            )
            
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.forEachLine { line ->
                    writer.write(line)
                    writer.newLine()
                }
            }
        } catch (e: Exception) {
            writer.write("Failed to capture immediate logcat: ${e.message}")
            writer.newLine()
        }
    }
    
    fun getCrashLogs(): List<File> {
        return crashLogDir.listFiles()
            ?.filter { it.name.startsWith("crash_") && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    fun getLatestCrashLog(): File? {
        return getCrashLogs().firstOrNull()
    }
    
    fun getCrashLogDirectory(): String {
        return crashLogDir.absolutePath
    }
    
    fun cleanOldLogs(keepCount: Int = 10) {
        getCrashLogs().drop(keepCount).forEach { it.delete() }
    }
    
    fun stopLogging() {
        logcatJob?.cancel()
    }
}

private operator fun String.times(count: Int): String = repeat(count)