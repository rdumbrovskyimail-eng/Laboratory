package com.opuside.app.feature.creator.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CreatorModule — DI для AI Edit сервиса
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * CreatorAIEditService помечен @Singleton @Inject constructor — Hilt создаёт
 * его автоматически, ручной @Provides не нужен. Модуль оставлен пустым на
 * случай, если в будущем потребуется явная конфигурация Creator-зависимостей.
 */
@Module
@InstallIn(SingletonComponent::class)
object CreatorModule