package com.opuside.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.opuside.app.core.util.CacheNotificationHelper
import com.opuside.app.core.util.CrashLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * OpusIDE Application
 * 
 * Главный Application класс с Hilt dependency injection.
 * Инициализируется при старте приложения.
 * 
 * ✅ ИСПРАВЛЕНО: Добавлена поддержка Hilt Workers
 * Решает проблему №2 - FATAL: WorkerModule неполный
 * ✅ ИСПРАВЛЕНО: Проблема №14 (BUG #14) - Двойная инициализация WorkManager
 * ✅ ДОБАВЛЕНО: CrashLogger - автоматический перехват крашей
 */
@HiltAndroidApp
class OpusIDEApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        // 🔥 КРИТИЧЕСКИ ВАЖНО: Инициализируем CrashLogger ПЕРВЫМ делом
        // ДО вызова super.onCreate() и любой другой инициализации
        initCrashLogger()
        
        super.onCreate()
        
        // ✅ ДОБАВЛЕНО: Инициализация notification channel
        // Решает проблему №7 - notifications не работают
        CacheNotificationHelper.createNotificationChannel(this)
        
        // Инициализация будет добавлена позже:
        // - Timber для логирования
        // - Coil для изображений (если понадобится)
        // - Strict Mode для debug
    }

    /**
     * 🔥 Инициализация системы перехвата крашей
     * Вызывается ДО всего остального
     */
    private fun initCrashLogger() {
        try {
            CrashLogger.init(this).apply {
                startLogging()
                // Очищаем старые логи, оставляем последние 20
                cleanOldLogs(keepCount = 20)
            }
            
            android.util.Log.d("OpusIDEApplication", "✅ CrashLogger initialized successfully")
            android.util.Log.d("OpusIDEApplication", "📁 Crash logs location: ${CrashLogger.getInstance()?.getCrashLogDirectory()}")
        } catch (e: Exception) {
            // Даже если инициализация крашлоггера упала, не даем упасть приложению
            android.util.Log.e("OpusIDEApplication", "❌ Failed to init CrashLogger", e)
        }
    }

    /**
     * ✅ ДОБАВЛЕНО: Конфигурация WorkManager с HiltWorkerFactory
     * Позволяет Workers получать зависимости через DI
     * WorkManager инициализируется автоматически через Configuration.Provider
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}