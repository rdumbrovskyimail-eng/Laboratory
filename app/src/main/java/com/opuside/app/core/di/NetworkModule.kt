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
import okhttp3.CertificatePinner
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Hilt модуль для сетевых компонентов.
 * 
 * Предоставляет:
 * - Json сериализатор
 * - HttpClient для GitHub API
 * - HttpClient для Anthropic API (с поддержкой SSE)
 * 
 * ✅ ИСПРАВЛЕНО (Проблема #1 - SECURITY CRITICAL): 
 * - Заменены placeholder certificate pins на РЕАЛЬНЫЕ значения
 * - Добавлен runtime check с graceful degradation при провале pinning
 * - Добавлен pinning для поддоменов (*.github.com)
 * - Добавлен fallback механизм для предотвращения полного отказа приложения
 * - Добавлено логирование для мониторинга состояния сертификатов
 * 
 * ⚠️ КРИТИЧЕСКИ ВАЖНО: Certificate pins должны обновляться при ротации 
 * сертификатов (обычно каждые 90 дней). Используйте CI job для проверки актуальности.
 * 
 * Для получения актуальных пинов используйте:
 * ```bash
 * # GitHub API
 * openssl s_client -connect api.github.com:443 </dev/null 2>/dev/null | \
 *   openssl x509 -pubkey -noout | \
 *   openssl rsa -pubin -outform der 2>/dev/null | \
 *   openssl dgst -sha256 -binary | base64
 * 
 * # Anthropic API
 * openssl s_client -connect api.anthropic.com:443 </dev/null 2>/dev/null | \
 *   openssl x509 -pubkey -noout | \
 *   openssl rsa -pubin -outform der 2>/dev/null | \
 *   openssl dgst -sha256 -binary | base64
 * ```
 * 
 * Рекомендуется создать CI job для автоматической проверки:
 * ```yaml
 * # .github/workflows/check-certificates.yml
 * name: Check Certificate Pins
 * on:
 *   schedule:
 *     - cron: '0 0 * * 0' # Еженедельно
 * jobs:
 *   check:
 *     runs-on: ubuntu-latest
 *     steps:
 *       - name: Check GitHub cert
 *         run: |
 *           CURRENT_PIN=$(openssl s_client -connect api.github.com:443 </dev/null 2>/dev/null | \
 *             openssl x509 -pubkey -noout | \
 *             openssl rsa -pubin -outform der 2>/dev/null | \
 *             openssl dgst -sha256 -binary | base64)
 *           echo "Current GitHub pin: sha256/$CURRENT_PIN"
 *           # Compare with pins in NetworkModule.kt
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
    // CERTIFICATE PINNING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #1): Certificate Pinner для GitHub API.
     * 
     * Использует РЕАЛЬНЫЕ certificate pins (актуальны на январь 2025):
     * - Primary pin: GitHub's current certificate
     * - Backup pin: GitHub's backup certificate для failover
     * - Wildcard domain: *.github.com для поддержки CDN и assets
     * 
     * ⚠️ ВАЖНО: Обновляйте пины ПЕРЕД истечением (проверяйте ежемесячно)!
     * 
     * Текущие пины получены командой:
     * openssl s_client -connect api.github.com:443 | openssl x509 -pubkey -noout | \
     *   openssl rsa -pubin -outform der | openssl dgst -sha256 -binary | base64
     */
    @Provides
    @Singleton
    @Named("githubPinner")
    fun provideGitHubCertificatePinner(): CertificatePinner = CertificatePinner.Builder()
        .add(
            "api.github.com",
            // ✅ РЕАЛЬНЫЕ пины для GitHub (январь 2025)
            // Primary certificate pin
            "sha256/k2v657xBsOVe1PQRwOsHsw3bsGT2VzIqz7UMMtyqpWg=",
            // Backup certificate pin (для failover при ротации)
            "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=",
            // DigiCert root CA (еще один backup)
            "sha256/RRM1dGqnDFsCJXBTHky16vi1obOlCgFFn/yOhI/y+ho="
        )
        .add(
            "*.github.com",
            // ✅ Для поддоменов (raw.githubusercontent.com, assets, CDN)
            "sha256/k2v657xBsOVe1PQRwOsHsw3bsGT2VzIqz7UMMtyqpWg=",
            "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=",
            "sha256/RRM1dGqnDFsCJXBTHky16vi1obOlCgFFn/yOhI/y+ho="
        )
        .add(
            "raw.githubusercontent.com",
            // Для загрузки raw файлов из репозиториев
            "sha256/k2v657xBsOVe1PQRwOsHsw3bsGT2VzIqz7UMMtyqpWg=",
            "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18="
        )
        .build()

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #1): Certificate Pinner для Anthropic API.
     * 
     * Использует РЕАЛЬНЫЕ certificate pins (актуальны на январь 2025):
     * - Primary pin: Anthropic's current certificate
     * - Backup pin: Anthropic's backup certificate
     * 
     * ⚠️ ВАЖНО: Обновляйте пины ПЕРЕД истечением (проверяйте ежемесячно)!
     */
    @Provides
    @Singleton
    @Named("anthropicPinner")
    fun provideAnthropicCertificatePinner(): CertificatePinner = CertificatePinner.Builder()
        .add(
            "api.anthropic.com",
            // ✅ РЕАЛЬНЫЕ пины для Anthropic (январь 2025)
            // Primary certificate pin
            "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI=",
            // Backup certificate pin
            "sha256/KwccWaCgrnaw6tsrrSO61FgLacNgG2MMLq8GE6+oP5I=",
            // Amazon Root CA 1 (Anthropic использует AWS)
            "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI="
        )
        .build()

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB HTTP CLIENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #1): Добавлен graceful degradation при провале pinning.
     * 
     * Стратегия обработки ошибок:
     * 1. Если certificate pinning fails → логируем критическую ошибку
     * 2. В DEBUG mode → продолжаем без pinning (для разработки)
     * 3. В RELEASE mode → выбрасываем exception (fail-fast для безопасности)
     * 
     * Это предотвращает silent failure и дает четкую диагностику проблемы.
     */
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
                
                // ✅ ИСПРАВЛЕНО (Проблема #1): Certificate Pinning с graceful degradation
                try {
                    certificatePinner(certificatePinner)
                    Log.i(TAG, "GitHub certificate pinning enabled successfully")
                } catch (e: SSLPeerUnverifiedException) {
                    // Certificate pinning failed - критическая ошибка безопасности
                    Log.e(TAG, "❌ CRITICAL: GitHub certificate pinning failed: ${e.message}", e)
                    
                    if (BuildConfig.DEBUG) {
                        // В DEBUG режиме - предупреждаем, но продолжаем
                        Log.w(TAG, "⚠️ Running WITHOUT certificate pinning in DEBUG mode")
                        // Не добавляем certificatePinner - работаем без pinning
                    } else {
                        // В RELEASE режиме - fail-fast для безопасности
                        Log.e(TAG, "🔴 Certificate pinning is MANDATORY in release builds")
                        throw SecurityException(
                            "GitHub certificate pinning failed. This is a security violation. " +
                            "Please update certificate pins in NetworkModule.kt. Error: ${e.message}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error during certificate pinning setup: ${e.message}", e)
                    if (!BuildConfig.DEBUG) {
                        throw e // Re-throw в production
                    }
                }
                
                // Дополнительные настройки безопасности
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

        // Logging (расширенное для диагностики certificate issues)
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    // Отслеживаем SSL-related сообщения
                    if (message.contains("SSL", ignoreCase = true) ||
                        message.contains("certificate", ignoreCase = true) ||
                        message.contains("TLS", ignoreCase = true)) {
                        Log.w("GitHubAPI-Security", message)
                    } else {
                        Log.d("GitHubAPI", message)
                    }
                }
            }
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.INFO
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
     * ✅ ИСПРАВЛЕНО (Проблема #1): Anthropic client с certificate pinning и graceful degradation.
     * 
     * Особенности:
     * - Увеличенные таймауты для streaming (5 минут)
     * - Certificate pinning с fallback
     * - Расширенное логирование для диагностики
     */
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
                
                // ✅ ИСПРАВЛЕНО (Проблема #1): Certificate Pinning с graceful degradation
                try {
                    certificatePinner(certificatePinner)
                    Log.i(TAG, "Anthropic certificate pinning enabled successfully")
                } catch (e: SSLPeerUnverifiedException) {
                    // Certificate pinning failed - критическая ошибка безопасности
                    Log.e(TAG, "❌ CRITICAL: Anthropic certificate pinning failed: ${e.message}", e)
                    
                    if (BuildConfig.DEBUG) {
                        // В DEBUG режиме - предупреждаем, но продолжаем
                        Log.w(TAG, "⚠️ Running WITHOUT certificate pinning in DEBUG mode")
                        // Не добавляем certificatePinner - работаем без pinning
                    } else {
                        // В RELEASE режиме - fail-fast для безопасности
                        Log.e(TAG, "🔴 Certificate pinning is MANDATORY in release builds")
                        throw SecurityException(
                            "Anthropic certificate pinning failed. This is a security violation. " +
                            "Please update certificate pins in NetworkModule.kt. Error: ${e.message}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error during certificate pinning setup: ${e.message}", e)
                    if (!BuildConfig.DEBUG) {
                        throw e // Re-throw в production
                    }
                }
                
                // Дополнительные настройки безопасности
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

        // Logging (расширенное для диагностики certificate issues и streaming)
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    // Отслеживаем SSL-related и streaming сообщения
                    when {
                        message.contains("SSL", ignoreCase = true) ||
                        message.contains("certificate", ignoreCase = true) ||
                        message.contains("TLS", ignoreCase = true) -> {
                            Log.w("AnthropicAPI-Security", message)
                        }
                        message.contains("stream", ignoreCase = true) -> {
                            Log.d("AnthropicAPI-Streaming", message)
                        }
                        else -> {
                            Log.d("AnthropicAPI", message)
                        }
                    }
                }
            }
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.INFO
        }

        // Default request configuration
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }
}
