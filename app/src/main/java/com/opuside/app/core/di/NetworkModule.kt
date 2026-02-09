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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Network Module v2.3 (FIX)
 *
 * ✅ ИСПРАВЛЕНО:
 * - provideJson(): explicitNulls = false, encodeDefaults = false
 *   Это гарантирует, что null-поля (temperature, system, top_p, etc.)
 *   НЕ попадают в JSON запрос.
 * - @EncodeDefault на обязательных полях в ClaudeRequest гарантирует,
 *   что model, max_tokens, messages, stream ВСЕГДА присутствуют в JSON.
 *
 * БЫЛО (ОШИБКА):
 *   {"model":"...","max_tokens":10,"messages":[...],"system":null,"stream":false,"temperature":null,"top_p":null,"top_k":null,"stop_sequences":null,"metadata":null}
 *
 * СТАЛО (ПРАВИЛЬНО):
 *   {"model":"...","max_tokens":10,"messages":[...],"stream":false}
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @OptIn(ExperimentalSerializationApi::class)
    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false      // ← НЕ сериализовать поля с дефолтными значениями
            explicitNulls = false        // ← НЕ сериализовать null-поля (КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ)
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