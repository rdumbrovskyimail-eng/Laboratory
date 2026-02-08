package com.opuside.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Chat Message Entity v2.1 (UPDATED)
 * 
 * ✅ НОВОЕ:
 * - Поддержка метаданных (model, cached tokens)
 * - Раздельные поля для input/output токенов
 * - Стоимость в USD/EUR
 * 
 * ✅ СОХРАНЕНО:
 * - Все существующие поля
 * - Совместимость с текущей схемой
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
    val tokensUsed: Int? = null, // ✅ СОХРАНЕНО: Для обратной совместимости (total tokens)
    
    @ColumnInfo(name = "model")
    val model: String? = null, // ✅ СОХРАНЕНО: claude-opus-4-6-20260115
    
    @ColumnInfo(name = "is_streaming")
    val isStreaming: Boolean = false, // ✅ СОХРАНЕНО: true пока идёт streaming
    
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null, // ✅ СОХРАНЕНО: Если запрос упал
    
    @ColumnInfo(name = "cached_files_context")
    val cachedFilesContext: List<String>? = null, // ✅ СОХРАНЕНО: Пути файлов из кеша
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ НОВЫЕ ПОЛЯ для метаданных v2.1
    // ═══════════════════════════════════════════════════════════════════════════
    
    @ColumnInfo(name = "model_used")
    val modelUsed: String? = null, // ✅ НОВОЕ: Какая модель использовалась (дублирует model для ясности)
    
    @ColumnInfo(name = "cached_tokens")
    val cachedTokens: Int? = null, // ✅ НОВОЕ: Сколько токенов было кешировано
    
    @ColumnInfo(name = "input_tokens")
    val inputTokens: Int? = null, // ✅ НОВОЕ: Input токены
    
    @ColumnInfo(name = "output_tokens")
    val outputTokens: Int? = null, // ✅ НОВОЕ: Output токены
    
    @ColumnInfo(name = "cost_usd")
    val costUSD: Double? = null, // ✅ НОВОЕ: Стоимость в USD
    
    @ColumnInfo(name = "cost_eur")
    val costEUR: Double? = null // ✅ НОВОЕ: Стоимость в EUR
) {
    /**
     * ✅ НОВОЕ: Вычисляемые свойства для удобства
     */
    val totalTokens: Int
        get() = (inputTokens ?: 0) + (outputTokens ?: 0)
    
    val hasCacheData: Boolean
        get() = cachedTokens != null && cachedTokens > 0
    
    val cacheEfficiency: Double
        get() = if (inputTokens != null && inputTokens > 0 && cachedTokens != null) {
            (cachedTokens.toDouble() / inputTokens) * 100
        } else 0.0
    
    /**
     * ✅ НОВОЕ: Проверка на завершенность сообщения
     */
    val isCompleted: Boolean
        get() = !isStreaming && errorMessage == null
    
    /**
     * ✅ НОВОЕ: Проверка на ошибку
     */
    val hasError: Boolean
        get() = errorMessage != null
}

/**
 * Роль отправителя сообщения.
 * 
 * ✅ СОХРАНЕНО: без изменений
 */
enum class MessageRole {
    USER,      // Сообщение от пользователя
    ASSISTANT, // Ответ от Claude
    SYSTEM     // Системное сообщение (ошибки, уведомления)
}