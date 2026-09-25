package com.opuside.app.core.di

import android.content.Context
import com.opuside.app.BuildConfig
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.ai.ToolExecutor
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.GitHubGraphQLClient
import com.opuside.app.core.security.SecureSettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Network Module v4.1
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
        gitHubClient: GitHubApiClient,
        appSettings: AppSettings
    ): ToolExecutor {
        return ToolExecutor(repoIndexManager, gitHubClient, appSettings)
    }
}