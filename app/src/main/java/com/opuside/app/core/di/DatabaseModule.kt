package com.opuside.app.core.di

import android.content.Context
import androidx.room.Room
import com.opuside.app.core.database.AppDatabase
import com.opuside.app.core.database.MIGRATION_1_3
import com.opuside.app.core.database.MIGRATION_2_3
import com.opuside.app.core.database.MIGRATION_3_4
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.feature.scratch.data.local.ScratchDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для базы данных Room v4 (UPDATED)
 *
 * ✅ ОБНОВЛЕНО:
 * - Добавлена миграция MIGRATION_3_4 (таблица scratch_records)
 * - Добавлен провайдер ScratchDao
 *
 * Предоставляет:
 * - AppDatabase instance
 * - ChatDao для истории чата
 * - ScratchDao для Scratch-экрана
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
        .addMigrations(
            MIGRATION_1_3,  // Для пользователей с версией 1
            MIGRATION_2_3,  // Для пользователей с версией 2
            MIGRATION_3_4   // ✅ ДОБАВЛЕНО: 3 → 4 (scratch_records)
        )
        .fallbackToDestructiveMigration()
        .build()

    @Provides
    @Singleton
    fun provideChatDao(database: AppDatabase): ChatDao = database.chatDao()

    // ✅ ДОБАВЛЕНО
    @Provides
    @Singleton
    fun provideScratchDao(database: AppDatabase): ScratchDao = database.scratchDao()
}
