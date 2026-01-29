package com.opuside.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.opuside.app.core.database.dao.CacheDao
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.CachedFileEntity
import com.opuside.app.core.database.entity.ChatMessageEntity

/**
 * Room Database для OpusIDE.
 * 
 * Содержит таблицы:
 * - cached_files: Кешированные файлы для анализа (таймер 5 мин)
 * - chat_messages: История чата с Claude
 */
@Database(
    entities = [
        CachedFileEntity::class,
        ChatMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun cacheDao(): CacheDao
    
    abstract fun chatDao(): ChatDao
}
