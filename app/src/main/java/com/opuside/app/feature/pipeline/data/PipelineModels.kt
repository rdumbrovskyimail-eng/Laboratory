package com.opuside.app.feature.pipeline.data

import com.opuside.app.feature.creator.data.CreatorAIEditService
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PIPELINE MODELS — все типы данных для G-конвейера
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Архитектура состояний:
 *   IDLE → PLANNING → REVIEWING → EXECUTING → DEFERRED_PASS → FINALIZING → DONE
 *                                       ↓
 *                                    CANCELLED
 *                                       ↓
 *                                     FATAL
 *
 * Каждая задача (FileTask) проходит свой жизненный цикл:
 *   PENDING → PROCESSING → SUCCESS / NO_CHANGES / DEFERRED → FAILED_FINAL
 */

// ═══════════════════════════════════════════════════════════════════════════
// PIPELINE STATE (общее состояние конвейера)
// ═══════════════════════════════════════════════════════════════════════════

enum class PipelinePhase {
    IDLE,              // ничего не делается, ждём ввода
    PLANNING,          // Gemini-планировщик разбирает промпт на задачи
    REVIEWING,         // план готов, пользователь смотрит и жмёт "Старт"
    EXECUTING,         // первый проход по задачам
    DEFERRED_PASS,     // второй проход по отложенным задачам
    FINALIZING,        // финальный AI-отчёт генерируется
    DONE,              // полностью завершено
    CANCELLED,         // пользователь нажал STOP
    FATAL              // критическая ошибка (401/403), всё остановлено
}

data class PipelineState(
    val phase: PipelinePhase = PipelinePhase.IDLE,
    val tasks: List<FileTask> = emptyList(),
    val currentTaskIndex: Int = -1,           // последняя начатая задача (для backward compat UI)
    val runningTaskIds: Set<String> = emptySet(), // все параллельно выполняющиеся задачи
    val finalReport: String? = null,
    val fatalError: String? = null,
    val pipelineRunId: String = UUID.randomUUID().toString().take(8),
    val maxParallelTasks: Int = 3,            // сколько задач параллельно (1=последовательно)
    val logFilterTaskId: String? = null       // фильтр логов по конкретной задаче (null = все)
) {
    val totalTasks: Int get() = tasks.size
    val completedTasks: Int get() = tasks.count { it.status.isTerminal }
    val successfulTasks: Int get() = tasks.count {
        it.status == TaskStatus.SUCCESS || it.status == TaskStatus.NO_CHANGES_NEEDED
    }
    val failedTasks: Int get() = tasks.count { it.status == TaskStatus.FAILED_FINAL }
    val deferredTasks: Int get() = tasks.count { it.status == TaskStatus.DEFERRED }
    val progress: Float get() = if (totalTasks == 0) 0f else completedTasks.toFloat() / totalTasks

    /**
     * Оценочная стоимость прогона (евро). Считается приблизительно:
     *   MODIFY = ~€0.00002 (Gemini AI Edit call)
     *   CREATE = €0 (нет AI вызова)
     * Не включает стоимость планировщика и финального отчёта (~€0.00004 фиксировано).
     */
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
}

enum class OverallStatus {
    RUNNING,           // в процессе
    SUCCESS_ALL,       // 🟢 всё успешно
    SUCCESS_PARTIAL,   // 🟡 часть упала, часть прошла
    FAILED_ALL,        // 🔴 всё упало
    FATAL,             // 🔴 критическая ошибка (401/403)
    CANCELLED          // ⚪ остановлено пользователем
}

// ═══════════════════════════════════════════════════════════════════════════
// TASK OPERATION (modify existing vs create new)
// ═══════════════════════════════════════════════════════════════════════════

enum class TaskOperation {
    MODIFY,   // изменение существующего файла через AI Edit
    CREATE;   // создание нового файла с готовым контентом

    val emoji: String get() = when (this) {
        MODIFY -> "✏️"
        CREATE -> "➕"
    }

    val displayName: String get() = when (this) {
        MODIFY -> "Изменить"
        CREATE -> "Создать"
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// FILE TASK (одна задача = один файл)
// ═══════════════════════════════════════════════════════════════════════════

data class FileTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val operation: TaskOperation = TaskOperation.MODIFY,
    val filePath: String,                      // полный путь от планировщика, после fuzzy-resolve
    val originalPathFromAi: String,            // как AI его назвал изначально (для отчёта)
    val instructions: String,                  // для MODIFY: инструкции для AI Edit; для CREATE: пусто или описание
    val newFileContent: String? = null,        // только для CREATE: готовый контент нового файла
    val packageName: String? = null,           // только для CREATE: package (для валидации/отчёта)
    val status: TaskStatus = TaskStatus.PENDING,
    val attempts: Int = 0,                     // сколько попыток сделано
    val lastError: String? = null,             // последнее сообщение об ошибке
    val errorCode: TaskErrorCode? = null,      // код ошибки (для отчёта)
    val commitSha: String? = null,             // SHA коммита после успеха
    val resolvedConflict: Boolean = false,     // был ли auto-resolved конфликт
    val tokensUsed: Int = 0,                   // токены потрачены на этот файл
    val durationMs: Long = 0                   // сколько обрабатывался
)

enum class TaskStatus {
    PENDING,              // ⏸ ждёт
    PROCESSING,           // ⏳ обрабатывается сейчас
    SUCCESS,              // ✅ закоммичено
    NO_CHANGES_NEEDED,    // ⚪ AI не нашёл что менять, закоммичен оригинал
    DEFERRED,             // 🔄 отложено на второй проход
    FAILED_FINAL;         // ❌ финально провалилось

    val isTerminal: Boolean get() = this == SUCCESS ||
            this == NO_CHANGES_NEEDED ||
            this == FAILED_FINAL

    val isDeferrable: Boolean get() = this == DEFERRED

    val emoji: String get() = when (this) {
        PENDING -> "⏸"
        PROCESSING -> "⏳"
        SUCCESS -> "✅"
        NO_CHANGES_NEEDED -> "⚪"
        DEFERRED -> "🔄"
        FAILED_FINAL -> "❌"
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
    UNKNOWN("Неизвестная ошибка")
}

// ═══════════════════════════════════════════════════════════════════════════
// LOG EVENTS (для двух live-логов)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Событие в логе "что делает Gemini".
 * Показывается на левом экране.
 */
data class GeminiLogEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: GeminiEventType,
    val icon: String,
    val message: String,
    val taskId: String? = null,        // привязка к конкретной задаче
    val durationMs: Long? = null,
    val tokens: Int? = null,
    val costEur: Double? = null
)

enum class GeminiEventType {
    PLANNER_START,        // начали планирование
    PLANNER_DONE,         // план готов
    TASK_START,           // начали обработку конкретного файла
    AI_REQUEST,           // отправили запрос в Gemini
    AI_RESPONSE,          // получили ответ
    AI_BLOCKS_PARSED,     // распарсили блоки замен
    AI_APPLY_OK,          // блоки применены
    AI_APPLY_FAIL,        // блоки не применились
    AI_RETRY,             // ретрай
    SUMMARY_START,        // финальный отчёт
    SUMMARY_DONE,
    ERROR,
    INFO
}

/**
 * Событие в логе "что происходит в репо".
 * Показывается на правом экране.
 */
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
    val workflowStatus: String? = null,        // queued, in_progress, completed
    val workflowConclusion: String? = null     // success, failure, cancelled
)

enum class RepoEventType {
    FILE_READ,            // прочитали файл
    FILE_COMMITTED,       // запушили коммит
    COMMIT_CONFLICT,      // 409 при коммите
    CONFLICT_RESOLVED,    // конфликт auto-resolved
    WORKFLOW_TRIGGERED,   // GitHub Actions запустился
    WORKFLOW_PROGRESS,    // обновление статуса
    WORKFLOW_SUCCESS,
    WORKFLOW_FAILURE,
    INDEX_INVALIDATED,    // индекс репо переиндексирован
    INFO,
    ERROR
}

// ═══════════════════════════════════════════════════════════════════════════
// PLANNER OUTPUT (что возвращает Gemini-планировщик)
// ═══════════════════════════════════════════════════════════════════════════

data class PlannerOutput(
    val tasks: List<PlannedTask>,
    val rawJson: String,                       // оригинальный JSON от Gemini (для дебага)
    val tokensUsed: Int,
    val costEur: Double
)

data class PlannedTask(
    val operation: TaskOperation = TaskOperation.MODIFY,
    val file: String,                          // путь как его дал AI (может быть неполный или сгенерированный для CREATE)
    val instructions: String = "",             // для MODIFY: инструкции AI Edit; для CREATE: пусто
    val content: String? = null,               // только для CREATE: полный контент нового файла
    val packageName: String? = null            // только для CREATE: package declaration
)

// ═══════════════════════════════════════════════════════════════════════════
// EXECUTION RESULT (результат выполнения одной задачи)
// ═══════════════════════════════════════════════════════════════════════════

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

    /** Recoverable — можно ретрайнуть или отложить */
    data class Deferrable(
        val errorCode: TaskErrorCode,
        val message: String,
        val tokensUsed: Int = 0
    ) : TaskExecutionResult()

    /** Fatal — останавливаем весь конвейер */
    data class Fatal(
        val errorCode: TaskErrorCode,
        val message: String
    ) : TaskExecutionResult()
}

// ═══════════════════════════════════════════════════════════════════════════
// REPO STATS (для шапки экрана)
// ═══════════════════════════════════════════════════════════════════════════

data class RepoStats(
    val owner: String,
    val repo: String,
    val branch: String,
    val totalFiles: Int,
    val totalDirectories: Int,
    val totalSizeBytes: Long,
    val totalSizeFormatted: String,
    val byExtension: Map<String, Int>,         // "kt" -> 245, "xml" -> 32
    val maxDepth: Int,
    val truncated: Boolean,
    val loadedAtMs: Long
) {
    val isReady: Boolean get() = totalFiles > 0

    /** Топ-5 расширений для UI */
    fun topExtensions(limit: Int = 5): List<Pair<String, Int>> =
        byExtension.entries
            .filter { it.key.isNotBlank() }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
}
