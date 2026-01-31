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
 * 
 * ✅ ОБНОВЛЕНО (Проблема #19): Version увеличена до 2 для поддержки миграций.
 * 
 * ИСТОРИЯ ВЕРСИЙ:
 * - Version 1: Начальная схема (CachedFileEntity, ChatMessageEntity)
 * - Version 2: Добавлены индексы для производительности
 *   - index_cached_files_added_at: ускоряет сортировку по времени
 *   - index_chat_messages_session_created: ускоряет фильтрацию чата
 * 
 * ВАЖНО:
 * При изменении схемы БД:
 * 1. Увеличить version (например, 2 → 3)
 * 2. Добавить миграцию в DatabaseModule.kt (MIGRATION_2_3)
 * 3. Обновить этот комментарий с описанием изменений
 */
@Database(
    entities = [
        CachedFileEntity::class,
        ChatMessageEntity::class
    ],
    version = 2, // ✅ ИСПРАВЛЕНО: Увеличено с 1 до 2 для поддержки MIGRATION_1_2
    exportSchema = true // ✅ Экспорт схемы для проверки в CI/CD
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun cacheDao(): CacheDao
    
    abstract fun chatDao(): ChatDao
}