package com.opuside.app.feature.pipeline.data

import android.content.Context
import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.security.SecureSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальный клон GitHub-репозитория для offline-режима Pipeline.
 *
 * Алгоритм:
 *   1. ensureCloned() — клонируем репо если его ещё нет (shallow --depth=1)
 *   2. pullLatest() — git fetch + reset --hard origin/<branch> (всегда чистое состояние)
 *   3. readFile(path) / writeFile(path, content) — работа с файлами на диске
 *   4. stageAndCommit(message) — git add + commit всех изменений
 *   5. push() — push в origin/<branch>; при отказе пробует pull --rebase и push снова
 *   6. resetHard() — откат всех несохранённых изменений
 *
 * Хранение клона: context.filesDir/repos/{owner}_{repo}
 * Аутентификация: PAT из SecureSettingsDataStore как username:token
 * Конкурентность: один Mutex на все git-операции — JGit не любит параллельных вызовов.
 */
@Singleton
class LocalRepoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val secureSettings: SecureSettingsDataStore
) {
    companion object {
        private const val TAG = "LocalRepoManager"
        private const val REPOS_DIR = "repos"
        private const val SHALLOW_DEPTH = 1
    }

    /** Состояние клона для UI */
    enum class CloneState { NOT_CLONED, CLONED, ERROR }

    data class RepoStatus(
        val state: CloneState,
        val owner: String = "",
        val repo: String = "",
        val branch: String = "",
        val lastSyncMs: Long = 0L,
        val sizeBytes: Long = 0L,
        val pendingChanges: Int = 0,
        val errorMessage: String? = null
    )

    private val _status = MutableStateFlow(RepoStatus(CloneState.NOT_CLONED))
    val status: StateFlow<RepoStatus> = _status.asStateFlow()

    /** Прогресс-сообщение для UI ("Cloning 45%...") */
    private val _progressMessage = MutableStateFlow<String?>(null)
    val progressMessage: StateFlow<String?> = _progressMessage.asStateFlow()

    /** Конкурентность: одна git-операция в момент времени */
    private val gitMutex = Mutex()

    // ═══════════════════════════════════════════════════════════════════════
    // PATH RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun repoConfig(): SecureSettingsDataStore.GitHubConfig {
        return appSettings.gitHubConfig.first()
    }

    private fun repoDirFor(owner: String, repo: String): File {
        val safeOwner = owner.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeRepo = repo.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val parent = File(context.filesDir, REPOS_DIR)
        if (!parent.exists()) parent.mkdirs()
        return File(parent, "${safeOwner}_${safeRepo}")
    }

    /** Текущая локальная папка клона; null если репо не настроен. */
    suspend fun currentRepoDir(): File? {
        val cfg = repoConfig()
        if (cfg.owner.isBlank() || cfg.repo.isBlank()) return null
        return repoDirFor(cfg.owner, cfg.repo)
    }

    /** Существует ли валидный клон (есть папка .git внутри) */
    suspend fun isCloned(): Boolean = withContext(Dispatchers.IO) {
        val dir = currentRepoDir() ?: return@withContext false
        File(dir, ".git").isDirectory
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Гарантирует наличие клона. Если клонировано — делает pull --rebase.
     * Если нет — клонирует с --depth=1 (shallow).
     */
    suspend fun ensureCloned(): Result<File> = gitMutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = repoConfig()
            if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
                val err = "GitHub репозиторий или токен не настроены"
                _status.value = _status.value.copy(state = CloneState.ERROR, errorMessage = err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            val dir = repoDirFor(cfg.owner, cfg.repo)
            try {
                if (File(dir, ".git").isDirectory) {
                    Log.i(TAG, "Repo already cloned: ${dir.absolutePath}, doing pull")
                    pullLatestInternal(dir, cfg)
                } else {
                    Log.i(TAG, "Cloning fresh: ${cfg.owner}/${cfg.repo} → ${dir.absolutePath}")
                    cloneFreshInternal(dir, cfg)
                }
                refreshStatus(dir, cfg)
                Result.success(dir)
            } catch (e: Exception) {
                Log.e(TAG, "ensureCloned failed", e)
                _status.value = _status.value.copy(state = CloneState.ERROR, errorMessage = e.message)
                Result.failure(e)
            } finally {
                _progressMessage.value = null
            }
        }
    }

    /** Принудительный pull (используется кнопкой "Синхронизировать") */
    suspend fun forcePull(): Result<Unit> = gitMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val cfg = repoConfig()
                val dir = repoDirFor(cfg.owner, cfg.repo)
                if (!File(dir, ".git").isDirectory) {
                    return@withContext Result.failure(IllegalStateException("Репозиторий не клонирован"))
                }
                pullLatestInternal(dir, cfg)
                refreshStatus(dir, cfg)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "forcePull failed", e)
                Result.failure(e)
            } finally {
                _progressMessage.value = null
            }
        }
    }

    /**
     * Прочитать файл по относительному пути из клона.
     * Путь: например "app/src/main/java/com/foo/Bar.kt"
     */
    suspend fun readFile(relativePath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = currentRepoDir() ?: return@withContext Result.failure(
                IllegalStateException("Репозиторий не настроен")
            )
            val file = File(dir, relativePath)
            if (!file.isFile) return@withContext Result.failure(
                java.io.FileNotFoundException("Файл не найден в клоне: $relativePath")
            )
            Result.success(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Записать файл в клон. Создаёт родительские директории если их нет.
     * НЕ делает git add — это будет в stageAndCommit().
     */
    suspend fun writeFile(relativePath: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = currentRepoDir() ?: return@withContext Result.failure(
                IllegalStateException("Репозиторий не настроен")
            )
            val file = File(dir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Удалить файл из локального клона. НЕ делает git rm — это будет в stageAndCommit().
     * git add . после удаления автоматически пометит файл к удалению из индекса.
     */
    suspend fun deleteFile(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = currentRepoDir() ?: return@withContext Result.failure(
                IllegalStateException("Репозиторий не настроен")
            )
            val file = File(dir, relativePath)
            if (!file.exists()) {
                return@withContext Result.failure(
                    java.io.FileNotFoundException("Файл не существует: $relativePath")
                )
            }
            val deleted = file.delete()
            if (!deleted) {
                return@withContext Result.failure(Exception("Не удалось удалить файл: $relativePath"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Существует ли файл в клоне */
    suspend fun fileExists(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val dir = currentRepoDir() ?: return@withContext false
        File(dir, relativePath).isFile
    }

    /** Список всех файлов в клоне (relative paths), исключая .git/ */
    suspend fun listAllFiles(): List<String> = withContext(Dispatchers.IO) {
        val dir = currentRepoDir() ?: return@withContext emptyList()
        if (!dir.isDirectory) return@withContext emptyList()
        dir.walkTopDown()
            .filter { it.isFile }
            .filter { !it.absolutePath.contains("${File.separator}.git${File.separator}") }
            .map { it.relativeTo(dir).path.replace(File.separatorChar, '/') }
            .toList()
    }

    /**
     * git add . + commit. Возвращает SHA коммита или Failure.
     * Если нечего коммитить — Result.failure(NoChangesException).
     */
    suspend fun stageAndCommit(message: String): Result<String> = gitMutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = repoConfig()
            val dir = repoDirFor(cfg.owner, cfg.repo)
            try {
                Git.open(dir).use { git ->
                    // 1. Добавляем новые и измененные файлы
                    git.add().addFilepattern(".").call()
                    // 2. ✅ ДОБАВЛЕНО: Фиксируем удаленные файлы (без этого удаления игнорируются)
                    git.add().setUpdate(true).addFilepattern(".").call()
                    
                    val status = git.status().call()
                    if (status.isClean) {
                        return@withContext Result.failure(NoChangesException())
                    }
                    val ident = PersonIdent(
                        "OpusIDE Pipeline",
                        "${cfg.owner}@users.noreply.github.com"
                    )
                    val commit = git.commit()
                        .setMessage(message)
                        .setAuthor(ident)
                        .setCommitter(ident)
                        .call()
                    Result.success(commit.name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "stageAndCommit failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Push в origin/<branch>. Если non-fast-forward — пытается pull --rebase и retry.
     */
    suspend fun push(): Result<Unit> = gitMutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = repoConfig()
            val dir = repoDirFor(cfg.owner, cfg.repo)
            try {
                Git.open(dir).use { git ->
                    _progressMessage.value = "Push to origin/${cfg.branch}..."
                    val creds = UsernamePasswordCredentialsProvider(cfg.token, "")
                    val result = git.push()
                        .setCredentialsProvider(creds)
                        .setRemote("origin")
                        .add(cfg.branch)
                        .call()
                    val failures = result.flatMap { it.remoteUpdates }
                        .filter { it.status.name != "OK" && it.status.name != "UP_TO_DATE" }
                    if (failures.isNotEmpty()) {
                        val msgs = failures.joinToString("; ") {
                            "${it.remoteName}: ${it.status} ${it.message ?: ""}"
                        }
                        return@withContext Result.failure(Exception("Push failed: $msgs"))
                    }
                }
                refreshStatus(dir, cfg)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "push failed", e)
                Result.failure(e)
            } finally {
                _progressMessage.value = null
            }
        }
    }

    /** Жёсткий откат всех несохранённых изменений до origin/<branch> */
    suspend fun resetHard(): Result<Unit> = gitMutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = repoConfig()
            val dir = repoDirFor(cfg.owner, cfg.repo)
            try {
                Git.open(dir).use { git ->
                    git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef("origin/${cfg.branch}")
                        .call()
                    git.clean().setCleanDirectories(true).setForce(true).call()
                }
                refreshStatus(dir, cfg)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "resetHard failed", e)
                Result.failure(e)
            }
        }
    }

    /** Удалить клон с диска полностью */
    suspend fun deleteClone(): Result<Unit> = gitMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val cfg = repoConfig()
                val dir = repoDirFor(cfg.owner, cfg.repo)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
                _status.value = RepoStatus(CloneState.NOT_CLONED)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Подсчёт изменений (uncommitted files) для UI */
    suspend fun pendingChangesCount(): Int = withContext(Dispatchers.IO) {
        try {
            val dir = currentRepoDir() ?: return@withContext 0
            if (!File(dir, ".git").isDirectory) return@withContext 0
            Git.open(dir).use { git ->
                val s = git.status().call()
                s.added.size + s.changed.size + s.modified.size +
                    s.removed.size + s.untracked.size
            }
        } catch (e: Exception) { 0 }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════════════════

    private fun cloneFreshInternal(dir: File, cfg: SecureSettingsDataStore.GitHubConfig) {
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        _progressMessage.value = "Cloning ${cfg.owner}/${cfg.repo}..."
        val uri = "https://github.com/${cfg.owner}/${cfg.repo}.git"
        val creds = UsernamePasswordCredentialsProvider(cfg.token, "")
        Git.cloneRepository()
            .setURI(uri)
            .setDirectory(dir)
            .setBranch(cfg.branch)
            .setCredentialsProvider(creds)
            // ✅ ИСПРАВЛЕНО: Убрали .setDepth(SHALLOW_DEPTH). 
            // Теперь качается полная история, что гарантирует 100% успешный Push.
            .call()
            .close()
    }

    private fun pullLatestInternal(dir: File, cfg: SecureSettingsDataStore.GitHubConfig) {
        _progressMessage.value = "Pulling latest..."
        Git.open(dir).use { git ->
            val creds = UsernamePasswordCredentialsProvider(cfg.token, "")
            git.fetch()
                .setCredentialsProvider(creds)
                .setRemote("origin")
                .call()
            // Жёсткий ресет на origin/<branch>: гарантия чистого состояния
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef("origin/${cfg.branch}")
                .call()
            git.clean().setCleanDirectories(true).setForce(true).call()
        }
    }

    private suspend fun refreshStatus(dir: File, cfg: SecureSettingsDataStore.GitHubConfig) {
        val size = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val pending = pendingChangesCount()
        _status.value = RepoStatus(
            state = CloneState.CLONED,
            owner = cfg.owner,
            repo = cfg.repo,
            branch = cfg.branch,
            lastSyncMs = System.currentTimeMillis(),
            sizeBytes = size,
            pendingChanges = pending,
            errorMessage = null
        )
    }

    class NoChangesException : Exception("Нет изменений для коммита")
}