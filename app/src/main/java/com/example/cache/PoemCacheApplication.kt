package com.example.cache

import android.app.Application
import android.util.Log

/**
 * Application-класс для прогрева кэша при старте приложения.
 *
 * Не забудьте указать в AndroidManifest.xml:
 *   <application android:name=".PoemCacheApplication" ... >
 */
class PoemCacheApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Прогреваем кэш стихов при старте
        CachedPoems.warmUp(this)
        Log.d("PoemCache", "✅ Кэш стихов прогрет")
    }
}