package com.opuside.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.feature.scratch.data.local.ScratchDao
import com.opuside.app.feature.scratch.data.local.ScratchEntity

/**
 * Room Database для OpusIDE v4 (UPDATED)
 *
 * Содержит таблицы:
 * - chat_messages: История чата с Claude
 * - scratch_records: Записи Scratch-экрана
 *
 * ИСТОРИЯ ВЕРСИЙ:
 * - Version 1: ChatMessageEntity для истории чата с Claude
 * - Version 2: (пропущена)
 * - Version 3: Добавлены поля для метаданных (model_used, cached_tokens, input_tokens, output_tokens, cost_usd, cost_eur)
 * - Version 4: ✅ НОВОЕ - Добавлена таблица scratch_records
 *
 * ВАЖНО:
 * При изменении схемы БД:
 * 1. Увеличить version (например, 4 → 5)
 * 2. Добавить миграцию в DatabaseModule.kt (MIGRATION_4_5)
 * 3. Обновить этот комментарий с описанием изменений
 */
@Database(
    entities = [
        ChatMessageEntity::class,
        ScratchEntity::class   // ✅ ДОБАВЛЕНО
    ],
    version = 4,  // ✅ ИЗМЕНЕНО: 3 → 4
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun scratchDao(): ScratchDao  // ✅ ДОБАВЛЕНО
}

/**
 * Миграция с версии 1 на 3
 */
val MIGRATION_1_3 = object : Migration(1, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN model_used TEXT")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cached_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN input_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN output_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cost_usd REAL")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cost_eur REAL")
    }
}

/**
 * Миграция с версии 2 на 3
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN model_used TEXT")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cached_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN input_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN output_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cost_usd REAL")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cost_eur REAL")
    }
}

/**
 * ✅ НОВОЕ: Миграция с версии 3 на 4
 * Создаёт таблицу scratch_records
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS scratch_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """)
    }
}
