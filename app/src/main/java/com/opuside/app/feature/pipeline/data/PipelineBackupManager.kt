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
 * 🛡️ PIPELINE BACKUP & AUDIT MANAGER v3.2
 *
 * Отвечает за:
 * 1. Сохранение снимков оригиналов перед правками.
 * 2. Полный откат проекта в 1 клик для Offline и Online режимов.
 * 3. Создание 3-секционного TXT-отчета: [Промпт] -> [Оригиналы] -> [Изменения].
 * 4. Защиту от падений при вызове Share Sheet из ApplicationContext.
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
        val formattedDate: String get() = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
        val affectedFilesCount: Int get() = files.size
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

            try {
                for (task in tasks) {
                    val relPath = task.filePath.trim().removePrefix("/")
                    var originalContent: String? = null
                    var existed = false

                    if (task.operation != TaskOperation.CREATE) {
                        if (mode == PipelineMode.OFFLINE) {
                            val r = localRepoManager.readFile(relPath)
                            if (r.isSuccess) {
                                originalContent = r.getOrNull()
                                existed = true
                            }
                        } else {
                            try {
                                val file = gitHubClient.getFileContent(relPath, branch).getOrNull()
                                if (file?.content != null) {
                                    val cleaned = file.content.filter { !it.isWhitespace() }
                                    originalContent = String(Base64.decode(cleaned, Base64.NO_WRAP), Charsets.UTF_8)
                                    existed = true
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    if (originalContent != null) {
                        val target = File(originalsDir, relPath)
                        target.parentFile?.mkdirs()
                        target.writeText(originalContent, Charsets.UTF_8)
                    }

                    filesMeta.add(BackupFileMeta(relPath, task.operation, existed))
                }

                val meta = JSONObject().apply {
                    put("id", backupId)
                    put("runId", runId)
                    put("timestamp", System.currentTimeMillis())
                    put("userPrompt", userPrompt)
                    put("mode", mode.name)
                    put("isRestored", false)
                    val arr = JSONArray()
                    filesMeta.forEach { m ->
                        arr.put(JSONObject().apply {
                            put("path", m.path)
                            put("operation", m.operation.name)
                            put("hadOriginalContent", m.hadOriginalContent)
                        })
                    }
                    put("files", arr)
                }

                File(backupDir, "metadata.json").writeText(meta.toString(), Charsets.UTF_8)
                cleanOldBackups()
                Result.success(backupId)
            } catch (e: Exception) {
                backupDir.deleteRecursively()
                Result.failure(e)
            }
        }
    }

    suspend fun recordFinalFiles(backupId: String, finalFiles: Map<String, String>): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val backupDir = File(baseBackupDir, backupId)
                if (!backupDir.exists()) return@withContext Result.failure(Exception("Бэкап не найден"))
                val modDir = File(backupDir, "modified").apply { mkdirs() }
                finalFiles.forEach { (path, content) ->
                    val t = File(modDir, path.trim().removePrefix("/"))
                    t.parentFile?.mkdirs()
                    t.writeText(content, Charsets.UTF_8)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun rollback(backupId: String): Result<RollbackResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val backupDir = File(baseBackupDir, backupId)
            val metaFile = File(backupDir, "metadata.json")
            if (!metaFile.exists()) return@withContext Result.failure(Exception("Бэкап поврежден"))

            val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
            val mode = PipelineMode.valueOf(meta.optString("mode", PipelineMode.ONLINE.name))
            val filesArr = meta.getJSONArray("files")
            val originalsDir = File(backupDir, "originals")
            val branch = try { appSettings.gitHubConfig.first().branch } catch (_: Exception) { "main" }

            try {
                var restored = 0
                var rollbackSha: String? = null

                if (mode == PipelineMode.OFFLINE) {
                    localRepoManager.ensureCloned().getOrThrow()
                    for (i in 0 until filesArr.length()) {
                        val item = filesArr.getJSONObject(i)
                        val p = item.getString("path")
                        if (item.getBoolean("hadOriginalContent")) {
                            val f = File(originalsDir, p)
                            if (f.exists()) {
                                localRepoManager.writeFile(p, f.readText(Charsets.UTF_8)).getOrThrow()
                                restored++
                            }
                        } else {
                            if (localRepoManager.fileExists(p)) {
                                localRepoManager.deleteFile(p)
                                restored++
                            }
                        }
                    }
                    val commitRes = localRepoManager.stageAndCommit("[Rollback] #$backupId")
                    if (commitRes.isSuccess) {
                        rollbackSha = commitRes.getOrNull()
                        localRepoManager.push().getOrThrow()
                    }
                } else {
                    for (i in 0 until filesArr.length()) {
                        val item = filesArr.getJSONObject(i)
                        val p = item.getString("path")
                        val had = item.getBoolean("hadOriginalContent")
                        val cur = gitHubClient.getFileContent(p, branch).getOrNull()

                        if (had) {
                            val f = File(originalsDir, p)
                            if (f.exists()) {
                                val res = gitHubClient.createOrUpdateFile(
                                    path = p,
                                    content = f.readText(Charsets.UTF_8),
                                    message = "[Rollback] $p",
                                    sha = cur?.sha,
                                    branch = branch
                                )
                                if (res.isSuccess) {
                                    restored++
                                    rollbackSha = res.getOrNull()?.content?.sha
                                }
                            }
                        } else if (cur?.sha != null) {
                            gitHubClient.deleteFile(p, "[Rollback] remove $p", cur.sha, branch)
                            restored++
                        }
                    }
                }

                meta.put("isRestored", true)
                metaFile.writeText(meta.toString(), Charsets.UTF_8)
                repoIndexManager.invalidate()
                Result.success(RollbackResult(backupId, restored, rollbackSha))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun generateFullReportText(backupId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val backupDir = File(baseBackupDir, backupId)
            val metaFile = File(backupDir, "metadata.json")
            if (!metaFile.exists()) return@withContext Result.failure(Exception("Отчёт отсутствует"))

            val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
            val prompt = meta.getString("userPrompt")
            val filesArr = meta.getJSONArray("files")
            val originalsDir = File(backupDir, "originals")
            val modifiedDir = File(backupDir, "modified")

            val sb = StringBuilder()
            val div = "=".repeat(80)

            sb.appendLine(div)
            sb.appendLine("ПОЛНЫЙ АУДИТОРСКИЙ ОТЧЁТ PIPELINE")
            sb.appendLine("Дата: ${dateFormat.format(Date(meta.getLong("timestamp")))}")
            sb.appendLine("Режим: ${meta.optString("mode")}")
            sb.appendLine(div).appendLine()

            sb.appendLine("1. ТОЧНЫЙ ПРОМПТ ПОЛЬЗОВАТЕЛЯ:\n$prompt\n")

            sb.appendLine(div)
            sb.appendLine("2. ИСХОДНЫЕ ФАЙЛЫ (ДО ИЗМЕНЕНИЙ):")
            sb.appendLine(div)
            for (i in 0 until filesArr.length()) {
                val item = filesArr.getJSONObject(i)
                val p = item.getString("path")
                sb.appendLine("--- FILE: $p ---")
                val f = File(originalsDir, p)
                if (f.exists()) sb.appendLine(f.readText(Charsets.UTF_8))
                else sb.appendLine("[Файл отсутствовал до запуска]")
                sb.appendLine()
            }

            sb.appendLine(div)
            sb.appendLine("3. ОТРЕДАКТИРОВАННЫЕ ФАЙЛЫ (ПОСЛЕ ИЗМЕНЕНИЙ):")
            sb.appendLine(div)
            for (i in 0 until filesArr.length()) {
                val item = filesArr.getJSONObject(i)
                val p = item.getString("path")
                val op = item.optString("operation")
                sb.appendLine("--- FILE: $p [$op] ---")
                val f = File(modifiedDir, p)
                if (f.exists()) sb.appendLine(f.readText(Charsets.UTF_8))
                else sb.appendLine(if (op == "DELETE") "[Файл удалён]" else "[Без изменений]")
                sb.appendLine()
            }

            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val list = mutableListOf<BackupEntry>()
        val dirs = baseBackupDir.listFiles { f -> f.isDirectory } ?: return@withContext emptyList()
        for (dir in dirs) {
            try {
                val mf = File(dir, "metadata.json")
                if (!mf.exists()) continue
                val json = JSONObject(mf.readText(Charsets.UTF_8))
                val fa = json.getJSONArray("files")
                val files = (0 until fa.length()).map { idx ->
                    val o = fa.getJSONObject(idx)
                    BackupFileMeta(
                        path = o.getString("path"),
                        operation = TaskOperation.valueOf(o.getString("operation")),
                        hadOriginalContent = o.getBoolean("hadOriginalContent")
                    )
                }
                list.add(
                    BackupEntry(
                        id = json.getString("id"),
                        runId = json.optString("runId"),
                        timestamp = json.getLong("timestamp"),
                        userPrompt = json.getString("userPrompt"),
                        mode = PipelineMode.valueOf(json.optString("mode", "ONLINE")),
                        files = files,
                        isRestored = json.optBoolean("isRestored")
                    )
                )
            } catch (_: Exception) {}
        }
        list.sortedByDescending { it.timestamp }
    }

    suspend fun deleteBackup(backupId: String): Boolean = withContext(Dispatchers.IO) {
        val d = File(baseBackupDir, backupId)
        if (d.exists()) d.deleteRecursively() else false
    }

    fun shareReportFile(context: Context, reportContent: String, title: String = "Pipeline_Report") {
        try {
            val file = File(context.cacheDir, "${title}_${System.currentTimeMillis()}.txt").apply {
                writeText(reportContent, Charsets.UTF_8)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // КРИТИЧЕСКИЙ ФИКС: Флаг FLAG_ACTIVITY_NEW_TASK добавляется на сам Chooser,
            // что полностью предотвращает вылет AndroidRuntimeException при вызове с ApplicationContext
            val chooser = Intent.createChooser(intent, "Сохранить отчет").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вызова Share Sheet", e)
        }
    }

    private fun cleanOldBackups() {
        val dirs = baseBackupDir.listFiles { f -> f.isDirectory } ?: return
        if (dirs.size > MAX_BACKUPS_TO_KEEP) {
            dirs.sortedBy { it.lastModified() }
                .take(dirs.size - MAX_BACKUPS_TO_KEEP)
                .forEach { it.deleteRecursively() }
        }
    }
}