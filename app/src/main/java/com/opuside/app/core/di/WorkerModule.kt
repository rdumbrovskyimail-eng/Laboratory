package com.opuside.app.core.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ✅ ИСПРАВЛЕНО: Убраны комментарии про OpusIDEApplication
 * HiltWorkerFactory предоставляется автоматически через androidx.hilt:hilt-work
 * Решает проблему №2 - FATAL: WorkerModule неполный
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkerModule {

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager = WorkManager.getInstance(context)
}