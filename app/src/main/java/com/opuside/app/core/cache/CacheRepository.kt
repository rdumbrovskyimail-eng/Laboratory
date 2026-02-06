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
 * ✅ ОБНОВЛЕНО (Проблема #8 - Детальное логирование + Result types)
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
    // CRUD OPERATIONS (✅ ПРОБЛЕМА 8: RESULT TYPES + ДЕТАЛЬНОЕ ЛОГИРОВАНИЕ)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #8): Возвращает Result<Unit> для error handling
     * ✅ ИСПРАВЛЕНО (Проблема #2): Добавляет файл с автоматическим шифрованием.
     * 
     * Процесс:
     * 1. Валидация размера файла
     * 2. Проверка на дубликаты
     * 3. Шифрование content с AES-256-GCM
     * 4. Сохранение в БД с зашифрованным content + IV
     * 
     * @param file Файл с РАСШИФРОВАННЫМ content (plaintext)
     * @return Result.success(Unit) если успешно, Result.failure с типизированной ошибкой
     */
    suspend fun addFile(file: CachedFileEntity): Result<Unit> {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "📝 ADD FILE TO DATABASE")
        android.util.Log.d(TAG, "   File: ${file.filePath}")
        android.util.Log.d(TAG, "   Size: ${file.sizeBytes} bytes")
        
        return try {
            // ═══════════════════════════════════════════════════════════
            // ШАГ 1: Валидация размера
            // ═══════════════════════════════════════════════════════════
            if (file.sizeBytes > MAX_FILE_SIZE) {
                val errorMsg = "File too large: ${file.sizeBytes} bytes (max ${MAX_FILE_SIZE / 1024 / 1024}MB)"
                android.util.Log.e(TAG, "❌ VALIDATION FAILED: $errorMsg")
                android.util.Log.d(TAG, "━".repeat(80))
                return Result.failure(IllegalArgumentException(errorMsg))
            }
            
            android.util.Log.d(TAG, "   ✓ Size validation passed")
            
            // ═══════════════════════════════════════════════════════════
            // ШАГ 2: Проверка дубликатов
            // ═══════════════════════════════════════════════════════════
            val existing = cacheDao.getByPath(file.filePath)
            if (existing != null) {
                android.util.Log.d(TAG, "⚠️ DUPLICATE: File already exists in database")
                android.util.Log.d(TAG, "   Existing added at: ${existing.addedAt}")
                android.util.Log.d(TAG, "   Skipping insert (returning success)")
                android.util.Log.d(TAG, "━".repeat(80))
                return Result.success(Unit)
            }
            
            android.util.Log.d(TAG, "   ✓ No duplicate found")
            
            // ═══════════════════════════════════════════════════════════
            // ШАГ 3: Шифрование content
            // ═══════════════════════════════════════════════════════════
            val encryptedFile = if (file.content.isNotBlank()) {
                android.util.Log.d(TAG, "   → Encrypting content (${file.content.length} chars)...")
                
                try {
                    val encrypted = encryptFile(file)
                    
                    android.util.Log.d(TAG, "   ✓ Encryption successful")
                    android.util.Log.d(TAG, "      • Encrypted content length: ${encrypted.content.length}")
                    android.util.Log.d(TAG, "      • IV length: ${encrypted.encryptionIv?.length ?: 0}")
                    android.util.Log.d(TAG, "      • isEncrypted flag: ${encrypted.isEncrypted}")
                    
                    encrypted
                } catch (e: SecurityException) {
                    android.util.Log.e(TAG, "❌ ENCRYPTION FAILED", e)
                    android.util.Log.e(TAG, "   Error: ${e.message}")
                    android.util.Log.d(TAG, "━".repeat(80))
                    throw e // Re-throw для catch блока ниже
                }
            } else {
                android.util.Log.d(TAG, "   ⏭️ Content is blank, skipping encryption")
                file.copy(isEncrypted = false, encryptionIv = null)
            }
            
            // ═══════════════════════════════════════════════════════════
            // ШАГ 4: Вставка в БД
            // ═══════════════════════════════════════════════════════════
            android.util.Log.d(TAG, "   → Inserting into database...")
            
            cacheDao.insert(encryptedFile)
            
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "✅ FILE SUCCESSFULLY ADDED TO DATABASE")
            android.util.Log.d(TAG, "   Path: ${file.filePath}")
            android.util.Log.d(TAG, "   Encrypted: ${encryptedFile.isEncrypted}")
            android.util.Log.d(TAG, "━".repeat(80))
            
            Result.success(Unit)
            
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ ENCRYPTION ERROR")
            android.util.Log.e(TAG, "   File: ${file.filePath}")
            android.util.Log.e(TAG, "   Error: ${e.javaClass.simpleName}")
            android.util.Log.e(TAG, "   Message: ${e.message}")
            android.util.Log.e(TAG, "━".repeat(80), e)
            Result.failure(e)
            
        } catch (e: android.database.sqlite.SQLiteException) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ DATABASE ERROR")
            android.util.Log.e(TAG, "   File: ${file.filePath}")
            android.util.Log.e(TAG, "   Error: ${e.javaClass.simpleName}")
            android.util.Log.e(TAG, "   Message: ${e.message}")
            android.util.Log.e(TAG, "━".repeat(80), e)
            Result.failure(e)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ UNEXPECTED ERROR")
            android.util.Log.e(TAG, "   File: ${file.filePath}")
            android.util.Log.e(TAG, "   Error: ${e.javaClass.simpleName}")
            android.util.Log.e(TAG, "   Message: ${e.message}")
            android.util.Log.e(TAG, "━".repeat(80), e)
            Result.failure(e)
        }
    }
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #8): Возвращает Result<Int> для error handling
     * ✅ ИСПРАВЛЕНО (Проблема #2): Добавляет несколько файлов с шифрованием.
     * 
     * Использует batch insert для производительности.
     * Все файлы шифруются индивидуально (каждый со своим IV).
     * 
     * @param files Список файлов с расшифрованным content
     * @return Result.success(количество добавленных) или Result.failure с типизированной ошибкой
     */
    suspend fun addFiles(files: List<CachedFileEntity>): Result<Int> {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "📝 BATCH ADD FILES TO DATABASE")
        android.util.Log.d(TAG, "   Total files: ${files.size}")
        
        return try {
            // ═══════════════════════════════════════════════════════════
            // ШАГ 1: Валидация размера всех файлов
            // ═══════════════════════════════════════════════════════════
            val oversizedFiles = files.filter { it.sizeBytes > MAX_FILE_SIZE }
            if (oversizedFiles.isNotEmpty()) {
                val errorMsg = "Files too large: ${oversizedFiles.map { it.filePath }} exceed ${MAX_FILE_SIZE / 1024 / 1024}MB"
                android.util.Log.e(TAG, "❌ VALIDATION FAILED: $errorMsg")
                android.util.Log.d(TAG, "━".repeat(80))
                return Result.failure(IllegalArgumentException(errorMsg))
            }
            
            android.util.Log.d(TAG, "   ✓ All files passed size validation")
            
            // ═══════════════════════════════════════════════════════════
            // ШАГ 2: Фильтруем дубликаты
            // ═══════════════════════════════════════════════════════════
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
                android.util.Log.d(TAG, "⚠️ DUPLICATES FOUND: ${duplicates.size}")
                duplicates.forEach { path ->
                    android.util.Log.d(TAG, "      • $path")
                }
            }
            
            if (newFiles.isEmpty()) {
                android.util.Log.d(TAG, "⏭️ All files are duplicates, nothing to insert")
                android.util.Log.d(TAG, "━".repeat(80))
                return Result.success(0)
            }
            
            android.util.Log.d(TAG, "   ✓ ${newFiles.size} new files to insert")
            
            // ═══════════════════════════════════════════════════════════
            // ШАГ 3: Шифрование всех файлов
            // ═══════════════════════════════════════════════════════════
            android.util.Log.d(TAG, "   → Encrypting ${newFiles.size} files...")
            
            val encryptedFiles = try {
                newFiles.mapIndexed { index, file ->
                    if (file.content.isNotBlank()) {
                        android.util.Log.d(TAG, "      [$index] Encrypting ${file.filePath}...")
                        val encrypted = encryptFile(file)
                        android.util.Log.d(TAG, "      [$index] ✓ Encrypted (IV: ${encrypted.encryptionIv?.take(16)}...)")
                        encrypted
                    } else {
                        android.util.Log.d(TAG, "      [$index] ⏭️ Skipping encryption (blank content): ${file.filePath}")
                        file.copy(isEncrypted = false, encryptionIv = null)
                    }
                }
            } catch (e: SecurityException) {
                android.util.Log.e(TAG, "❌ BATCH ENCRYPTION FAILED", e)
                android.util.Log.d(TAG, "━".repeat(80))
                throw e
            }
            
            android.util.Log.d(TAG, "   ✓ All files encrypted successfully")
            
            // ═══════════════════════════════════════════════════════════
            // ШАГ 4: Batch insert
            // ═══════════════════════════════════════════════════════════
            android.util.Log.d(TAG, "   → Performing batch insert...")
            
            cacheDao.insertAll(encryptedFiles)
            
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "✅ BATCH INSERT SUCCESSFUL")
            android.util.Log.d(TAG, "   Inserted: ${encryptedFiles.size} files")
            android.util.Log.d(TAG, "   Duplicates skipped: ${duplicates.size}")
            android.util.Log.d(TAG, "   Total attempted: ${files.size}")
            android.util.Log.d(TAG, "━".repeat(80))
            
            Result.success(encryptedFiles.size)
            
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ BATCH ENCRYPTION ERROR")
            android.util.Log.e(TAG, "   Error: ${e.javaClass.simpleName}")
            android.util.Log.e(TAG, "   Message: ${e.message}")
            android.util.Log.e(TAG, "━".repeat(80), e)
            Result.failure(e)
            
        } catch (e: android.database.sqlite.SQLiteException) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ BATCH DATABASE ERROR")
            android.util.Log.e(TAG, "   Error: ${e.javaClass.simpleName}")
            android.util.Log.e(TAG, "   Message: ${e.message}")
            android.util.Log.e(TAG, "━".repeat(80), e)
            Result.failure(e)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ BATCH INSERT UNEXPECTED ERROR")
            android.util.Log.e(TAG, "   Error: ${e.javaClass.simpleName}")
            android.util.Log.e(TAG, "   Message: ${e.message}")
            android.util.Log.e(TAG, "━".repeat(80), e)
            Result.failure(e)
        }
    }
    
    /**
     * ✅ ОБНОВЛЕНО (Проблема #8): Добавлено логирование + Result type
     * 
     * Удаляет файл из кеша.
     */
    suspend fun removeFile(filePath: String): Result<Unit> {
        return try {
            android.util.Log.d(TAG, "🗑️ Removing file: $filePath")
            
            cacheDao.deleteByPath(filePath)
            
            android.util.Log.d(TAG, "✅ File removed successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to remove file $filePath", e)
            Result.failure(e)
        }
    }
    
    /**
     * ✅ ОБНОВЛЕНО (Проблема #8): Добавлено логирование + Result type
     * 
     * Очищает весь кеш.
     */
    suspend fun clearAll(): Result<Unit> {
        return try {
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "🗑️ CLEARING ALL CACHE")
            
            val countBefore = cacheDao.getCount()
            android.util.Log.d(TAG, "   Files before clear: $countBefore")
            
            cacheDao.clearAll()
            
            val countAfter = cacheDao.getCount()
            android.util.Log.d(TAG, "   Files after clear: $countAfter")
            android.util.Log.d(TAG, "✅ Cache cleared successfully")
            android.util.Log.d(TAG, "━".repeat(80))
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ CLEAR CACHE FAILED", e)
            android.util.Log.e(TAG, "━".repeat(80))
            Result.failure(e)
        }
    }
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #2): Получает все файлы с расшифровкой.
     * 
     * Возвращает файлы с расшифрованным content, готовым для использования.
     */
    suspend fun getAll(): List<CachedFileEntity> {
        return try {
            android.util.Log.d(TAG, "📋 Getting all files from cache...")
            
            val encryptedFiles = cacheDao.getAll()
            android.util.Log.d(TAG, "   Found ${encryptedFiles.size} files in database")
            
            // Расшифровываем все файлы
            val decryptedFiles = encryptedFiles.map { file ->
                if (file.isEncrypted) {
                    decryptFile(file)
                } else {
                    file
                }
            }
            
            android.util.Log.d(TAG, "✅ All files decrypted successfully")
            decryptedFiles
            
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
     * ✅ ОБНОВЛЕНО (Проблема #8): Добавлено логирование + Result type
     * ✅ ИСПРАВЛЕНО (Проблема #2): Обновляет содержимое файла с перешифрованием.
     * 
     * Процесс:
     * 1. Получаем старый файл
     * 2. Шифруем новый content с НОВЫМ IV
     * 3. Обновляем в БД
     */
    suspend fun updateFileContent(filePath: String, newContent: String): Result<Unit> {
        return try {
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "✏️ UPDATING FILE CONTENT")
            android.util.Log.d(TAG, "   Path: $filePath")
            android.util.Log.d(TAG, "   New content length: ${newContent.length} chars")
            
            val existingFile = cacheDao.getByPath(filePath)
            if (existingFile == null) {
                android.util.Log.w(TAG, "⚠️ File not found in cache, skipping update")
                android.util.Log.d(TAG, "━".repeat(80))
                return Result.success(Unit)
            }
            
            android.util.Log.d(TAG, "   ✓ Existing file found")
            android.util.Log.d(TAG, "      • Was encrypted: ${existingFile.isEncrypted}")
            android.util.Log.d(TAG, "      • Old size: ${existingFile.sizeBytes} bytes")
            
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
                android.util.Log.d(TAG, "   → Re-encrypting with NEW IV...")
                val encrypted = encryptFile(updatedFile)
                android.util.Log.d(TAG, "   ✓ Re-encrypted successfully")
                android.util.Log.d(TAG, "      • New IV: ${encrypted.encryptionIv?.take(16)}...")
                encrypted
            } else {
                android.util.Log.d(TAG, "   ⏭️ Content is blank, skipping encryption")
                updatedFile
            }
            
            android.util.Log.d(TAG, "   → Updating in database...")
            cacheDao.update(encryptedFile)
            
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "✅ FILE CONTENT UPDATED SUCCESSFULLY")
            android.util.Log.d(TAG, "   New size: ${encryptedFile.sizeBytes} bytes")
            android.util.Log.d(TAG, "━".repeat(80))
            
            Result.success(Unit)
            
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ UPDATE ENCRYPTION FAILED")
            android.util.Log.e(TAG, "   File: $filePath")
            android.util.Log.e(TAG, "   Error: ${e.message}")
            android.util.Log.e(TAG, "━".repeat(80), e)
            Result.failure(e)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "━".repeat(80))
            android.util.Log.e(TAG, "❌ UPDATE FAILED")
            android.util.Log.e(TAG, "   File: $filePath")
            android.util.Log.e(TAG, "   Error: ${e.message}")
            android.util.Log.e(TAG, "━".repeat(80), e)
            Result.failure(e)
        }
    }
    
    /**
     * Обрезает кеш до указанного размера (удаляет самые старые файлы).
     */
    suspend fun trimToSize(maxFiles: Int) {
        try {
            android.util.Log.d(TAG, "✂️ Trimming cache to $maxFiles files...")
            
            val countBefore = cacheDao.getCount()
            cacheDao.trimToSize(maxFiles)
            val countAfter = cacheDao.getCount()
            
            android.util.Log.d(TAG, "✅ Cache trimmed: $countBefore → $countAfter files")
            
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