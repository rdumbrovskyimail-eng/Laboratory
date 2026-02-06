package com.opuside.app.core.network.github

import android.util.Base64
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО (2026-02-06):
 * 
 * ПРОБЛЕМА: Crash при первом запуске приложения
 * ────────────────────────────────────────────────
 * ПРИЧИНА:
 * - getConfig() требовал наличия owner/repo/token
 * - При первом запуске они пустые
 * - Выбрасывался IllegalStateException
 * - Приложение крашилось
 * 
 * РЕШЕНИЕ:
 * - getConfig() возвращает null если настройки не заполнены
 * - Все API методы проверяют config на null
 * - Возвращают Result.failure с понятным сообщением
 * - UI показывает placeholder вместо краша
 */
@Singleton
class GitHubApiClient @Inject constructor(
    @Named("github") private val httpClient: HttpClient,
    private val json: Json,
    private val appSettings: AppSettings
) {
    companion object {
        private const val BASE_URL = "https://api.github.com"
        private const val API_VERSION = "2022-11-28"
    }

    /**
     * ✅ ИСПРАВЛЕНО: Возвращает null если настройки не заполнены
     * Больше НЕ выбрасывает exception
     */
    private suspend fun getConfig(): GitHubConfig? {
        val config = appSettings.gitHubConfig.first()
        
        // Проверяем что ВСЕ обязательные поля заполнены
        if (config.owner.isBlank() || config.repo.isBlank() || config.token.isBlank()) {
            android.util.Log.w("GitHubApiClient", "⚠️ GitHub not configured: owner=${config.owner.isNotBlank()}, repo=${config.repo.isNotBlank()}, token=${config.token.isNotBlank()}")
            return null
        }
        
        return config
    }

    /**
     * ✅ НОВЫЙ МЕТОД: Проверка наличия конфигурации
     */
    suspend fun isConfigured(): Boolean {
        return getConfig() != null
    }

    private fun encodePath(path: String): String {
        return path.split("/")
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
            }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY CONTENT
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getContent(
        path: String = "",
        ref: String? = null
    ): Result<List<GitHubContent>> {
        val config = getConfig() 
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured. Please set Owner, Repository, and Token in Settings."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}") {
                setupHeaders(config.token)
                ref?.let { parameter("ref", it) }
            }
        }
    }

    suspend fun getFileContent(
        path: String,
        ref: String? = null
    ): Result<GitHubContent> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured. Please set Owner, Repository, and Token in Settings."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}") {
                setupHeaders(config.token)
                ref?.let { parameter("ref", it) }
            }
        }
    }

    suspend fun getFileContentDecoded(
        path: String,
        ref: String? = null
    ): Result<String> {
        return getFileContent(path, ref).map { content ->
            content.content?.let { base64 ->
                String(Base64.decode(base64.replace("\n", ""), Base64.DEFAULT))
            } ?: ""
        }
    }

    suspend fun createOrUpdateFile(
        path: String,
        content: String,
        message: String,
        sha: String? = null,
        branch: String? = null
    ): Result<CreateOrUpdateFileResponse> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured. Please set Owner, Repository, and Token in Settings."
            ))
            
        val encodedContent = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        val request = CreateOrUpdateFileRequest(
            message = message,
            content = encodedContent,
            sha = sha,
            branch = branch ?: config.branch
        )
        
        return apiCall {
            httpClient.put("$BASE_URL/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}") {
                setupHeaders(config.token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    suspend fun deleteFile(
        path: String,
        message: String,
        sha: String,
        branch: String? = null
    ): Result<Unit> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured. Please set Owner, Repository, and Token in Settings."
            ))
        
        return apiCall {
            httpClient.delete("$BASE_URL/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}") {
                setupHeaders(config.token)
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "message" to message,
                    "sha" to sha,
                    "branch" to (branch ?: config.branch)
                ))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BRANCHES
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getBranches(): Result<List<GitHubBranch>> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured. Please set Owner, Repository, and Token in Settings."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/branches") {
                setupHeaders(config.token)
            }
        }
    }

    suspend fun getBranch(branch: String): Result<GitHubBranch> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured. Please set Owner, Repository, and Token in Settings."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/branches/$branch") {
                setupHeaders(config.token)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getWorkflows(): Result<WorkflowsResponse> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/actions/workflows") {
                setupHeaders(config.token)
            }
        }
    }

    suspend fun triggerWorkflow(
        workflowId: Long,
        ref: String = "main",
        inputs: Map<String, String>? = null
    ): Result<Unit> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
            
        val request = WorkflowDispatchRequest(ref = ref, inputs = inputs)
        
        return apiCallUnit {
            httpClient.post("$BASE_URL/repos/${config.owner}/${config.repo}/actions/workflows/$workflowId/dispatches") {
                setupHeaders(config.token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    suspend fun getWorkflowRuns(
        workflowId: Long? = null,
        branch: String? = null,
        status: String? = null,
        perPage: Int = 10
    ): Result<WorkflowRunsResponse> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
            
        val url = if (workflowId != null) {
            "$BASE_URL/repos/${config.owner}/${config.repo}/actions/workflows/$workflowId/runs"
        } else {
            "$BASE_URL/repos/${config.owner}/${config.repo}/actions/runs"
        }
        
        return apiCall {
            httpClient.get(url) {
                setupHeaders(config.token)
                branch?.let { parameter("branch", it) }
                status?.let { parameter("status", it) }
                parameter("per_page", perPage)
            }
        }
    }

    suspend fun getWorkflowRun(runId: Long): Result<WorkflowRun> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/actions/runs/$runId") {
                setupHeaders(config.token)
            }
        }
    }

    suspend fun getWorkflowJobs(runId: Long): Result<WorkflowJobsResponse> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/actions/runs/$runId/jobs") {
                setupHeaders(config.token)
            }
        }
    }

    suspend fun getJobLogs(jobId: Long): Result<String> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
        
        return try {
            val response = httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/actions/jobs/$jobId/logs") {
                setupHeaders(config.token)
            }
            
            if (response.status.isSuccess()) {
                Result.success(response.bodyAsText())
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException("network_error", e.message ?: "Unknown error"))
        }
    }

    suspend fun rerunWorkflow(runId: Long): Result<Unit> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
        
        return apiCallUnit {
            httpClient.post("$BASE_URL/repos/${config.owner}/${config.repo}/actions/runs/$runId/rerun") {
                setupHeaders(config.token)
            }
        }
    }

    suspend fun cancelWorkflow(runId: Long): Result<Unit> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
        
        return apiCallUnit {
            httpClient.post("$BASE_URL/repos/${config.owner}/${config.repo}/actions/runs/$runId/cancel") {
                setupHeaders(config.token)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ARTIFACTS
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getRunArtifacts(runId: Long): Result<ArtifactsResponse> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/actions/runs/$runId/artifacts") {
                setupHeaders(config.token)
            }
        }
    }

    suspend fun getArtifactDownloadUrl(artifactId: Long): Result<String> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured."
            ))
        
        return try {
            val response = httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}/actions/artifacts/$artifactId/zip") {
                setupHeaders(config.token)
            }
            
            val location = response.headers["Location"]
            if (location != null) {
                Result.success(location)
            } else {
                Result.failure(GitHubApiException("no_redirect", "No download URL returned"))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException("network_error", e.message ?: "Unknown error"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY INFO
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getRepository(): Result<GitHubRepository> {
        val config = getConfig()
            ?: return Result.failure(GitHubApiException(
                type = "not_configured",
                message = "GitHub not configured. Please set Owner, Repository, and Token in Settings."
            ))
        
        return apiCall {
            httpClient.get("$BASE_URL/repos/${config.owner}/${config.repo}") {
                setupHeaders(config.token)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun HttpRequestBuilder.setupHeaders(token: String) {
        header("Authorization", "Bearer $token")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", API_VERSION)
    }

    private suspend inline fun <reified T> apiCall(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        crossinline block: suspend () -> HttpResponse
    ): Result<T> {
        var currentDelay = initialDelayMs

        repeat(maxRetries) { attempt ->
            try {
                val response = block()

                if (response.status.isSuccess()) {
                    return Result.success(response.body<T>())
                }

                if (response.status.value in 400..499 && response.status.value != 429) {
                    return Result.failure(parseError(response))
                }

                if (attempt == maxRetries - 1) {
                    return Result.failure(parseError(response))
                }

                if (response.status.value == 429) {
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                    val delayMs = if (retryAfter != null) {
                        retryAfter * 1000
                    } else {
                        currentDelay
                    }
                    delay(delayMs)
                    currentDelay = (delayMs * 2).coerceAtMost(60_000)
                } else {
                    delay(currentDelay)
                    currentDelay *= 2
                }

            } catch (e: Exception) {
                if (attempt == maxRetries - 1) {
                    return Result.failure(GitHubApiException("network_error", e.message ?: "Unknown error"))
                }
                delay(currentDelay)
                currentDelay *= 2
            }
        }

        return Result.failure(GitHubApiException("max_retries", "Max retries exceeded"))
    }

    private suspend fun apiCallUnit(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        block: suspend () -> HttpResponse
    ): Result<Unit> {
        var currentDelay = initialDelayMs

        repeat(maxRetries) { attempt ->
            try {
                val response = block()

                if (response.status.isSuccess() || response.status == HttpStatusCode.NoContent) {
                    return Result.success(Unit)
                }

                if (response.status.value in 400..499 && response.status.value != 429) {
                    return Result.failure(parseError(response))
                }

                if (attempt == maxRetries - 1) {
                    return Result.failure(parseError(response))
                }

                if (response.status.value == 429) {
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                    val delayMs = if (retryAfter != null) {
                        retryAfter * 1000
                    } else {
                        currentDelay
                    }
                    delay(delayMs)
                    currentDelay = (delayMs * 2).coerceAtMost(60_000)
                } else {
                    delay(currentDelay)
                    currentDelay *= 2
                }

            } catch (e: Exception) {
                if (attempt == maxRetries - 1) {
                    return Result.failure(GitHubApiException("network_error", e.message ?: "Unknown error"))
                }
                delay(currentDelay)
                currentDelay *= 2
            }
        }

        return Result.failure(GitHubApiException("max_retries", "Max retries exceeded"))
    }

    private suspend fun parseError(response: HttpResponse): GitHubApiException {
        return try {
            val errorBody = response.body<GitHubError>()
            GitHubApiException(
                type = "github_error",
                message = errorBody.message,
                statusCode = response.status.value
            )
        } catch (e: Exception) {
            GitHubApiException(
                type = "http_error",
                message = "HTTP ${response.status.value}: ${response.status.description}",
                statusCode = response.status.value
            )
        }
    }
}

class GitHubApiException(
    val type: String,
    override val message: String,
    val statusCode: Int = 0
) : Exception("[$type] $message") {
    
    val isNotFound: Boolean get() = statusCode == 404
    val isUnauthorized: Boolean get() = statusCode == 401
    val isForbidden: Boolean get() = statusCode == 403
    val isRateLimited: Boolean get() = statusCode == 403 && message.contains("rate limit", ignoreCase = true)
    val isNotConfigured: Boolean get() = type == "not_configured"
}

typealias GitHubConfig = com.opuside.app.core.data.GitHubConfig