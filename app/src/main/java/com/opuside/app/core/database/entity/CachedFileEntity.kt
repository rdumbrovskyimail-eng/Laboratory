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
 * 
 * ✅ ИСПРАВЛЕНО (Проблема #2 - DATA LEAK CRITICAL):
 * - Добавлено поле [isEncrypted] для маркировки зашифрованного контента
 * - Добавлено поле [encryptionIv] для хранения Initialization Vector (обязательно для AES-GCM)
 * - Все чувствительные данные (content с потенциальными API ключами, токенами) 
 *   теперь ОБЯЗАТЕЛЬНО шифруются перед сохранением в Room DB
 * - Room DB файл больше НЕ содержит plaintext секретов
 * 
 * ВАЖНО: Шифрование использует AES-256-GCM с MasterKey из AndroidKeyStore.
 * MasterKey привязан к устройству и неизвлекаем, что защищает от утечек при:
 * - Root-доступе к устройству
 * - Физическом доступе к /data/data/
 * - Бэкапе БД на другое устройство (см. AndroidManifest allowBackup=false)
 * 
 * Шифрование/расшифровка выполняется в PersistentCacheManager автоматически.
 * Пользователь и UI работают только с расшифрованными данными.
 * 
 * Формат зашифрованного content:
 * Base64(IV[12 bytes] + Ciphertext + AuthTag[16 bytes])
 * 
 * @property filePath Уникальный путь файла (Primary Key)
 * @property fileName Имя файла для отображения
 * @property content Содержимое файла (ЗАШИФРОВАНО если isEncrypted=true)
 * @property language Язык программирования для syntax highlighting
 * @property sizeBytes Размер РАСШИФРОВАННОГО контента (для валидации)
 * @property addedAt Timestamp добавления (UTC)
 * @property repoOwner GitHub owner
 * @property repoName GitHub repo
 * @property branch Git branch
 * @property sha Git commit SHA (для версионирования)
 * @property isEncrypted TRUE если content зашифрован (ВСЕГДА true в production)
 * @property encryptionIv Initialization Vector для AES-GCM (обязательно если isEncrypted=true)
 */
@Entity(tableName = "cached_files")
data class CachedFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "file_path")
    val filePath: String,
    
    @ColumnInfo(name = "file_name")
    val fileName: String,
    
    /**
     * ✅ КРИТИЧЕСКИ ВАЖНО: Это поле содержит ЗАШИФРОВАННЫЕ данные если isEncrypted=true.
     * НИКОГДА не используйте это поле напрямую в UI или бизнес-логике!
     * Всегда расшифровывайте через PersistentCacheManager.decryptContent()
     * 
     * Формат (если isEncrypted=true):
     * Base64(IV[12 bytes] + AES-GCM-Ciphertext + AuthenticationTag[16 bytes])
     */
    @ColumnInfo(name = "content")
    val content: String,
    
    @ColumnInfo(name = "language")
    val language: String, // kotlin, java, xml, json, etc.
    
    /**
     * Размер РАСШИФРОВАННОГО контента в байтах.
     * Используется для валидации и проверки лимитов (MAX_FILE_SIZE).
     * 
     * ВАЖНО: sizeBytes != content.length (т.к. content зашифрован и в Base64)
     */
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
    val sha: String? = null, // Git SHA для версионирования
    
    /**
     * ✅ ИСПРАВЛЕНО (Проблема #2): Флаг шифрования.
     * 
     * TRUE (по умолчанию в production) - content зашифрован AES-256-GCM
     * FALSE (только для legacy данных или миграции) - content в plaintext
     * 
     * В новых версиях приложения это поле ВСЕГДА должно быть true.
     * FALSE только для backward compatibility с данными до версии 2.0.
     */
    @ColumnInfo(name = "is_encrypted", defaultValue = "1")
    val isEncrypted: Boolean = true,
    
    /**
     * ✅ НОВОЕ ПОЛЕ (Проблема #2): Initialization Vector для AES-GCM.
     * 
     * Обязательное поле для расшифровки зашифрованного контента.
     * Содержит Base64-encoded IV (12 байт для GCM).
     * 
     * NULL только если isEncrypted=false (legacy данные).
     * 
     * ВАЖНО: IV должен быть УНИКАЛЬНЫМ для каждого шифрования.
     * Никогда не переиспользуйте один IV для разных данных!
     * 
     * Генерация: Cipher.init(ENCRYPT_MODE) автоматически создает случайный IV
     */
    @ColumnInfo(name = "encryption_iv", defaultValue = "NULL")
    val encryptionIv: String? = null
) {
    /**
     * Проверяет, истёк ли кеш для файла.
     * @param timeoutMs таймаут в миллисекундах (по умолчанию 5 минут)
     */
    fun isExpired(timeoutMs: Long = 5 * 60 * 1000L): Boolean {
        val now = Clock.System.now()
        return (now.toEpochMilliseconds() - addedAt.toEpochMilliseconds()) > timeoutMs
    }
    
    /**
     * ✅ НОВЫЙ МЕТОД: Валидация корректности шифрования.
     * 
     * Проверяет что если файл помечен как зашифрованный, у него есть IV.
     * Используется для предотвращения data corruption.
     */
    fun isEncryptionValid(): Boolean {
        return if (isEncrypted) {
            // Зашифрованные данные ОБЯЗАТЕЛЬНО должны иметь IV
            !encryptionIv.isNullOrBlank() && content.isNotBlank()
        } else {
            // Незашифрованные данные НЕ должны иметь IV
            encryptionIv == null
        }
    }
    
    init {
        // Runtime валидация при создании entity
        require(isEncryptionValid()) {
            "Invalid encryption state for file $filePath: " +
            "isEncrypted=$isEncrypted but encryptionIv=${encryptionIv?.take(10)}"
        }
    }
}