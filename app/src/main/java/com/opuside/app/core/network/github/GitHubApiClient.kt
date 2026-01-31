package com.opuside.app.core.network.github

import android.util.Base64
import com.opuside.app.BuildConfig
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
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Клиент для работы с GitHub REST API.
 * 
 * Поддерживает:
 * - Операции с файлами (CRUD)
 * - GitHub Actions (запуск, статус, логи)
 * - Артефакты
 * 
 * ✅ ИСПРАВЛЕНО:
 * - Проблема №4: Добавлена валидация BuildConfig полей
 * - Проблема №11: Добавлен retry logic с exponential backoff
 */
@Singleton
class GitHubApiClient @Inject constructor(
    @Named("github") private val httpClient: HttpClient,
    private val json: Json
) {
    companion object {
        private const val BASE_URL = "https://api.github.com"
        private const val API_VERSION = "2022-11-28"
    }

    // ✅ ИСПРАВЛЕНО: Проблема №4 - Валидация BuildConfig полей
    private val owner: String
        get() = BuildConfig.GITHUB_OWNER.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GITHUB_OWNER not configured in local.properties")

    private val repo: String
        get() = BuildConfig.GITHUB_REPO.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GITHUB_REPO not configured in local.properties")

    private val token: String
        get() = BuildConfig.GITHUB_TOKEN.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GITHUB_TOKEN not configured in local.properties")

    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY CONTENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Получить содержимое директории или файла.
     */
    suspend fun getContent(
        path: String = "",
        ref: String? = null
    ): Result<List<GitHubContent>> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo/contents/$path") {
            setupHeaders()
            ref?.let { parameter("ref", it) }
        }
    }

    /**
     * Получить содержимое одного файла.
     */
    suspend fun getFileContent(
        path: String,
        ref: String? = null
    ): Result<GitHubContent> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo/contents/$path") {
            setupHeaders()
            ref?.let { parameter("ref", it) }
        }
    }

    /**
     * Получить декодированное содержимое файла.
     */
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

    /**
     * Создать или обновить файл.
     */
    suspend fun createOrUpdateFile(
        path: String,
        content: String,
        message: String,
        sha: String? = null, // Требуется для обновления
        branch: String? = null
    ): Result<CreateOrUpdateFileResponse> {
        val encodedContent = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        val request = CreateOrUpdateFileRequest(
            message = message,
            content = encodedContent,
            sha = sha,
            branch = branch
        )
        
        return apiCall {
            httpClient.put("$BASE_URL/repos/$owner/$repo/contents/$path") {
                setupHeaders()
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    /**
     * Удалить файл.
     */
    suspend fun deleteFile(
        path: String,
        message: String,
        sha: String,
        branch: String? = null
    ): Result<Unit> = apiCall {
        httpClient.delete("$BASE_URL/repos/$owner/$repo/contents/$path") {
            setupHeaders()
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "message" to message,
                "sha" to sha,
                "branch" to (branch ?: "main")
            ))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BRANCHES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Получить список веток.
     */
    suspend fun getBranches(): Result<List<GitHubBranch>> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo/branches") {
            setupHeaders()
        }
    }

    /**
     * Получить информацию о ветке.
     */
    suspend fun getBranch(branch: String): Result<GitHubBranch> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo/branches/$branch") {
            setupHeaders()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Получить список workflows.
     */
    suspend fun getWorkflows(): Result<WorkflowsResponse> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo/actions/workflows") {
            setupHeaders()
        }
    }

    /**
     * Запустить workflow (workflow_dispatch).
     */
    suspend fun triggerWorkflow(
        workflowId: Long,
        ref: String = "main",
        inputs: Map<String, String>? = null
    ): Result<Unit> {
        val request = WorkflowDispatchRequest(ref = ref, inputs = inputs)
        
        return apiCallUnit {
            httpClient.post("$BASE_URL/repos/$owner/$repo/actions/workflows/$workflowId/dispatches") {
                setupHeaders()
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    /**
     * Получить последние запуски workflow.
     */
    suspend fun getWorkflowRuns(
        workflowId: Long? = null,
        branch: String? = null,
        status: String? = null,
        perPage: Int = 10
    ): Result<WorkflowRunsResponse> = apiCall {
        val url = if (workflowId != null) {
            "$BASE_URL/repos/$owner/$repo/actions/workflows/$workflowId/runs"
        } else {
            "$BASE_URL/repos/$owner/$repo/actions/runs"
        }
        
        httpClient.get(url) {
            setupHeaders()
            branch?.let { parameter("branch", it) }
            status?.let { parameter("status", it) }
            parameter("per_page", perPage)
        }
    }

    /**
     * Получить информацию о конкретном запуске.
     */
    suspend fun getWorkflowRun(runId: Long): Result<WorkflowRun> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo/actions/runs/$runId") {
            setupHeaders()
        }
    }

    /**
     * Получить jobs для запуска workflow.
     */
    suspend fun getWorkflowJobs(runId: Long): Result<WorkflowJobsResponse> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo/actions/runs/$runId/jobs") {
            setupHeaders()
        }
    }

    /**
     * Получить логи job.
     */
    suspend fun getJobLogs(jobId: Long): Result<String> {
        return try {
            val response = httpClient.get("$BASE_URL/repos/$owner/$repo/actions/jobs/$jobId/logs") {
                setupHeaders()
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

    /**
     * Перезапустить workflow.
     */
    suspend fun rerunWorkflow(runId: Long): Result<Unit> = apiCallUnit {
        httpClient.post("$BASE_URL/repos/$owner/$repo/actions/runs/$runId/rerun") {
            setupHeaders()
        }
    }

    /**
     * Отменить workflow.
     */
    suspend fun cancelWorkflow(runId: Long): Result<Unit> = apiCallUnit {
        httpClient.post("$BASE_URL/repos/$owner/$repo/actions/runs/$runId/cancel") {
            setupHeaders()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ARTIFACTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Получить артефакты для запуска workflow.
     */
    suspend fun getRunArtifacts(runId: Long): Result<ArtifactsResponse> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo/actions/runs/$runId/artifacts") {
            setupHeaders()
        }
    }

    /**
     * Получить URL для скачивания артефакта.
     */
    suspend fun getArtifactDownloadUrl(artifactId: Long): Result<String> {
        return try {
            val response = httpClient.get("$BASE_URL/repos/$owner/$repo/actions/artifacts/$artifactId/zip") {
                setupHeaders()
            }
            
            // GitHub возвращает 302 redirect на URL скачивания
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

    /**
     * Получить информацию о репозитории.
     */
    suspend fun getRepository(): Result<GitHubRepository> = apiCall {
        httpClient.get("$BASE_URL/repos/$owner/$repo") {
            setupHeaders()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun HttpRequestBuilder.setupHeaders() {
        header("Authorization", "Bearer $token")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", API_VERSION)
    }

    /**
     * ✅ ИСПРАВЛЕНО: Проблема №11 - Добавлен retry logic с exponential backoff
     */
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

                // Не retry для client errors (4xx кроме 429 Rate Limit)
                if (response.status.value in 400..499 && response.status.value != 429) {
                    return Result.failure(parseError(response))
                }

                // Последняя попытка - возвращаем ошибку
                if (attempt == maxRetries - 1) {
                    return Result.failure(parseError(response))
                }

                // Exponential backoff
                delay(currentDelay)
                currentDelay *= 2

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

    /**
     * ✅ ИСПРАВЛЕНО: Проблема №11 - Добавлен retry logic с exponential backoff
     */
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

                // Не retry для client errors (4xx кроме 429 Rate Limit)
                if (response.status.value in 400..499 && response.status.value != 429) {
                    return Result.failure(parseError(response))
                }

                // Последняя попытка - возвращаем ошибку
                if (attempt == maxRetries - 1) {
                    return Result.failure(parseError(response))
                }

                // Exponential backoff
                delay(currentDelay)
                currentDelay *= 2

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

/**
 * Exception для ошибок GitHub API.
 */
class GitHubApiException(
    val type: String,
    override val message: String,
    val statusCode: Int = 0
) : Exception("[$type] $message") {
    
    val isNotFound: Boolean get() = statusCode == 404
    val isUnauthorized: Boolean get() = statusCode == 401
    val isForbidden: Boolean get() = statusCode == 403
    val isRateLimited: Boolean get() = statusCode == 403 && message.contains("rate limit", ignoreCase = true)
}