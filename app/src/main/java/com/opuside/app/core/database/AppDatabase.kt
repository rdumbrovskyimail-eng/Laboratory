package com.opuside.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity

/**
 * Room Database для OpusIDE.
 * 
 * Содержит таблицы:
 * - chat_messages: История чата с Claude
 * 
 * ИСТОРИЯ ВЕРСИЙ:
 * - Version 1: ChatMessageEntity для истории чата с Claude
 * 
 * ВАЖНО:
 * При изменении схемы БД:
 * 1. Увеличить version (например, 1 → 2)
 * 2. Добавить миграцию в DatabaseModule.kt (MIGRATION_1_2)
 * 3. Обновить этот комментарий с описанием изменений
 */
@Database(
    entities = [
        ChatMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun chatDao(): ChatDao
}