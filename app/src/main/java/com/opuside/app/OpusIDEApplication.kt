package com.opuside.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * OpusIDE Application
 * 
 * Главный Application класс с Hilt dependency injection.
 * Инициализируется при старте приложения.
 */
@HiltAndroidApp
class OpusIDEApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Инициализация будет добавлена позже:
        // - Timber для логирования
        // - Coil для изображений (если понадобится)
        // - Strict Mode для debug
    }
}
