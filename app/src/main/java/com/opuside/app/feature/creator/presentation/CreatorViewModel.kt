package com.opuside.app.feature.creator.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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
import com.opuside.app.feature.creator.data.CreatorAIEditService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreatorViewModel @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val graphQLClient: GitHubGraphQLClient,
    private val appSettings: AppSettings,
    private val conflictResolver: GitConflictResolver,
    private val aiEditService: CreatorAIEditService
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

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _conflictState = MutableStateFlow<ConflictResult?>(null)
    val conflictState: StateFlow<ConflictResult?> = _conflictState.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // ★ SELECTION & BATCH OPERATIONS STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectedCount: StateFlow<Int> = _selectedPaths
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _moveStatus = MutableStateFlow<MoveStatus>(MoveStatus.Idle)
    val moveStatus: StateFlow<MoveStatus> = _moveStatus.asStateFlow()

    private val _collectedTxt = MutableStateFlow<String?>(null)
    val collectedTxt: StateFlow<String?> = _collectedTxt.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // ★ AI EDIT STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _aiEditStatus = MutableStateFlow<CreatorAIEditService.EditStatus>(
        CreatorAIEditService.EditStatus.Idle
    )
    val aiEditStatus: StateFlow<CreatorAIEditService.EditStatus> = _aiEditStatus.asStateFlow()

    private val _showAIEditScreen = MutableStateFlow(false)
    val showAIEditScreen: StateFlow<Boolean> = _showAIEditScreen.asStateFlow()

    private var _aiEditNewContent: String = ""

    private val _selectedAiModel = MutableStateFlow(CreatorAIEditService.AiModel.GEMINI_3_1_FLASH_LITE)
    val selectedAiModel: StateFlow<CreatorAIEditService.AiModel> = _selectedAiModel.asStateFlow()

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
                        conflictResolver.resolveManualMerge(conflict, mergedContent, _currentBranch.value)
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
                    if (_selectedFile.value?.path == file.path) closeFile()
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
                android.util.Log.d("CreatorViewModel", "✅ Folder deleted: $deleted files")
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
        val contents = gitHubClient.getContent(path, _currentBranch.value).getOrNull() ?: return 0

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

    fun uploadFile(uri: Uri, context: Context) {
        viewModelScope.launch {
            _isUploading.value = true
            _error.value = null
            try {
                val fileName = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst())
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    else null
                } ?: uri.lastPathSegment ?: "uploaded_file"

                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Не удалось прочитать файл")

                val repoPath = if (_currentPath.value.isEmpty()) fileName
                               else "${_currentPath.value}/$fileName"

                val base64Content = Base64.encodeToString(bytes, Base64.NO_WRAP)

                android.util.Log.d("CreatorViewModel", "📤 Uploading: $fileName → $repoPath")

                gitHubClient.createOrUpdateFileRaw(
                    path    = repoPath,
                    content = base64Content,
                    message = "Upload $fileName",
                    branch  = _currentBranch.value
                ).onSuccess {
                    android.util.Log.d("CreatorViewModel", "✅ Upload successful")
                    refresh()
                }.onFailure { e ->
                    _error.value = "Upload failed: ${e.message}"
                    android.util.Log.e("CreatorViewModel", "❌ Upload failed", e)
                }
            } catch (e: Exception) {
                _error.value = "Upload error: ${e.message}"
                android.util.Log.e("CreatorViewModel", "❌ Upload error", e)
            }
            _isUploading.value = false
        }
    }

    fun renameFile(file: GitHubContent, newName: String) {
        viewModelScope.launch {
            _isLoading.value = true

            android.util.Log.d("CreatorViewModel", "✏️ Renaming: ${file.name} → $newName")

            gitHubClient.getFileContentDecoded(file.path).onSuccess { content ->
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
    // ★ AI EDIT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun openAIEdit() {
        if (_selectedFile.value == null) return
        _showAIEditScreen.value = true
        _aiEditStatus.value = CreatorAIEditService.EditStatus.Idle
        _aiEditNewContent = ""
        android.util.Log.d("CreatorViewModel", "🤖 AI Edit opened for: ${_selectedFile.value?.name}")
    }

    fun closeAIEdit() {
        _showAIEditScreen.value = false
        _aiEditStatus.value = CreatorAIEditService.EditStatus.Idle
        _aiEditNewContent = ""
        android.util.Log.d("CreatorViewModel", "🤖 AI Edit closed")
    }

    fun onAiModelChange(model: CreatorAIEditService.AiModel) {
        _selectedAiModel.value = model
        if (_aiEditStatus.value is CreatorAIEditService.EditStatus.Success ||
            _aiEditStatus.value is CreatorAIEditService.EditStatus.Error) {
            _aiEditStatus.value = CreatorAIEditService.EditStatus.Idle
            _aiEditNewContent = ""
        }
        android.util.Log.d("CreatorViewModel", "🤖 AI model switched to: ${model.displayName}")
    }

    fun processAIEdit(instructions: String) {
        val file = _selectedFile.value ?: return
        val currentContent = _fileContent.value
        val model = _selectedAiModel.value

        if (instructions.isBlank()) {
            _aiEditStatus.value = CreatorAIEditService.EditStatus.Error("Введите инструкции")
            return
        }

        viewModelScope.launch {
            _aiEditStatus.value = CreatorAIEditService.EditStatus.Processing

            android.util.Log.d("CreatorViewModel",
                "🤖 Processing AI edit [${model.displayName}]: ${instructions.take(80)}...")

            aiEditService.processEdit(
                fileContent = currentContent,
                fileName = file.name,
                instructions = instructions,
                model = model
            ).onSuccess { result ->
                if (result.blocks.isEmpty()) {
                    _aiEditStatus.value = CreatorAIEditService.EditStatus.Error(
                        "AI не нашёл изменений: ${result.summary}"
                    )
                    return@launch
                }

                aiEditService.applyEdits(currentContent, result.blocks)
                    .onSuccess { applyResult ->
                        _aiEditNewContent = applyResult.newContent

                        val updatedResult = result.copy(
                            blocks = applyResult.appliedBlocks,
                            summary = if (applyResult.isFullyApplied) {
                                result.summary
                            } else {
                                "${result.summary} | ⚠️ ${applyResult.statusMessage}"
                            }
                        )

                        _aiEditStatus.value = CreatorAIEditService.EditStatus.Success(
                            result = updatedResult,
                            newContent = applyResult.newContent
                        )

                        android.util.Log.d("CreatorViewModel",
                            "✅ AI edit [${model.displayName}]: ${applyResult.totalApplied}/${result.blocks.size} blocks, " +
                            "${result.inputTokens}in+${result.outputTokens}out, " +
                            "€${String.format("%.5f", result.costEUR)}"
                        )

                        if (!applyResult.isFullyApplied) {
                            android.util.Log.w("CreatorViewModel", "⚠️ ${applyResult.statusMessage}")
                        }
                    }
                    .onFailure { e ->
                        _aiEditStatus.value = CreatorAIEditService.EditStatus.Error(
                            "Ошибка применения блоков: ${e.message}"
                        )
                    }

            }.onFailure { e ->
                _aiEditStatus.value = CreatorAIEditService.EditStatus.Error(
                    e.message ?: "Неизвестная ошибка API"
                )
                android.util.Log.e("CreatorViewModel", "❌ AI edit failed", e)
            }
        }
    }

    fun applyAIEdit() {
        if (_aiEditNewContent.isBlank()) return
        _fileContent.value = _aiEditNewContent
        android.util.Log.d("CreatorViewModel", "✅ AI edit applied to file content")
        closeAIEdit()
    }

    fun discardAIEdit() {
        _aiEditStatus.value = CreatorAIEditService.EditStatus.Idle
        _aiEditNewContent = ""
        android.util.Log.d("CreatorViewModel", "↩️ AI edit discarded")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SELECTION OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun enterSelectionMode() {
        _selectionMode.value = true
        _selectedPaths.value = emptySet()
    }

    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedPaths.value = emptySet()
    }

    fun toggleItemSelection(path: String) {
        val cur = _selectedPaths.value
        _selectedPaths.value = if (path in cur) cur - path else cur + path
    }

    fun selectAll() {
        _selectedPaths.value = _contents.value.map { it.path }.toSet()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MOVE SELECTED ITEMS
    // ═══════════════════════════════════════════════════════════════════════════

    fun moveSelectedItems(destinationBasePath: String) {
        val selectedItems = _contents.value.filter { it.path in _selectedPaths.value }
        if (selectedItems.isEmpty()) return

        val dest = destinationBasePath.trim().trimEnd('/')

        viewModelScope.launch {
            _moveStatus.value = MoveStatus.Progress(0, 0, "Scanning…")

            val plan = mutableListOf<Pair<GitHubContent, String>>()

            for (item in selectedItems) {
                val itemDest = if (dest.isEmpty()) item.name else "$dest/${item.name}"
                if (item.type == "dir") {
                    _collectFilesForMove(item.path, itemDest, plan)
                } else {
                    plan.add(item to itemDest)
                }
            }

            if (plan.isEmpty()) {
                _moveStatus.value = MoveStatus.Err("Нет файлов для перемещения")
                return@launch
            }

            var done = 0
            val errors = mutableListOf<String>()

            for ((file, newPath) in plan) {
                _moveStatus.value = MoveStatus.Progress(done, plan.size, file.name)

                gitHubClient.getFileContentDecoded(file.path, _currentBranch.value)
                    .onSuccess { content ->
                        gitHubClient.createOrUpdateFile(
                            path    = newPath,
                            content = content,
                            message = "Move ${file.path} → $newPath",
                            branch  = _currentBranch.value
                        ).onSuccess {
                            gitHubClient.deleteFile(
                                path    = file.path,
                                message = "Move: remove old ${file.path}",
                                sha     = file.sha,
                                branch  = _currentBranch.value
                            ).onSuccess {
                                done++
                            }.onFailure { e ->
                                errors.add("delete ${file.path}: ${e.message}")
                            }
                        }.onFailure { e ->
                            errors.add("create $newPath: ${e.message}")
                        }
                    }
                    .onFailure { e ->
                        errors.add("read ${file.path}: ${e.message}")
                    }
            }

            _moveStatus.value = if (errors.isEmpty()) {
                MoveStatus.Done(done)
            } else {
                MoveStatus.Err("Перемещено $done/${plan.size}. Ошибки:\n${errors.take(3).joinToString("\n")}")
            }

            exitSelectionMode()
            refresh()
        }
    }

    private suspend fun _collectFilesForMove(
        srcPath: String,
        destPath: String,
        out: MutableList<Pair<GitHubContent, String>>
    ) {
        val children = gitHubClient.getContent(srcPath, _currentBranch.value).getOrNull() ?: return
        for (child in children) {
            val childDest = "$destPath/${child.name}"
            if (child.type == "dir") {
                _collectFilesForMove(child.path, childDest, out)
            } else {
                out.add(child to childDest)
            }
        }
    }

    fun dismissMoveStatus() {
        _moveStatus.value = MoveStatus.Idle
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COLLECT SELECTED TO TXT
    // ═══════════════════════════════════════════════════════════════════════════

    fun collectSelectedToTxt() {
        val selectedItems = _contents.value.filter { it.path in _selectedPaths.value }
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            _isLoading.value = true

            val sb = StringBuilder()
            sb.appendLine("# Collected by OpusIDE")
            sb.appendLine("# Branch: ${_currentBranch.value}")
            sb.appendLine("# Items: ${selectedItems.size}")
            sb.appendLine()

            for (item in selectedItems) {
                if (item.type == "dir") {
                    _collectFolderToTxt(item.path, sb)
                } else {
                    _appendFileTxt(item.path, item.sha, sb)
                }
            }

            _collectedTxt.value = sb.toString()
            _isLoading.value = false
        }
    }

    private suspend fun _collectFolderToTxt(path: String, sb: StringBuilder) {
        val children = gitHubClient.getContent(path, _currentBranch.value).getOrNull() ?: return
        val sorted = children.sortedWith(
            compareBy<GitHubContent> { it.type != "dir" }.thenBy { it.name }
        )
        for (child in sorted) {
            if (child.type == "dir") _collectFolderToTxt(child.path, sb)
            else _appendFileTxt(child.path, child.sha, sb)
        }
    }

    private suspend fun _appendFileTxt(
        path: String,
        @Suppress("UNUSED_PARAMETER") sha: String,
        sb: StringBuilder
    ) {
        gitHubClient.getFileContentDecoded(path, _currentBranch.value)
            .onSuccess { content ->
                val divider = "─".repeat(60)
                sb.appendLine(divider)
                sb.appendLine(">>> FILE: $path")
                sb.appendLine(divider)
                sb.appendLine(content)
                sb.appendLine()
            }
            .onFailure {
                sb.appendLine(">>> FILE: $path  [ERROR: ${it.message}]")
                sb.appendLine()
            }
    }

    fun clearCollectedTxt() {
        _collectedTxt.value = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BRANCH OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun createBranch(branchName: String, fromBranch: String = _currentBranch.value) {
        viewModelScope.launch {
            _isLoading.value = true
            android.util.Log.d("CreatorViewModel", "🌿 Creating branch: $branchName from $fromBranch")
            gitHubClient.getBranch(fromBranch)
                .onSuccess {
                    _error.value = "Branch creation via API requires refs endpoint (TODO)"
                }
                .onFailure { e ->
                    _error.value = "Failed: ${e.message}"
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
            if (path.isEmpty()) listOf("root")
            else listOf("root") + path.split("/").filter { it.isNotEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("root"))

    val gitHubConfig = appSettings.gitHubConfig
}

sealed class MoveStatus {
    object Idle : MoveStatus()
    data class Progress(val done: Int, val total: Int, val currentFile: String) : MoveStatus()
    data class Done(val movedCount: Int) : MoveStatus()
    data class Err(val message: String) : MoveStatus()
}
