package com.opuside.app.feature.creator.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.git.ConflictResult
import com.opuside.app.core.git.ConflictStrategy
import com.opuside.app.core.git.GitConflictResolver
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.GitHubGraphQLClient
import com.opuside.app.core.network.github.model.GitHubBranch
import com.opuside.app.core.network.github.model.GitHubContent
import com.opuside.app.core.util.PersistentCacheManager
import com.opuside.app.core.util.createCachedFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreatorViewModel @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val graphQLClient: GitHubGraphQLClient,
    private val cacheManager: PersistentCacheManager,
    private val appSettings: AppSettings,
    private val conflictResolver: GitConflictResolver
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _currentOwner = MutableStateFlow("")
    val currentOwner: StateFlow<String> = _currentOwner.asStateFlow()

    private val _currentRepo = MutableStateFlow("")
    val currentRepo: StateFlow<String> = _currentRepo.asStateFlow()

    private val _currentBranch = MutableStateFlow("main")
    val currentBranch: StateFlow<String> = _currentBranch.asStateFlow()

    private val _branches = MutableStateFlow<List<GitHubBranch>>(emptyList())
    val branches: StateFlow<List<GitHubBranch>> = _branches.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE BROWSER STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _pathHistory = MutableStateFlow<List<String>>(listOf(""))
    
    val canGoBack: StateFlow<Boolean> = _pathHistory
        .map { it.size > 1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _contents = MutableStateFlow<List<GitHubContent>>(emptyList())
    val contents: StateFlow<List<GitHubContent>> = _contents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loadingProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val loadingProgress: StateFlow<Pair<Int, Int>?> = _loadingProgress.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // EDITOR STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _selectedFile = MutableStateFlow<GitHubContent?>(null)
    val selectedFile: StateFlow<GitHubContent?> = _selectedFile.asStateFlow()

    private val _fileContent = MutableStateFlow("")
    val fileContent: StateFlow<String> = _fileContent.asStateFlow()

    private val _originalContent = MutableStateFlow("")
    
    val hasChanges: StateFlow<Boolean> = combine(_fileContent, _originalContent) { current, original ->
        current != original
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _conflictState = MutableStateFlow<ConflictResult?>(null)
    val conflictState: StateFlow<ConflictResult?> = _conflictState.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-SELECT FOR CACHE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _selectedForCache = MutableStateFlow<Set<String>>(emptySet())
    val selectedForCache: StateFlow<Set<String>> = _selectedForCache.asStateFlow()

    val selectedCount: StateFlow<Int> = _selectedForCache
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    init {
        android.util.Log.d("CreatorViewModel", "🚀 Initializing CreatorViewModel...")
        
        viewModelScope.launch {
            appSettings.gitHubConfig
                .debounce(500)
                .distinctUntilChanged()
                .collectLatest { config ->
                    
                    android.util.Log.d("CreatorViewModel", "📡 Config received:")
                    android.util.Log.d("CreatorViewModel", "   Owner: ${config.owner}")
                    android.util.Log.d("CreatorViewModel", "   Repo: ${config.repo}")
                    android.util.Log.d("CreatorViewModel", "   Branch: ${config.branch}")
                    android.util.Log.d("CreatorViewModel", "   Token: ${if (config.token.isNotEmpty()) "[SET]" else "[EMPTY]"}")
                    
                    if (config.owner.isNotBlank() && config.repo.isNotBlank() && config.token.isNotBlank()) {
                        val ownerChanged = _currentOwner.value != config.owner
                        val repoChanged = _currentRepo.value != config.repo
                        val branchChanged = _currentBranch.value != config.branch
                        
                        if (ownerChanged || repoChanged || branchChanged) {
                            android.util.Log.d("CreatorViewModel", "🔄 Config changed, reloading repository...")
                            
                            _currentOwner.value = config.owner
                            _currentRepo.value = config.repo
                            _currentBranch.value = config.branch
                            
                            try {
                                loadContents("")
                                loadBranches()
                            } catch (e: Exception) {
                                android.util.Log.e("CreatorViewModel", "❌ Failed to load repository data", e)
                                _error.value = "Failed to load repository: ${e.message}"
                            }
                        } else {
                            android.util.Log.d("CreatorViewModel", "⏭️ Config unchanged, skipping reload")
                        }
                    } else {
                        android.util.Log.d("CreatorViewModel", "⚠️ Config incomplete, clearing state")
                        
                        _currentOwner.value = ""
                        _currentRepo.value = ""
                        _currentBranch.value = "main"
                        _contents.value = emptyList()
                        _branches.value = emptyList()
                        _error.value = null
                    }
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun setRepository(owner: String, repo: String, branch: String = "main") {
        viewModelScope.launch {
            android.util.Log.d("CreatorViewModel", "📝 Setting repository: $owner/$repo@$branch")
            
            appSettings.setGitHubConfig(owner, repo, branch)
            _currentOwner.value = owner
            _currentRepo.value = repo
            _currentBranch.value = branch
            _currentPath.value = ""
            _pathHistory.value = listOf("")
            loadContents("")
            loadBranches()
        }
    }

    fun switchBranch(branch: String) {
        viewModelScope.launch {
            android.util.Log.d("CreatorViewModel", "🌿 Switching to branch: $branch")
            
            _currentBranch.value = branch
            appSettings.setGitHubConfig(_currentOwner.value, _currentRepo.value, branch)
            _currentPath.value = ""
            _pathHistory.value = listOf("")
            loadContents("")
        }
    }

    private fun loadBranches() {
        viewModelScope.launch {
            android.util.Log.d("CreatorViewModel", "🌿 Loading branches...")
            
            gitHubClient.getBranches()
                .onSuccess { branches ->
                    _branches.value = branches
                    android.util.Log.d("CreatorViewModel", "✅ Loaded ${branches.size} branches")
                }
                .onFailure { e ->
                    android.util.Log.e("CreatorViewModel", "❌ Failed to load branches", e)
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE BROWSER OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun loadContents(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            android.util.Log.d("CreatorViewModel", "📂 Loading contents: ${if (path.isEmpty()) "/" else path}")

            gitHubClient.getContent(path, _currentBranch.value)
                .onSuccess { contentList ->
                    _contents.value = contentList.sortedWith(
                        compareBy<GitHubContent> { it.type != "dir" }
                            .thenBy { it.name.lowercase() }
                    )
                    _currentPath.value = path
                    android.util.Log.d("CreatorViewModel", "✅ Loaded ${contentList.size} items")
                }
                .onFailure { e ->
                    _error.value = e.message
                    android.util.Log.e("CreatorViewModel", "❌ Failed to load contents", e)
                }

            _isLoading.value = false
        }
    }

    fun navigateToFolder(folderPath: String) {
        android.util.Log.d("CreatorViewModel", "📁 Navigating to: $folderPath")
        _pathHistory.value = _pathHistory.value + folderPath
        loadContents(folderPath)
    }

    fun navigateBack() {
        val history = _pathHistory.value
        if (history.size > 1) {
            android.util.Log.d("CreatorViewModel", "⬅️ Navigating back")
            _pathHistory.value = history.dropLast(1)
            loadContents(history[history.size - 2])
        }
    }

    fun navigateToRoot() {
        android.util.Log.d("CreatorViewModel", "🏠 Navigating to root")
        _pathHistory.value = listOf("")
        loadContents("")
    }

    fun refresh() {
        android.util.Log.d("CreatorViewModel", "🔄 Refreshing current path")
        loadContents(_currentPath.value)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun openFile(file: GitHubContent) {
        if (file.type != "file") return

        viewModelScope.launch {
            _isLoading.value = true
            _selectedFile.value = file
            
            android.util.Log.d("CreatorViewModel", "📄 Opening file: ${file.path}")

            gitHubClient.getFileContentDecoded(file.path, _currentBranch.value)
                .onSuccess { content ->
                    _fileContent.value = content
                    _originalContent.value = content
                    android.util.Log.d("CreatorViewModel", "✅ File loaded: ${content.length} chars")
                }
                .onFailure { e ->
                    _error.value = "Failed to load file: ${e.message}"
                    _selectedFile.value = null
                    android.util.Log.e("CreatorViewModel", "❌ Failed to open file", e)
                }

            _isLoading.value = false
        }
    }

    fun updateFileContent(newContent: String) {
        _fileContent.value = newContent
    }

    fun closeFile() {
        android.util.Log.d("CreatorViewModel", "❌ Closing file")
        _selectedFile.value = null
        _fileContent.value = ""
        _originalContent.value = ""
    }

    fun discardChanges() {
        android.util.Log.d("CreatorViewModel", "↩️ Discarding changes")
        _fileContent.value = _originalContent.value
    }

    fun saveFile(commitMessage: String) {
        val file = _selectedFile.value ?: return

        viewModelScope.launch {
            _isSaving.value = true
            _error.value = null
            
            android.util.Log.d("CreatorViewModel", "💾 Saving file: ${file.path}")
            android.util.Log.d("CreatorViewModel", "   Commit message: $commitMessage")

            val result = conflictResolver.saveFileWithConflictHandling(
                path = file.path,
                localContent = _fileContent.value,
                currentSha = file.sha,
                branch = _currentBranch.value,
                commitMessage = commitMessage
            )

            when (result) {
                is ConflictResult.Success -> {
                    _selectedFile.value = file.copy(sha = result.newSha)
                    _originalContent.value = _fileContent.value
                    result.message?.let { _error.value = it }
                    
                    if (cacheManager.hasFile(file.path)) {
                        cacheManager.updateFileContent(file.path, _fileContent.value)
                    }
                    
                    android.util.Log.d("CreatorViewModel", "✅ File saved successfully")
                }
                
                is ConflictResult.Conflict -> {
                    _conflictState.value = result
                    android.util.Log.w("CreatorViewModel", "⚠️ Conflict detected")
                }
                
                is ConflictResult.Error -> {
                    _error.value = result.message
                    android.util.Log.e("CreatorViewModel", "❌ Save failed: ${result.message}")
                }
            }

            _isSaving.value = false
        }
    }

    fun resolveConflict(strategy: ConflictStrategy, mergedContent: String?) {
        val conflict = (_conflictState.value as? ConflictResult.Conflict) ?: return

        viewModelScope.launch {
            _isSaving.value = true
            
            android.util.Log.d("CreatorViewModel", "🔧 Resolving conflict with strategy: $strategy")

            val result = when (strategy) {
                ConflictStrategy.KEEP_MINE -> 
                    conflictResolver.resolveKeepMine(conflict, _currentBranch.value)
                
                ConflictStrategy.KEEP_THEIRS -> 
                    conflictResolver.resolveKeepTheirs(conflict)
                
                ConflictStrategy.MANUAL_MERGE -> {
                    if (mergedContent != null) {
                        conflictResolver.resolveManualMerge(
                            conflict, mergedContent, _currentBranch.value
                        )
                    } else {
                        ConflictResult.Error("No merged content provided")
                    }
                }
                
                ConflictStrategy.SAVE_AS_COPY -> 
                    conflictResolver.resolveSaveAsCopy(conflict, _currentBranch.value)
            }

            when (result) {
                is ConflictResult.Success -> {
                    _conflictState.value = null
                    _error.value = result.message ?: "Conflict resolved successfully"
                    
                    _selectedFile.value?.let { file ->
                        _selectedFile.value = file.copy(sha = result.newSha)
                    }
                    
                    android.util.Log.d("CreatorViewModel", "✅ Conflict resolved")
                }
                is ConflictResult.Error -> {
                    _error.value = result.message
                    android.util.Log.e("CreatorViewModel", "❌ Conflict resolution failed: ${result.message}")
                }
                else -> {}
            }

            _isSaving.value = false
        }
    }

    fun dismissConflict() {
        _conflictState.value = null
    }

    fun createNewFile(fileName: String, initialContent: String = "") {
        val path = if (_currentPath.value.isEmpty()) fileName else "${_currentPath.value}/$fileName"
        
        viewModelScope.launch {
            _isSaving.value = true
            _error.value = null
            
            android.util.Log.d("CreatorViewModel", "➕ Creating new file: $path")

            gitHubClient.createOrUpdateFile(
                path = path,
                content = initialContent,
                message = "Create $fileName",
                branch = _currentBranch.value
            )
                .onSuccess {
                    android.util.Log.d("CreatorViewModel", "✅ File created successfully")
                    refresh()
                }
                .onFailure { e ->
                    _error.value = "Failed to create: ${e.message}"
                    android.util.Log.e("CreatorViewModel", "❌ Failed to create file", e)
                }

            _isSaving.value = false
        }
    }

    fun deleteFile(file: GitHubContent, commitMessage: String = "Delete ${file.name}") {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            android.util.Log.d("CreatorViewModel", "🗑️ Deleting file: ${file.path}")

            gitHubClient.deleteFile(
                path = file.path,
                message = commitMessage,
                sha = file.sha,
                branch = _currentBranch.value
            )
                .onSuccess {
                    if (_selectedFile.value?.path == file.path) {
                        closeFile()
                    }
                    cacheManager.removeFile(file.path)
                    android.util.Log.d("CreatorViewModel", "✅ File deleted successfully")
                    refresh()
                }
                .onFailure { e ->
                    _error.value = "Failed to delete: ${e.message}"
                    android.util.Log.e("CreatorViewModel", "❌ Failed to delete file", e)
                }

            _isLoading.value = false
        }
    }

    // ✅ ПРОБЛЕМА 7: Рекурсивное удаление папок
    fun deleteFolder(folder: GitHubContent) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            android.util.Log.d("CreatorViewModel", "🗑️ Deleting folder recursively: ${folder.path}")
            
            try {
                val deleted = deleteFolderRecursive(folder.path)
                android.util.Log.d("CreatorViewModel", "✅ Folder deleted: $deleted files/folders")
                refresh()
            } catch (e: Exception) {
                _error.value = "Failed to delete folder: ${e.message}"
                android.util.Log.e("CreatorViewModel", "❌ Failed to delete folder", e)
            }
            
            _isLoading.value = false
        }
    }

    private suspend fun deleteFolderRecursive(path: String): Int {
        var deletedCount = 0
        
        // Получаем содержимое папки
        val contents = gitHubClient.getContent(path, _currentBranch.value)
            .getOrNull() ?: return 0
        
        // Удаляем каждый элемент
        contents.forEach { item ->
            if (item.type == "dir") {
                // Рекурсивно удаляем подпапку
                deletedCount += deleteFolderRecursive(item.path)
            } else {
                // Удаляем файл
                gitHubClient.deleteFile(
                    path = item.path,
                    message = "Delete ${item.path}",
                    sha = item.sha,
                    branch = _currentBranch.value
                ).onSuccess {
                    deletedCount++
                    cacheManager.removeFile(item.path)
                    android.util.Log.d("CreatorViewModel", "  ✓ Deleted: ${item.path}")
                }
            }
        }
        
        return deletedCount
    }

    fun renameFile(file: GitHubContent, newName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            
            android.util.Log.d("CreatorViewModel", "✏️ Renaming file: ${file.name} → $newName")
            
            val contentResult = gitHubClient.getFileContentDecoded(file.path)
            
            contentResult.onSuccess { content ->
                val newPath = file.path.substringBeforeLast("/").let {
                    if (it.isEmpty()) newName else "$it/$newName"
                }
                
                gitHubClient.createOrUpdateFile(
                    path = newPath,
                    content = content,
                    message = "Rename ${file.name} to $newName",
                    branch = _currentBranch.value
                ).onSuccess {
                    gitHubClient.deleteFile(
                        path = file.path,
                        message = "Rename ${file.name} to $newName (delete old)",
                        sha = file.sha,
                        branch = _currentBranch.value
                    )
                    android.util.Log.d("CreatorViewModel", "✅ File renamed successfully")
                    refresh()
                }
            }.onFailure { e ->
                _error.value = "Failed to rename: ${e.message}"
                android.util.Log.e("CreatorViewModel", "❌ Failed to rename file", e)
            }
            
            _isLoading.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE OPERATIONS (✅ ПРОБЛЕМА 8: ПРОФЕССИОНАЛЬНЫЙ ERROR HANDLING)
    // ═══════════════════════════════════════════════════════════════════════════

    fun toggleFileSelection(filePath: String) {
        _selectedForCache.value = _selectedForCache.value.toMutableSet().apply {
            if (contains(filePath)) remove(filePath) else add(filePath)
        }
    }

    fun selectAllInCurrentFolder() {
        val files = _contents.value.filter { it.type == "file" }.map { it.path }
        _selectedForCache.value = _selectedForCache.value + files
        android.util.Log.d("CreatorViewModel", "✅ Selected ${files.size} files")
    }

    fun clearSelection() {
        _selectedForCache.value = emptySet()
        android.util.Log.d("CreatorViewModel", "❌ Selection cleared")
    }

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #8): Batch добавление с error handling
     * 
     * Обрабатывает ошибки при массовом добавлении файлов:
     * - Частичный успех (некоторые файлы добавлены, некоторые нет)
     * - Полный провал
     * - Превышение лимита размера
     */
    fun addSelectedToCache() {
        val paths = _selectedForCache.value.toList()
        if (paths.isEmpty()) {
            android.util.Log.w("CreatorViewModel", "⚠️ No files selected for cache")
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _loadingProgress.value = 0 to paths.size
            
            android.util.Log.d("CreatorViewModel", "━".repeat(80))
            android.util.Log.d("CreatorViewModel", "📦 BATCH ADD TO CACHE")
            android.util.Log.d("CreatorViewModel", "   Total files: ${paths.size}")
            android.util.Log.d("CreatorViewModel", "━".repeat(80))

            val cachedFiles = mutableListOf<com.opuside.app.core.database.entity.CachedFileEntity>()
            val failedFiles = mutableListOf<Pair<String, String>>() // path to error message
            var loaded = 0

            // ═══════════════════════════════════════════════════════════
            // ШАГ 1: Загружаем контент всех файлов
            // ═══════════════════════════════════════════════════════════
            paths.forEach { path ->
                try {
                    gitHubClient.getFileContentDecoded(path, _currentBranch.value)
                        .onSuccess { content ->
                            gitHubClient.getFileContent(path, _currentBranch.value)
                                .onSuccess { fileInfo ->
                                    try {
                                        val cachedFile = createCachedFile(
                                            filePath = path,
                                            content = content,
                                            repoOwner = _currentOwner.value,
                                            repoName = _currentRepo.value,
                                            branch = _currentBranch.value,
                                            sha = fileInfo.sha
                                        )
                                        cachedFiles.add(cachedFile)
                                        
                                        android.util.Log.d("CreatorViewModel", "   ✓ Loaded: $path (${content.length} chars)")
                                    } catch (e: Exception) {
                                        failedFiles.add(path to "Failed to create entity: ${e.message}")
                                        android.util.Log.e("CreatorViewModel", "   ❌ Entity creation failed: $path", e)
                                    }
                                }
                                .onFailure { e ->
                                    failedFiles.add(path to "Failed to get file info: ${e.message}")
                                    android.util.Log.e("CreatorViewModel", "   ❌ File info failed: $path", e)
                                }
                            
                            loaded++
                            _loadingProgress.value = loaded to paths.size
                        }
                        .onFailure { e ->
                            failedFiles.add(path to "Failed to download: ${e.message}")
                            android.util.Log.e("CreatorViewModel", "   ❌ Download failed: $path", e)
                            
                            loaded++
                            _loadingProgress.value = loaded to paths.size
                        }
                } catch (e: Exception) {
                    failedFiles.add(path to "Unexpected error: ${e.message}")
                    android.util.Log.e("CreatorViewModel", "   ❌ Unexpected error: $path", e)
                    
                    loaded++
                    _loadingProgress.value = loaded to paths.size
                }
            }

            // ═══════════════════════════════════════════════════════════
            // ШАГ 2: Добавляем успешно загруженные файлы в кеш
            // ═══════════════════════════════════════════════════════════
            if (cachedFiles.isNotEmpty()) {
                android.util.Log.d("CreatorViewModel", "   → Adding ${cachedFiles.size} files to cache...")
                
                cacheManager.addFiles(cachedFiles)
                    .onSuccess { addedCount ->
                        android.util.Log.d("CreatorViewModel", "━".repeat(80))
                        android.util.Log.d("CreatorViewModel", "✅ BATCH ADD COMPLETED")
                        android.util.Log.d("CreatorViewModel", "   Successfully added: $addedCount/${paths.size}")
                        
                        if (failedFiles.isNotEmpty()) {
                            android.util.Log.w("CreatorViewModel", "   Failed: ${failedFiles.size}/${paths.size}")
                            failedFiles.forEach { (path, error) ->
                                android.util.Log.w("CreatorViewModel", "      • $path: $error")
                            }
                        }
                        android.util.Log.d("CreatorViewModel", "━".repeat(80))
                        
                        // Формируем сообщение для пользователя
                        _error.value = when {
                            failedFiles.isEmpty() -> {
                                "✅ All $addedCount files added to cache"
                            }
                            addedCount > 0 -> {
                                "⚠️ Partial success: $addedCount/${paths.size} files added (${failedFiles.size} failed)"
                            }
                            else -> {
                                "❌ Failed to add any files"
                            }
                        }
                    }
                    .onFailure { error ->
                        android.util.Log.e("CreatorViewModel", "━".repeat(80))
                        android.util.Log.e("CreatorViewModel", "❌ BATCH INSERT FAILED")
                        android.util.Log.e("CreatorViewModel", "   Error type: ${error.javaClass.simpleName}")
                        android.util.Log.e("CreatorViewModel", "   Error message: ${error.message}")
                        android.util.Log.e("CreatorViewModel", "   Files prepared: ${cachedFiles.size}")
                        android.util.Log.e("CreatorViewModel", "━".repeat(80), error)
                        
                        _error.value = when (error) {
                            is IllegalArgumentException -> {
                                "❌ Some files too large: ${error.message}"
                            }
                            is SecurityException -> {
                                "❌ Encryption failed: ${error.message}"
                            }
                            else -> {
                                "❌ Database error: ${error.message}"
                            }
                        }
                    }
            } else {
                android.util.Log.e("CreatorViewModel", "━".repeat(80))
                android.util.Log.e("CreatorViewModel", "❌ NO FILES TO ADD")
                android.util.Log.e("CreatorViewModel", "   All ${paths.size} files failed to download")
                android.util.Log.e("CreatorViewModel", "━".repeat(80))
                
                _error.value = "❌ Failed to download any files"
            }
            
            _selectedForCache.value = emptySet()
            _loadingProgress.value = null
            _isLoading.value = false
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #8): Профессиональный error handling для добавления в кеш
     * 
     * Обрабатывает все возможные ошибки:
     * - Файл слишком большой (>1MB)
     * - Ошибка шифрования (SecurityException)
     * - Ошибка БД (SQLiteException)
     * - Сетевая ошибка при загрузке контента
     */
    fun addToCache(file: GitHubContent) {
        viewModelScope.launch {
            android.util.Log.d("CreatorViewModel", "━".repeat(80))
            android.util.Log.d("CreatorViewModel", "📦 ADD TO CACHE INITIATED")
            android.util.Log.d("CreatorViewModel", "   File: ${file.path}")
            android.util.Log.d("CreatorViewModel", "   Type: ${file.type}")
            android.util.Log.d("CreatorViewModel", "   SHA: ${file.sha}")
            android.util.Log.d("CreatorViewModel", "━".repeat(80))
            
            if (file.type != "file") {
                android.util.Log.w("CreatorViewModel", "⚠️ Cannot cache non-file item")
                _error.value = "Cannot add folder to cache"
                return@launch
            }
            
            _isLoading.value = true
            
            try {
                // ═══════════════════════════════════════════════════════════
                // ШАГ 1: Получаем контент файла
                // ═══════════════════════════════════════════════════════════
                val content = if (file.path == _selectedFile.value?.path) {
                    android.util.Log.d("CreatorViewModel", "   ✓ Using current editor content")
                    _fileContent.value
                } else {
                    android.util.Log.d("CreatorViewModel", "   → Fetching content from GitHub...")
                    val result = gitHubClient.getFileContentDecoded(file.path, _currentBranch.value)
                    
                    if (result.isFailure) {
                        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                        android.util.Log.e("CreatorViewModel", "   ❌ Failed to fetch content: $errorMsg")
                        _error.value = "Network error: $errorMsg"
                        _isLoading.value = false
                        return@launch
                    }
                    
                    result.getOrNull() ?: run {
                        android.util.Log.e("CreatorViewModel", "   ❌ Content is null")
                        _error.value = "File content is empty"
                        _isLoading.value = false
                        return@launch
                    }
                }

                android.util.Log.d("CreatorViewModel", "   ✓ Content loaded: ${content.length} chars")

                // ═══════════════════════════════════════════════════════════
                // ШАГ 2: Создаем CachedFileEntity
                // ═══════════════════════════════════════════════════════════
                val cachedFile = try {
                    createCachedFile(
                        filePath = file.path,
                        content = content,
                        repoOwner = _currentOwner.value,
                        repoName = _currentRepo.value,
                        branch = _currentBranch.value,
                        sha = file.sha
                    )
                } catch (e: IllegalArgumentException) {
                    android.util.Log.e("CreatorViewModel", "   ❌ Invalid file data", e)
                    _error.value = "Invalid file: ${e.message}"
                    _isLoading.value = false
                    return@launch
                }

                android.util.Log.d("CreatorViewModel", "   ✓ CachedFile entity created")
                android.util.Log.d("CreatorViewModel", "   • Path: ${cachedFile.filePath}")
                android.util.Log.d("CreatorViewModel", "   • Size: ${cachedFile.sizeBytes} bytes")
                android.util.Log.d("CreatorViewModel", "   • Language: ${cachedFile.language}")
                android.util.Log.d("CreatorViewModel", "   • Repository: ${_currentOwner.value}/${_currentRepo.value}")
                android.util.Log.d("CreatorViewModel", "   • Branch: ${_currentBranch.value}")

                // ═══════════════════════════════════════════════════════════
                // ШАГ 3: Добавляем в кеш через CacheRepository
                // ═══════════════════════════════════════════════════════════
                android.util.Log.d("CreatorViewModel", "   → Calling cacheManager.addFile()...")
                
                cacheManager.addFile(cachedFile)
                    .onSuccess {
                        android.util.Log.d("CreatorViewModel", "━".repeat(80))
                        android.util.Log.d("CreatorViewModel", "✅ FILE SUCCESSFULLY ADDED TO CACHE")
                        android.util.Log.d("CreatorViewModel", "   File: ${file.name}")
                        android.util.Log.d("CreatorViewModel", "   Path: ${file.path}")
                        android.util.Log.d("CreatorViewModel", "━".repeat(80))
                        
                        _error.value = "✅ ${file.name} added to cache"
                    }
                    .onFailure { error ->
                        android.util.Log.e("CreatorViewModel", "━".repeat(80))
                        android.util.Log.e("CreatorViewModel", "❌ CACHE OPERATION FAILED")
                        android.util.Log.e("CreatorViewModel", "   Error type: ${error.javaClass.simpleName}")
                        android.util.Log.e("CreatorViewModel", "   Error message: ${error.message}")
                        android.util.Log.e("CreatorViewModel", "━".repeat(80), error)
                        
                        // ✅ Специфичные ошибки для пользователя
                        _error.value = when (error) {
                            is IllegalArgumentException -> {
                                "❌ File too large: ${error.message}"
                            }
                            is SecurityException -> {
                                "❌ Encryption failed: ${error.message}"
                            }
                            is android.database.sqlite.SQLiteException -> {
                                "❌ Database error: ${error.message}"
                            }
                            else -> {
                                "❌ Failed to cache file: ${error.message}"
                            }
                        }
                    }
                
            } catch (e: Exception) {
                android.util.Log.e("CreatorViewModel", "━".repeat(80))
                android.util.Log.e("CreatorViewModel", "❌ UNEXPECTED ERROR IN addToCache()", e)
                android.util.Log.e("CreatorViewModel", "   File: ${file.path}")
                android.util.Log.e("CreatorViewModel", "   Error: ${e.javaClass.simpleName}")
                android.util.Log.e("CreatorViewModel", "   Message: ${e.message}")
                android.util.Log.e("CreatorViewModel", "━".repeat(80))
                
                _error.value = "Unexpected error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addCurrentFileToCache() {
        android.util.Log.d("CreatorViewModel", "📦 Add current file to cache requested")
        _selectedFile.value?.let { file ->
            android.util.Log.d("CreatorViewModel", "   Current file: ${file.path}")
            addToCache(file)
        } ?: run {
            android.util.Log.w("CreatorViewModel", "   ⚠️ No file selected")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BRANCH OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun createBranch(branchName: String, fromBranch: String = _currentBranch.value) {
        viewModelScope.launch {
            _isLoading.value = true
            
            android.util.Log.d("CreatorViewModel", "🌿 Creating branch: $branchName from $fromBranch")
            
            gitHubClient.getBranch(fromBranch)
                .onSuccess { branch ->
                    _error.value = "Branch creation via API requires refs endpoint (TODO)"
                    android.util.Log.w("CreatorViewModel", "⚠️ Branch creation not implemented")
                }
                .onFailure { e ->
                    _error.value = "Failed: ${e.message}"
                    android.util.Log.e("CreatorViewModel", "❌ Failed to create branch", e)
                }
            
            _isLoading.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    fun clearError() {
        _error.value = null
    }

    val breadcrumbs: StateFlow<List<String>> = _currentPath
        .map { path ->
            if (path.isEmpty()) {
                listOf("root")
            } else {
                listOf("root") + path.split("/").filter { it.isNotEmpty() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("root"))
    
    val gitHubConfig = appSettings.gitHubConfig
}