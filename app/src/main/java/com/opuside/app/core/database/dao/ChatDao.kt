package com.opuside.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с историей чата.
 * 
 * Поддерживает:
 * - Сессии чата
 * - Streaming обновления
 * - Статистика использования токенов
 */
@Dao
interface ChatDao {

    // ═══════════════════════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Получить все сообщения сессии (reactive).
     * Сортировка по времени (старые первые).
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun observeSession(sessionId: String): Flow<List<ChatMessageEntity>>

    /**
     * Получить все сообщения сессии (one-shot).
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun getSession(sessionId: String): List<ChatMessageEntity>

    /**
     * Получить последнее сообщение сессии.
     */
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLastMessage(sessionId: String): ChatMessageEntity?

    /**
     * Получить сообщение по ID.
     */
    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: Long): ChatMessageEntity?

    /**
     * Получить все уникальные сессии.
     */
    @Query("SELECT DISTINCT session_id FROM chat_messages ORDER BY created_at DESC")
    fun observeSessions(): Flow<List<String>>

    /**
     * Получить количество сообщений в сессии.
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_id = :sessionId")
    suspend fun getSessionMessageCount(sessionId: String): Int

    /**
     * Получить общее количество использованных токенов в сессии.
     */
    @Query("SELECT COALESCE(SUM(tokens_used), 0) FROM chat_messages WHERE session_id = :sessionId")
    suspend fun getSessionTokensUsed(sessionId: String): Int

    /**
     * Проверить, есть ли активный streaming в сессии.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE session_id = :sessionId AND is_streaming = 1)")
    suspend fun hasActiveStreaming(sessionId: String): Boolean

    /**
     * Получить последние N сообщений для контекста.
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE session_id = :sessionId 
        ORDER BY created_at DESC 
        LIMIT :limit
    """)
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessageEntity>

    // ═══════════════════════════════════════════════════════════════════════════
    // INSERT / UPDATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Добавить сообщение.
     * @return ID вставленного сообщения
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    /**
     * Добавить несколько сообщений.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    /**
     * Обновить сообщение (например, после завершения streaming).
     */
    @Update
    suspend fun update(message: ChatMessageEntity)

    /**
     * Обновить контент сообщения (для streaming).
     */
    @Query("UPDATE chat_messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    /**
     * Завершить streaming для сообщения.
     */
    @Query("UPDATE chat_messages SET is_streaming = 0, content = :finalContent, tokens_used = :tokensUsed WHERE id = :id")
    suspend fun finishStreaming(id: Long, finalContent: String, tokensUsed: Int?)

    /**
     * Отметить сообщение как ошибочное.
     */
    @Query("UPDATE chat_messages SET is_streaming = 0, error_message = :errorMessage WHERE id = :id")
    suspend fun markAsError(id: Long, errorMessage: String)

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Удалить сообщение.
     */
    @Delete
    suspend fun delete(message: ChatMessageEntity)

    /**
     * Удалить сообщение по ID.
     */
    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Очистить сессию.
     */
    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun clearSession(sessionId: String)

    /**
     * Очистить всю историю.
     */
    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()

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
    suspend fun trimOldSessions(keepSessions: Int)
}
