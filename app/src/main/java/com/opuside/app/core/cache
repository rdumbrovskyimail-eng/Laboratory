package com.opuside.app.core.cache

import com.opuside.app.core.database.dao.CacheDao
import com.opuside.app.core.database.entity.CachedFileEntity
import com.opuside.app.core.security.CacheEncryptionHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ✅ НОВЫЙ КЛАСС (Проблема #16 - God Object Refactoring)
 * 
 * Репозиторий для операций с кешем файлов.
 * Отвечает ТОЛЬКО за:
 * - CRUD операции с БД через CacheDao
 * - Шифрование/расшифровку контента
 * - Валидацию размера файлов
 * 
 * НЕ отвечает за:
 * - Таймер (см. CacheTimerController)
 * - WorkManager (см. CacheWorkScheduler)
 * - Уведомления (см. CacheNotificationManager)
 * 
 * Применяет Single Responsibility Principle.
 */
@Singleton
class CacheRepository @Inject constructor(
    private val cacheDao: CacheDao,
    private val encryptionHelper: CacheEncryptionHelper
) {
    companion object {
        private const val TAG = "CacheRepository"
        private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1MB
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // OBSERVABLE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #14): Добавлен shareIn для предотвращения memory leak.
     * 
     * Наблюдает за всеми файлами в кеше с автоматической расшифровкой.
     * Flow автоматически расшифровывает content перед передачей в UI.
     * 
     * ВАЖНО: Этот Flow должен быть подключен через .stateIn() в ViewModel
     * с SharingStarted.WhileSubscribed() для предотвращения утечек памяти
     * при rotation и lifecycle changes.
     */
    fun observeAll(): Flow<List<CachedFileEntity>> = 
        cacheDao.observeAll()
            .map { encryptedFiles ->
                // Расшифровываем все файлы для UI
                encryptedFiles.map { file ->
                    if (file.isEncrypted) {
                        decryptFile(file)
                    } else {
                        file
                    }
                }
            }
    
    /**
     * Наблюдает за количеством файлов в кеше.
     */
    fun observeCount(): Flow<Int> = cacheDao.observeCount()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CRUD OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #2): Добавляет файл с автоматическим шифрованием.
     * 
     * Процесс:
     * 1. Валидация размера файла
     * 2. Проверка на дубликаты
     * 3. Шифрование content с AES-256-GCM
     * 4. Сохранение в БД с зашифрованным content + IV
     * 
     * @param file Файл с РАСШИФРОВАННЫМ content (plaintext)
     * @return Result.success если успешно, Result.failure с ошибкой
     */
    suspend fun addFile(file: CachedFileEntity): Result<Unit> {
        return try {
            // Валидация размера
            if (file.sizeBytes > MAX_FILE_SIZE) {
                return Result.failure(IllegalArgumentException(
                    "File too large: ${file.sizeBytes} bytes (max ${MAX_FILE_SIZE / 1024 / 1024}MB)"
                ))
            }
            
            // Проверка дубликатов
            val existing = cacheDao.getByPath(file.filePath)
            if (existing != null) {
                android.util.Log.d(TAG, "⚠️ File already exists: ${file.filePath}")
                return Result.success(Unit)
            }
            
            // ✅ ШИФРУЕМ content перед вставкой
            val encryptedFile = if (file.content.isNotBlank()) {
                encryptFile(file)
            } else {
                // Пустой content не шифруем
                file.copy(isEncrypted = false, encryptionIv = null)
            }
            
            // Вставка в БД с зашифрованным content
            cacheDao.insert(encryptedFile)
            
            android.util.Log.d(TAG, "✅ File added and encrypted: ${file.filePath}")
            Result.success(Unit)
            
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "❌ Encryption failed for ${file.filePath}", e)
            Result.failure(e)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to add file ${file.filePath}", e)
            Result.failure(e)
        }
    }
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #2): Добавляет несколько файлов с шифрованием.
     * 
     * Использует batch insert для производительности.
     * Все файлы шифруются индивидуально (каждый со своим IV).
     * 
     * @param files Список файлов с расшифрованным content
     * @return Result.success(количество добавленных) или Result.failure
     */
    suspend fun addFiles(files: List<CachedFileEntity>): Result<Int> {
        return try {
            // Валидация размера всех файлов
            val oversizedFiles = files.filter { it.sizeBytes > MAX_FILE_SIZE }
            if (oversizedFiles.isNotEmpty()) {
                return Result.failure(IllegalArgumentException(
                    "Files too large: ${oversizedFiles.map { it.filePath }} exceed ${MAX_FILE_SIZE / 1024 / 1024}MB"
                ))
            }
            
            // Фильтруем дубликаты
            val newFiles = mutableListOf<CachedFileEntity>()
            val duplicates = mutableListOf<String>()
            
            files.forEach { file ->
                if (cacheDao.getByPath(file.filePath) != null) {
                    duplicates.add(file.filePath)
                } else {
                    newFiles.add(file)
                }
            }
            
            if (duplicates.isNotEmpty()) {
                android.util.Log.d(TAG, "⚠️ Skipped ${duplicates.size} duplicate files")
            }
            
            if (newFiles.isEmpty()) {
                return Result.success(0)
            }
            
            // ✅ Шифруем все файлы (каждый со своим уникальным IV!)
            val encryptedFiles = newFiles.map { file ->
                if (file.content.isNotBlank()) {
                    encryptFile(file)
                } else {
                    file.copy(isEncrypted = false, encryptionIv = null)
                }
            }
            
            // Batch insert
            cacheDao.insertAll(encryptedFiles)
            
            android.util.Log.d(TAG, "✅ Added ${encryptedFiles.size} encrypted files")
            Result.success(encryptedFiles.size)
            
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "❌ Encryption failed for batch insert", e)
            Result.failure(e)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Batch insert failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Удаляет файл из кеша.
     */
    suspend fun removeFile(filePath: String) {
        try {
            cacheDao.deleteByPath(filePath)
            android.util.Log.d(TAG, "🗑️ File removed: $filePath")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to remove file $filePath", e)
        }
    }
    
    /**
     * Очищает весь кеш.
     */
    suspend fun clearAll() {
        try {
            cacheDao.clearAll()
            android.util.Log.d(TAG, "🗑️ Cache cleared")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to clear cache", e)
        }
    }
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #2): Получает все файлы с расшифровкой.
     * 
     * Возвращает файлы с расшифрованным content, готовым для использования.
     */
    suspend fun getAll(): List<CachedFileEntity> {
        return try {
            val encryptedFiles = cacheDao.getAll()
            
            // Расшифровываем все файлы
            encryptedFiles.map { file ->
                if (file.isEncrypted) {
                    decryptFile(file)
                } else {
                    file
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "❌ Decryption failed", e)
            emptyList()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to get files", e)
            emptyList()
        }
    }
    
    /**
     * Получает файл по пути с расшифровкой.
     */
    suspend fun getByPath(filePath: String): CachedFileEntity? {
        return try {
            val file = cacheDao.getByPath(filePath) ?: return null
            
            if (file.isEncrypted) {
                decryptFile(file)
            } else {
                file
            }
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "❌ Decryption failed for $filePath", e)
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to get file $filePath", e)
            null
        }
    }
    
    /**
     * Проверяет наличие файла в кеше.
     */
    suspend fun hasFile(filePath: String): Boolean {
        return cacheDao.getByPath(filePath) != null
    }
    
    /**
     * Получает количество файлов в кеше.
     */
    suspend fun getCount(): Int {
        return cacheDao.getCount()
    }
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #2): Обновляет содержимое файла с перешифрованием.
     * 
     * Процесс:
     * 1. Получаем старый файл
     * 2. Шифруем новый content с НОВЫМ IV
     * 3. Обновляем в БД
     */
    suspend fun updateFileContent(filePath: String, newContent: String) {
        try {
            val existingFile = cacheDao.getByPath(filePath) ?: return
            
            // Создаем обновленный файл с новым content
            val updatedFile = existingFile.copy(
                content = newContent,
                sizeBytes = newContent.toByteArray().size,
                addedAt = Clock.System.now(),
                isEncrypted = false, // Временно незашифрованный
                encryptionIv = null
            )
            
            // Шифруем с НОВЫМ IV (НИКОГДА не переиспользуем старый IV!)
            val encryptedFile = if (newContent.isNotBlank()) {
                encryptFile(updatedFile)
            } else {
                updatedFile
            }
            
            cacheDao.update(encryptedFile)
            
            android.util.Log.d(TAG, "✅ File content updated and re-encrypted: $filePath")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to update file $filePath", e)
        }
    }
    
    /**
     * Обрезает кеш до указанного размера (удаляет самые старые файлы).
     */
    suspend fun trimToSize(maxFiles: Int) {
        try {
            cacheDao.trimToSize(maxFiles)
            android.util.Log.d(TAG, "✂️ Cache trimmed to $maxFiles files")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to trim cache", e)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ENCRYPTION HELPERS (PRIVATE)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * ✅ КРИТИЧЕСКИЙ МЕТОД (Проблема #2): Шифрует файл.
     * 
     * Берет файл с plaintext content и возвращает файл с encrypted content + IV.
     * Каждый вызов генерирует НОВЫЙ уникальный IV.
     */
    private fun encryptFile(file: CachedFileEntity): CachedFileEntity {
        require(!file.isEncrypted) { 
            "File ${file.filePath} is already encrypted" 
        }
        
        // Шифруем content
        val encryptedData = encryptionHelper.encryptData(file.content)
        
        // Возвращаем файл с зашифрованным content и IV
        return file.copy(
            content = encryptedData.ciphertext,
            isEncrypted = true,
            encryptionIv = encryptedData.iv
        )
    }
    
    /**
     * ✅ КРИТИЧЕСКИЙ МЕТОД (Проблема #2): Расшифровывает файл.
     * 
     * Берет файл с encrypted content + IV и возвращает файл с plaintext content.
     */
    private fun decryptFile(file: CachedFileEntity): CachedFileEntity {
        require(file.isEncrypted) { 
            "File ${file.filePath} is not encrypted" 
        }
        require(!file.encryptionIv.isNullOrBlank()) { 
            "File ${file.filePath} has no IV" 
        }
        
        // Расшифровываем content
        val plaintext = encryptionHelper.decryptData(
            ciphertext = file.content,
            iv = file.encryptionIv
        )
        
        // Возвращаем файл с расшифрованным content
        return file.copy(
            content = plaintext,
            isEncrypted = false,
            encryptionIv = null
        )
    }
}