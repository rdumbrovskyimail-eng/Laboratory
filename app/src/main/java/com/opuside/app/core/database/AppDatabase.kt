package com.opuside.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity

/**
 * Room Database для OpusIDE v3 (UPDATED)
 * 
 * Содержит таблицы:
 * - chat_messages: История чата с Claude
 * 
 * ИСТОРИЯ ВЕРСИЙ:
 * - Version 1: ChatMessageEntity для истории чата с Claude
 * - Version 2: (пропущена)
 * - Version 3: ✅ НОВОЕ - Добавлены поля для метаданных (model_used, cached_tokens, input_tokens, output_tokens, cost_usd, cost_eur)
 * 
 * ВАЖНО:
 * При изменении схемы БД:
 * 1. Увеличить version (например, 3 → 4)
 * 2. Добавить миграцию в DatabaseModule.kt (MIGRATION_3_4)
 * 3. Обновить этот комментарий с описанием изменений
 */
@Database(
    entities = [
        ChatMessageEntity::class
    ],
    version = 3,  // ✅ ИЗМЕНЕНО: 1 → 3
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun chatDao(): ChatDao
}

/**
 * ✅ НОВОЕ: Миграция с версии 1 на 3
 * 
 * Добавляет 6 новых полей для поддержки:
 * - Детальной статистики токенов (input/output/cached)
 * - Отслеживания стоимости (USD/EUR)
 * - Истории использованных моделей
 */
val MIGRATION_1_3 = object : Migration(1, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Добавляем новые поля для метаданных
        database.execSQL("""
            ALTER TABLE chat_messages 
            ADD COLUMN model_used TEXT
        """)
        
        database.execSQL("""
            ALTER TABLE chat_messages 
            ADD COLUMN cached_tokens INTEGER
        """)
        
        database.execSQL("""
            ALTER TABLE chat_messages 
            ADD COLUMN input_tokens INTEGER
        """)
        
        database.execSQL("""
            ALTER TABLE chat_messages 
            ADD COLUMN output_tokens INTEGER
        """)
        
        database.execSQL("""
            ALTER TABLE chat_messages 
            ADD COLUMN cost_usd REAL
        """)
        
        database.execSQL("""
            ALTER TABLE chat_messages 
            ADD COLUMN cost_eur REAL
        """)
    }
}

/**
 * ✅ АЛЬТЕРНАТИВА: Миграция с версии 2 на 3 (если у кого-то уже есть версия 2)
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // То же самое, что MIGRATION_1_3
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN model_used TEXT")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cached_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN input_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN output_tokens INTEGER")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cost_usd REAL")
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN cost_eur REAL")
    }
}