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
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #19): Migration от версии 1 к версии 2
     * 
     * ИЗМЕНЕНИЯ В V2:
     * - Добавлены индексы для оптимизации производительности queries
     * - index_cached_files_added_at: ускоряет сортировку и поиск по времени добавления
     * - index_chat_messages_session_created: ускоряет фильтрацию по сессии и времени
     * 
     * ПРИМЕЧАНИЕ:
     * Если в будущем нужно добавить колонку is_encrypted в cached_files
     * (из Проблемы #2), раскомментируйте соответствующий SQL:
     * 
     * database.execSQL(
     *     "ALTER TABLE cached_files ADD COLUMN is_encrypted INTEGER NOT NULL DEFAULT 0"
     * )
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // ✅ Добавление индексов для производительности
            // Эти индексы улучшают скорость queries в CacheDao и ChatDao
            
            // Индекс для быстрой сортировки кешированных файлов по времени
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cached_files_added_at ON cached_files(added_at)"
            )
            
            // Составной индекс для быстрой фильтрации сообщений чата
            // Ускоряет observeSession() и getRecentMessages()
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chat_messages_session_created ON chat_messages(session_id, created_at)"
            )
            
            // ✅ ОПЦИОНАЛЬНО: Добавление колонки is_encrypted (если нужно)
            // Раскомментируйте, если реализовали шифрование из Проблемы #2:
            /*
            database.execSQL(
                "ALTER TABLE cached_files ADD COLUMN is_encrypted INTEGER NOT NULL DEFAULT 0"
            )
            */
        }
    }

    /**
     * ✅ ПРИМЕР: Migration от версии 2 к версии 3 (для будущих изменений)
     * 
     * Раскомментируйте и адаптируйте когда потребуется изменить схему БД.
     * 
     * ПРИМЕРЫ ИЗМЕНЕНИЙ:
     * - Добавление новых таблиц
     * - Добавление колонок
     * - Изменение типов данных (требует пересоздания таблицы)
     * - Добавление/удаление индексов
     */
    /*
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Пример 1: Добавление новой таблицы для пользовательских настроек
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS user_preferences (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(key)
                )
            """)
            
            // Пример 2: Добавление колонки в существующую таблицу
            database.execSQL(
                "ALTER TABLE chat_messages ADD COLUMN model_name TEXT DEFAULT 'claude-sonnet-4-20250514'"
            )
            
            // Пример 3: Изменение типа колонки (требует пересоздания)
            // Шаги:
            // 1. Создать новую таблицу с правильной схемой
            // 2. Скопировать данные из старой таблицы
            // 3. Удалить старую таблицу
            // 4. Переименовать новую таблицу
            /*
            database.execSQL("ALTER TABLE cached_files RENAME TO cached_files_old")
            database.execSQL("""
                CREATE TABLE cached_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    file_path TEXT NOT NULL,
                    content TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,  // Изменили тип
                    added_at INTEGER NOT NULL,
                    UNIQUE(file_path)
                )
            """)
            database.execSQL("""
                INSERT INTO cached_files (id, file_path, content, size_bytes, added_at)
                SELECT id, file_path, content, CAST(size_bytes AS INTEGER), added_at
                FROM cached_files_old
            """)
            database.execSQL("DROP TABLE cached_files_old")
            */
        }
    }
    */

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
        // Теперь данные пользователя сохраняются при обновлении приложения
        .addMigrations(
            MIGRATION_1_2
            // MIGRATION_2_3,  // ← Добавить когда нужно будет мигрировать на V3
            // MIGRATION_3_4,
            // ...
        )
        
        // ✅ ВАЖНО: fallbackToDestructiveMigration() УДАЛЕНО!
        // 
        // БЫЛО (Проблема #19):
        // .fallbackToDestructiveMigration() 
        // ↑ Это удаляло ВСЮ БД при любом изменении схемы → DATA LOSS
        //
        // СТАЛО:
        // - Только явные миграции через addMigrations()
        // - Данные пользователя сохраняются
        // - При отсутствии нужной миграции → crash с понятной ошибкой
        //   (лучше crash чем silent data loss!)
        
        // ✅ ОПЦИОНАЛЬНО: Разрешить destructive migration только при downgrade
        // Это безопасно, т.к. downgrade редко происходит в production
        .fallbackToDestructiveMigrationOnDowngrade()
        
        // ✅ РЕКОМЕНДАЦИЯ: Включить автоматическую миграцию (Android Room 2.4+)
        // Работает только для простых изменений (добавление колонок с DEFAULT)
        // .setAutoMigrationSpecs(...) // Требует Room 2.4+ и KSP
        
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
 *    Например: version = 1 → version = 2
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
 *    ```kotlin
 *    @Test
 *    fun migrate1To2() {
 *        val db = helper.createDatabase(TEST_DB, 1)
 *        // Вставить данные в V1
 *        db.close()
 *        
 *        val dbV2 = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)
 *        // Проверить, что данные остались
 *    }
 *    ```
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