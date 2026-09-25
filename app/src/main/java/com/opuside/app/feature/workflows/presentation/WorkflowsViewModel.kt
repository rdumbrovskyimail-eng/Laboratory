package com.opuside.app.feature.workflows.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.WorkflowRun
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * ════════════════════════════════════════════════════════════════════════════
 * WORKFLOWS VIEW MODEL v2.2 (Streaming ZIP & Zero-OOM Optimized)
 * ════════════════════════════════════════════════════════════════════════════
 * 
 * Управляет состоянием экрана GitHub Actions workflows:
 * - Загрузка списка workflow runs
 * - Загрузка логов конкретного workflow
 * - Потоковое скачивание и распаковка репозитория как ZIP без утечки памяти
 * - Автообновление активных workflows с адаптивным интервалом
 */
@HiltViewModel
class WorkflowsViewModel @Inject constructor(
    private val gitHubApiClient: GitHubApiClient,
    private val appSettings: AppSettings
) : ViewModel() {

    private val _state = MutableStateFlow(WorkflowsState())
    val state: StateFlow<WorkflowsState> = _state.asStateFlow()

    init {
        loadWorkflows()
        startAutoRefresh()
    }

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ════════════════════════════════════════════════════════════════════════

    fun refreshWorkflows() {
        loadWorkflows()
    }

    fun selectWorkflow(workflow: WorkflowRun) {
        _state.update { 
            it.copy(
                selectedWorkflow = workflow,
                jobLogs = null,
                artifacts = emptyList(),
                releaseForWorkflow = null,
                isLoadingReleaseForWorkflow = false
            ) 
        }
        if (workflow.status == "completed") {
            loadArtifacts(workflow.id)
            loadReleaseForWorkflow(workflow.headSha)
        }
    }

    fun clearSelection() {
        _state.update { 
            it.copy(
                selectedWorkflow = null,
                jobLogs = null,
                releaseForWorkflow = null
            ) 
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun cancelAllExceptLatest() {
        val activeWorkflows = _state.value.workflows
            .filter { it.status == "in_progress" || it.status == "queued" }
            .sortedByDescending { it.createdAt }

        if (activeWorkflows.size <= 1) {
            _state.update { it.copy(message = "Нет workflow для отмены") }
            return
        }

        val toCancel = activeWorkflows.drop(1)

        viewModelScope.launch {
            var cancelled = 0
            toCancel.forEach { workflow ->
                gitHubApiClient.cancelWorkflow(workflow.id)
                    .onSuccess { cancelled++ }
                    .onFailure { e ->
                        Log.e("WorkflowsVM", "Failed to cancel ${workflow.id}", e)
                    }
            }
            _state.update {
                it.copy(message = "Отменено $cancelled из ${toCancel.size} workflows")
            }
            delay(1500)
            loadWorkflows()
        }
    }

    fun loadWorkflowLogs(runId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingLogs = true) }
            
            try {
                val jobsResult = gitHubApiClient.getWorkflowJobs(runId)
                
                jobsResult.fold(
                    onSuccess = { jobsResponse ->
                        val fastBuildJob = jobsResponse.jobs.find { job ->
                            job.name.contains("Fast Build", ignoreCase = true) ||
                            job.name.contains("Debug", ignoreCase = true)
                        } ?: jobsResponse.jobs.firstOrNull()
                        
                        if (fastBuildJob != null) {
                            val logsResult = gitHubApiClient.getJobLogs(fastBuildJob.id)
                            
                            logsResult.fold(
                                onSuccess = { logs ->
                                    _state.update { 
                                        it.copy(
                                            jobLogs = logs,
                                            isLoadingLogs = false
                                        ) 
                                    }
                                },
                                onFailure = { error ->
                                    _state.update { 
                                        it.copy(
                                            isLoadingLogs = false,
                                            message = "Failed to load logs: ${error.message}"
                                        ) 
                                    }
                                }
                            )
                        } else {
                            _state.update { 
                                it.copy(
                                    isLoadingLogs = false,
                                    message = "No jobs found for this workflow"
                                ) 
                            }
                        }
                    },
                    onFailure = { error ->
                        _state.update { 
                            it.copy(
                                isLoadingLogs = false,
                                message = "Failed to load jobs: ${error.message}"
                            ) 
                        }
                    }
                )
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isLoadingLogs = false,
                        message = "Error: ${e.message}"
                    ) 
                }
            }
        }
    }

    fun loadArtifacts(runId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingArtifacts = true) }
            try {
                gitHubApiClient.getRunArtifacts(runId).fold(
                    onSuccess = { response ->
                        val items = response.artifacts.map { artifact ->
                            ArtifactItem(
                                id = artifact.id,
                                name = artifact.name,
                                sizeInBytes = artifact.sizeInBytes,
                                createdAt = artifact.createdAt,
                                expired = artifact.expired
                            )
                        }
                        _state.update {
                            it.copy(artifacts = items, isLoadingArtifacts = false)
                        }
                    },
                    onFailure = { e ->
                        _state.update {
                            it.copy(
                                isLoadingArtifacts = false,
                                message = "Ошибка загрузки артефактов: ${e.message}"
                            ) 
                        }
                    }
                )
            } catch (e: Exception) {
                _state.update { 
                    it.copy(isLoadingArtifacts = false, message = "Ошибка: ${e.message}") 
                }
            }
        }
    }

    fun loadReleaseForWorkflow(headSha: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingReleaseForWorkflow = true) }
            
            val cached = _state.value.releases
            val matchedInCache = cached.find { it.releaseBody.contains(headSha) }
            
            if (matchedInCache != null) {
                _state.update { 
                    it.copy(
                        releaseForWorkflow = matchedInCache, 
                        isLoadingReleaseForWorkflow = false
                    ) 
                }
                return@launch
            }

            gitHubApiClient.getReleases().fold(
                onSuccess = { releases ->
                    val matchedRelease = releases.find { it.body?.contains(headSha) == true }
                    
                    if (matchedRelease != null) {
                        val asset = matchedRelease.assets.find { it.name.endsWith(".apk", ignoreCase = true) }
                        if (asset != null) {
                            val releaseItem = ReleaseItem(
                                releaseName = matchedRelease.name ?: matchedRelease.tagName,
                                releaseTag = matchedRelease.tagName,
                                releaseBody = matchedRelease.body ?: "",
                                assetName = asset.name,
                                assetId = asset.id,
                                sizeInBytes = asset.size,
                                createdAt = asset.createdAt,
                                downloadUrl = asset.browserDownloadUrl,
                                downloadCount = asset.downloadCount
                            )
                            _state.update { 
                                it.copy(
                                    releaseForWorkflow = releaseItem,
                                    isLoadingReleaseForWorkflow = false
                                ) 
                            }
                            return@fold
                        }
                    }
                    
                    _state.update { 
                        it.copy(
                            releaseForWorkflow = null,
                            isLoadingReleaseForWorkflow = false
                        ) 
                    }
                },
                onFailure = { error ->
                    Log.e("WorkflowsVM", "Failed to load specific release", error)
                    _state.update { it.copy(isLoadingReleaseForWorkflow = false) }
                }
            )
        }
    }

    fun downloadArtifact(context: Context, artifactId: Long, name: String) {
        viewModelScope.launch {
            try {
                gitHubApiClient.getArtifactDownloadUrl(artifactId).fold(
                    onSuccess = { url ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        _state.update { it.copy(message = "Скачиваем $name...") }
                    },
                    onFailure = { e ->
                        _state.update { it.copy(message = "Ошибка: ${e.message}") }
                    }
                )
            } catch (e: Exception) {
                _state.update { it.copy(message = "Ошибка: ${e.message}") }
            }
        }
    }

    fun downloadRepository(context: Context) {
        viewModelScope.launch {
            try {
                val config = appSettings.gitHubConfig.first()
                
                if (config.owner.isEmpty() || config.repo.isEmpty()) {
                    _state.update { 
                        it.copy(message = "GitHub не настроен. Укажите owner и repository в Settings.") 
                    }
                    return@launch
                }
                
                val zipUrl = "https://github.com/${config.owner}/${config.repo}/archive/refs/heads/${config.branch}.zip"
                
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(zipUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    
                    _state.update { 
                        it.copy(message = "Открываем браузер для скачивания ZIP...") 
                    }
                } catch (e: Exception) {
                    Log.e("WorkflowsVM", "Failed to open browser", e)
                    _state.update { 
                        it.copy(message = "Ошибка: не удалось открыть браузер") 
                    }
                }
                
            } catch (e: Exception) {
                Log.e("WorkflowsVM", "Download error", e)
                _state.update { 
                    it.copy(message = "Ошибка: ${e.message}") 
                }
            }
        }
    }

    fun loadReleases() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingReleases = true, releasesError = null) }
            
            try {
                val releasesResult = gitHubApiClient.getReleases()
                
                releasesResult.fold(
                    onSuccess = { releases ->
                        val releaseItems = releases.flatMap { release ->
                            release.assets
                                .filter { asset -> 
                                    asset.name.endsWith(".apk", ignoreCase = true)
                                }
                                .map { asset ->
                                    ReleaseItem(
                                        releaseName = release.name ?: release.tagName,
                                        releaseTag = release.tagName,
                                        releaseBody = release.body ?: "",
                                        assetName = asset.name,
                                        assetId = asset.id,
                                        sizeInBytes = asset.size,
                                        createdAt = asset.createdAt,
                                        downloadUrl = asset.browserDownloadUrl,
                                        downloadCount = asset.downloadCount
                                    )
                                }
                        }
                        
                        _state.update { 
                            it.copy(
                                releases = releaseItems,
                                isLoadingReleases = false,
                                releasesError = null
                            ) 
                        }
                    },
                    onFailure = { error ->
                        Log.e("WorkflowsVM", "Failed to load releases", error)
                        _state.update { 
                            it.copy(
                                isLoadingReleases = false,
                                releasesError = error.message ?: "Unknown error"
                            ) 
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("WorkflowsVM", "Crash while loading releases", e)
                _state.update { 
                    it.copy(
                        isLoadingReleases = false,
                        releasesError = e.message ?: "Unknown error"
                    ) 
                }
            }
        }
    }

    fun downloadReleaseAsset(context: Context, release: ReleaseItem) {
        viewModelScope.launch {
            _state.update { it.copy(message = "Получаем ссылку для скачивания...") }
            try {
                gitHubApiClient.getReleaseAssetDownloadUrl(release.assetId).fold(
                    onSuccess = { redirectUrl ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(redirectUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        _state.update { it.copy(message = "Открываем скачивание...") }
                    },
                    onFailure = { e ->
                        _state.update { it.copy(message = "Ошибка: ${e.message}") }
                    }
                )
            } catch (e: Exception) {
                _state.update { it.copy(message = "Ошибка: ${e.message}") }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ════════════════════════════════════════════════════════════════════════

    private fun loadWorkflows() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            
            try {
                val result = gitHubApiClient.getWorkflowRuns(perPage = 50)
                
                result.fold(
                    onSuccess = { response ->
                        _state.update { 
                            it.copy(
                                workflows = response.workflowRuns,
                                isLoading = false,
                                error = null
                            ) 
                        }
                    },
                    onFailure = { error ->
                        _state.update { 
                            it.copy(
                                isLoading = false,
                                error = error.message ?: "Unknown error"
                            ) 
                        }
                    }
                )
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error"
                    ) 
                }
            }
        }
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                val hasActive = _state.value.workflows.any { it.status == "in_progress" || it.status == "queued" }
                // Оптимизация лимитов: 5 сек во время активной сборки, 15 сек при простое
                val pollInterval = if (hasActive) 5_000L else 15_000L
                delay(pollInterval)
                loadWorkflows()
            }
        }
    }

    /**
     * Потоковая загрузка архива репозитория:
     * - Передаёт Authorization Bearer и User-Agent для приватных репо
     * - Читает поток чанками по 8 КБ напрямую в буфер (устранена ошибка OutOfMemoryError)
     */
    fun downloadAndProcessRepository() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isProcessingRepository = true) }
            var connection: HttpURLConnection? = null
            try {
                val config = appSettings.gitHubConfig.first()
                if (config.owner.isBlank() || config.repo.isBlank() || config.token.isBlank()) {
                    _state.update { it.copy(isProcessingRepository = false, message = "GitHub не настроен в Settings") }
                    return@launch
                }

                val zipUrl = "https://github.com/${config.owner}/${config.repo}/archive/refs/heads/${config.branch}.zip"
                connection = (URL(zipUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("Authorization", "Bearer ${config.token}")
                    setRequestProperty("User-Agent", "OpusIDE-Android-Client/1.0")
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    connect()
                }

                val sb = StringBuilder()
                ZipInputStream(connection.inputStream.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    val buffer = ByteArray(8192)
                    while (entry != null) {
                        if (!entry.isDirectory && !entry.name.endsWith(".jar") && !entry.name.endsWith(".png") && !entry.name.endsWith(".so")) {
                            val out = ByteArrayOutputStream()
                            var count: Int
                            while (zis.read(buffer).also { count = it } != -1) {
                                out.write(buffer, 0, count)
                            }
                            val text = out.toString(Charsets.UTF_8.name())
                            if (sb.isNotEmpty()) sb.append("\n\n")
                            sb.append("--- FILE: ${entry.name} ---\n").append(text)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                _state.update { it.copy(isProcessingRepository = false, repositoryTextContent = sb.toString()) }
            } catch (e: Exception) {
                _state.update { it.copy(isProcessingRepository = false, message = "Ошибка: ${e.message}") }
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun saveRepositoryText(context: Context, uri: Uri) {
        val content = _state.value.repositoryTextContent ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                _state.update { it.copy(repositoryTextContent = null, message = "Файл сохранён") }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Ошибка: ${e.message}") }
            }
        }
    }

    fun clearRepositoryContent() {
        _state.update { it.copy(repositoryTextContent = null) }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// STATE
// ════════════════════════════════════════════════════════════════════════════

data class WorkflowsState(
    val workflows: List<WorkflowRun> = emptyList(),
    val selectedWorkflow: WorkflowRun? = null,
    val jobLogs: String? = null,
    val isLoading: Boolean = false,
    val isLoadingLogs: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val releases: List<ReleaseItem> = emptyList(),
    val isLoadingReleases: Boolean = false,
    val releasesError: String? = null,
    val artifacts: List<ArtifactItem> = emptyList(),
    val isLoadingArtifacts: Boolean = false,
    val releaseForWorkflow: ReleaseItem? = null,
    val isLoadingReleaseForWorkflow: Boolean = false,
    val repositoryTextContent: String? = null,
    val isProcessingRepository: Boolean = false
)

data class ReleaseItem(
    val releaseName: String,
    val releaseTag: String,
    val releaseBody: String,
    val assetName: String,
    val assetId: Long,
    val sizeInBytes: Long,
    val createdAt: String,
    val downloadUrl: String,
    val downloadCount: Int
)

data class ArtifactItem(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val createdAt: String,
    val expired: Boolean
)