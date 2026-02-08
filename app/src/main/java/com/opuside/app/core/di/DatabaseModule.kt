package com.opuside.app.core.di

import android.content.Context
import androidx.room.Room
import com.opuside.app.core.database.AppDatabase
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
 * - ChatDao для истории чата
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "opuside_database"
    )
        // Простое пересоздание БД при несовпадении схемы
        // (допустимо, так как чаты не критичны)
        .fallbackToDestructiveMigration()
        
        .build()

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
 *        MIGRATION_1_2  // ← Новая миграция
 *    )
 *    ```
 * 
 * 4. УДАЛИТЬ fallbackToDestructiveMigration() если нужно сохранять данные
 * 
 * 5. ТЕСТИРОВАНИЕ:
 *    - Создать тест с MigrationTestHelper
 *    - Проверить, что данные сохраняются
 * 
 * 6. ТИПИЧНЫЕ ОПЕРАЦИИ:
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