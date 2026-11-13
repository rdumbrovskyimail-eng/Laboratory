package com.opuside.app.feature.pipeline.data

import java.util.UUID

enum class PipelineMode(val displayName: String, val emoji: String) {
    ONLINE("Online", "🌐"),
    OFFLINE("Offline", "📦");
}

enum class PipelinePhase {
    IDLE, PLANNING, REVIEWING, EXECUTING, DEFERRED_PASS, FINALIZING,
    DONE, CANCELLED, FATAL
}

/**
 * Состояние конвейера Pipeline v4.0.
 * Модель не выбирается — всегда [PipelineAiConfig.MODEL_ID].
 */
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
    val pipelineMode: PipelineMode = PipelineMode.ONLINE,
    /** Комментарий планировщика (что он понял / что не смог сопоставить). */
    val planNotes: String? = null,
    /** Сколько code-блоков найдено в промпте. */
    val promptBlocksCount: Int = 0,
    /** Сколько пар «Найти / Заменить на» разобрано из промпта. */
    val promptPairsCount: Int = 0,

    // ── Независимые опции Бэкапа и TXT-отчёта ──────────────────────
    val isDetailedReportEnabled: Boolean = true,
    val isBackupEnabled: Boolean = true,
    val currentBackupId: String? = null,
    val detailedReportText: String? = null,
    val isRollingBack: Boolean = false
) {
    val totalTasks: Int get() = tasks.size
    val completedTasks: Int get() = tasks.count { it.status.isTerminal }
    val successfulTasks: Int get() = tasks.count {
        it.status == TaskStatus.SUCCESS || it.status == TaskStatus.NO_CHANGES_NEEDED
    }
    val failedTasks: Int get() = tasks.count { it.status == TaskStatus.FAILED_FINAL }
    val deferredTasks: Int get() = tasks.count { it.status == TaskStatus.DEFERRED }
    val progress: Float get() = if (totalTasks == 0) 0f else completedTasks.toFloat() / totalTasks

    val canStart: Boolean get() = phase == PipelinePhase.REVIEWING && tasks.isNotEmpty()
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

    val modelDisplayName: String get() = PipelineAiConfig.DISPLAY_NAME
}

enum class OverallStatus {
    RUNNING, SUCCESS_ALL, SUCCESS_PARTIAL, FAILED_ALL, FATAL, CANCELLED
}

enum class TaskOperation {
    MODIFY, CREATE, DELETE;
    val emoji: String get() = when (this) {
        MODIFY -> "✏️"; CREATE -> "➕"; DELETE -> "🗑"
    }
    val displayName: String get() = when (this) {
        MODIFY -> "Изменить"; CREATE -> "Создать"; DELETE -> "Удалить"
    }
}

data class FileTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val operation: TaskOperation = TaskOperation.MODIFY,
    val filePath: String,
    val originalPathFromAi: String,
    val instructions: String,
    /** Номера code-блоков промпта, относящихся к файлу (передаются исполнителю дословно). */
    val codeBlocks: List<Int> = emptyList(),
    /** Блок с ПОЛНЫМ итоговым содержимым файла (копируется как есть, без модели). */
    val fullContentBlock: Int? = null,
    /** Номера пар «Найти / Заменить на» для этого файла (применяются кодом дословно). */
    val editPairs: List<Int> = emptyList(),
    /** true — все правки файла покрыты парами, модель не нужна. */
    val pairsOnly: Boolean = false,
    /** Предупреждение планировщика (например, путь не найден / create→modify). */
    val warning: String? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val errorCode: TaskErrorCode? = null,
    val commitSha: String? = null,
    val resolvedConflict: Boolean = false,
    val tokensUsed: Int = 0,
    val costEur: Double = 0.0,
    val durationMs: Long = 0,
    /** Краткое описание того, что реально сделано (от модели или движка). */
    val resultSummary: String? = null
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
    NOT_FOUND_BLOCKS("Модель указала место правки, которого нет в файле"),
    FILE_NOT_FOUND("Файл не найден в репозитории"),
    FILE_ALREADY_EXISTS("Файл уже существует (для создания)"),
    HTTP_409_UNRESOLVED("Файл изменился в репозитории во время работы"),
    HTTP_5XX_SERVER("Сервер GitHub/Gemini временно недоступен"),
    HTTP_429_RATE_LIMIT("Превышен лимит запросов API"),
    NETWORK_ERROR("Сетевая ошибка"),
    AI_EMPTY_RESPONSE("Модель вернула пустой ответ"),
    AI_INVALID_RESPONSE("Модель вернула некорректный ответ"),
    AI_BLOCKED("Ответ заблокирован фильтром Gemini"),
    OUTPUT_TRUNCATED("Ответ модели обрезан по лимиту токенов"),
    LAZY_PLACEHOLDER("Модель вставила плейсхолдер вместо кода"),
    VALIDATION_FAILED("Результат не прошёл проверку целостности"),
    PATH_RESOLVE_FAILED("Не удалось определить точный путь к файлу"),
    INVALID_CREATE_CONTENT("Нет содержимого для создания файла"),
    AUTH_ERROR("Ошибка авторизации GitHub/Gemini"),
    TASK_TIMEOUT("Задача превысила лимит времени"),
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
    SUMMARY_START, SUMMARY_DONE, ERROR, INFO, GUARD_REJECT
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
    INDEX_INVALIDATED, INFO, ERROR,
    CLONE_START, CLONE_PROGRESS, CLONE_DONE,
    LOCAL_WRITE, LOCAL_COMMIT, PUSH_START, PUSH_DONE, PUSH_FAILED,
    BACKUP_CREATED, ROLLBACK_STARTED, ROLLBACK_COMPLETED, REPORT_GENERATED
}

data class PlannerOutput(
    val tasks: List<PlannedTask>,
    val rawJson: String,
    val tokensUsed: Int,
    val costEur: Double,
    val notes: String?
)

data class PlannedTask(
    val operation: TaskOperation = TaskOperation.MODIFY,
    val file: String,
    val instructions: String = "",
    val codeBlocks: List<Int> = emptyList(),
    val fullContentBlock: Int? = null,
    val editPairs: List<Int> = emptyList(),
    val pairsOnly: Boolean = false,
    val warning: String? = null
)

sealed class TaskExecutionResult {
    data class Success(
        val commitSha: String,
        val resolvedConflict: Boolean,
        val tokensUsed: Int,
        val costEur: Double,
        val summary: String? = null,
        val originalContent: String? = null,
        val finalContent: String? = null
    ) : TaskExecutionResult()

    data class NoChangesNeeded(
        val commitSha: String,
        val tokensUsed: Int,
        val costEur: Double,
        val originalContent: String? = null,
        val finalContent: String? = null
    ) : TaskExecutionResult()

    /** Временная проблема — задача пойдёт в повторный проход. */
    data class Deferrable(
        val errorCode: TaskErrorCode,
        val message: String,
        val tokensUsed: Int = 0,
        val costEur: Double = 0.0
    ) : TaskExecutionResult()

    /** Задача провалена окончательно, но конвейер продолжает работу. */
    data class Failed(
        val errorCode: TaskErrorCode,
        val message: String,
        val tokensUsed: Int = 0,
        val costEur: Double = 0.0
    ) : TaskExecutionResult()

    /** Проблема уровня всего конвейера (авторизация, конфиг) — останавливаем всё. */
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

    fun allExtensions(): List<Pair<String, Int>> =
        byExtension.entries
            .filter { it.key.isNotBlank() }
            .sortedByDescending { it.value }
            .map { it.key to it.value }
}