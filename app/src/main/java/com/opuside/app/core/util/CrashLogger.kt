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
 * Логи сохраняются в корне телефона:
 * /storage/emulated/0/OpusIDE_CrashLogs/
 */
class CrashLogger private constructor(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private var logcatJob: Job? = null
    
    // 🔥 ЛОГИ СОХРАНЯЮТСЯ В КОРНЕ ТЕЛЕФОНА!
    private val crashLogDir: File by lazy {
        // Путь: /storage/emulated/0/OpusIDE_CrashLogs/
        File(Environment.getExternalStorageDirectory(), "OpusIDE_CrashLogs").apply {
            if (!exists()) {
                mkdirs()
                android.util.Log.d("CrashLogger", "📁 Created crash log directory: $absolutePath")
            }
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
                        "-v", "threadtime",  // Формат с временем и потоком
                        "*:V"  // Все уровни логирования
                    )
                )
                
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                val logBuffer = mutableListOf<String>()
                val maxBufferSize = 5000  // Храним последние 5000 строк
                
                bufferedReader.useLines { lines ->
                    lines.forEach { line ->
                        // Добавляем в буфер
                        logBuffer.add(line)
                        
                        // Ограничиваем размер буфера
                        if (logBuffer.size > maxBufferSize) {
                            logBuffer.removeAt(0)
                        }
                        
                        // Сохраняем буфер во временный файл каждые 100 строк
                        if (logBuffer.size % 100 == 0) {
                            saveBufferToTemp(logBuffer)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
            e.printStackTrace()
        }
    }
    
    /**
     * Записывает полный лог краша
     */
    private fun writeCrashLog(throwable: Throwable, thread: Thread) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            val crashFile = File(crashLogDir, "crash_$timestamp.txt")
            
            crashFile.bufferedWriter().use { writer ->
                // Заголовок
                writer.write("=" * 80)
                writer.newLine()
                writer.write("🔥 CRASH REPORT - OpusIDE")
                writer.newLine()
                writer.write("=" * 80)
                writer.newLine()
                writer.newLine()
                
                // Информация о девайсе и приложении
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
                
                // Стек трейс исключения
                writer.write("-" * 80)
                writer.newLine()
                writer.write("EXCEPTION STACK TRACE:")
                writer.newLine()
                writer.write("-" * 80)
                writer.newLine()
                writer.write(throwable.stackTraceToString())
                writer.newLine()
                writer.newLine()
                
                // Логи из временного файла
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
                    // Если временного файла нет, делаем моментальный дамп
                    captureImmediateLogcat(writer)
                }
                
                writer.write("-" * 80)
                writer.newLine()
                writer.write("END OF CRASH REPORT")
                writer.newLine()
                writer.write("=" * 80)
            }
            
            // Выводим путь к файлу в системный лог
            android.util.Log.e("CrashLogger", "━".repeat(80))
            android.util.Log.e("CrashLogger", "🔥 CRASH DETECTED! Log saved to:")
            android.util.Log.e("CrashLogger", "📁 ${crashFile.absolutePath}")
            android.util.Log.e("CrashLogger", "━".repeat(80))
            
        } catch (e: Exception) {
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
                    "-d",  // Дамп существующих логов
                    "-v", "threadtime",
                    "-t", "1000",  // Последние 1000 строк
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
    
    /**
     * Получить список всех файлов крашей
     */
    fun getCrashLogs(): List<File> {
        return crashLogDir.listFiles()
            ?.filter { it.name.startsWith("crash_") && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    /**
     * Получить последний краш-лог
     */
    fun getLatestCrashLog(): File? {
        return getCrashLogs().firstOrNull()
    }
    
    /**
     * Получить путь к директории с логами
     */
    fun getCrashLogDirectory(): String {
        return crashLogDir.absolutePath
    }
    
    /**
     * Очистить старые логи (оставить только последние N)
     */
    fun cleanOldLogs(keepCount: Int = 10) {
        getCrashLogs().drop(keepCount).forEach { it.delete() }
    }
    
    /**
     * Остановить логирование
     */
    fun stopLogging() {
        logcatJob?.cancel()
    }
}

// Расширение для упрощения повторения символов
private operator fun String.times(count: Int): String = repeat(count)