package com.opuside.app.core.di

import com.opuside.app.BuildConfig
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.ai.ToolExecutor
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.GitHubGraphQLClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Network Module v4.2 (High-Throughput & Zero-Abuse Optimized)
 *
 * Предоставляет HTTP-клиенты, API-клиенты GitHub и ToolExecutor с поддержкой AppSettings.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

    /**
     * Единый высокопроизводительный пул соединений OkHttp с расширенными лимитами очереди
     */
    @Provides
    @Singleton
    @Named("sharedOkHttp")
    fun provideSharedOkHttpClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 20 // Устранена задержка: параллельные запросы к api.github.com больше не блокируются
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubHttpClient(
        @Named("sharedOkHttp") okHttpClient: OkHttpClient,
        json: Json
    ): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }

            install(ContentNegotiation) {
                json(json)
            }

            // Автоматическая обработка редиректов (301, 302, 307) к AWS S3 / ассетам релизов
            install(HttpRedirect) {
                checkHttpMethod = false
                allowHttpsDowngrade = false
            }

            // Обязательный заголовок User-Agent для исключения 403 Forbidden со стороны GitHub
            defaultRequest {
                header("User-Agent", "OpusIDE-Android-Client/1.0")
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
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
        }
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
        gitHubClient: GitHubApiClient,
        appSettings: AppSettings
    ): GitHubGraphQLClient {
        return GitHubGraphQLClient(httpClient, json, gitHubClient, appSettings)
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
        gitHubClient: GitHubApiClient,
        appSettings: AppSettings
    ): ToolExecutor {
        return ToolExecutor(repoIndexManager, gitHubClient, appSettings)
    }
}