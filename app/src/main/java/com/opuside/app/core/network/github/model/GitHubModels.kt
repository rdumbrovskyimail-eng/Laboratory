package com.opuside.app.core.network.github.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════════════════════════════════════════
// REPOSITORY CONTENT
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Элемент в дереве репозитория (файл или папка).
 */
@Serializable
data class GitHubContent(
    @SerialName("name")
    val name: String,
    
    @SerialName("path")
    val path: String,
    
    @SerialName("sha")
    val sha: String,
    
    @SerialName("size")
    val size: Int,
    
    @SerialName("type")
    val type: String, // "file" или "dir"
    
    @SerialName("content")
    val content: String? = null, // Base64 encoded content
    
    @SerialName("encoding")
    val encoding: String? = null, // "base64"
    
    @SerialName("url")
    val url: String,
    
    @SerialName("html_url")
    val htmlUrl: String,
    
    @SerialName("git_url")
    val gitUrl: String? = null,
    
    @SerialName("download_url")
    val downloadUrl: String? = null
)

/**
 * Информация о репозитории.
 */
@Serializable
data class GitHubRepository(
    @SerialName("id")
    val id: Long,
    
    @SerialName("name")
    val name: String,
    
    @SerialName("full_name")
    val fullName: String,
    
    @SerialName("description")
    val description: String?,
    
    @SerialName("private")
    val isPrivate: Boolean,
    
    @SerialName("default_branch")
    val defaultBranch: String,
    
    @SerialName("html_url")
    val htmlUrl: String,
    
    @SerialName("language")
    val language: String?,
    
    @SerialName("stargazers_count")
    val stars: Int,
    
    @SerialName("forks_count")
    val forks: Int
)

/**
 * Ветка репозитория.
 */
@Serializable
data class GitHubBranch(
    @SerialName("name")
    val name: String,
    
    @SerialName("commit")
    val commit: GitHubCommitRef,
    
    @SerialName("protected")
    val isProtected: Boolean
)

/**
 * Ссылка на коммит.
 */
@Serializable
data class GitHubCommitRef(
    @SerialName("sha")
    val sha: String,
    
    @SerialName("url")
    val url: String
)

// ═══════════════════════════════════════════════════════════════════════════════
// COMMIT / FILE OPERATIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Запрос на создание/обновление файла.
 */
@Serializable
data class CreateOrUpdateFileRequest(
    @SerialName("message")
    val message: String,
    
    @SerialName("content")
    val content: String, // Base64 encoded
    
    @SerialName("sha")
    val sha: String? = null, // Требуется для обновления существующего файла
    
    @SerialName("branch")
    val branch: String? = null
)

/**
 * Ответ на создание/обновление файла.
 */
@Serializable
data class CreateOrUpdateFileResponse(
    @SerialName("content")
    val content: GitHubContent,
    
    @SerialName("commit")
    val commit: GitHubCommitInfo
)

/**
 * Информация о коммите.
 */
@Serializable
data class GitHubCommitInfo(
    @SerialName("sha")
    val sha: String,
    
    @SerialName("message")
    val message: String,
    
    @SerialName("html_url")
    val htmlUrl: String,
    
    @SerialName("author")
    val author: GitHubAuthor?,
    
    @SerialName("committer")
    val committer: GitHubAuthor?
)

/**
 * Автор коммита.
 */
@Serializable
data class GitHubAuthor(
    @SerialName("name")
    val name: String,
    
    @SerialName("email")
    val email: String,
    
    @SerialName("date")
    val date: String
)

// ═══════════════════════════════════════════════════════════════════════════════
// GITHUB ACTIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Список workflow runs.
 */
@Serializable
data class WorkflowRunsResponse(
    @SerialName("total_count")
    val totalCount: Int,
    
    @SerialName("workflow_runs")
    val workflowRuns: List<WorkflowRun>
)

/**
 * Информация о запуске workflow.
 */
@Serializable
data class WorkflowRun(
    @SerialName("id")
    val id: Long,
    
    @SerialName("name")
    val name: String?,
    
    @SerialName("head_branch")
    val headBranch: String,
    
    @SerialName("head_sha")
    val headSha: String,
    
    @SerialName("status")
    val status: String, // "queued", "in_progress", "completed"
    
    @SerialName("conclusion")
    val conclusion: String?, // "success", "failure", "cancelled", "skipped", etc.
    
    @SerialName("workflow_id")
    val workflowId: Long,
    
    @SerialName("html_url")
    val htmlUrl: String,
    
    @SerialName("created_at")
    val createdAt: String,
    
    @SerialName("updated_at")
    val updatedAt: String,
    
    @SerialName("run_started_at")
    val runStartedAt: String?
)

/**
 * Список workflows.
 */
@Serializable
data class WorkflowsResponse(
    @SerialName("total_count")
    val totalCount: Int,
    
    @SerialName("workflows")
    val workflows: List<Workflow>
)

/**
 * Информация о workflow.
 */
@Serializable
data class Workflow(
    @SerialName("id")
    val id: Long,
    
    @SerialName("name")
    val name: String,
    
    @SerialName("path")
    val path: String,
    
    @SerialName("state")
    val state: String, // "active", "deleted", "disabled_fork", etc.
    
    @SerialName("html_url")
    val htmlUrl: String
)

/**
 * Запрос на запуск workflow.
 */
@Serializable
data class WorkflowDispatchRequest(
    @SerialName("ref")
    val ref: String, // Ветка или тег
    
    @SerialName("inputs")
    val inputs: Map<String, String>? = null
)

/**
 * Jobs в workflow run.
 */
@Serializable
data class WorkflowJobsResponse(
    @SerialName("total_count")
    val totalCount: Int,
    
    @SerialName("jobs")
    val jobs: List<WorkflowJob>
)

/**
 * Job в workflow.
 */
@Serializable
data class WorkflowJob(
    @SerialName("id")
    val id: Long,
    
    @SerialName("name")
    val name: String,
    
    @SerialName("status")
    val status: String,
    
    @SerialName("conclusion")
    val conclusion: String?,
    
    @SerialName("started_at")
    val startedAt: String?,
    
    @SerialName("completed_at")
    val completedAt: String?,
    
    @SerialName("steps")
    val steps: List<WorkflowStep>?
)

/**
 * Шаг в job.
 */
@Serializable
data class WorkflowStep(
    @SerialName("name")
    val name: String,
    
    @SerialName("status")
    val status: String,
    
    @SerialName("conclusion")
    val conclusion: String?,
    
    @SerialName("number")
    val number: Int
)

// ═══════════════════════════════════════════════════════════════════════════════
// ARTIFACTS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Список артефактов.
 */
@Serializable
data class ArtifactsResponse(
    @SerialName("total_count")
    val totalCount: Int,
    
    @SerialName("artifacts")
    val artifacts: List<Artifact>
)

/**
 * Артефакт сборки.
 */
@Serializable
data class Artifact(
    @SerialName("id")
    val id: Long,
    
    @SerialName("name")
    val name: String,
    
    @SerialName("size_in_bytes")
    val sizeInBytes: Long,
    
    @SerialName("archive_download_url")
    val archiveDownloadUrl: String,
    
    @SerialName("expired")
    val expired: Boolean,
    
    @SerialName("created_at")
    val createdAt: String,
    
    @SerialName("expires_at")
    val expiresAt: String
)

// ═══════════════════════════════════════════════════════════════════════════════
// GRAPHQL
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * GraphQL запрос.
 */
@Serializable
data class GraphQLRequest(
    @SerialName("query")
    val query: String,
    
    @SerialName("variables")
    val variables: Map<String, String>? = null
)

/**
 * GraphQL ответ.
 */
@Serializable
data class GraphQLResponse<T>(
    @SerialName("data")
    val data: T?,
    
    @SerialName("errors")
    val errors: List<GraphQLError>? = null
)

/**
 * GraphQL ошибка.
 */
@Serializable
data class GraphQLError(
    @SerialName("message")
    val message: String,
    
    @SerialName("type")
    val type: String? = null,
    
    @SerialName("path")
    val path: List<String>? = null
)

// ═══════════════════════════════════════════════════════════════════════════════
// ERROR
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * GitHub API ошибка.
 */
@Serializable
data class GitHubError(
    @SerialName("message")
    val message: String,
    
    @SerialName("documentation_url")
    val documentationUrl: String? = null
)
