package com.opuside.app.core.util

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ✅ ОЧИЩЕНО: ConfigImporter - импорт настроек из TXT файла
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
 */
object ConfigImporter {
    
    private const val TAG = "ConfigImporter"
    
    data class ImportedConfig(
        // GitHub
        val githubOwner: String? = null,
        val githubRepo: String? = null,
        val githubBranch: String? = null,
        val githubToken: String? = null,
        
    ) {
        val isGitHubComplete: Boolean
            get() = !githubOwner.isNullOrBlank() && 
                    !githubRepo.isNullOrBlank() && 
                    !githubToken.isNullOrBlank()
        
        fun toSummary(): String {
            val parts = mutableListOf<String>()
            
            if (isGitHubComplete) {
                parts.add("✅ GitHub: $githubOwner/$githubRepo")
            } else {
                parts.add("⚠️ GitHub: Incomplete")
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
            githubToken = config["github.token"]
        )
    }
    
    /**
     * Экспортирует текущую конфигурацию в строку
     */
    fun exportConfig(
        githubOwner: String,
        githubRepo: String,
        githubBranch: String,
        githubToken: String
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
        }
    }
}
