package com.opuside.app.core.di

import android.content.Context
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.security.SecureSettingsDataStore
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
}