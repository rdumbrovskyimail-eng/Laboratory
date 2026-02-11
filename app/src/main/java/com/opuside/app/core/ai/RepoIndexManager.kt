package com.opuside.app.core.ai

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🗂️ REPO INDEX MANAGER v2.0 (FIXED)
 *
 * Загружает ПОЛНОЕ дерево репозитория за 1 API запрос через GitHub Git Trees API.
 * Дерево хранится В ПАМЯТИ — все операции навигации и поиска ЛОКАЛЬНЫЕ.
 *
 * ✅ v2.0 FIXES:
 * - finally {} для _isLoading (no state leak on CancellationException)
 * - Atomic _indexedRepoKey вместо 3 отдельных var (no race condition)
 * - Fixed toTreeText indent (relative depth, not absolute)
 * - FileNode.size: Long (no overflow for >2GB files)
 * - getFilesInPath simplified
 */
@Singleton
class RepoIndexManager @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "RepoIndexManager"
        private const val MAX_TREE_TEXT_LINES = 2000
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE — all thread-safe via StateFlow + Mutex
    // ═══════════════════════════════════════════════════════════════════════════

    private val _index = MutableStateFlow<RepoIndex?>(null)
    val index: StateFlow<RepoIndex?> = _index.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val refreshMutex = Mutex()

    /** Atomic: какой репозиторий сейчас проиндексирован */
    private data class RepoKey(val owner: String, val repo: String, val branch: String)
    private val _indexedRepoKey = MutableStateFlow(RepoKey("", "", ""))

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    data class FileNode(
        val path: String,
        val name: String,
        val type: NodeType,
        val size: Long,            // Long: no overflow for large files
        val sha: String,
        val extension: String,
        val depth: Int
    ) {
        val isFile: Boolean get() = type == NodeType.BLOB
        val isDirectory: Boolean get() = type == NodeType.TREE
        val parentPath: String get() = path.substringBeforeLast('/', "")

        val sizeFormatted: String get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
        }
    }

    enum class NodeType { BLOB, TREE }

    data class RepoIndex(
        val owner: String,
        val repo: String,
        val branch: String,
        val nodes: List<FileNode>,
        val treeSha: String,
        val truncated: Boolean,
        val loadedAt: Long = System.currentTimeMillis()
    ) {
        private val byPath: Map<String, FileNode> by lazy { nodes.associateBy { it.path } }
        private val byExtension: Map<String, List<FileNode>> by lazy {
            nodes.filter { it.isFile }.groupBy { it.extension.lowercase() }
        }
        private val byParent: Map<String, List<FileNode>> by lazy {
            nodes.groupBy { it.parentPath }
        }

        val totalFiles: Int by lazy { nodes.count { it.isFile } }
        val totalDirectories: Int by lazy { nodes.count { it.isDirectory } }
        val totalSize: Long by lazy { nodes.filter { it.isFile }.sumOf { it.size } }
        val maxDepth: Int by lazy { nodes.maxOfOrNull { it.depth } ?: 0 }

        val totalSizeFormatted: String get() = when {
            totalSize < 1024 -> "$totalSize B"
            totalSize < 1024 * 1024 -> "${totalSize / 1024} KB"
            else -> "${"%.1f".format(totalSize / (1024.0 * 1024.0))} MB"
        }

        fun getNode(path: String): FileNode? = byPath[path]

        fun getFilesByExtension(ext: String): List<FileNode> =
            byExtension[ext.lowercase().removePrefix(".")] ?: emptyList()

        fun getChildren(parentPath: String): List<FileNode> =
            byParent[parentPath] ?: emptyList()

        fun getDirectChildren(parentPath: String): List<FileNode> =
            getChildren(parentPath).filter { node ->
                val relative = if (parentPath.isEmpty()) node.path else node.path.removePrefix("$parentPath/")
                !relative.contains('/')
            }

        fun findByName(query: String): List<FileNode> {
            val q = query.lowercase()
            return nodes.filter { it.name.lowercase().contains(q) }
        }

        fun findByExtensions(extensions: List<String>): List<FileNode> {
            val exts = extensions.map { it.lowercase().removePrefix(".") }.toSet()
            return nodes.filter { it.isFile && it.extension.lowercase() in exts }
        }

        fun getFilesInPath(path: String): List<FileNode> {
            if (path.isEmpty()) return nodes.filter { it.isFile }
            val prefix = "$path/"
            return nodes.filter { it.isFile && it.path.startsWith(prefix) }
        }

        /**
         * Текстовое дерево для Claude.
         * ✅ FIXED: indent использует relative depth, не absolute.
         */
        fun toTreeText(
            rootPath: String = "",
            maxDepth: Int = Int.MAX_VALUE,
            maxLines: Int = MAX_TREE_TEXT_LINES,
            extensions: List<String>? = null
        ): String = buildString {
            var lineCount = 0
            val prefix = if (rootPath.isEmpty()) "" else "$rootPath/"
            val baseDepth = if (rootPath.isEmpty()) 0 else rootPath.count { it == '/' } + 1
            val extFilter = extensions?.map { it.lowercase().removePrefix(".") }?.toSet()

            val relevantNodes = nodes
                .filter { node ->
                    val matchesPath = rootPath.isEmpty() || node.path.startsWith(prefix) || node.path == rootPath
                    val matchesExt = extFilter == null || node.isDirectory || node.extension.lowercase() in extFilter
                    matchesPath && matchesExt
                }
                .filter { node ->
                    val relativeDepth = node.depth - baseDepth
                    relativeDepth in 0..maxDepth
                }
                .sortedBy { it.path }

            for (node in relevantNodes) {
                if (lineCount >= maxLines) {
                    appendLine("... (ещё ${relevantNodes.size - lineCount} элементов)")
                    break
                }

                val relativeDepth = (node.depth - baseDepth).coerceAtLeast(0)
                val indent = "  ".repeat(relativeDepth)
                if (node.isDirectory) {
                    appendLine("${indent}📁 ${node.name}/")
                } else {
                    appendLine("${indent}📄 ${node.name} (${node.sizeFormatted})")
                }
                lineCount++
            }

            if (lineCount == 0) {
                appendLine("(пусто)")
            }
        }

        fun toSummary(): String = buildString {
            append("$owner/$repo ($branch) — ")
            append("$totalFiles files, $totalDirectories dirs, $totalSizeFormatted")
            if (truncated) append(" [TRUNCATED]")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Загрузить/обновить индекс.
     * ✅ FIXED: finally {} гарантирует сброс _isLoading даже при CancellationException.
     */
    suspend fun refresh(): Result<RepoIndex> = refreshMutex.withLock {
        _isLoading.value = true
        _lastError.value = null

        try {
            val config = appSettings.gitHubConfig.first()
            if (config.owner.isBlank() || config.repo.isBlank()) {
                _lastError.value = "GitHub repository not configured"
                return@withLock Result.failure(IllegalStateException(_lastError.value!!))
            }

            Log.i(TAG, "Refreshing: ${config.owner}/${config.repo} (${config.branch})")

            val tree = gitHubClient.getFullTree(config.branch).getOrThrow()

            val nodes = tree.tree.map { item ->
                val path = item.path
                val name = path.substringAfterLast('/')
                val depth = path.count { it == '/' }
                FileNode(
                    path = path,
                    name = name,
                    type = if (item.type == "blob") NodeType.BLOB else NodeType.TREE,
                    size = (item.size ?: 0).toLong(),
                    sha = item.sha,
                    extension = if (item.type == "blob") name.substringAfterLast('.', "") else "",
                    depth = depth
                )
            }

            val index = RepoIndex(
                owner = config.owner,
                repo = config.repo,
                branch = config.branch,
                nodes = nodes,
                treeSha = tree.sha,
                truncated = tree.truncated
            )

            _index.value = index
            _indexedRepoKey.value = RepoKey(config.owner, config.repo, config.branch)

            Log.i(TAG, "Indexed: ${index.totalFiles} files, ${index.totalDirectories} dirs, " +
                    "${index.totalSizeFormatted}, depth=${index.maxDepth}")

            Result.success(index)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
            _lastError.value = e.message
            Result.failure(e)
        } finally {
            _isLoading.value = false  // ✅ GUARANTEED — even on CancellationException
        }
    }

    /**
     * Получить текущий индекс или загрузить если нет / сменился репо.
     * ✅ FIXED: reads atomic _indexedRepoKey instead of 3 separate vars.
     */
    suspend fun getOrRefresh(): RepoIndex? {
        val config = appSettings.gitHubConfig.first()
        val key = _indexedRepoKey.value
        if (config.owner != key.owner || config.repo != key.repo || config.branch != key.branch) {
            refresh()
        }
        return _index.value ?: refresh().getOrNull()
    }

    fun invalidate() {
        Log.i(TAG, "Index invalidated")
        _index.value = null
    }

    suspend fun isCurrentRepoIndexed(): Boolean {
        val config = appSettings.gitHubConfig.first()
        val key = _indexedRepoKey.value
        return _index.value != null &&
                key.owner == config.owner &&
                key.repo == config.repo &&
                key.branch == config.branch
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVENIENCE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getTreeText(
        path: String = "",
        maxDepth: Int = Int.MAX_VALUE,
        extensions: List<String>? = null
    ): String {
        val idx = getOrRefresh() ?: return "[Error: Could not load repository index]"
        return buildString {
            appendLine("Repository: ${idx.toSummary()}")
            appendLine()
            append(idx.toTreeText(rootPath = path, maxDepth = maxDepth, extensions = extensions))
        }
    }

    suspend fun searchByName(query: String): List<FileNode> {
        val idx = getOrRefresh() ?: return emptyList()
        return idx.findByName(query)
    }

    suspend fun searchByExtensions(extensions: List<String>): List<FileNode> {
        val idx = getOrRefresh() ?: return emptyList()
        return idx.findByExtensions(extensions)
    }

    suspend fun getFilesInDirectory(path: String, recursive: Boolean = true): List<FileNode> {
        val idx = getOrRefresh() ?: return emptyList()
        return if (recursive) idx.getFilesInPath(path) else idx.getDirectChildren(path)
    }
}
