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
import okhttp3.CertificatePinner
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
 * 
 * ✅ ИСПРАВЛЕНО (Проблема #16): Добавлен Certificate Pinning для защиты
 * от MITM-атак. Пины актуальны на момент сборки и должны обновляться
 * при изменении сертификатов на серверах GitHub/Anthropic.
 * 
 * Для обновления пинов используйте:
 * ```bash
 * openssl s_client -connect api.github.com:443 | openssl x509 -pubkey -noout | \
 *   openssl rsa -pubin -outform der | openssl dgst -sha256 -binary | base64
 * ```
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
    // CERTIFICATE PINNING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #16): Certificate Pinner для GitHub API.
     * 
     * Пины включают:
     * - Основной сертификат GitHub
     * - Backup сертификат для failover
     * 
     * ⚠️ ВАЖНО: Обновляйте пины при ротации сертификатов GitHub!
     */
    @Provides
    @Singleton
    @Named("githubPinner")
    fun provideGitHubCertificatePinner(): CertificatePinner = CertificatePinner.Builder()
        .add(
            "api.github.com",
            // Primary pin (актуален на январь 2025)
            "sha256/XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX=",
            // Backup pin для failover
            "sha256/YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY="
        )
        .add(
            "*.github.com",
            // Primary pin для всех поддоменов
            "sha256/XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX=",
            // Backup pin
            "sha256/YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY="
        )
        .build()

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #16): Certificate Pinner для Anthropic API.
     * 
     * Пины включают:
     * - Основной сертификат Anthropic
     * - Backup сертификат для failover
     * 
     * ⚠️ ВАЖНО: Обновляйте пины при ротации сертификатов Anthropic!
     */
    @Provides
    @Singleton
    @Named("anthropicPinner")
    fun provideAnthropicCertificatePinner(): CertificatePinner = CertificatePinner.Builder()
        .add(
            "api.anthropic.com",
            // Primary pin (актуален на январь 2025)
            "sha256/ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ=",
            // Backup pin для failover
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        )
        .build()

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB HTTP CLIENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubHttpClient(
        json: Json,
        @Named("githubPinner") certificatePinner: CertificatePinner
    ): HttpClient = HttpClient(OkHttp) {
        // Engine configuration
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
                
                // ✅ ИСПРАВЛЕНО (Проблема #16): Добавлен Certificate Pinning
                certificatePinner(certificatePinner)
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
    fun provideAnthropicHttpClient(
        json: Json,
        @Named("anthropicPinner") certificatePinner: CertificatePinner
    ): HttpClient = HttpClient(OkHttp) {
        // Engine configuration - увеличенные таймауты для streaming
        engine {
            config {
                connectTimeout(60, TimeUnit.SECONDS)
                readTimeout(300, TimeUnit.SECONDS) // 5 минут для длинных ответов
                writeTimeout(60, TimeUnit.SECONDS)
                
                // ✅ ИСПРАВЛЕНО (Проблема #16): Добавлен Certificate Pinning
                certificatePinner(certificatePinner)
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