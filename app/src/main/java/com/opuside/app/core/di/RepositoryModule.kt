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

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

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

    @Provides
    @Singleton
    fun provideCacheEncryptionHelper(): CacheEncryptionHelper = CacheEncryptionHelper()

    @Provides
    @Singleton
    fun provideCacheRepository(
        cacheDao: CacheDao,
        encryptionHelper: CacheEncryptionHelper
    ): CacheRepository = CacheRepository(cacheDao, encryptionHelper)

    /**
     * ✅ ИСПРАВЛЕНО: Убран context - PersistentCacheManager не требует его
     */
    @Provides
    @Singleton
    fun provideCacheManager(
        cacheRepository: CacheRepository,
        appSettings: AppSettings
    ): PersistentCacheManager = PersistentCacheManager(cacheRepository, appSettings)
}