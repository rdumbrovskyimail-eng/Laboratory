package com.opuside.app.core.di

import com.opuside.app.BuildConfig
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.GitHubGraphQLClient
import com.opuside.app.core.security.SecureSettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Network Module v2.2 (FINAL)
 * 
 * ✅ ИСПРАВЛЕНО:
 * - Добавлен AppSettings в provideClaudeApiClient()
 * - Добавлены все необходимые @Provides методы для API клиентов
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            prettyPrint = false
            coerceInputValues = true
        }
    }

    @Provides
    @Singleton
    @Named("anthropicApiUrl")
    fun provideAnthropicApiUrl(): String {
        return BuildConfig.ANTHROPIC_API_URL.ifBlank { 
            "https://api.anthropic.com/v1/messages" 
        }
    }

    @Provides
    @Singleton
    @Named("anthropic")
    fun provideAnthropicHttpClient(json: Json): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)
                }
            }

            install(ContentNegotiation) {
                json(json)
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        android.util.Log.d("Claude-HTTP", message)
                    }
                }
                level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubHttpClient(json: Json): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                }
            }

            install(ContentNegotiation) {
                json(json)
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        android.util.Log.d("GitHub-HTTP", message)
                    }
                }
                level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: Добавлен AppSettings в зависимости
     */
    @Provides
    @Singleton
    fun provideClaudeApiClient(
        @Named("anthropic") httpClient: HttpClient,
        json: Json,
        @Named("anthropicApiUrl") apiUrl: String,
        secureSettings: SecureSettingsDataStore,
        appSettings: AppSettings
    ): ClaudeApiClient {
        return ClaudeApiClient(httpClient, json, apiUrl, secureSettings, appSettings)
    }

    @Provides
    @Singleton
    fun provideGitHubApiClient(
        @Named("github") httpClient: HttpClient,
        json: Json,
        appSettings: AppSettings
    ): GitHubApiClient {
        return GitHubApiClient(httpClient, json, appSettings)
    }

    @Provides
    @Singleton
    fun provideGitHubGraphQLClient(
        @Named("github") httpClient: HttpClient,
        json: Json,
        gitHubClient: GitHubApiClient
    ): GitHubGraphQLClient {
        return GitHubGraphQLClient(httpClient, json, gitHubClient)
    }
}