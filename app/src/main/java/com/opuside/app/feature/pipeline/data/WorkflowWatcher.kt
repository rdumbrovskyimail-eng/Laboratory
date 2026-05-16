package com.opuside.app.feature.pipeline.data

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.WorkflowRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * WORKFLOW WATCHER v1.0
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * После каждого успешного коммита конвейер вызывает watch(commitSha),
 * чтобы отследить, какой GitHub Actions workflow run триггернулся этим
 * коммитом, и публиковать обновления статуса в RepoLog.
 *
 * Стратегия: polling каждые 5 секунд в течение максимум ~3 минут
 * (36 итераций). Этого хватает для большинства Android CI/CD.
 *
 * API: GET /repos/{owner}/{repo}/actions/runs?head_sha={sha}&branch={branch}
 * — возвращает workflow runs, привязанные ровно к этому коммиту.
 *
 * Использует Flow — UI может cancel'нуть watcher в любой момент
 * (например, когда конвейер завершён или перешёл к следующему файлу).
 */
@Singleton
class WorkflowWatcher @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "WorkflowWatcher"
        private const val POLL_INTERVAL_MS = 5000L      // 5 секунд
        private const val MAX_POLL_ITERATIONS = 36      // 3 минуты максимум
        private const val INITIAL_DELAY_MS = 3000L      // даём GH 3с на регистрацию workflow
    }

    /**
     * Следит за workflow run для конкретного коммита.
     * Эмитит RepoLogEvent для каждого значимого обновления статуса.
     * Завершается когда workflow закончился или закончились итерации.
     */
    fun watch(
        commitSha: String,
        taskId: String?,
        workflowFilterName: String? = null    // если null — берём первый workflow для этого коммита
    ): Flow<RepoLogEvent> = flow {
        Log.d(TAG, "Watching workflows for commit ${commitSha.take(8)}")

        // Initial delay — GitHub нужно время чтобы зарегистрировать workflow
        delay(INITIAL_DELAY_MS)

        var foundRun: WorkflowRun? = null
        var lastStatus: String? = null
        var lastConclusion: String? = null

        repeat(MAX_POLL_ITERATIONS) { iteration ->
            try {
                val branch = appSettings.gitHubConfig.first().branch
                val runs = fetchRunsForCommit(commitSha, branch)

                val match = if (workflowFilterName != null) {
                    runs.firstOrNull { it.name == workflowFilterName }
                } else {
                    runs.firstOrNull()
                }

                if (match == null) {
                    if (iteration < 3) {
                        // первые 15 секунд: пишем только один раз "ждём workflow"
                        if (iteration == 0) {
                            emit(RepoLogEvent(
                                type = RepoEventType.INFO,
                                icon = "👁",
                                message = "Ждём GitHub Actions для ${commitSha.take(8)}...",
                                taskId = taskId,
                                commitSha = commitSha
                            ))
                        }
                    } else if (iteration == MAX_POLL_ITERATIONS - 1) {
                        // ничего не нашли за всё время — наверное, нет workflows для этого пути
                        emit(RepoLogEvent(
                            type = RepoEventType.INFO,
                            icon = "ℹ️",
                            message = "Workflow для ${commitSha.take(8)} не обнаружен",
                            taskId = taskId,
                            commitSha = commitSha
                        ))
                    }
                    delay(POLL_INTERVAL_MS)
                    return@repeat
                }

                // Найден workflow — сообщаем первый раз
                if (foundRun == null) {
                    foundRun = match
                    emit(RepoLogEvent(
                        type = RepoEventType.WORKFLOW_TRIGGERED,
                        icon = "🚀",
                        message = "Workflow «${match.name}» запущен (#${match.id})",
                        taskId = taskId,
                        commitSha = commitSha,
                        workflowRunId = match.id,
                        workflowRunUrl = match.htmlUrl,
                        workflowStatus = match.status
                    ))
                }

                // Изменился статус? (queued → in_progress → completed)
                if (match.status != lastStatus) {
                    lastStatus = match.status
                    emit(RepoLogEvent(
                        type = RepoEventType.WORKFLOW_PROGRESS,
                        icon = statusIcon(match.status),
                        message = "Workflow #${match.id}: ${match.status}",
                        taskId = taskId,
                        commitSha = commitSha,
                        workflowRunId = match.id,
                        workflowRunUrl = match.htmlUrl,
                        workflowStatus = match.status
                    ))
                }

                // Финальный статус
                val conclusion = match.conclusion
                if (match.status == "completed" && conclusion != null && conclusion != lastConclusion) {
                    lastConclusion = conclusion
                    val isSuccess = conclusion == "success"
                    emit(RepoLogEvent(
                        type = if (isSuccess) RepoEventType.WORKFLOW_SUCCESS
                               else RepoEventType.WORKFLOW_FAILURE,
                        icon = if (isSuccess) "✅" else "❌",
                        message = "Workflow #${match.id}: ${conclusion.uppercase()}",
                        taskId = taskId,
                        commitSha = commitSha,
                        workflowRunId = match.id,
                        workflowRunUrl = match.htmlUrl,
                        workflowStatus = match.status,
                        workflowConclusion = conclusion
                    ))
                    return@flow   // финал — выходим
                }

                delay(POLL_INTERVAL_MS)

            } catch (e: CancellationException) {
                Log.d(TAG, "Watcher cancelled for ${commitSha.take(8)}")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Watcher poll failed: ${e.message}")
                delay(POLL_INTERVAL_MS)
            }
        }

        // Истекло время — workflow не завершился
        val finalRun = foundRun
        if (finalRun != null && lastConclusion == null) {
            emit(RepoLogEvent(
                type = RepoEventType.INFO,
                icon = "⏱",
                message = "Workflow #${finalRun.id} ещё выполняется (превышен таймаут наблюдения)",
                taskId = taskId,
                commitSha = commitSha,
                workflowRunId = finalRun.id,
                workflowRunUrl = finalRun.htmlUrl
            ))
        }
    }

    /**
     * Достаёт список workflow runs для конкретного SHA.
     * Использует head_sha + branch фильтры.
     */
    private suspend fun fetchRunsForCommit(
        commitSha: String,
        branch: String
    ): List<WorkflowRun> = withContext(Dispatchers.IO) {
        // gitHubClient.getWorkflowRuns принимает branch, но не head_sha напрямую.
        // Делаем list по branch и фильтруем локально.
        val runs = gitHubClient.getWorkflowRuns(
            workflowId = null,
            branch = branch,
            status = null,
            perPage = 20
        ).getOrNull() ?: return@withContext emptyList()

        runs.workflowRuns.filter { it.headSha == commitSha }
    }

    private fun statusIcon(status: String): String = when (status) {
        "queued" -> "📋"
        "in_progress" -> "🔄"
        "completed" -> "🏁"
        else -> "ℹ️"
    }
}
