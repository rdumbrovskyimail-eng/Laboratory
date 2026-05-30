// Путь: app/src/main/java/com/opuside/app/feature/workflows/presentation/WorkflowsViewModel.kt
package com.opuside.app.feature.workflows.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import java.util.zip.ZipInputStream
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.WorkflowRun
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ════════════════════════════════════════════════════════════════════════════
 * WORKFLOWS VIEW MODEL
 * ════════════════════════════════════════════════════════════════════════════
 * 
 * Управляет состоянием экрана GitHub Actions workflows:
 * - Загрузка списка workflow runs
 * - Загрузка логов конкретного workflow
 * - Скачивание репозитория как ZIP
 * - Автообновление активных workflows
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
            
            // 1. Сначала пытаемся найти нужный релиз в кэше (по совпадению SHA в описании релиза)
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

            // 2. Если в кэше нет, загружаем с сервера и ищем по SHA
            gitHubApiClient.getReleases().fold(
                onSuccess = { releases ->
                    // Ищем релиз, который Github Action пометил этим же хэшем (headSha)
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
                    
                    // Если релиз не найден
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
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
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
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(zipUrl))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(redirectUrl))
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
                val result = gitHubApiClient.getWorkflowRuns(
                    perPage = 50
                )
                
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
                delay(5_000)
                loadWorkflows() // всегда обновляем — UI всегда актуален
            }
        }
    }

    fun downloadAndProcessRepository() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isProcessingRepository = true) }
            try {
                val config = appSettings.gitHubConfig.first()
                val zipUrl = "https://github.com/${config.owner}/${config.repo}/archive/refs/heads/${config.branch}.zip"
                val connection = java.net.URL(zipUrl).openConnection() as java.net.HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connect()
                val bytes = connection.inputStream.readBytes()
                connection.disconnect()
                val sb = StringBuilder()
                ZipInputStream(bytes.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !entry.name.endsWith(".jar")) {
                            try {
                                val text = zis.readBytes().toString(Charsets.UTF_8)
                                if (sb.isNotEmpty()) sb.append(" ")
                                sb.append(text)
                            } catch (_: Exception) {}
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                _state.update { it.copy(isProcessingRepository = false, repositoryTextContent = sb.toString()) }
            } catch (e: Exception) {
                _state.update { it.copy(isProcessingRepository = false, message = "Ошибка: ${e.message}") }
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