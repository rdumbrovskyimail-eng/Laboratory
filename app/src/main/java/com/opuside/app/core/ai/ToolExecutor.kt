package com.opuside.app.core.ai

import android.util.Log
import com.opuside.app.core.network.github.GitHubApiClient
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔧 TOOL EXECUTOR v3.0 (LONG CONTEXT OPTIMIZED)
 *
 * ✅ v3.0 CHANGES:
 * - MAX_FILES_PER_READ: 10 → 250 (для работы с огромными кодовыми базами)
 * - MAX_FILE_SIZE_BYTES: 150KB → 1.5MB (позволяет читать очень крупные файлы)
 * - MAX_SEARCH_RESULTS: 30 → 250 (пропорционально увеличено)
 * - lazy toolDefinitions — не пересоздаются на каждый вызов
 * - Path validation (no traversal)
 * - Bounded results
 */
@Singleton
class ToolExecutor @Inject constructor(
    private val repoIndexManager: RepoIndexManager,
    private val gitHubClient: GitHubApiClient
) {
    companion object {
        private const val TAG = "ToolExecutor"
        private const val MAX_FILES_PER_READ = 250           // было 10
        private const val MAX_FILE_SIZE_BYTES = 1_500_000    // было 150_000 (1.5MB)
        private const val MAX_SEARCH_RESULTS = 250           // было 30
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL DEFINITIONS — lazy, allocated ONCE
    // ═══════════════════════════════════════════════════════════════════════════

    val toolDefinitions: List<JsonObject> by lazy {
        listOf(
            buildToolDef(
                name = "list_files",
                description = """Show the file and folder structure of the repository.
Use when user asks about project structure, wants to see files, or you need to understand the architecture.
Returns a tree view with paths, sizes, and file types.
This is INSTANT — data comes from a local index, no API calls needed.
You can filter by path (subdirectory) and file extensions.""",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Root path to list from ('' = entire project). Example: 'app/src/main'"))
                    })
                    put("max_depth", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put("description", JsonPrimitive("Maximum nesting depth to show. Default: unlimited"))
                    })
                    put("extensions", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("description", JsonPrimitive("Filter by file extensions, e.g. ['kt', 'xml']"))
                    })
                },
                required = emptyList()
            ),

            buildToolDef(
                name = "read_files",
                description = """Read the full content of one or more files from the repository.
Use when you need to see the actual code, analyze a file, find bugs, or understand implementation.
Maximum $MAX_FILES_PER_READ files per call, max ${MAX_FILE_SIZE_BYTES / 1024}KB per file.
IMPORTANT: Always use list_files first to verify file paths exist before reading.""",
                properties = buildJsonObject {
                    put("paths", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("description", JsonPrimitive("Array of full file paths to read"))
                    })
                },
                required = listOf("paths")
            ),

            buildToolDef(
                name = "search_in_files",
                description = """Search for files by name pattern across the repository.
Use when you need to find where a class, function, or variable is declared.
Searches file NAMES in the local index (instant).""",
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Search query — matches against file names (case-insensitive)"))
                    })
                    put("extensions", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("description", JsonPrimitive("Filter results by extensions, e.g. ['kt', 'java']"))
                    })
                },
                required = listOf("query")
            ),

            buildToolDef(
                name = "create_file",
                description = """Create a new file in the repository and commit it to GitHub.
ALWAYS include complete file content with proper package declarations and imports.""",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Full path for the new file"))
                    })
                    put("content", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Complete file content"))
                    })
                    put("commit_message", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Git commit message"))
                    })
                },
                required = listOf("path", "content", "commit_message")
            ),

            buildToolDef(
                name = "edit_file",
                description = """Replace the entire content of an existing file and commit the change.
You MUST provide the COMPLETE new file content. Always read_files first.""",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Full path of the file to edit"))
                    })
                    put("content", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Complete NEW file content"))
                    })
                    put("commit_message", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Git commit message"))
                    })
                },
                required = listOf("path", "content", "commit_message")
            ),

            buildToolDef(
                name = "delete_file",
                description = """Delete a file from the repository. This action is irreversible.""",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Full path of the file to delete"))
                    })
                    put("commit_message", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Git commit message"))
                    })
                },
                required = listOf("path", "commit_message")
            ),

            buildToolDef(
                name = "create_directory",
                description = """Create a new directory via .gitkeep placeholder.""",
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Full path for the new directory"))
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
        Log.i(TAG, "Executing: $toolName (id=$toolUseId)")

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
    // IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun executeListFiles(id: String, input: JsonObject): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val maxDepth = input["max_depth"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val extensions = input["extensions"]?.jsonArray?.map { it.jsonPrimitive.content }

        val tree = repoIndexManager.getTreeText(path = path, maxDepth = maxDepth, extensions = extensions)
        return ToolResult(id, tree)
    }

    private suspend fun executeReadFiles(id: String, input: JsonObject): ToolResult {
        val paths = input["paths"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        if (paths.isEmpty()) return ToolResult(id, "Error: 'paths' array is empty", isError = true)
        if (paths.size > MAX_FILES_PER_READ) return ToolResult(id, "Error: Too many files (${paths.size} > $MAX_FILES_PER_READ)", isError = true)

        // Validate paths
        for (p in paths) {
            if (p.contains("..")) return ToolResult(id, "Error: path traversal not allowed: $p", isError = true)
        }

        val result = buildString {
            var loadedCount = 0
            for (path in paths) {
                try {
                    val content = gitHubClient.getFileContentDecoded(path).getOrNull()
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
                        appendLine("### File: `$path` — NOT FOUND")
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
        val query = input["query"]?.jsonPrimitive?.contentOrNull ?: ""
        val extensions = input["extensions"]?.jsonArray?.map { it.jsonPrimitive.content }

        if (query.isBlank()) return ToolResult(id, "Error: 'query' cannot be empty", isError = true)

        var results = repoIndexManager.searchByName(query)
        if (extensions != null) {
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
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(id, "Error: 'path' required", isError = true)
        val content = input["content"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(id, "Error: 'content' required", isError = true)
        val commitMsg = input["commit_message"]?.jsonPrimitive?.contentOrNull ?: "Create $path via Claude"

        if (path.contains("..")) return ToolResult(id, "Error: path traversal not allowed", isError = true)

        return try {
            val result = gitHubClient.createOrUpdateFile(path = path, content = content, message = commitMsg).getOrThrow()
            repoIndexManager.invalidate()
            ToolResult(id, "✅ Created: `$path` (sha: ${result.content.sha.take(8)})", operation = FileOperation.Created(path))
        } catch (e: Exception) {
            ToolResult(id, "❌ Failed to create `$path`: ${e.message}", isError = true)
        }
    }

    private suspend fun executeEditFile(id: String, input: JsonObject): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(id, "Error: 'path' required", isError = true)
        val content = input["content"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(id, "Error: 'content' required", isError = true)
        val commitMsg = input["commit_message"]?.jsonPrimitive?.contentOrNull ?: "Edit $path via Claude"

        if (path.contains("..")) return ToolResult(id, "Error: path traversal not allowed", isError = true)

        return try {
            val currentFile = gitHubClient.getFileContent(path).getOrThrow()
            val result = gitHubClient.createOrUpdateFile(path = path, content = content, message = commitMsg, sha = currentFile.sha).getOrThrow()
            repoIndexManager.invalidate()
            ToolResult(id, "✅ Edited: `$path` (sha: ${result.content.sha.take(8)})", operation = FileOperation.Edited(path))
        } catch (e: Exception) {
            ToolResult(id, "❌ Failed to edit `$path`: ${e.message}", isError = true)
        }
    }

    private suspend fun executeDeleteFile(id: String, input: JsonObject): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(id, "Error: 'path' required", isError = true)
        val commitMsg = input["commit_message"]?.jsonPrimitive?.contentOrNull ?: "Delete $path via Claude"

        if (path.contains("..")) return ToolResult(id, "Error: path traversal not allowed", isError = true)

        return try {
            val currentFile = gitHubClient.getFileContent(path).getOrThrow()
            gitHubClient.deleteFile(path = path, message = commitMsg, sha = currentFile.sha).getOrThrow()
            repoIndexManager.invalidate()
            ToolResult(id, "✅ Deleted: `$path`", operation = FileOperation.Deleted(path))
        } catch (e: Exception) {
            ToolResult(id, "❌ Failed to delete `$path`: ${e.message}", isError = true)
        }
    }

    private suspend fun executeCreateDirectory(id: String, input: JsonObject): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(id, "Error: 'path' required", isError = true)

        if (path.contains("..")) return ToolResult(id, "Error: path traversal not allowed", isError = true)

        return try {
            gitHubClient.createOrUpdateFile(path = "$path/.gitkeep", content = "", message = "Create directory $path via Claude").getOrThrow()
            repoIndexManager.invalidate()
            ToolResult(id, "✅ Directory: `$path/`", operation = FileOperation.DirectoryCreated(path))
        } catch (e: Exception) {
            ToolResult(id, "❌ Failed to create directory `$path`: ${e.message}", isError = true)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildToolDef(name: String, description: String, properties: JsonObject, required: List<String>): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("description", JsonPrimitive(description))
        put("input_schema", buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", properties)
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        })
    }
}