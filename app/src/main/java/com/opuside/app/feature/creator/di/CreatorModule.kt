package com.opuside.app.feature.creator.di

import com.opuside.app.core.data.AppSettings
import com.opuside.app.feature.creator.data.CreatorAIEditService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CreatorModule — DI для AI Edit сервиса
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Если CreatorAIEditService уже имеет @Singleton @Inject constructor,
 * то этот модуль НЕ нужен — Hilt подхватит автоматически.
 *
 * Модуль нужен только если вы хотите явно управлять созданием.
 */
@Module
@InstallIn(SingletonComponent::class)
object CreatorModule {

    @Provides
    @Singleton
    fun provideCreatorAIEditService(
        appSettings: AppSettings
    ): CreatorAIEditService {
        return CreatorAIEditService(appSettings)
    }
}
