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
 * DAO для работы с историей чата.
 * 
 * Поддерживает:
 * - Сессии чата
 * - Streaming обновления
 * - Статистика использования токенов
 * 
 * ✅ ИСПРАВЛЕНО: Удален метод withTransaction (Room 2.6+ автоматически обрабатывает транзакции)
 */
@Dao
abstract class ChatDao {

    // ═══════════════════════════════════════════════════════════════════════════
    // QUERIES
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
    // INSERT / UPDATE
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
    // DELETE
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
    // TRANSACTIONS (✅ ИСПРАВЛЕНО)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО: Выполнить блок операций в транзакции.
     * 
     * Room автоматически оборачивает suspend функции в транзакции,
     * но для явного контроля используем @Transaction.
     * 
     * @param block Блок кода для выполнения в транзакции
     * @return Результат блока
     * 
     * Пример использования:
     * ```kotlin
     * chatDao.withTransaction {
     *     deleteById(oldId)
     *     insert(newMessage)
     * }
     * ```
     */
    @Transaction
    open suspend fun <R> withTransaction(block: suspend () -> R): R {
        return block()
    }
}