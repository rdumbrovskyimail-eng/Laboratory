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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreatorViewModel @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val graphQLClient: GitHubGraphQLClient,
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
        
        val contents = gitHubClient.getContent(path, _currentBranch.value)
            .getOrNull() ?: return 0
        
        contents.forEach { item ->
            if (item.type == "dir") {
                deletedCount += deleteFolderRecursive(item.path)
            } else {
                gitHubClient.deleteFile(
                    path = item.path,
                    message = "Delete ${item.path}",
                    sha = item.sha,
                    branch = _currentBranch.value
                ).onSuccess {
                    deletedCount++
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