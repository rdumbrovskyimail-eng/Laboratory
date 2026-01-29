package com.opuside.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Entity для кешированных файлов.
 * 
 * Хранит файлы, выбранные пользователем для анализа в Окне 2.
 * Таймер 5 минут отсчитывается от [addedAt].
 */
@Entity(tableName = "cached_files")
data class CachedFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "file_path")
    val filePath: String,
    
    @ColumnInfo(name = "file_name")
    val fileName: String,
    
    @ColumnInfo(name = "content")
    val content: String,
    
    @ColumnInfo(name = "language")
    val language: String, // kotlin, java, xml, json, etc.
    
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Int,
    
    @ColumnInfo(name = "added_at")
    val addedAt: Instant = Clock.System.now(),
    
    @ColumnInfo(name = "repo_owner")
    val repoOwner: String,
    
    @ColumnInfo(name = "repo_name")
    val repoName: String,
    
    @ColumnInfo(name = "branch")
    val branch: String = "main",
    
    @ColumnInfo(name = "sha")
    val sha: String? = null // Git SHA для версионирования
) {
    /**
     * Проверяет, истёк ли кеш для файла.
     * @param timeoutMs таймаут в миллисекундах (по умолчанию 5 минут)
     */
    fun isExpired(timeoutMs: Long = 5 * 60 * 1000L): Boolean {
        val now = Clock.System.now()
        return (now.toEpochMilliseconds() - addedAt.toEpochMilliseconds()) > timeoutMs
    }
}
