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
            _state.update { it.copy(message = "Starting download...") }
            
            try {
                // Получаем конфигурацию GitHub
                val config = appSettings.gitHubConfig.first()
                
                if (config.owner.isEmpty() || config.repo.isEmpty()) {
                    _state.update { 
                        it.copy(message = "GitHub not configured. Please set owner and repository in Settings.") 
                    }
                    return@launch
                }
                
                // URL для скачивания ZIP архива
                val zipUrl = "https://github.com/${config.owner}/${config.repo}/archive/refs/heads/${config.branch}.zip"
                val fileName = "${config.repo}-${config.branch}.zip"
                
                // Скачиваем в фоновом потоке
                withContext(Dispatchers.IO) {
                    val outputFile = File(context.cacheDir, fileName)
                    
                    val url = URL(zipUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    
                    try {
                        connection.connect()
                        
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            connection.inputStream.use { input ->
                                FileOutputStream(outputFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            
                            // Открываем файл через Intent
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                outputFile
                            )
                            
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/zip")
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            
                            context.startActivity(Intent.createChooser(intent, "Open ZIP file"))
                            
                            _state.update { 
                                it.copy(message = "Repository downloaded: $fileName") 
                            }
                        } else {
                            _state.update { 
                                it.copy(message = "Download failed: HTTP ${connection.responseCode}") 
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            } catch (e: Exception) {
                _state.update { 
                    it.copy(message = "Download error: ${e.message}") 
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
    val message: String? = null
)
