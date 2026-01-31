package com.opuside.app.core.di

import android.content.Context
import com.opuside.app.core.cache.CacheRepository
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.CacheDao
import com.opuside.app.core.security.CacheEncryptionHelper
import com.opuside.app.core.security.SecureSettingsDataStore
import com.opuside.app.core.util.PersistentCacheManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для репозиториев и менеджеров.
 * 
 * ✅ ИСПРАВЛЕНО: Добавлены CacheEncryptionHelper и CacheRepository
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    /**
     * ✅ ИСПРАВЛЕНО: Добавлен provider для SecureSettingsDataStore
     * Решает проблему №1 - FATAL: MissingBinding crash at startup
     */
    @Provides
    @Singleton
    fun provideSecureSettings(
        @ApplicationContext context: Context
    ): SecureSettingsDataStore = SecureSettingsDataStore(context)

    @Provides
    @Singleton
    fun provideAppSettings(
        @ApplicationContext context: Context,
        secureSettings: SecureSettingsDataStore
    ): AppSettings = AppSettings(context, secureSettings)

    /**
     * ✅ НОВОЕ: Provider для CacheEncryptionHelper
     * Необходим для шифрования кеша в CacheRepository
     */
    @Provides
    @Singleton
    fun provideCacheEncryptionHelper(): CacheEncryptionHelper = CacheEncryptionHelper()

    /**
     * ✅ НОВОЕ: Provider для CacheRepository
     * Обертка над CacheDao с поддержкой шифрования
     */
    @Provides
    @Singleton
    fun provideCacheRepository(
        cacheDao: CacheDao,
        encryptionHelper: CacheEncryptionHelper
    ): CacheRepository = CacheRepository(cacheDao, encryptionHelper)

    /**
     * ✅ ИСПРАВЛЕНО: Используем CacheRepository вместо CacheDao
     * Правильный порядок параметров: context, cacheRepository, appSettings
     */
    @Provides
    @Singleton
    fun provideCacheManager(
        @ApplicationContext context: Context,
        cacheRepository: CacheRepository,
        appSettings: AppSettings
    ): PersistentCacheManager = PersistentCacheManager(context, cacheRepository, appSettings)
}