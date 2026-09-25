package com.opuside.app.feature.pipeline.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🛡️ PIPELINE BACKUP & AUDIT MANAGER
 *
 * Отвечает за:
 * 1. Моментальное сохранение оригиналов файлов перед началом правок.
 * 2. Полный откат (Rollback) проекта в 1 клик для Offline и Online режимов.
 * 3. Формирование 3-секционного TXT-отчета:
 *    [Полный промпт] -> [Оригинальные файлы] -> [Отредактированные файлы].
 * 4. Хранение истории точек восстановления на диске.
 */
@Singleton
class PipelineBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitHubClient: GitHubApiClient,
    private val localRepoManager: LocalRepoManager,
    private val appSettings: AppSettings,
    private val repoIndexManager: RepoIndexManager
) {
    companion object {
        private const val TAG = "PipelineBackupManager"
        private const val BACKUPS_DIR = "pipeline_backups"
        private const val MAX_BACKUPS_TO_KEEP = 30
    }

    private val mutex = Mutex()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class BackupEntry(
        val id: String,
        val runId: String,
        val timestamp: Long,
        val userPrompt: String,
        val mode: PipelineMode,
        val files: List<BackupFileMeta>,
        val isRestored: Boolean = false
    ) {
        val formattedDate: String
            get() = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))

        val affectedFilesCount: Int
            get() = files.size
    }

    data class BackupFileMeta(
        val path: String,
        val operation: TaskOperation,
        val hadOriginalContent: Boolean
    )

    data class RollbackResult(
        val backupId: String,
        val restoredFilesCount: Int,
        val commitSha: String?
    )

    private val baseBackupDir: File
        get() = File(context.filesDir, BACKUPS_DIR).apply { if (!exists()) mkdirs() }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. СОЗДАНИЕ СНИМКА (SNAPSHOT) ДО НАЧАЛА ВЫПОЛНЕНИЯ
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun createSnapshot(
        runId: String,
        userPrompt: String,
        mode: PipelineMode,
        tasks: List<FileTask>
    ): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val backupId = "backup_${System.currentTimeMillis()}_$runId"
            val backupDir = File(baseBackupDir, backupId).apply { mkdirs() }
            val originalsDir = File(backupDir, "originals").apply { mkdirs() }

            val branch = try { appSettings.gitHubConfig.first().branch } catch (_: Exception) { "main" }
            val filesMeta = mutableListOf<BackupFileMeta>()

            Log.i(TAG, "📸 Создание снимка оригиналов: $backupId (${tasks.size} задач, режим=$mode)")

            try {
                for (task in tasks) {
                    val relativePath = task.filePath.trim().removePrefix("/")
                    var originalContent: String? = null
                    var existed = false

                    when (task.operation) {
                        TaskOperation.MODIFY, TaskOperation.DELETE -> {
                            if (mode == PipelineMode.OFFLINE) {
                                val readRes = localRepoManager.readFile(relativePath)
                                if (readRes.isSuccess) {
                                    originalContent = readRes.getOrNull()
                                    existed = true
                                }
                            } else {
                                try {
                                    val file = gitHubClient.getFileContent(relativePath, branch).getOrNull()
                                    if (file?.content != null) {
                                        val cleaned = file.content.filter { !it.isWhitespace() }
                                        originalContent = String(Base64.decode(cleaned, Base64.NO_WRAP), Charsets.UTF_8)
                                        existed = true
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Не удалось вычитать оригинал $relativePath: ${e.message}")
                                }
                            }
                        }
                        TaskOperation.CREATE -> {
                            // Файл создаётся с нуля
                            existed = false
                            originalContent = null
                        }
                    }

                    if (originalContent != null) {
                        val targetFile = File(originalsDir, relativePath)
                        targetFile.parentFile?.mkdirs()
                        targetFile.writeText(originalContent, Charsets.UTF_8)
                    }

                    filesMeta.add(
                        BackupFileMeta(
                            path = relativePath,
                            operation = task.operation,
                            hadOriginalContent = existed
                        )
                    )
                }

                // Сохраняем metadata.json
                val metadata = JSONObject().apply {
                    put("id", backupId)
                    put("runId", runId)
                    put("timestamp", System.currentTimeMillis())
                    put("userPrompt", userPrompt)
                    put("mode", mode.name)
                    put("isRestored", false)
                    val arr = JSONArray()
                    filesMeta.forEach { meta ->
                        arr.put(JSONObject().apply {
                            put("path", meta.path)
                            put("operation", meta.operation.name)
                            put("hadOriginalContent", meta.hadOriginalContent)
                        })
                    }
                    put("files", arr)
                }

                File(backupDir, "metadata.json").writeText(metadata.toString(), Charsets.UTF_8)
                cleanOldBackups()

                Log.i(TAG, "✅ Снимок $backupId успешно сохранён")
                Result.success(backupId)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка создания снимка оригиналов", e)
                backupDir.deleteRecursively()
                Result.failure(e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. СОХРАНЕНИЕ ОТРЕДАКТИРОВАННЫХ ФАЙЛОВ ПОСЛЕ ЗАВЕРШЕНИЯ
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun recordFinalFiles(
        backupId: String,
        finalFiles: Map<String, String> // path -> content
    ): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val backupDir = File(baseBackupDir, backupId)
                if (!backupDir.exists()) return@withContext Result.failure(Exception("Бэкап $backupId не найден"))

                val modifiedDir = File(backupDir, "modified").apply { mkdirs() }
                finalFiles.forEach { (path, content) ->
                    val target = File(modifiedDir, path.trim().removePrefix("/"))
                    target.parentFile?.mkdirs()
                    target.writeText(content, Charsets.UTF_8)
                }

                Log.d(TAG, "💾 Финальные файлы зафиксированы в бэкапе $backupId (${finalFiles.size} шт)")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. ОТКАТ (ROLLBACK) К ТОЧКЕ ВОССТАНОВЛЕНИЯ
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun rollback(backupId: String): Result<RollbackResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val backupDir = File(baseBackupDir, backupId)
            if (!backupDir.exists()) return@withContext Result.failure(Exception("Бэкап $backupId не найден"))

            val metaFile = File(backupDir, "metadata.json")
            if (!metaFile.exists()) return@withContext Result.failure(Exception("Метаданные бэкапа повреждены"))

            val metaJson = JSONObject(metaFile.readText(Charsets.UTF_8))
            val mode = PipelineMode.valueOf(metaJson.optString("mode", PipelineMode.ONLINE.name))
            val filesArray = metaJson.getJSONArray("files")
            val originalsDir = File(backupDir, "originals")
            val branch = try { appSettings.gitHubConfig.first().branch } catch (_: Exception) { "main" }

            Log.i(TAG, "⏪ Запуск отката к точке $backupId (режим=$mode, файлов=${filesArray.length()})")

            try {
                var restoredCount = 0
                var rollbackSha: String? = null

                if (mode == PipelineMode.OFFLINE) {
                    // ── Откат в Offline-режиме ──
                    localRepoManager.ensureCloned().getOrThrow()

                    for (i in 0 until filesArray.length()) {
                        val item = filesArray.getJSONObject(i)
                        val path = item.getString("path")
                        val hadOriginal = item.getBoolean("hadOriginalContent")

                        if (hadOriginal) {
                            val origFile = File(originalsDir, path)
                            if (origFile.exists()) {
                                localRepoManager.writeFile(path, origFile.readText(Charsets.UTF_8)).getOrThrow()
                                restoredCount++
                            }
                        } else {
                            // Файл был создан пайплайном — удаляем его при откате
                            if (localRepoManager.fileExists(path)) {
                                localRepoManager.deleteFile(path)
                                restoredCount++
                            }
                        }
                    }

                    val commitRes = localRepoManager.stageAndCommit("[Rollback] Восстановление до точки #$backupId")
                    if (commitRes.isSuccess) {
                        rollbackSha = commitRes.getOrNull()
                        localRepoManager.push().getOrThrow()
                    }
                } else {
                    // ── Откат в Online-режиме (прямые коммиты через GitHub API) ──
                    for (i in 0 until filesArray.length()) {
                        val item = filesArray.getJSONObject(i)
                        val path = item.getString("path")
                        val hadOriginal = item.getBoolean("hadOriginalContent")

                        val currentFile = gitHubClient.getFileContent(path, branch).getOrNull()
                        val currentSha = currentFile?.sha

                        if (hadOriginal) {
                            val origFile = File(originalsDir, path)
                            if (origFile.exists()) {
                                val content = origFile.readText(Charsets.UTF_8)
                                val res = gitHubClient.createOrUpdateFile(
                                    path = path,
                                    content = content,
                                    message = "[Rollback] Восстановление $path",
                                    sha = currentSha,
                                    branch = branch
                                )
                                if (res.isSuccess) {
                                    restoredCount++
                                    rollbackSha = res.getOrNull()?.content?.sha
                                }
                            }
                        } else {
                            // Файл был создан пайплайном — удаляем из GitHub
                            if (currentSha != null) {
                                gitHubClient.deleteFile(
                                    path = path,
                                    message = "[Rollback] Удаление созданного $path",
                                    sha = currentSha,
                                    branch = branch
                                )
                                restoredCount++
                            }
                        }
                    }
                }

                // Помечаем бэкап как восстановленный
                metaJson.put("isRestored", true)
                metaFile.writeText(metaJson.toString(), Charsets.UTF_8)
                repoIndexManager.invalidate()

                Log.i(TAG, "✅ Откат завершён успешно. Восстановлено файлов: $restoredCount")
                Result.success(RollbackResult(backupId, restoredCount, rollbackSha))
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка при откате", e)
                Result.failure(e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. ГЕНЕРАЦИЯ ПОЛНОГО 3-СЕКЦИОННОГО TXT-ОТЧЕТА
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun generateFullReportText(backupId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val backupDir = File(baseBackupDir, backupId)
            if (!backupDir.exists()) return@withContext Result.failure(Exception("Бэкап $backupId не найден"))

            val metaFile = File(backupDir, "metadata.json")
            if (!metaFile.exists()) return@withContext Result.failure(Exception("Метаданные отсутствуют"))

            val metaJson = JSONObject(metaFile.readText(Charsets.UTF_8))
            val prompt = metaJson.getString("userPrompt")
            val timestamp = metaJson.getLong("timestamp")
            val mode = metaJson.optString("mode", "ONLINE")
            val runId = metaJson.optString("runId", "unknown")
            val filesArray = metaJson.getJSONArray("files")

            val originalsDir = File(backupDir, "originals")
            val modifiedDir = File(backupDir, "modified")

            val sb = StringBuilder()
            val div80 = "=".repeat(80)
            val div40 = "-".repeat(80)

            sb.appendLine(div80)
            sb.appendLine("OPUSIDE PIPELINE AUDIT REPORT")
            sb.appendLine("Дата: ${dateFormat.format(Date(timestamp))}")
            sb.appendLine("Режим: $mode")
            sb.appendLine("ID запуска: #$runId")
            sb.appendLine(div80)
            sb.appendLine()

            // ── СЕКЦИЯ 1: ТОЧНЫЙ ПРОМПТ ──
            sb.appendLine(div80)
            sb.appendLine("1. ПОЛНЫЙ ТОЧНЫЙ ПРОМПТ")
            sb.appendLine(div80)
            sb.appendLine(prompt.trim())
            sb.appendLine()

            // ── СЕКЦИЯ 2: ОРИГИНАЛЬНЫЕ ФАЙЛЫ ──
            sb.appendLine(div80)
            sb.appendLine("2. ОРИГИНАЛЬНЫЕ ФАЙЛЫ (ДО ИЗМЕНЕНИЙ)")
            sb.appendLine(div80)

            for (i in 0 until filesArray.length()) {
                val item = filesArray.getJSONObject(i)
                val path = item.getString("path")
                val hadOrig = item.getBoolean("hadOriginalContent")

                sb.appendLine(">>> FILE: $path")
                sb.appendLine(div40)
                if (hadOrig) {
                    val file = File(originalsDir, path)
                    if (file.exists()) {
                        sb.appendLine(file.readText(Charsets.UTF_8))
                    } else {
                        sb.appendLine("[Оригинальный контент не был сохранен]")
                    }
                } else {
                    sb.appendLine("[Файл отсутствовал в репозитории до запуска (CREATE)]")
                }
                sb.appendLine()
            }

            // ── СЕКЦИЯ 3: ОТРЕДАКТИРОВАННЫЕ ФАЙЛЫ ──
            sb.appendLine(div80)
            sb.appendLine("3. ОТРЕДАКТИРОВАННЫЕ ФАЙЛЫ (ПОСЛЕ ИЗМЕНЕНИЙ)")
            sb.appendLine(div80)

            for (i in 0 until filesArray.length()) {
                val item = filesArray.getJSONObject(i)
                val path = item.getString("path")
                val op = item.optString("operation", "MODIFY")

                sb.appendLine(">>> FILE: $path [$op]")
                sb.appendLine(div40)

                val file = File(modifiedDir, path)
                if (file.exists()) {
                    sb.appendLine(file.readText(Charsets.UTF_8))
                } else {
                    if (op == "DELETE") {
                        sb.appendLine("[Файл удален согласно задаче DELETE]")
                    } else {
                        sb.appendLine("[Файл не был изменен или модификация завершилась ошибкой]")
                    }
                }
                sb.appendLine()
            }

            sb.appendLine(div80)
            sb.appendLine("КОНЕЦ ОТЧЕТА")
            sb.appendLine(div80)

            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. УТИЛИТЫ И СПИСОК БЭКАПОВ ДЛЯ UI
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getAllBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val list = mutableListOf<BackupEntry>()
        val dirs = baseBackupDir.listFiles { file -> file.isDirectory } ?: return@withContext emptyList()

        for (dir in dirs) {
            try {
                val metaFile = File(dir, "metadata.json")
                if (!metaFile.exists()) continue

                val json = JSONObject(metaFile.readText(Charsets.UTF_8))
                val filesArr = json.getJSONArray("files")
                val files = (0 until filesArr.length()).map { idx ->
                    val obj = filesArr.getJSONObject(idx)
                    BackupFileMeta(
                        path = obj.getString("path"),
                        operation = TaskOperation.valueOf(obj.optString("operation", TaskOperation.MODIFY.name)),
                        hadOriginalContent = obj.optBoolean("hadOriginalContent", true)
                    )
                }

                list.add(
                    BackupEntry(
                        id = json.getString("id"),
                        runId = json.optString("runId", ""),
                        timestamp = json.getLong("timestamp"),
                        userPrompt = json.getString("userPrompt"),
                        mode = PipelineMode.valueOf(json.optString("mode", PipelineMode.ONLINE.name)),
                        files = files,
                        isRestored = json.optBoolean("isRestored", false)
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка чтения бэкапа в ${dir.name}: ${e.message}")
            }
        }

        list.sortedByDescending { it.timestamp }
    }

    suspend fun deleteBackup(backupId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(baseBackupDir, backupId)
        if (dir.exists()) dir.deleteRecursively() else false
    }

    fun shareReportFile(context: Context, reportContent: String, title: String = "Pipeline_Report") {
        try {
            val file = File(context.cacheDir, "${title}_${System.currentTimeMillis()}.txt").apply {
                writeText(reportContent, Charsets.UTF_8)
            }
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Сохранить или отправить отчет"))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вызова Share Sheet", e)
        }
    }

    private fun cleanOldBackups() {
        val dirs = baseBackupDir.listFiles { file -> file.isDirectory } ?: return
        if (dirs.size > MAX_BACKUPS_TO_KEEP) {
            val sorted = dirs.sortedBy { it.lastModified() }
            val toDelete = sorted.take(dirs.size - MAX_BACKUPS_TO_KEEP)
            toDelete.forEach { it.deleteRecursively() }
        }
    }
}