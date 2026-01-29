package com.opuside.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Entity для сообщений чата с Claude.
 * 
 * Хранит историю переписки для возможности восстановления контекста.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    
    @ColumnInfo(name = "session_id")
    val sessionId: String, // UUID сессии чата
    
    @ColumnInfo(name = "role")
    val role: MessageRole,
    
    @ColumnInfo(name = "content")
    val content: String,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Clock.System.now(),
    
    @ColumnInfo(name = "tokens_used")
    val tokensUsed: Int? = null, // Для tracking использования API
    
    @ColumnInfo(name = "model")
    val model: String? = null, // claude-opus-4-5-20251101
    
    @ColumnInfo(name = "is_streaming")
    val isStreaming: Boolean = false, // true пока идёт streaming
    
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null, // Если запрос упал
    
    @ColumnInfo(name = "cached_files_context")
    val cachedFilesContext: List<String>? = null // Пути файлов из кеша, которые были в контексте
)

/**
 * Роль отправителя сообщения.
 */
enum class MessageRole {
    USER,      // Сообщение от пользователя
    ASSISTANT, // Ответ от Claude
    SYSTEM     // Системное сообщение (ошибки, уведомления)
}
