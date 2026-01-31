package com.opuside.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opuside.app.core.database.entity.CachedFileEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с кешированными файлами.
 * 
 * Поддерживает:
 * - CRUD операции
 * - Reactive Flow для UI
 * - Очистка по таймауту
 * 
 * ⚠️ ВАЖНО (Проблема №18): Метод search() не имеет встроенного debounce.
 * При использовании в UI (например, SearchBar) необходимо добавить debounce
 * в ViewModel, чтобы избежать избыточных запросов к БД на каждое нажатие клавиши.
 * 
 * Пример использования с debounce в ViewModel:
 * ```
 * val searchQuery = MutableStateFlow("")
 * val searchResults = searchQuery
 *     .debounce(300)  // Ждем 300ms после последнего символа
 *     .flatMapLatest { query ->
 *         if (query.isBlank()) flowOf(emptyList())
 *         else flow { emit(cacheDao.search(query)) }
 *     }
 *     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 * ```
 */
@Dao
interface CacheDao {

    // ═══════════════════════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Получить все кешированные файлы (reactive).
     * Сортировка по времени добавления (новые первые).
     */
    @Query("SELECT * FROM cached_files ORDER BY added_at DESC")
    fun observeAll(): Flow<List<CachedFileEntity>>

    /**
     * Получить все кешированные файлы (one-shot).
     */
    @Query("SELECT * FROM cached_files ORDER BY added_at DESC")
    suspend fun getAll(): List<CachedFileEntity>

    /**
     * Получить файл по пути.
     */
    @Query("SELECT * FROM cached_files WHERE file_path = :filePath")
    suspend fun getByPath(filePath: String): CachedFileEntity?

    /**
     * Получить количество файлов в кеше.
     */
    @Query("SELECT COUNT(*) FROM cached_files")
    fun observeCount(): Flow<Int>

    /**
     * Получить количество файлов в кеше (one-shot).
     */
    @Query("SELECT COUNT(*) FROM cached_files")
    suspend fun getCount(): Int

    /**
     * Получить общий размер кеша в байтах.
     */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM cached_files")
    suspend fun getTotalSize(): Long

    /**
     * Найти файлы по имени (поиск).
     * 
     * ⚠️ ВАЖНО: Используйте с debounce в ViewModel, чтобы избежать
     * избыточных запросов при вводе в поле поиска (см. документацию класса).
     * 
     * ✅ ИСПРАВЛЕНО (Проблема #7): 
     * - Добавлены wildcards '%' для поиска подстроки (MainActivity найдется по "Main")
     * - Используется ESCAPE '\' для экранирования спецсимволов %, _, \
     * - Параметризованный запрос защищает от SQL injection
     * 
     * ВАЖНО: ViewModel должен экранировать пользовательский ввод ПЕРЕД вызовом:
     * ```
     * fun escapeSearchQuery(userInput: String): String {
     *     return userInput
     *         .replace("\\", "\\\\")  // Экранируем backslash
     *         .replace("%", "\\%")    // Экранируем wildcard
     *         .replace("_", "\\_")    // Экранируем single char wildcard
     * }
     * ```
     */
    @Query("SELECT * FROM cached_files WHERE file_name LIKE '%' || :query || '%' ESCAPE '\\'")
    suspend fun search(query: String): List<CachedFileEntity>

    /**
     * Получить файлы, добавленные после указанного времени.
     */
    @Query("SELECT * FROM cached_files WHERE added_at >= :afterTimestamp ORDER BY added_at DESC")
    suspend fun getAddedAfter(afterTimestamp: Long): List<CachedFileEntity>

    // ═══════════════════════════════════════════════════════════════════════════
    // INSERT / UPDATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Добавить файл в кеш.
     * При конфликте (файл уже есть) — заменить.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: CachedFileEntity)

    /**
     * Добавить несколько файлов в кеш.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<CachedFileEntity>)

    /**
     * Обновить файл в кеше.
     */
    @Update
    suspend fun update(file: CachedFileEntity)

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Удалить файл из кеша.
     */
    @Delete
    suspend fun delete(file: CachedFileEntity)

    /**
     * Удалить файл по пути.
     */
    @Query("DELETE FROM cached_files WHERE file_path = :filePath")
    suspend fun deleteByPath(filePath: String)

    /**
     * Очистить весь кеш.
     */
    @Query("DELETE FROM cached_files")
    suspend fun clearAll()

    /**
     * Удалить файлы старше указанного времени.
     * Используется для очистки по таймауту 5 минут.
     */
    @Query("DELETE FROM cached_files WHERE added_at < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    /**
     * Удалить самые старые файлы, если превышен лимит.
     * Оставляет только [keepCount] самых новых файлов.
     */
    @Query("""
        DELETE FROM cached_files 
        WHERE file_path NOT IN (
            SELECT file_path FROM cached_files 
            ORDER BY added_at DESC 
            LIMIT :keepCount
        )
    """)
    suspend fun trimToSize(keepCount: Int)
}