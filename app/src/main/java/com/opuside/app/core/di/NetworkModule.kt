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
 * Network Module v4.0 (LONG CONTEXT + SSE STREAMING STABILITY)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * КРИТИЧЕСКИЕ ИСПРАВЛЕНИЯ ДЛЯ 125K OUTPUT + 1M CONTEXT:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. OkHttp readTimeout = 0 (INFINITE) — SSE стрим без ограничений
 *    Причина: Claude может думать 2-3 минуты перед первым токеном (thinking)
 *    Защита: MAX_STREAMING_TIME_MS в ClaudeApiClient (90 минут)
 *
 * 2. OkHttp writeTimeout = 120s — для отправки больших файлов (до 2MB)
 *
 * 3. Ktor requestTimeout = INFINITE — SSE стрим без обрывов
 *    Причина: readTimeout=120s убивает соединение если между чанками >120s
 *
 * 4. Ktor socketTimeout = INFINITE — матчит OkHttp readTimeout=0
 *
 * 5. retryOnConnectionFailure(false) — нет скрытых задержек на retry
 *
 * ТЕСТОВЫЙ СЦЕНАРИЙ:
 * - 1MB файл → 270K input tokens
 * - Thinking 40K tokens → 2-5 минут ожидания
 * - Output 125K tokens → 40-67 минут генерации
 * - Общее время: до 90 минут
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
     * КРИТИЧНО для стабильности SSE стриминга:
     * - readTimeout = 0 (бесконечный) — SSE может ждать сколько угодно между чанками
     * - writeTimeout = 120s — отправка 2MB файла занимает время
     * - Ktor requestTimeout = null (INFINITE) — не обрывает долгие запросы
     * - Ktor socketTimeout = null (INFINITE) — матчит readTimeout=0
     *
     * Защита от зависания: MAX_STREAMING_TIME_MS = 90 минут в ClaudeApiClient
     */
    @Provides
    @Singleton
    @Named("anthropic")
    fun provideAnthropicHttpClient(json: Json): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.SECONDS)       // 0 = бесконечный (SSE стрим)
                    writeTimeout(120, TimeUnit.SECONDS)    // Для отправки 2MB файла
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
                requestTimeoutMillis = null  // null = бесконечный timeout для SSE стриминга
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = null   // null = бесконечный timeout для SSE стриминга
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