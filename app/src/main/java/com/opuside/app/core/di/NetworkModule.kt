package com.opuside.app.core.di

import com.opuside.app.BuildConfig
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.ai.ToolExecutor
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
 * Network Module v3.0 (ZERO-LATENCY STREAMING)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ИСПРАВЛЕНИЯ ДЛЯ МГНОВЕННОГО СТРИМИНГА:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. OkHttp: retryOnConnectionFailure(false) — нет скрытых задержек на retry
 * 2. readTimeout = 120s — достаточно для длинных ответов Claude
 * 3. Ktor HttpTimeout.socketTimeoutMillis = 120s — матчит OkHttp
 * 4. Logging: sanitizeHeader для API key — безопасность
 * 5. Добавлены провайдеры RepoIndexManager + ToolExecutor
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
            encodeDefaults = false
            explicitNulls = false
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

    /**
     * HTTP клиент для Claude API.
     *
     * КЛЮЧЕВОЕ для стриминга:
     * - readTimeout = 120s (Claude может думать долго перед первым токеном)
     * - Ktor socketTimeout = 120s (должен матчить OkHttp)
     * - retryOnConnectionFailure(false) — нет скрытых retry задержек
     */
    @Provides
    @Singleton
    @Named("anthropic")
    fun provideAnthropicHttpClient(json: Json): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(120, TimeUnit.SECONDS)    // Длинный: Claude думает
                    writeTimeout(30, TimeUnit.SECONDS)
                    retryOnConnectionFailure(false)        // Нет скрытых retry
                }
            }

            install(ContentNegotiation) {
                json(json)
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        // Не логируем тела ответов (SSE flood)
                        android.util.Log.d("Claude-HTTP", message)
                    }
                }
                level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
                sanitizeHeader { name -> name.equals("x-api-key", ignoreCase = true) }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 180_000     // 3 min для tool loops
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 120_000       // Матчит OkHttp readTimeout
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
                    connectTimeout(15, TimeUnit.SECONDS)
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
                level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
                sanitizeHeader { name -> name.equals("Authorization", ignoreCase = true) }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }

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

    @Provides
    @Singleton
    fun provideRepoIndexManager(
        gitHubClient: GitHubApiClient,
        appSettings: AppSettings
    ): RepoIndexManager {
        return RepoIndexManager(gitHubClient, appSettings)
    }

    @Provides
    @Singleton
    fun provideToolExecutor(
        repoIndexManager: RepoIndexManager,
        gitHubClient: GitHubApiClient
    ): ToolExecutor {
        return ToolExecutor(repoIndexManager, gitHubClient)
    }
}
