package com.opuside.app.core.di

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
 * Hilt модуль для сетевых компонентов.
 * 
 * Предоставляет:
 * - Json сериализатор
 * - HttpClient для GitHub API
 * - HttpClient для Anthropic API (с поддержкой SSE)
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

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

        // Logging (только в debug)
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    android.util.Log.d("GitHubAPI", message)
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

        // Logging
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    android.util.Log.d("AnthropicAPI", message)
                }
            }
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
        }

        // Default request configuration
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }
}
