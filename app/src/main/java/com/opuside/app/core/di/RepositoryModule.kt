package com.opuside.app.core.di

import android.content.Context
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.util.CacheManager
import com.opuside.app.core.database.dao.CacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для репозиториев и менеджеров.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideAppSettings(
        @ApplicationContext context: Context
    ): AppSettings = AppSettings(context)

    @Provides
    @Singleton
    fun provideCacheManager(
        cacheDao: CacheDao,
        appSettings: AppSettings
    ): CacheManager = CacheManager(cacheDao, appSettings)
}
