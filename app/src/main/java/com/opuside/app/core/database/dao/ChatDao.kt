package com.opuside.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import kotlinx.coroutines.flow.Flow

/**
 * Chat DAO v2.1 (UPDATED)
 * 
 * ✅ НОВОЕ:
 * - Статистика сеанса
 * - Поддержка кеш-токенов
 * - Метрики производительности
 * 
 * ✅ СОХРАНЕНО:
 * - Все существующие методы
 * - Transaction для insertUserAndAssistantMessages
 */
@Dao
abstract class ChatDao {

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ СУЩЕСТВУЮЩИЕ QUERIES (без изменений)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Получить все сообщения сессии (reactive).
     * Сортировка по времени (старые первые).
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    abstract fun observeSession(sessionId: String): Flow<List<ChatMessageEntity>>

    /**
     * Получить все сообщения сессии (one-shot).
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    abstract suspend fun getSession(sessionId: String): List<ChatMessageEntity>

    /**
     * Получить последнее сообщение сессии.
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT 1")
    abstract suspend fun getLastMessage(sessionId: String): ChatMessageEntity?

    /**
     * Получить сообщение по ID.
     */
    @Query("SELECT * FROM chat_messages WHERE id = :id")
    abstract suspend fun getById(id: Long): ChatMessageEntity?

    /**
     * Получить все уникальные сессии.
     */
    @Query("SELECT DISTINCT session_id FROM chat_messages ORDER BY created_at DESC")
    abstract fun observeSessions(): Flow<List<String>>

    /**
     * Получить количество сообщений в сессии.
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_id = :sessionId")
    abstract suspend fun getSessionMessageCount(sessionId: String): Int

    /**
     * Получить общее количество использованных токенов в сессии.
     */
    @Query("SELECT COALESCE(SUM(tokens_used), 0) FROM chat_messages WHERE session_id = :sessionId")
    abstract suspend fun getSessionTokensUsed(sessionId: String): Int

    /**
     * Проверить, есть ли активный streaming в сессии.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE session_id = :sessionId AND is_streaming = 1)")
    abstract suspend fun hasActiveStreaming(sessionId: String): Boolean

    /**
     * Получить последние N сообщений для контекста.
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE session_id = :sessionId 
        ORDER BY created_at DESC 
        LIMIT :limit
    """)
    abstract suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessageEntity>

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ НОВЫЕ МЕТОДЫ для статистики сеанса
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ НОВОЕ: Получить статистику сеанса
     * Возвращает общее количество токенов и сообщений от ассистента
     */
    @Query("""
        SELECT 
            COALESCE(SUM(tokens_used), 0) as totalTokens,
            COUNT(*) as messageCount
        FROM chat_messages 
        WHERE session_id = :sessionId 
          AND role = 'ASSISTANT'
          AND is_streaming = 0
    """)
    abstract suspend fun getSessionStats(sessionId: String): SessionStats

    /**
     * ✅ НОВОЕ: Получить количество сообщений пользователя в сеансе
     */
    @Query("""
        SELECT COUNT(*) 
        FROM chat_messages 
        WHERE session_id = :sessionId 
          AND role = 'USER'
    """)
    abstract suspend fun getUserMessageCount(sessionId: String): Int

    /**
     * ✅ НОВОЕ: Получить все уникальные sessionId
     */
    @Query("SELECT DISTINCT session_id FROM chat_messages")
    abstract suspend fun getAllSessionIds(): List<String>

    /**
     * ✅ НОВОЕ: Удалить сообщения старше определенной даты
     */
    @Query("DELETE FROM chat_messages WHERE created_at < :timestamp")
    abstract suspend fun deleteOlderThan(timestamp: Long): Int

    /**
     * ✅ НОВОЕ: Получить размер сеанса в байтах (примерно)
     */
    @Query("""
        SELECT SUM(LENGTH(content)) 
        FROM chat_messages 
        WHERE session_id = :sessionId
    """)
    abstract suspend fun getSessionSizeBytes(sessionId: String): Long?

    /**
     * ✅ НОВОЕ: Получить суммарную статистику по всем полям
     */
    @Query("""
        SELECT 
            COALESCE(SUM(input_tokens), 0) as totalInputTokens,
            COALESCE(SUM(output_tokens), 0) as totalOutputTokens,
            COALESCE(SUM(cached_tokens), 0) as totalCachedTokens,
            COUNT(*) as totalMessages
        FROM chat_messages 
        WHERE session_id = :sessionId 
          AND role = 'ASSISTANT'
          AND is_streaming = 0
    """)
    abstract suspend fun getDetailedSessionStats(sessionId: String): DetailedSessionStats

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ СУЩЕСТВУЮЩИЕ INSERT / UPDATE (без изменений)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Добавить сообщение.
     * @return ID вставленного сообщения
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(message: ChatMessageEntity): Long

    /**
     * Добавить несколько сообщений.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(messages: List<ChatMessageEntity>)

    /**
     * Обновить сообщение (например, после завершения streaming).
     */
    @Update
    abstract suspend fun update(message: ChatMessageEntity)

    /**
     * Обновить контент сообщения (для streaming).
     */
    @Query("UPDATE chat_messages SET content = :content WHERE id = :id")
    abstract suspend fun updateContent(id: Long, content: String)

    /**
     * Завершить streaming для сообщения.
     */
    @Query("UPDATE chat_messages SET is_streaming = 0, content = :finalContent, tokens_used = :tokensUsed WHERE id = :id")
    abstract suspend fun finishStreaming(id: Long, finalContent: String, tokensUsed: Int?)

    /**
     * Отметить сообщение как ошибочное.
     */
    @Query("UPDATE chat_messages SET is_streaming = 0, error_message = :errorMessage WHERE id = :id")
    abstract suspend fun markAsError(id: Long, errorMessage: String)

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ СУЩЕСТВУЮЩИЕ DELETE (без изменений)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Удалить сообщение.
     */
    @Delete
    abstract suspend fun delete(message: ChatMessageEntity)

    /**
     * Удалить сообщение по ID.
     */
    @Query("DELETE FROM chat_messages WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    /**
     * Очистить сессию.
     */
    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    abstract suspend fun clearSession(sessionId: String)

    /**
     * Очистить всю историю.
     */
    @Query("DELETE FROM chat_messages")
    abstract suspend fun clearAll()

    /**
     * Удалить старые сессии (оставить последние N).
     */
    @Query("""
        DELETE FROM chat_messages 
        WHERE session_id NOT IN (
            SELECT DISTINCT session_id FROM chat_messages 
            GROUP BY session_id 
            ORDER BY MAX(created_at) DESC 
            LIMIT :keepSessions
        )
    """)
    abstract suspend fun trimOldSessions(keepSessions: Int)

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ СУЩЕСТВУЮЩИЕ TRANSACTIONS (без изменений)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ СОХРАНЕНО: Атомарная вставка пары сообщений (user + assistant).
     */
    @Transaction
    open suspend fun insertUserAndAssistantMessages(
        userMessage: ChatMessageEntity,
        assistantMessage: ChatMessageEntity
    ): Long {
        insert(userMessage)
        return insert(assistantMessage)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ✅ НОВЫЕ DATA CLASSES для статистики
// ═══════════════════════════════════════════════════════════════════════════

/**
 * ✅ НОВОЕ: Базовая статистика сеанса
 */
data class SessionStats(
    val totalTokens: Int,
    val messageCount: Int
) {
    val averageTokensPerMessage: Int
        get() = if (messageCount > 0) totalTokens / messageCount else 0
}

/**
 * ✅ НОВОЕ: Детальная статистика сеанса с разбивкой по типам токенов
 */
data class DetailedSessionStats(
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCachedTokens: Int,
    val totalMessages: Int
) {
    val totalTokens: Int
        get() = totalInputTokens + totalOutputTokens
    
    val cacheHitRate: Double
        get() = if (totalInputTokens > 0) {
            (totalCachedTokens.toDouble() / totalInputTokens) * 100
        } else 0.0
    
    val averageTokensPerMessage: Int
        get() = if (totalMessages > 0) totalTokens / totalMessages else 0
}