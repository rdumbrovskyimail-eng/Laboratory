package com.opuside.app.core.ai

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔧 TOOL EXECUTOR v3.5 (Gemini 3 OpenAPI Schema Compliant)
 *
 * Исправления и улучшения:
 * - Все типы схемы переведены в строгий верхний регистр (OBJECT, STRING, INTEGER, ARRAY)
 * - Двойная совместимость: объявлены и parameters, и input_schema
 * - Автоматическая очистка путей от ведущих / и ./
 * - Fallback на одиночные аргументы (path -> paths, file -> path)
 * - Привязка всех операций к активной ветке из AppSettings
 * - Полный фильтр бинарных файлов для защиты памяти
 */
@Singleton
class ToolExecutor @Inject constructor(
    private val repoIndexManager: RepoIndexManager,
    private val gitHubClient: GitHubApiClient,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "ToolExecutor"
        private const val MAX_FILES_PER_READ = 250
        private const val MAX_FILE_SIZE_BYTES = 1_500_000 // 1.5 MB
        private const val MAX_SEARCH_RESULTS = 250

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "ico", "bmp",
            "jar", "aar", "zip", "tar", "gz", "7z", "rar",
            "keystore", "jks", "so", "dylib", "dll",
            "ttf", "otf", "woff", "woff2",
            "pdf", "apk", "aab", "dex", "class",
            "mp3", "wav", "ogg", "mp4", "mkv", "avi"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL DEFINITIONS (Gemini OpenAPI Schema)
    // ═══════════════════════════════════════════════════════════════════════════

    val toolDefinitions: List<JsonObject> by lazy {
        listOf(
            buildToolDef(
                name = "list_files",
                description = "Show the file and folder structure of the repository. Returns tree view with paths, sizes, and file types.",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Root path to list from ('' or empty = entire project). Example: 'app/src/main'"))
                    })
                    put("max_depth", buildJsonObject {
                        put("type", JsonPrimitive("INTEGER"))
                        put("description", JsonPrimitive("Maximum nesting depth to show. Default: unlimited"))
                    })
                    put("extensions", buildJsonObject {
                        put("type", JsonPrimitive("ARRAY"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("STRING")) })
                        put("description", JsonPrimitive("Filter by file extensions, e.g. ['kt', 'xml']"))
                    })
                },
                required = emptyList()
            ),

            buildToolDef(
                name = "read_files",
                description = "Read the full content of one or more files from the repository. Always verify file paths using list_files first.",
                properties = buildJsonObject {
                    put("paths", buildJsonObject {
                        put("type", JsonPrimitive("ARRAY"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("STRING")) })
                        put("description", JsonPrimitive("Array of full file paths to read"))
                    })
                },
                required = listOf("paths")
            ),

            buildToolDef(
                name = "search_in_files",
                description = "Search for files by name pattern across the repository index (instant).",
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Search query matching against file names (case-insensitive)"))
                    })
                    put("extensions", buildJsonObject {
                        put("type", JsonPrimitive("ARRAY"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("STRING")) })
                        put("description", JsonPrimitive("Filter results by extensions, e.g. ['kt', 'java']"))
                    })
                },
                required = listOf("query")
            ),

            buildToolDef(
                name = "create_file",
                description = "Create a new file in the repository and commit it to GitHub. Include complete file content.",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Full path for the new file, e.g. 'app/src/main/java/com/example/MyClass.kt'"))
                    })
                    put("content", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Complete file content"))
                    })
                    put("commit_message", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Git commit message"))
                    })
                },
                required = listOf("path", "content")
            ),

            buildToolDef(
                name = "edit_file",
                description = "Replace the entire content of an existing file and commit the change. You must provide complete new file content.",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Full path of the file to edit"))
                    })
                    put("content", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Complete NEW file content"))
                    })
                    put("commit_message", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Git commit message"))
                    })
                },
                required = listOf("path", "content")
            ),

            buildToolDef(
                name = "delete_file",
                description = "Delete a file from the repository with a commit. This action is permanent.",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Full path of the file to delete"))
                    })
                    put("commit_message", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Git commit message"))
                    })
                },
                required = listOf("path")
            ),

            buildToolDef(
                name = "create_directory",
                description = "Create a new directory via a .gitkeep placeholder file.",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("STRING"))
                        put("description", JsonPrimitive("Full path for the new directory, e.g. 'app/src/main/assets'"))
                    })
                },
                required = listOf("path")
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
        val operation: FileOperation? = null
    )

    sealed class FileOperation {
        data class Created(val path: String) : FileOperation()
        data class Edited(val path: String) : FileOperation()
        data class Deleted(val path: String) : FileOperation()
        data class DirectoryCreated(val path: String) : FileOperation()
    }

    suspend fun execute(toolName: String, toolUseId: String, input: JsonObject): ToolResult {
        Log.i(TAG, "Executing tool: $toolName (id=$toolUseId)")

        return try {
            when (toolName) {
                "list_files" -> executeListFiles(toolUseId, input)
                "read_files" -> executeReadFiles(toolUseId, input)
                "search_in_files" -> executeSearchFiles(toolUseId, input)
                "create_file" -> executeCreateFile(toolUseId, input)
                "edit_file" -> executeEditFile(toolUseId, input)
                "delete_file" -> executeDeleteFile(toolUseId, input)
                "create_directory" -> executeCreateDirectory(toolUseId, input)
                else -> ToolResult(toolUseId, "Unknown tool: $toolName", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool failed: $toolName", e)
            ToolResult(toolUseId, "Error: ${e.message}", isError = true)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun executeListFiles(id: String, input: JsonObject): ToolResult {
        val rawPath = input["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val cleanPath = sanitizePath(rawPath)
        val maxDepth = input["max_depth"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val extensions = input["extensions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }

        val tree = repoIndexManager.getTreeText(path = cleanPath, maxDepth = maxDepth, extensions = extensions)
        return ToolResult(id, tree)
    }

    private suspend fun executeReadFiles(id: String, input: JsonObject): ToolResult {
        val arrayPaths = input["paths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val singlePath = input["path"]?.jsonPrimitive?.contentOrNull
        val rawPaths = if (arrayPaths.isNotEmpty()) arrayPaths else listOfNotNull(singlePath)

        if (rawPaths.isEmpty()) {
            return ToolResult(id, "Error: 'paths' array is empty", isError = true)
        }
        if (rawPaths.size > MAX_FILES_PER_READ) {
            return ToolResult(id, "Error: Too many files (${rawPaths.size} > $MAX_FILES_PER_READ)", isError = true)
        }

        val branch = getCurrentBranch()

        val result = buildString {
            var loadedCount = 0

            for (raw in rawPaths) {
                val path = sanitizePath(raw)
                if (path.contains("..")) {
                    appendLine("### File: `$path` — ERROR: Path traversal not allowed")
                    appendLine()
                    continue
                }

                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext in BINARY_EXTENSIONS) {
                    appendLine("### File: `$path` — SKIPPED: Binary file format (images, archives, bytecode)")
                    appendLine()
                    continue
                }

                try {
                    val content = gitHubClient.getFileContentDecoded(path, branch).getOrNull()
                    if (content != null) {
                        if (content.length > MAX_FILE_SIZE_BYTES) {
                            appendLine("### File: `$path` [TRUNCATED — ${content.length / 1024}KB]")
                            appendLine("```")
                            appendLine(content.take(MAX_FILE_SIZE_BYTES))
                            appendLine("... (truncated)")
                            appendLine("```")
                        } else {
                            appendLine("### File: `$path`")
                            appendLine("```")
                            appendLine(content)
                            appendLine("```")
                        }
                        loadedCount++
                    } else {
                        appendLine("### File: `$path` — NOT FOUND in branch '$branch'")
                    }
                } catch (e: Exception) {
                    appendLine("### File: `$path` — ERROR: ${e.message}")
                }
                appendLine()
            }
            if (loadedCount == 0) appendLine("No files could be loaded.")
        }

        return ToolResult(id, result)
    }

    private suspend fun executeSearchFiles(id: String, input: JsonObject): ToolResult {
        val query = input["query"]?.jsonPrimitive?.contentOrNull
            ?: input["search"]?.jsonPrimitive?.contentOrNull ?: ""
        val extensions = input["extensions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }

        if (query.isBlank()) {
            return ToolResult(id, "Error: 'query' cannot be empty", isError = true)
        }

        var results = repoIndexManager.searchByName(query)
        if (!extensions.isNullOrEmpty()) {
            val extSet = extensions.map { it.lowercase().removePrefix(".") }.toSet()
            results = results.filter { it.extension.lowercase() in extSet }
        }

        val limited = results.take(MAX_SEARCH_RESULTS)
        val text = buildString {
            appendLine("Found ${results.size} matches for '$query':")
            for (node in limited) {
                if (node.isFile) appendLine("  📄 ${node.path} (${node.sizeFormatted})")
                else appendLine("  📁 ${node.path}/")
            }
            if (results.size > MAX_SEARCH_RESULTS) appendLine("... (showing first $MAX_SEARCH_RESULTS)")
        }

        return ToolResult(id, text)
    }

    private suspend fun executeCreateFile(id: String, input: JsonObject): ToolResult {
        val rawPath = input["path"]?.jsonPrimitive?.contentOrNull
            ?: input["file"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(id, "Error: 'path' required", isError = true)
        val path = sanitizePath(rawPath)
        val content = input["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(id, "Error: 'content' required", isError = true)
        val commitMsg = input["commit_message"]?.jsonPrimitive?.contentOrNull
            ?: input["message"]?.jsonPrimitive?.contentOrNull
            ?: "Create $path via Gemini"

        if (path.contains("..")) return ToolResult(id, "Error: path traversal not allowed", isError = true)

        return createOrUpdateWithRetry(id, path, content, commitMsg, FileOperation.Created(path))
    }

    private suspend fun executeEditFile(id: String, input: JsonObject): ToolResult {
        val rawPath = input["path"]?.jsonPrimitive?.contentOrNull
            ?: input["file"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(id, "Error: 'path' required", isError = true)
        val path = sanitizePath(rawPath)
        val content = input["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(id, "Error: 'content' required", isError = true)
        val commitMsg = input["commit_message"]?.jsonPrimitive?.contentOrNull
            ?: input["message"]?.jsonPrimitive?.contentOrNull
            ?: "Edit $path via Gemini"

        if (path.contains("..")) return ToolResult(id, "Error: path traversal not allowed", isError = true)

        return createOrUpdateWithRetry(id, path, content, commitMsg, FileOperation.Edited(path))
    }

    private suspend fun executeDeleteFile(id: String, input: JsonObject): ToolResult {
        val rawPath = input["path"]?.jsonPrimitive?.contentOrNull
            ?: input["file"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(id, "Error: 'path' required", isError = true)
        val path = sanitizePath(rawPath)
        val commitMsg = input["commit_message"]?.jsonPrimitive?.contentOrNull
            ?: input["message"]?.jsonPrimitive?.contentOrNull
            ?: "Delete $path via Gemini"

        if (path.contains("..")) return ToolResult(id, "Error: path traversal not allowed", isError = true)

        val branch = getCurrentBranch()

        return try {
            val currentFile = gitHubClient.getFileContent(path, branch).getOrThrow()
            gitHubClient.deleteFile(path = path, message = commitMsg, sha = currentFile.sha, branch = branch).getOrThrow()
            repoIndexManager.invalidate()
            ToolResult(id, "✅ Deleted: `$path`", operation = FileOperation.Deleted(path))
        } catch (e: Exception) {
            ToolResult(id, "❌ Failed to delete `$path`: ${e.message}", isError = true)
        }
    }

    private suspend fun executeCreateDirectory(id: String, input: JsonObject): ToolResult {
        val rawPath = input["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(id, "Error: 'path' required", isError = true)
        val path = sanitizePath(rawPath)

        if (path.contains("..")) return ToolResult(id, "Error: path traversal not allowed", isError = true)

        val branch = getCurrentBranch()
        val keepPath = "$path/.gitkeep"

        return try {
            gitHubClient.createOrUpdateFile(
                path = keepPath,
                content = "",
                message = "Create directory $path via Gemini",
                branch = branch
            ).getOrThrow()
            repoIndexManager.invalidate()
            ToolResult(id, "✅ Directory created: `$path/`", operation = FileOperation.DirectoryCreated(path))
        } catch (e: Exception) {
            ToolResult(id, "❌ Failed to create directory `$path`: ${e.message}", isError = true)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun createOrUpdateWithRetry(
        id: String,
        path: String,
        content: String,
        commitMsg: String,
        operation: FileOperation
    ): ToolResult {
        val branch = getCurrentBranch()

        for (attempt in 1..3) {
            try {
                val existingSha = try {
                    gitHubClient.getFileContent(path, branch).getOrNull()?.sha
                } catch (_: Exception) { null }

                val result = gitHubClient.createOrUpdateFile(
                    path = path,
                    content = content,
                    message = commitMsg,
                    sha = existingSha,
                    branch = branch
                ).getOrThrow()

                repoIndexManager.invalidate()
                val action = if (existingSha != null) "Updated" else "Created"
                return ToolResult(
                    id,
                    "✅ $action: `$path` (sha: ${result.content.sha.take(8)})",
                    operation = operation
                )
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val isConflict = msg.contains("sha", ignoreCase = true) || msg.contains("422") || msg.contains("409")
                if (isConflict && attempt < 3) {
                    Log.w(TAG, "SHA conflict on $path, retrying ($attempt/3)...")
                    delay(1200L * attempt)
                    continue
                }
                return ToolResult(id, "❌ Failed: `$path`: ${e.message}", isError = true)
            }
        }
        return ToolResult(id, "❌ Failed after 3 retries: `$path`", isError = true)
    }

    private suspend fun getCurrentBranch(): String = try {
        appSettings.gitHubConfig.first().branch.ifBlank { "main" }
    } catch (_: Exception) {
        "main"
    }

    private fun sanitizePath(path: String): String =
        path.trim().removePrefix("/").removePrefix("./").trimEnd('/')

    private fun buildToolDef(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String>
    ): JsonObject {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("OBJECT"))
            put("properties", properties)
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }
        return buildJsonObject {
            put("name", JsonPrimitive(name))
            put("description", JsonPrimitive(description))
            // Добавляем оба ключа для 100% совместимости
            put("parameters", schema)
            put("input_schema", schema)
        }
    }
}