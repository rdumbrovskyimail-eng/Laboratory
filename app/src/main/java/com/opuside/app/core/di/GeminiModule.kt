package com.opuside.app.core.di

import com.opuside.app.core.network.gemini.GeminiApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 🔷 GEMINI DI MODULE
 *
 * Provides GeminiApiClient singleton.
 * GeminiApiClient has no constructor dependencies (API key is passed per-call).
 */
@Module
@InstallIn(SingletonComponent::class)
object GeminiModule {

    @Provides
    @Singleton
    fun provideGeminiApiClient(): GeminiApiClient {
        return GeminiApiClient()
    }
}
