package com.opuside.app.feature.pipeline.data

import com.opuside.app.feature.creator.data.CreatorAIEditService
import java.util.UUID

enum class PipelineMode(val displayName: String, val emoji: String) {
    ONLINE("Online", "🌐"),
    OFFLINE("Offline", "📦");
}

enum class PipelinePhase {
    IDLE, PLANNING, REVIEWING, EXECUTING, DEFERRED_PASS, FINALIZING,
    DONE, CANCELLED, FATAL
}

data class PipelineState(
    val phase: PipelinePhase = PipelinePhase.IDLE,
    val tasks: List<FileTask> = emptyList(),
    val currentTaskIndex: Int = -1,
    val runningTaskIds: Set<String> = emptySet(),
    val finalReport: String? = null,
    val fatalError: String? = null,
    val pipelineRunId: String = UUID.randomUUID().toString().take(8),
    val maxParallelTasks: Int = 3,
    val logFilterTaskId: String? = null,
    // НОВОЕ: Override модели Gemini
    val modelOverrideEnabled: Boolean = false,
    val modelOverrideName: String = "",
    // Дефолтная модель из списка (если override OFF)
    val selectedModelApiId: String = "gemini-3-flash-preview",
    // Thinking-уровень для Lite модели (low / medium / high)
    val liteThinkingLevel: String = "high",
    // Режим работы пайплайна: Online = коммиты напрямую через GitHub API,
    // Offline = клонируем репо локально, правим, в конце один коммит + push
    val pipelineMode: PipelineMode = PipelineMode.ONLINE
) {
    val totalTasks: Int get() = tasks.size
    val completedTasks: Int get() = tasks.count { it.status.isTerminal }
    val successfulTasks: Int get() = tasks.count {
        it.status == TaskStatus.SUCCESS || it.status == TaskStatus.NO_CHANGES_NEEDED
    }
    val failedTasks: Int get() = tasks.count { it.status == TaskStatus.FAILED_FINAL }
    val deferredTasks: Int get() = tasks.count { it.status == TaskStatus.DEFERRED }
    val progress: Float get() = if (totalTasks == 0) 0f else completedTasks.toFloat() / totalTasks

    val estimatedCost: Double get() {
        val modifyCount = tasks.count { it.operation == TaskOperation.MODIFY }
        return modifyCount * 0.00002 + 0.00004
    }

    val canStart: Boolean get() = phase == PipelinePhase.REVIEWING
    val canStop: Boolean get() = phase == PipelinePhase.EXECUTING ||
            phase == PipelinePhase.DEFERRED_PASS || phase == PipelinePhase.PLANNING
    val isRunning: Boolean get() = phase == PipelinePhase.PLANNING ||
            phase == PipelinePhase.EXECUTING ||
            phase == PipelinePhase.DEFERRED_PASS ||
            phase == PipelinePhase.FINALIZING

    val overallStatus: OverallStatus get() = when {
        phase == PipelinePhase.FATAL -> OverallStatus.FATAL
        phase == PipelinePhase.CANCELLED -> OverallStatus.CANCELLED
        phase != PipelinePhase.DONE -> OverallStatus.RUNNING
        failedTasks == 0 -> OverallStatus.SUCCESS_ALL
        successfulTasks == 0 -> OverallStatus.FAILED_ALL
        else -> OverallStatus.SUCCESS_PARTIAL
    }

    /** Какую модель отправлять в Gemini API. Override-поле приоритетнее селектора. */
    val effectiveModelApiId: String get() =
        if (modelOverrideEnabled && modelOverrideName.isNotBlank())
            modelOverrideName.trim()
        else
            selectedModelApiId
}

enum class OverallStatus {
    RUNNING, SUCCESS_ALL, SUCCESS_PARTIAL, FAILED_ALL, FATAL, CANCELLED
}

enum class TaskOperation {
    MODIFY, CREATE;
    val emoji: String get() = when (this) { MODIFY -> "✏️"; CREATE -> "➕" }
    val displayName: String get() = when (this) { MODIFY -> "Изменить"; CREATE -> "Создать" }
}

data class FileTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val operation: TaskOperation = TaskOperation.MODIFY,
    val filePath: String,
    val originalPathFromAi: String,
    val instructions: String,
    val newFileContent: String? = null,
    val packageName: String? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val errorCode: TaskErrorCode? = null,
    val commitSha: String? = null,
    val resolvedConflict: Boolean = false,
    val tokensUsed: Int = 0,
    val durationMs: Long = 0
)

enum class TaskStatus {
    PENDING, PROCESSING, SUCCESS, NO_CHANGES_NEEDED, DEFERRED, FAILED_FINAL;
    val isTerminal: Boolean get() = this == SUCCESS || this == NO_CHANGES_NEEDED || this == FAILED_FINAL
    val isDeferrable: Boolean get() = this == DEFERRED
    val emoji: String get() = when (this) {
        PENDING -> "⏸"; PROCESSING -> "⏳"; SUCCESS -> "✅"
        NO_CHANGES_NEEDED -> "⚪"; DEFERRED -> "🔄"; FAILED_FINAL -> "❌"
    }
}

enum class TaskErrorCode(val displayName: String) {
    NOT_FOUND_BLOCKS("AI не смог найти блоки замены в файле"),
    FILE_NOT_FOUND("Файл не найден в репозитории"),
    FILE_ALREADY_EXISTS("Файл уже существует (для создания)"),
    HTTP_409_UNRESOLVED("Конфликт версий не разрешён после retry"),
    HTTP_5XX_SERVER("Сервер GitHub/Gemini временно недоступен"),
    HTTP_429_RATE_LIMIT("Превышен лимит запросов API"),
    NETWORK_ERROR("Сетевая ошибка"),
    AI_EMPTY_RESPONSE("AI вернул пустой ответ"),
    AI_INVALID_RESPONSE("AI вернул некорректный ответ"),
    PATH_RESOLVE_FAILED("Не удалось определить точный путь к файлу"),
    INVALID_CREATE_CONTENT("Контент для создания файла не указан"),
    TASK_TIMEOUT("Задача превысила лимит времени (5 минут)"),
    UNKNOWN("Неизвестная ошибка")
}

data class GeminiLogEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: GeminiEventType,
    val icon: String,
    val message: String,
    val taskId: String? = null,
    val durationMs: Long? = null,
    val tokens: Int? = null,
    val costEur: Double? = null
)

enum class GeminiEventType {
    PLANNER_START, PLANNER_DONE, TASK_START, AI_REQUEST, AI_RESPONSE,
    AI_BLOCKS_PARSED, AI_APPLY_OK, AI_APPLY_FAIL, AI_RETRY,
    SUMMARY_START, SUMMARY_DONE, ERROR, INFO
}

data class RepoLogEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: RepoEventType,
    val icon: String,
    val message: String,
    val taskId: String? = null,
    val commitSha: String? = null,
    val commitUrl: String? = null,
    val workflowRunId: Long? = null,
    val workflowRunUrl: String? = null,
    val workflowStatus: String? = null,
    val workflowConclusion: String? = null
)

enum class RepoEventType {
    FILE_READ, FILE_COMMITTED, COMMIT_CONFLICT, CONFLICT_RESOLVED,
    WORKFLOW_TRIGGERED, WORKFLOW_PROGRESS, WORKFLOW_SUCCESS, WORKFLOW_FAILURE,
    INDEX_INVALIDATED, INFO, ERROR
}

data class PlannerOutput(
    val tasks: List<PlannedTask>,
    val rawJson: String,
    val tokensUsed: Int,
    val costEur: Double
)

data class PlannedTask(
    val operation: TaskOperation = TaskOperation.MODIFY,
    val file: String,
    val instructions: String = "",
    val content: String? = null,
    val packageName: String? = null
)

sealed class TaskExecutionResult {
    data class Success(
        val commitSha: String,
        val resolvedConflict: Boolean,
        val tokensUsed: Int,
        val costEur: Double,
        val editResult: CreatorAIEditService.EditResult?
    ) : TaskExecutionResult()

    data class NoChangesNeeded(
        val commitSha: String,
        val tokensUsed: Int,
        val costEur: Double
    ) : TaskExecutionResult()

    data class Deferrable(
        val errorCode: TaskErrorCode,
        val message: String,
        val tokensUsed: Int = 0
    ) : TaskExecutionResult()

    data class Fatal(
        val errorCode: TaskErrorCode,
        val message: String
    ) : TaskExecutionResult()
}

data class RepoStats(
    val owner: String,
    val repo: String,
    val branch: String,
    val totalFiles: Int,
    val totalDirectories: Int,
    val totalSizeBytes: Long,
    val totalSizeFormatted: String,
    val byExtension: Map<String, Int>,
    val maxDepth: Int,
    val truncated: Boolean,
    val loadedAtMs: Long
) {
    val isReady: Boolean get() = totalFiles > 0

    fun topExtensions(limit: Int = 5): List<Pair<String, Int>> =
        byExtension.entries
            .filter { it.key.isNotBlank() }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    /** ВСЕ расширения — для expandable секции со скроллом */
    fun allExtensions(): List<Pair<String, Int>> =
        byExtension.entries
            .filter { it.key.isNotBlank() }
            .sortedByDescending { it.value }
            .map { it.key to it.value }
}