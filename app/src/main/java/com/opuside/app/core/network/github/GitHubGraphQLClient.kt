package com.opuside.app.core.network.github

import com.opuside.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * GitHub GraphQL клиент для batch-операций.
 * 
 * Позволяет загружать до 20 файлов одним запросом,
 * что значительно быстрее REST API.
 * 
 * ✅ ИСПРАВЛЕНО:
 * - Проблема №4: Добавлена валидация BuildConfig полей
 * - Проблема №10: Добавлена проверка размера файлов и разбиение на batches
 * - Проблема №18 (BUG #18): Добавлен escaping для GraphQL query
 */
@Singleton
class GitHubGraphQLClient @Inject constructor(
    @Named("github") private val httpClient: HttpClient,
    private val json: Json,
    private val gitHubClient: GitHubApiClient // ✅ ДОБАВЛЕНО: Для получения размеров файлов
) {
    companion object {
        private const val GRAPHQL_URL = "https://api.github.com/graphql"
        private const val MAX_FILES_PER_REQUEST = 20
        private const val MAX_BATCH_SIZE_BYTES = 500_000 // ✅ ДОБАВЛЕНО: 500KB на batch
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
    // BATCH FILE LOADING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Загрузить несколько файлов одним запросом.
     * 
     * ✅ ИСПРАВЛЕНО: Проблема №10 - Разбиение на batches по размеру
     * 
     * @param paths Список путей к файлам (макс 20)
     * @param ref Ветка или коммит (опционально)
     * @return Map<путь, содержимое>
     */
    suspend fun getMultipleFiles(
        paths: List<String>,
        ref: String = "HEAD"
    ): Result<Map<String, FileContent>> {
        if (paths.isEmpty()) return Result.success(emptyMap())

        // ✅ ДОБАВЛЕНО: Проблема №10 - Получаем размеры файлов через REST API
        val filesWithSize = mutableListOf<Pair<String, Int>>()
        for (path in paths) {
            val result = gitHubClient.getFileContent(path, ref)
            result.onSuccess { content ->
                filesWithSize.add(path to (content.size ?: 0))
            }.onFailure {
                // Если не удалось получить размер, считаем файл небольшим
                filesWithSize.add(path to 1000)
            }
        }

        // ✅ ДОБАВЛЕНО: Проблема №10 - Делим на batches по размеру и количеству
        val batches = mutableListOf<List<String>>()
        var currentBatch = mutableListOf<String>()
        var currentSize = 0

        filesWithSize.forEach { (path, size) ->
            if (currentSize + size > MAX_BATCH_SIZE_BYTES || currentBatch.size >= MAX_FILES_PER_REQUEST) {
                if (currentBatch.isNotEmpty()) {
                    batches.add(currentBatch)
                    currentBatch = mutableListOf()
                    currentSize = 0
                }
            }
            currentBatch.add(path)
            currentSize += size
        }

        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }

        // ✅ ДОБАВЛЕНО: Проблема №10 - Загружаем batches последовательно
        val allFiles = mutableMapOf<String, FileContent>()
        for (batch in batches) {
            val result = loadBatch(batch, ref)
            result.onSuccess { files ->
                allFiles.putAll(files)
            }.onFailure { error ->
                // Если хотя бы один batch провалился, возвращаем ошибку
                return Result.failure(error)
            }
        }

        return Result.success(allFiles)
    }

    /**
     * ✅ ДОБАВЛЕНО: Проблема №10 - Загрузка одного batch
     */
    private suspend fun loadBatch(
        paths: List<String>,
        ref: String
    ): Result<Map<String, FileContent>> {
        if (paths.isEmpty()) return Result.success(emptyMap())
        if (paths.size > MAX_FILES_PER_REQUEST) {
            return Result.failure(IllegalArgumentException("Max $MAX_FILES_PER_REQUEST files per request"))
        }

        // Строим GraphQL запрос с алиасами для каждого файла
        val query = buildBatchQuery(paths, ref)

        return try {
            val response = httpClient.post(GRAPHQL_URL) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(GraphQLRequest(query))
            }

            if (!response.status.isSuccess()) {
                return Result.failure(GitHubApiException(
                    type = "graphql_error",
                    message = "HTTP ${response.status.value}",
                    statusCode = response.status.value
                ))
            }

            val responseBody = response.body<JsonObject>()
            
            // Проверяем ошибки GraphQL
            responseBody["errors"]?.let { errors ->
                val errorMessages = errors.jsonArray.mapNotNull { 
                    it.jsonObject["message"]?.jsonPrimitive?.content 
                }
                if (errorMessages.isNotEmpty()) {
                    return Result.failure(GitHubApiException(
                        type = "graphql_error",
                        message = errorMessages.joinToString("; ")
                    ))
                }
            }

            // Парсим результаты
            val data = responseBody["data"]?.jsonObject
                ?: return Result.failure(GitHubApiException("no_data", "No data in response"))
            
            val repoData = data["repository"]?.jsonObject
                ?: return Result.failure(GitHubApiException("no_repo", "Repository not found"))

            val results = mutableMapOf<String, FileContent>()
            
            paths.forEachIndexed { index, path ->
                val alias = "file$index"
                val fileData = repoData[alias]?.jsonObject
                
                if (fileData != null && !fileData.containsKey("null")) {
                    val text = fileData["text"]?.jsonPrimitive?.contentOrNull
                    val isBinary = fileData["isBinary"]?.jsonPrimitive?.booleanOrNull ?: false
                    val byteSize = fileData["byteSize"]?.jsonPrimitive?.intOrNull ?: 0
                    val oid = fileData["oid"]?.jsonPrimitive?.contentOrNull
                    
                    results[path] = FileContent(
                        path = path,
                        content = text ?: "", // ✅ ИСПРАВЛЕНО: Пустая строка для binary
                        isBinary = isBinary,
                        byteSize = byteSize,
                        oid = oid
                    )
                }
            }

            Result.success(results)

        } catch (e: Exception) {
            Result.failure(GitHubApiException("network_error", e.message ?: "Unknown error"))
        }
    }

    /**
     * Загрузить дерево директории.
     */
    suspend fun getDirectoryTree(
        path: String = "",
        ref: String = "HEAD",
        recursive: Boolean = false
    ): Result<List<TreeEntry>> {
        val expression = if (path.isEmpty()) "$ref:" else "$ref:$path"
        
        val query = """
            query {
                repository(owner: "$owner", name: "$repo") {
                    object(expression: "$expression") {
                        ... on Tree {
                            entries {
                                name
                                path
                                type
                                mode
                                oid
                                object {
                                    ... on Blob {
                                        byteSize
                                        isBinary
                                    }
                                    ${if (recursive) """
                                    ... on Tree {
                                        entries {
                                            name
                                            path
                                            type
                                            oid
                                        }
                                    }
                                    """ else ""}
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = httpClient.post(GRAPHQL_URL) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(GraphQLRequest(query))
            }

            if (!response.status.isSuccess()) {
                return Result.failure(GitHubApiException(
                    "graphql_error",
                    "HTTP ${response.status.value}",
                    response.status.value
                ))
            }

            val responseBody = response.body<JsonObject>()
            
            val entries = responseBody["data"]
                ?.jsonObject?.get("repository")
                ?.jsonObject?.get("object")
                ?.jsonObject?.get("entries")
                ?.jsonArray
                ?: return Result.success(emptyList())

            val result = entries.map { entry ->
                val obj = entry.jsonObject
                val innerObj = obj["object"]?.jsonObject
                
                TreeEntry(
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    path = obj["path"]?.jsonPrimitive?.content ?: "",
                    type = obj["type"]?.jsonPrimitive?.content ?: "",
                    mode = obj["mode"]?.jsonPrimitive?.intOrNull ?: 0,
                    oid = obj["oid"]?.jsonPrimitive?.content,
                    byteSize = innerObj?.get("byteSize")?.jsonPrimitive?.intOrNull,
                    isBinary = innerObj?.get("isBinary")?.jsonPrimitive?.booleanOrNull
                )
            }

            Result.success(result)

        } catch (e: Exception) {
            Result.failure(GitHubApiException("network_error", e.message ?: "Unknown error"))
        }
    }

    /**
     * Получить информацию о последнем коммите.
     */
    suspend fun getLastCommit(ref: String = "HEAD"): Result<CommitInfo> {
        val query = """
            query {
                repository(owner: "$owner", name: "$repo") {
                    object(expression: "$ref") {
                        ... on Commit {
                            oid
                            messageHeadline
                            message
                            committedDate
                            author {
                                name
                                email
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = httpClient.post(GRAPHQL_URL) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(GraphQLRequest(query))
            }

            val responseBody = response.body<JsonObject>()
            val commit = responseBody["data"]
                ?.jsonObject?.get("repository")
                ?.jsonObject?.get("object")
                ?.jsonObject
                ?: return Result.failure(GitHubApiException("no_commit", "Commit not found"))

            val author = commit["author"]?.jsonObject

            Result.success(CommitInfo(
                oid = commit["oid"]?.jsonPrimitive?.content ?: "",
                messageHeadline = commit["messageHeadline"]?.jsonPrimitive?.content ?: "",
                message = commit["message"]?.jsonPrimitive?.content ?: "",
                committedDate = commit["committedDate"]?.jsonPrimitive?.content ?: "",
                authorName = author?.get("name")?.jsonPrimitive?.content,
                authorEmail = author?.get("email")?.jsonPrimitive?.content
            ))

        } catch (e: Exception) {
            Result.failure(GitHubApiException("network_error", e.message ?: "Unknown error"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО: Проблема №18 (BUG #18) - Добавлен escaping для путей с кавычками.
     */
    private fun buildBatchQuery(paths: List<String>, ref: String): String {
        val fileQueries = paths.mapIndexed { index, path ->
            // ✅ ДОБАВЛЕНО: Escaping для кавычек и обратных слэшей
            val escapedPath = path.replace("\\", "\\\\").replace("\"", "\\\"")
            """
            file$index: object(expression: "$ref:$escapedPath") {
                ... on Blob {
                    text
                    byteSize
                    isBinary
                    oid
                }
            }
            """.trimIndent()
        }.joinToString("\n")

        return """
            query {
                repository(owner: "$owner", name: "$repo") {
                    $fileQueries
                }
            }
        """.trimIndent()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════════════

@Serializable
private data class GraphQLRequest(
    @SerialName("query") val query: String
)

data class FileContent(
    val path: String,
    val content: String?,
    val isBinary: Boolean,
    val byteSize: Int,
    val oid: String?
)

data class TreeEntry(
    val name: String,
    val path: String,
    val type: String, // "blob" или "tree"
    val mode: Int,
    val oid: String?,
    val byteSize: Int? = null,
    val isBinary: Boolean? = null
) {
    val isDirectory: Boolean get() = type == "tree"
    val isFile: Boolean get() = type == "blob"
}

data class CommitInfo(
    val oid: String,
    val messageHeadline: String,
    val message: String,
    val committedDate: String,
    val authorName: String?,
    val authorEmail: String?
)