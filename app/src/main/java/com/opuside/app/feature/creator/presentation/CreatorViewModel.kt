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
import com.opuside.app.core.util.CacheManager
import com.opuside.app.core.util.createCachedFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel для Creator (Окно 1) — Редактирование без кеша/таймера.
 * 
 * Функции:
 * - Навигация по файлам репозитория
 * - Создание/редактирование/удаление файлов
 * - Commit и Push с обработкой конфликтов
 * - Работа с ветками
 * - Добавление файлов в кеш (для Analyzer)
 * 
 * ✅ ОБНОВЛЕНО: Добавлена индикация прогресса загрузки (Проблема №14)
 */
@HiltViewModel
class CreatorViewModel @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val graphQLClient: GitHubGraphQLClient,
    private val cacheManager: CacheManager,
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

    // ✅ НОВОЕ (Проблема №14): Прогресс загрузки файлов в кеш
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

    // ✅ НОВОЕ: State для Git конфликтов
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
        // Загружаем настройки репозитория
        viewModelScope.launch {
            appSettings.gitHubConfig.collect { config ->
                if (config.isConfigured) {
                    _currentOwner.value = config.owner
                    _currentRepo.value = config.repo
                    _currentBranch.value = config.branch
                    loadContents("")
                    loadBranches()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun setRepository(owner: String, repo: String, branch: String = "main") {
        viewModelScope.launch {
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
                .onSuccess { _branches.value = it }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE BROWSER OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun loadContents(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            gitHubClient.getContent(path, _currentBranch.value)
                .onSuccess { contentList ->
                    _contents.value = contentList.sortedWith(
                        compareBy<GitHubContent> { it.type != "dir" }
                            .thenBy { it.name.lowercase() }
                    )
                    _currentPath.value = path
                }
                .onFailure { e ->
                    _error.value = e.message
                }

            _isLoading.value = false
        }
    }

    fun navigateToFolder(folderPath: String) {
        _pathHistory.value = _pathHistory.value + folderPath
        loadContents(folderPath)
    }

    fun navigateBack() {
        val history = _pathHistory.value
        if (history.size > 1) {
            _pathHistory.value = history.dropLast(1)
            loadContents(history[history.size - 2])
        }
    }

    fun navigateToRoot() {
        _pathHistory.value = listOf("")
        loadContents("")
    }

    fun refresh() {
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

            gitHubClient.getFileContentDecoded(file.path, _currentBranch.value)
                .onSuccess { content ->
                    _fileContent.value = content
                    _originalContent.value = content
                }
                .onFailure { e ->
                    _error.value = "Failed to load file: ${e.message}"
                    _selectedFile.value = null
                }

            _isLoading.value = false
        }
    }

    fun updateFileContent(newContent: String) {
        _fileContent.value = newContent
    }

    fun closeFile() {
        _selectedFile.value = null
        _fileContent.value = ""
        _originalContent.value = ""
    }

    fun discardChanges() {
        _fileContent.value = _originalContent.value
    }

    /**
     * ✅ ОБНОВЛЕНО: Сохранить файл с обработкой конфликтов через GitConflictResolver.
     */
    fun saveFile(commitMessage: String) {
        val file = _selectedFile.value ?: return

        viewModelScope.launch {
            _isSaving.value = true
            _error.value = null

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
                    
                    // Обновляем в кеше, если файл там есть
                    if (cacheManager.hasFile(file.path)) {
                        cacheManager.updateFileContent(file.path, _fileContent.value)
                    }
                }
                
                is ConflictResult.Conflict -> {
                    // Показываем UI для разрешения конфликта
                    _conflictState.value = result
                }
                
                is ConflictResult.Error -> {
                    _error.value = result.message
                }
            }

            _isSaving.value = false
        }
    }

    /**
     * ✅ НОВОЕ: Разрешить Git конфликт.
     */
    fun resolveConflict(strategy: ConflictStrategy, mergedContent: String?) {
        val conflict = (_conflictState.value as? ConflictResult.Conflict) ?: return

        viewModelScope.launch {
            _isSaving.value = true

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
                    
                    // Обновляем файл после разрешения
                    _selectedFile.value?.let { file ->
                        _selectedFile.value = file.copy(sha = result.newSha)
                    }
                }
                is ConflictResult.Error -> {
                    _error.value = result.message
                }
                else -> {}
            }

            _isSaving.value = false
        }
    }

    /**
     * ✅ НОВОЕ: Закрыть диалог конфликтов.
     */
    fun dismissConflict() {
        _conflictState.value = null
    }

    /**
     * Создать новый файл.
     */
    fun createNewFile(fileName: String, initialContent: String = "") {
        val path = if (_currentPath.value.isEmpty()) fileName else "${_currentPath.value}/$fileName"
        
        viewModelScope.launch {
            _isSaving.value = true
            _error.value = null

            gitHubClient.createOrUpdateFile(
                path = path,
                content = initialContent,
                message = "Create $fileName",
                branch = _currentBranch.value
            )
                .onSuccess {
                    refresh()
                }
                .onFailure { e ->
                    _error.value = "Failed to create: ${e.message}"
                }

            _isSaving.value = false
        }
    }

    /**
     * Удалить файл.
     */
    fun deleteFile(file: GitHubContent, commitMessage: String = "Delete ${file.name}") {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            gitHubClient.deleteFile(
                path = file.path,
                message = commitMessage,
                sha = file.sha,
                branch = _currentBranch.value
            )
                .onSuccess {
                    // Если удаляемый файл открыт — закрываем
                    if (_selectedFile.value?.path == file.path) {
                        closeFile()
                    }
                    // Удаляем из кеша если там
                    cacheManager.removeFile(file.path)
                    refresh()
                }
                .onFailure { e ->
                    _error.value = "Failed to delete: ${e.message}"
                }

            _isLoading.value = false
        }
    }

    /**
     * Переименовать файл (создать новый + удалить старый).
     */
    fun renameFile(file: GitHubContent, newName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            
            // Получаем содержимое
            val contentResult = gitHubClient.getFileContentDecoded(file.path)
            
            contentResult.onSuccess { content ->
                val newPath = file.path.substringBeforeLast("/").let {
                    if (it.isEmpty()) newName else "$it/$newName"
                }
                
                // Создаём новый файл
                gitHubClient.createOrUpdateFile(
                    path = newPath,
                    content = content,
                    message = "Rename ${file.name} to $newName",
                    branch = _currentBranch.value
                ).onSuccess {
                    // Удаляем старый
                    gitHubClient.deleteFile(
                        path = file.path,
                        message = "Rename ${file.name} to $newName (delete old)",
                        sha = file.sha,
                        branch = _currentBranch.value
                    )
                    refresh()
                }
            }.onFailure { e ->
                _error.value = "Failed to rename: ${e.message}"
            }
            
            _isLoading.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE OPERATIONS (для передачи в Analyzer)
    // ═══════════════════════════════════════════════════════════════════════════

    fun toggleFileSelection(filePath: String) {
        _selectedForCache.value = _selectedForCache.value.toMutableSet().apply {
            if (contains(filePath)) remove(filePath) else add(filePath)
        }
    }

    fun selectAllInCurrentFolder() {
        val files = _contents.value.filter { it.type == "file" }.map { it.path }
        _selectedForCache.value = _selectedForCache.value + files
    }

    fun clearSelection() {
        _selectedForCache.value = emptySet()
    }

    /**
     * ✅ ОБНОВЛЕНО (Проблема №14): Добавить выбранные файлы в кеш с индикацией прогресса.
     * Загружаем файлы по одному для отображения прогресса вместо batch GraphQL.
     */
    fun addSelectedToCache() {
        val paths = _selectedForCache.value.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _loadingProgress.value = 0 to paths.size

            val cachedFiles = mutableListOf<com.opuside.app.core.database.entity.CachedFileEntity>()
            var loaded = 0

            // Загружаем по одному для прогресса
            paths.forEach { path ->
                gitHubClient.getFileContentDecoded(path, _currentBranch.value)
                    .onSuccess { content ->
                        // Получаем SHA через getContent
                        gitHubClient.getFileContent(path, _currentBranch.value)
                            .onSuccess { fileInfo ->
                                val cachedFile = createCachedFile(
                                    filePath = path,
                                    content = content,
                                    repoOwner = _currentOwner.value,
                                    repoName = _currentRepo.value,
                                    branch = _currentBranch.value,
                                    sha = fileInfo.sha
                                )
                                cachedFiles.add(cachedFile)
                            }
                        
                        loaded++
                        _loadingProgress.value = loaded to paths.size
                    }
                    .onFailure { e ->
                        _error.value = "Failed to load $path: ${e.message}"
                    }
            }

            // Добавляем все файлы в кеш
            if (cachedFiles.isNotEmpty()) {
                cacheManager.addFiles(cachedFiles)
            }
            
            _selectedForCache.value = emptySet()
            _loadingProgress.value = null
            _isLoading.value = false
        }
    }

    /**
     * Добавить один файл в кеш.
     */
    fun addToCache(file: GitHubContent) {
        viewModelScope.launch {
            val content = if (file.path == _selectedFile.value?.path) {
                _fileContent.value
            } else {
                gitHubClient.getFileContentDecoded(file.path, _currentBranch.value)
                    .getOrNull() ?: return@launch
            }

            val cachedFile = createCachedFile(
                filePath = file.path,
                content = content,
                repoOwner = _currentOwner.value,
                repoName = _currentRepo.value,
                branch = _currentBranch.value,
                sha = file.sha
            )

            cacheManager.addFile(cachedFile)
        }
    }

    /**
     * Добавить текущий открытый файл в кеш.
     */
    fun addCurrentFileToCache() {
        _selectedFile.value?.let { addToCache(it) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BRANCH OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun createBranch(branchName: String, fromBranch: String = _currentBranch.value) {
        viewModelScope.launch {
            _isLoading.value = true
            
            // Получаем SHA текущей ветки
            gitHubClient.getBranch(fromBranch)
                .onSuccess { branch ->
                    // Создание ветки требует refs API — пока заглушка
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
            else listOf("root") + path.split("/")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("root"))
}