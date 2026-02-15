package com.opuside.app.feature.workflows.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        _state.update { it.copy(selectedWorkflow = workflow, jobLogs = null) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedWorkflow = null, jobLogs = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun loadWorkflowLogs(runId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingLogs = true) }
            
            try {
                // Получаем jobs для этого run
                val jobsResult = gitHubApiClient.getWorkflowJobs(runId)
                
                jobsResult.fold(
                    onSuccess = { jobsResponse ->
                        // Ищем job с названием "Fast Build Debug APK"
                        val fastBuildJob = jobsResponse.jobs.find { job ->
                            job.name.contains("Fast Build", ignoreCase = true) ||
                            job.name.contains("Debug", ignoreCase = true)
                        } ?: jobsResponse.jobs.firstOrNull()
                        
                        if (fastBuildJob != null) {
                            // Загружаем логи этого job
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

    fun downloadRepository(context: Context) {
        viewModelScope.launch {
            _state.update { it.copy(message = "Начинается скачивание...") }
            
            try {
                // Получаем конфигурацию GitHub
                val config = appSettings.gitHubConfig.first()
                
                if (config.owner.isEmpty() || config.repo.isEmpty()) {
                    _state.update { 
                        it.copy(message = "GitHub не настроен. Укажите owner и repository в Settings.") 
                    }
                    return@launch
                }
                
                val token = appSettings.gitHubToken.first()
                
                // URL для скачивания ZIP архива
                val zipUrl = "https://github.com/${config.owner}/${config.repo}/archive/refs/heads/${config.branch}.zip"
                val fileName = "${config.repo}-${config.branch}.zip"
                
                // Скачиваем в фоновом потоке
                withContext(Dispatchers.IO) {
                    // Сохраняем в Downloads
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val outputFile = File(downloadsDir, fileName)
                    
                    val url = URL(zipUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    
                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 15000
                        connection.readTimeout = 15000
                        
                        // Добавляем токен если есть
                        if (token.isNotEmpty()) {
                            connection.setRequestProperty("Authorization", "Bearer $token")
                        }
                        
                        connection.connect()
                        
                        when (connection.responseCode) {
                            HttpURLConnection.HTTP_OK -> {
                                val totalSize = connection.contentLength
                                var downloaded = 0L
                                
                                connection.inputStream.use { input ->
                                    FileOutputStream(outputFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                            downloaded += bytesRead
                                            
                                            // Обновляем прогресс
                                            if (totalSize > 0) {
                                                val progress = (downloaded * 100 / totalSize).toInt()
                                                withContext(Dispatchers.Main) {
                                                    _state.update { 
                                                        it.copy(message = "Скачивание... $progress%") 
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                withContext(Dispatchers.Main) {
                                    _state.update { 
                                        it.copy(message = "✓ Репозиторий скачан: ${outputFile.absolutePath}") 
                                    }
                                }
                                
                                // Сканируем файл для MediaStore
                                android.media.MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(outputFile.absolutePath),
                                    arrayOf("application/zip"),
                                    null
                                )
                            }
                            
                            HttpURLConnection.HTTP_MOVED_TEMP,
                            HttpURLConnection.HTTP_MOVED_PERM,
                            HttpURLConnection.HTTP_SEE_OTHER -> {
                                val location = connection.getHeaderField("Location")
                                withContext(Dispatchers.Main) {
                                    _state.update { 
                                        it.copy(message = "Ошибка: редирект на $location") 
                                    }
                                }
                            }
                            
                            else -> {
                                withContext(Dispatchers.Main) {
                                    _state.update { 
                                        it.copy(message = "Ошибка скачивания: HTTP ${connection.responseCode}") 
                                    }
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WorkflowsVM", "Download error", e)
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
                // Получаем последние workflow runs которые завершились успешно
                val runsResult = gitHubApiClient.getWorkflowRuns(
                    status = "completed",
                    perPage = 20
                )
                
                runsResult.fold(
                    onSuccess = { response ->
                        val successfulRuns = response.workflowRuns.filter { 
                            it.conclusion == "success" 
                        }
                        
                        // Для каждого успешного run загружаем артефакты
                        val releasesWithApk = mutableListOf<ReleaseItem>()
                        
                        successfulRuns.forEach { run ->
                            val artifactsResult = gitHubApiClient.getRunArtifacts(run.id)
                            
                            artifactsResult.fold(
                                onSuccess = { artifacts ->
                                    // Ищем APK артефакты
                                    val apkArtifacts = artifacts.artifacts.filter { artifact ->
                                        artifact.name.contains("apk", ignoreCase = true) ||
                                        artifact.name.contains("debug", ignoreCase = true)
                                    }
                                    
                                    apkArtifacts.forEach { artifact ->
                                        releasesWithApk.add(
                                            ReleaseItem(
                                                workflowName = run.name ?: "Build",
                                                branch = run.headBranch,
                                                commitSha = run.headSha,
                                                artifactName = artifact.name,
                                                artifactId = artifact.id,
                                                sizeInBytes = artifact.sizeInBytes,
                                                createdAt = artifact.createdAt,
                                                downloadUrl = artifact.archiveDownloadUrl
                                            )
                                        )
                                    }
                                },
                                onFailure = { /* Игнорируем ошибки для отдельных runs */ }
                            )
                        }
                        
                        _state.update { 
                            it.copy(
                                releases = releasesWithApk,
                                isLoadingReleases = false,
                                releasesError = null
                            ) 
                        }
                    },
                    onFailure = { error ->
                        _state.update { 
                            it.copy(
                                isLoadingReleases = false,
                                releasesError = error.message ?: "Unknown error"
                            ) 
                        }
                    }
                )
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isLoadingReleases = false,
                        releasesError = e.message ?: "Unknown error"
                    ) 
                }
            }
        }
    }

    fun downloadApk(context: Context, releaseItem: ReleaseItem) {
        viewModelScope.launch {
            _state.update { it.copy(message = "Подготовка к скачиванию APK...") }
            
            try {
                val token = appSettings.gitHubToken.first()
                
                if (token.isEmpty()) {
                    _state.update { 
                        it.copy(message = "Требуется GitHub токен для скачивания APK") 
                    }
                    return@launch
                }
                
                withContext(Dispatchers.IO) {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val fileName = "${releaseItem.artifactName}.zip"
                    val outputFile = File(downloadsDir, fileName)
                    
                    val url = URL(releaseItem.downloadUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    
                    try {
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("Authorization", "Bearer $token")
                        connection.setRequestProperty("Accept", "application/vnd.github+json")
                        connection.connectTimeout = 15000
                        connection.readTimeout = 30000
                        connection.connect()
                        
                        when (connection.responseCode) {
                            HttpURLConnection.HTTP_OK -> {
                                val totalSize = connection.contentLength
                                var downloaded = 0L
                                
                                connection.inputStream.use { input ->
                                    FileOutputStream(outputFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                            downloaded += bytesRead
                                            
                                            if (totalSize > 0) {
                                                val progress = (downloaded * 100 / totalSize).toInt()
                                                withContext(Dispatchers.Main) {
                                                    _state.update { 
                                                        it.copy(message = "Скачивание APK... $progress%") 
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                withContext(Dispatchers.Main) {
                                    _state.update { 
                                        it.copy(message = "✓ APK скачан: ${outputFile.absolutePath}") 
                                    }
                                }
                                
                                android.media.MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(outputFile.absolutePath),
                                    arrayOf("application/zip"),
                                    null
                                )
                            }
                            
                            else -> {
                                withContext(Dispatchers.Main) {
                                    _state.update { 
                                        it.copy(message = "Ошибка скачивания APK: HTTP ${connection.responseCode}") 
                                    }
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WorkflowsVM", "APK download error", e)
                _state.update { 
                    it.copy(message = "Ошибка скачивания: ${e.message}") 
                }
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
                    perPage = 50  // Загружаем последние 50 runs
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

    /**
     * Автоматически обновляет workflow runs каждые 5 секунд
     * если есть активные (running/queued) workflows
     */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(5_000) // 5 секунд
                
                val hasActiveWorkflows = _state.value.workflows.any { workflow ->
                    workflow.status == "in_progress" || workflow.status == "queued"
                }
                
                if (hasActiveWorkflows) {
                    loadWorkflows()
                }
            }
        }
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
    // Releases tab
    val releases: List<ReleaseItem> = emptyList(),
    val isLoadingReleases: Boolean = false,
    val releasesError: String? = null
)

data class ReleaseItem(
    val workflowName: String,
    val branch: String,
    val commitSha: String,
    val artifactName: String,
    val artifactId: Long,
    val sizeInBytes: Long,
    val createdAt: String,
    val downloadUrl: String
)
