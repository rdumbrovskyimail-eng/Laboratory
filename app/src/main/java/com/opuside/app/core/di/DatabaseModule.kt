package com.opuside.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opuside.app.core.database.AppDatabase
import com.opuside.app.core.database.dao.CacheDao
import com.opuside.app.core.database.dao.ChatDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для базы данных Room.
 * 
 * Предоставляет:
 * - AppDatabase instance
 * - CacheDao для работы с кешированными файлами
 * - ChatDao для истории чата
 * 
 * ✅ ИСПРАВЛЕНО (Проблема #19): Добавлены миграции вместо fallbackToDestructiveMigration
 * для предотвращения потери данных пользователя при обновлении приложения.
 * 
 * ✅ ОБНОВЛЕНО (Проблема #2 - DATA LEAK): Добавлены колонки для шифрования кеша
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * ✅ ОБНОВЛЕНО (Проблема #2): Migration от версии 1 к версии 2
     * 
     * ИЗМЕНЕНИЯ В V2:
     * - Добавлены индексы для оптимизации производительности queries
     * - index_cached_files_added_at: ускоряет сортировку и поиск по времени добавления
     * - index_chat_messages_session_created: ускоряет фильтрацию по сессии и времени
     * - ✅ НОВОЕ: Добавлены колонки для шифрования в cached_files
     *   - is_encrypted: флаг шифрования (default = 1)
     *   - encryption_iv: Initialization Vector для AES-GCM (nullable для legacy данных)
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // ═══════════════════════════════════════════════════════════════
            // ЧАСТЬ 1: Индексы для производительности
            // ═══════════════════════════════════════════════════════════════
            
            // Индекс для быстрой сортировки кешированных файлов по времени
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cached_files_added_at ON cached_files(added_at)"
            )
            
            // Составной индекс для быстрой фильтрации сообщений чата
            // Ускоряет observeSession() и getRecentMessages()
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chat_messages_session_created ON chat_messages(session_id, created_at)"
            )
            
            // ═══════════════════════════════════════════════════════════════
            // ЧАСТЬ 2: ✅ Шифрование кеша (Проблема #2 - DATA LEAK CRITICAL)
            // ═══════════════════════════════════════════════════════════════
            
            // Добавляем колонку is_encrypted (по умолчанию = 1 / TRUE)
            // Все НОВЫЕ записи будут автоматически помечены как зашифрованные
            database.execSQL(
                "ALTER TABLE cached_files ADD COLUMN is_encrypted INTEGER NOT NULL DEFAULT 1"
            )
            
            // Добавляем колонку encryption_iv (nullable для legacy данных)
            // Legacy данные (до миграции) будут иметь NULL в этом поле
            // Новые записи ОБЯЗАТЕЛЬНО будут содержать IV
            database.execSQL(
                "ALTER TABLE cached_files ADD COLUMN encryption_iv TEXT DEFAULT NULL"
            )
            
            // ✅ ВАЖНО: Существующие данные в БД остаются НЕЗАШИФРОВАННЫМИ
            // Они будут помечены is_encrypted=1, но content - в plaintext
            // 
            // ВАРИАНТЫ ОБРАБОТКИ LEGACY ДАННЫХ:
            // 
            // ВАРИАНТ A (Безопасный): Удалить все старые данные при миграции
            // database.execSQL("DELETE FROM cached_files")
            // ↑ Это безопасно, т.к. кеш - временные данные (таймер 5 мин)
            // 
            // ВАРИАНТ B (Сложный): Зашифровать legacy данные на лету
            // Требует вызова CacheEncryptionHelper из миграции
            // НЕ РЕКОМЕНДУЕТСЯ: миграции должны быть чисто SQL
            //
            // МЫ ВЫБРАЛИ: ВАРИАНТ A - удаляем старый кеш
            database.execSQL("DELETE FROM cached_files")
            
            // После удаления, все новые записи будут создаваться
            // через PersistentCacheManager с автоматическим шифрованием
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "opuside_database"
    )
        // ✅ ИСПРАВЛЕНО: Добавлены миграции вместо fallbackToDestructiveMigration
        .addMigrations(
            MIGRATION_1_2
            // MIGRATION_2_3,  // ← Добавить когда нужно будет мигрировать на V3
        )
        
        // ✅ ВАЖНО: fallbackToDestructiveMigration() УДАЛЕНО!
        // Данные пользователя сохраняются при обновлении
        
        // ✅ Разрешить destructive migration только при downgrade
        .fallbackToDestructiveMigrationOnDowngrade()
        
        .build()

    @Provides
    @Singleton
    fun provideCacheDao(database: AppDatabase): CacheDao = database.cacheDao()

    @Provides
    @Singleton
    fun provideChatDao(database: AppDatabase): ChatDao = database.chatDao()
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ИНСТРУКЦИЯ: Как создать новую миграцию
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * 1. ИЗМЕНИТЬ СХЕМУ ENTITY:
 *    - Добавьте/измените колонки в @Entity классе
 *    - Обновите version в @Database аннотации
 *    Например: version = 2 → version = 3
 * 
 * 2. СОЗДАТЬ MIGRATION:
 *    ```kotlin
 *    private val MIGRATION_X_Y = object : Migration(X, Y) {
 *        override fun migrate(database: SupportSQLiteDatabase) {
 *            // SQL команды для миграции
 *            database.execSQL("ALTER TABLE ...")
 *        }
 *    }
 *    ```
 * 
 * 3. ДОБАВИТЬ В addMigrations():
 *    ```kotlin
 *    .addMigrations(
 *        MIGRATION_1_2,
 *        MIGRATION_2_3  // ← Новая миграция
 *    )
 *    ```
 * 
 * 4. ТЕСТИРОВАНИЕ:
 *    - Создать тест с MigrationTestHelper
 *    - Проверить, что данные сохраняются
 * 
 * 5. ТИПИЧНЫЕ ОПЕРАЦИИ:
 * 
 *    Добавить колонку:
 *    database.execSQL("ALTER TABLE table_name ADD COLUMN column_name TYPE DEFAULT value")
 *    
 *    Создать индекс:
 *    database.execSQL("CREATE INDEX index_name ON table_name(column_name)")
 *    
 *    Создать таблицу:
 *    database.execSQL("CREATE TABLE ...")
 *    
 *    Изменить тип колонки (сложно - нужно пересоздать таблицу):
 *    - Переименовать таблицу (ALTER TABLE ... RENAME TO ...)
 *    - Создать новую таблицу с правильной схемой
 *    - Скопировать данные (INSERT INTO ... SELECT ...)
 *    - Удалить старую таблицу (DROP TABLE ...)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */