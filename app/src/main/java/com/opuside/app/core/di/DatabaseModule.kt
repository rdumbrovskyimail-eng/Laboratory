package com.opuside.app.core.di

import android.content.Context
import androidx.room.Room
import com.opuside.app.core.database.AppDatabase
import com.opuside.app.core.database.dao.CacheDao
import com.opuside.app.core.database.dao.ChatDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для базы данных Room.
 * 
 * Предоставляет:
 * - AppDatabase instance
 * - CacheDao для работы с кешированными файлами
 * - ChatDao для истории чата
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "opuside_database"
    )
        .fallbackToDestructiveMigration() // Для разработки. В продакшене использовать миграции!
        .build()

    @Provides
    @Singleton
    fun provideCacheDao(database: AppDatabase): CacheDao = database.cacheDao()

    @Provides
    @Singleton
    fun provideChatDao(database: AppDatabase): ChatDao = database.chatDao()
}
