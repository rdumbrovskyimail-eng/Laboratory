package com.opuside.app.core.di

import android.util.Log
import com.opuside.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * ✅ ИСПРАВЛЕНО: Certificate Pinning ОТКЛЮЧЕН для тестирования
 * 
 * Hilt модуль для сетевых компонентов.
 * 
 * Предоставляет:
 * - Json сериализатор
 * - HttpClient для GitHub API
 * - HttpClient для Anthropic API (с поддержкой SSE)
 * 
 * ⚠️ ВАЖНО: Certificate pinning отключен для упрощения тестирования.
 * Для production сборки рекомендуется включить его обратно с актуальными пинами.
 * 
 * Для получения актуальных certificate pins используйте:
 * ```bash
 * # GitHub API
 * openssl s_client -connect api.github.com:443 -servername api.github.com </dev/null 2>/dev/null | \
 *   openssl x509 -pubkey -noout | \
 *   openssl rsa -pubin -outform der 2>/dev/null | \
 *   openssl dgst -sha256 -binary | base64
 * 
 * # Anthropic API
 * openssl s_client -connect api.anthropic.com:443 -servername api.anthropic.com </dev/null 2>/dev/null | \
 *   openssl x509 -pubkey -noout | \
 *   openssl rsa -pubin -outform der 2>/dev/null | \
 *   openssl dgst -sha256 -binary | base64
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val TAG = "NetworkModule"

    // ═══════════════════════════════════════════════════════════════════════════
    // JSON SERIALIZER
    // ═══════════════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
        coerceInputValues = true
        explicitNulls = false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB HTTP CLIENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО: Certificate pinning отключен для тестирования
     * 
     * Теперь использует стандартную SSL верификацию без pinning.
     * Это позволяет избежать ошибок с устаревшими сертификатами.
     */
    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        // Engine configuration
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
                
                // ✅ БЕЗ Certificate Pinning - используем стандартную SSL проверку
                retryOnConnectionFailure(true)
            }
        }

        // Content negotiation
        install(ContentNegotiation) {
            json(json)
        }

        // Timeouts
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }

        // Logging (минимальное для production)
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    if (BuildConfig.DEBUG) {
                        Log.d("GitHubAPI", message)
                    }
                }
            }
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
        }

        // Default request configuration
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANTHROPIC HTTP CLIENT (для SSE Streaming)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО: Certificate pinning отключен для тестирования
     * 
     * Особенности:
     * - Увеличенные таймауты для streaming (5 минут)
     * - Стандартная SSL проверка без pinning
     * - Минимальное логирование для production
     */
    @Provides
    @Singleton
    @Named("anthropic")
    fun provideAnthropicHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        // Engine configuration - увеличенные таймауты для streaming
        engine {
            config {
                connectTimeout(60, TimeUnit.SECONDS)
                readTimeout(300, TimeUnit.SECONDS) // 5 минут для длинных ответов
                writeTimeout(60, TimeUnit.SECONDS)
                
                // ✅ БЕЗ Certificate Pinning - используем стандартную SSL проверку
                retryOnConnectionFailure(true)
            }
        }

        // Content negotiation
        install(ContentNegotiation) {
            json(json)
        }

        // Timeouts - увеличены для streaming
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000 // 5 минут
            connectTimeoutMillis = 60_000
            socketTimeoutMillis = 300_000
        }

        // Logging (минимальное для production)
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    if (BuildConfig.DEBUG) {
                        when {
                            message.contains("stream", ignoreCase = true) -> {
                                Log.d("AnthropicAPI-Streaming", message)
                            }
                            else -> {
                                Log.d("AnthropicAPI", message)
                            }
                        }
                    }
                }
            }
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
        }

        // Default request configuration
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API CONFIGURATION STRINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Provides Anthropic API URL from BuildConfig.
     * Used for ClaudeApiClient dependency injection.
     */
    @Provides
    @Singleton
    @Named("anthropicApiUrl")
    fun provideAnthropicApiUrl(): String = BuildConfig.ANTHROPIC_API_URL

    /**
     * Provides GitHub API URL from BuildConfig.
     * Used for GitHubApiClient dependency injection.
     */
    @Provides
    @Singleton
    @Named("githubApiUrl")
    fun provideGitHubApiUrl(): String = BuildConfig.GITHUB_API_URL

    /**
     * Provides GitHub GraphQL URL from BuildConfig.
     * Used for GitHubGraphQLClient dependency injection.
     */
    @Provides
    @Singleton
    @Named("githubGraphQLUrl")
    fun provideGitHubGraphQLUrl(): String = BuildConfig.GITHUB_GRAPHQL_URL
}
