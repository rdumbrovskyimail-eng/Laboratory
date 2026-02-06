package com.opuside.app.core.util

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ✅ НОВОЕ: ConfigImporter - импорт настроек из TXT файла
 * 
 * Формат файла:
 * ─────────────
 * [GitHub]
 * owner=username
 * repository=repo-name
 * branch=main
 * token=ghp_xxxx
 * 
 * [Claude]
 * api_key=sk-ant-api03-xxxx
 * model=claude-sonnet-4-5-20250929
 * 
 * [Cache]
 * timeout_minutes=5
 * max_files=20
 * auto_clear=true
 */
object ConfigImporter {
    
    private const val TAG = "ConfigImporter"
    
    data class ImportedConfig(
        // GitHub
        val githubOwner: String? = null,
        val githubRepo: String? = null,
        val githubBranch: String? = null,
        val githubToken: String? = null,
        
        // Claude
        val claudeApiKey: String? = null,
        val claudeModel: String? = null,
        
        // Cache
        val cacheTimeout: Int? = null,
        val maxCacheFiles: Int? = null,
        val autoClearCache: Boolean? = null
    ) {
        val isGitHubComplete: Boolean
            get() = !githubOwner.isNullOrBlank() && 
                    !githubRepo.isNullOrBlank() && 
                    !githubToken.isNullOrBlank()
        
        val isClaudeComplete: Boolean
            get() = !claudeApiKey.isNullOrBlank()
        
        fun toSummary(): String {
            val parts = mutableListOf<String>()
            
            if (isGitHubComplete) {
                parts.add("✅ GitHub: $githubOwner/$githubRepo")
            } else {
                parts.add("⚠️ GitHub: Incomplete")
            }
            
            if (isClaudeComplete) {
                parts.add("✅ Claude API: Configured")
            } else {
                parts.add("⚠️ Claude API: Missing")
            }
            
            if (cacheTimeout != null) {
                parts.add("✅ Cache: $cacheTimeout min, max $maxCacheFiles files")
            }
            
            return parts.joinToString("\n")
        }
    }
    
    /**
     * Импортирует конфигурацию из файла
     */
    fun importConfig(context: Context, fileUri: Uri): Result<ImportedConfig> {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "📥 IMPORTING CONFIG FROM FILE")
        android.util.Log.d(TAG, "   URI: $fileUri")
        android.util.Log.d(TAG, "━".repeat(80))
        
        return try {
            val inputStream = context.contentResolver.openInputStream(fileUri)
                ?: return Result.failure(Exception("Cannot open file"))
            
            val config = BufferedReader(InputStreamReader(inputStream)).use { reader ->
                parseConfig(reader.readText())
            }
            
            android.util.Log.d(TAG, "")
            android.util.Log.d(TAG, "📊 PARSED CONFIGURATION:")
            android.util.Log.d(TAG, "   GitHub Owner: ${config.githubOwner ?: "[MISSING]"}")
            android.util.Log.d(TAG, "   GitHub Repo: ${config.githubRepo ?: "[MISSING]"}")
            android.util.Log.d(TAG, "   GitHub Branch: ${config.githubBranch ?: "[MISSING]"}")
            android.util.Log.d(TAG, "   GitHub Token: ${if (config.githubToken != null) "[${config.githubToken.take(10)}...]" else "[MISSING]"}")
            android.util.Log.d(TAG, "   Claude API: ${if (config.claudeApiKey != null) "[${config.claudeApiKey.take(10)}...]" else "[MISSING]"}")
            android.util.Log.d(TAG, "   Claude Model: ${config.claudeModel ?: "[MISSING]"}")
            android.util.Log.d(TAG, "   Cache Timeout: ${config.cacheTimeout ?: "[MISSING]"}")
            android.util.Log.d(TAG, "   Max Files: ${config.maxCacheFiles ?: "[MISSING]"}")
            android.util.Log.d(TAG, "   Auto Clear: ${config.autoClearCache ?: "[MISSING]"}")
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "✅ IMPORT SUCCESSFUL")
            android.util.Log.d(TAG, "━".repeat(80))
            
            Result.success(config)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ IMPORT FAILED", e)
            android.util.Log.e(TAG, "━".repeat(80))
            Result.failure(e)
        }
    }
    
    /**
     * Парсит текст конфигурационного файла
     */
    private fun parseConfig(content: String): ImportedConfig {
        val lines = content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        
        var currentSection = ""
        val config = mutableMapOf<String, String>()
        
        for (line in lines) {
            when {
                // Секция
                line.startsWith("[") && line.endsWith("]") -> {
                    currentSection = line.substring(1, line.length - 1).lowercase()
                }
                // Параметр
                line.contains("=") -> {
                    val (key, value) = line.split("=", limit = 2)
                    val fullKey = "${currentSection}.${key.trim()}"
                    config[fullKey] = value.trim()
                }
            }
        }
        
        return ImportedConfig(
            // GitHub
            githubOwner = config["github.owner"],
            githubRepo = config["github.repository"],
            githubBranch = config["github.branch"] ?: "main",
            githubToken = config["github.token"],
            
            // Claude
            claudeApiKey = config["claude.api_key"],
            claudeModel = config["claude.model"] ?: "claude-sonnet-4-5-20250929",
            
            // Cache
            cacheTimeout = config["cache.timeout_minutes"]?.toIntOrNull() ?: 5,
            maxCacheFiles = config["cache.max_files"]?.toIntOrNull() ?: 20,
            autoClearCache = config["cache.auto_clear"]?.toBoolean() ?: true
        )
    }
    
    /**
     * Экспортирует текущую конфигурацию в строку
     */
    fun exportConfig(
        githubOwner: String,
        githubRepo: String,
        githubBranch: String,
        githubToken: String,
        claudeApiKey: String,
        claudeModel: String,
        cacheTimeout: Int,
        maxFiles: Int,
        autoClear: Boolean
    ): String {
        return buildString {
            appendLine("# OpusIDE Configuration File")
            appendLine("# Save this file securely and DO NOT share it publicly")
            appendLine()
            appendLine("[GitHub]")
            appendLine("owner=$githubOwner")
            appendLine("repository=$githubRepo")
            appendLine("branch=$githubBranch")
            appendLine("token=$githubToken")
            appendLine()
            appendLine("[Claude]")
            appendLine("api_key=$claudeApiKey")
            appendLine("model=$claudeModel")
            appendLine()
            appendLine("[Cache]")
            appendLine("timeout_minutes=$cacheTimeout")
            appendLine("max_files=$maxFiles")
            appendLine("auto_clear=$autoClear")
        }
    }
}
