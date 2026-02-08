package com.opuside.app.core.di

import android.content.Context
import androidx.room.Room
import com.opuside.app.core.database.AppDatabase
import com.opuside.app.core.database.MIGRATION_1_3
import com.opuside.app.core.database.MIGRATION_2_3
import com.opuside.app.core.database.dao.ChatDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для базы данных Room v3 (UPDATED)
 * 
 * ✅ ОБНОВЛЕНО:
 * - Добавлены миграции MIGRATION_1_3 и MIGRATION_2_3
 * - Сохранен fallbackToDestructiveMigration как запасной вариант
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
        // ✅ ДОБАВЛЕНО: Миграции для версий 1→3 и 2→3
        .addMigrations(
            MIGRATION_1_3,  // Для пользователей с версией 1
            MIGRATION_2_3   // Для пользователей с версией 2 (если такие есть)
        )
        
        // ✅ СОХРАНЕНО: Fallback на случай если миграция не найдена
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
 * ✅ СОХРАНЕНО: Все инструкции без изменений
 * 
 * 1. ИЗМЕНИТЬ СХЕМУ ENTITY:
 *    - Добавьте/измените колонки в @Entity классе
 *    - Обновите version в @Database аннотации
 *    Например: version = 3 → version = 4
 * 
 * 2. СОЗДАТЬ MIGRATION в AppDatabase.kt:
 *    ```kotlin
 *    val MIGRATION_3_4 = object : Migration(3, 4) {
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
 *        MIGRATION_1_3,
 *        MIGRATION_2_3,
 *        MIGRATION_3_4  // ← Новая миграция
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
 * 
 * ТЕКУЩЕЕ СОСТОЯНИЕ:
 * - Версия БД: 3
 * - Активные миграции: 1→3, 2→3
 * - Fallback: Включен (удалит данные при неудаче миграции)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */