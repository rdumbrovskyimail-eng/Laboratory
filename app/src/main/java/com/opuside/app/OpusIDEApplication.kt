package com.opuside.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.opuside.app.core.util.CacheNotificationHelper
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
 */
@HiltAndroidApp
class OpusIDEApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
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
     * ✅ ДОБАВЛЕНО: Конфигурация WorkManager с HiltWorkerFactory
     * Позволяет Workers получать зависимости через DI
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}