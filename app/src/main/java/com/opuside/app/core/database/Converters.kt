package com.opuside.app.core.database

import androidx.room.TypeConverter
import kotlinx.datetime.Instant

/**
 * Type converters для Room.
 * 
 * Конвертирует типы, которые Room не поддерживает нативно:
 * - Instant (kotlinx.datetime)
 * - List<String>
 */
class Converters {

    // ═══════════════════════════════════════════════════════════════════════════
    // INSTANT (kotlinx.datetime)
    // ═══════════════════════════════════════════════════════════════════════════

    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(epochMilli: Long?): Instant? = epochMilli?.let { 
        Instant.fromEpochMilliseconds(it) 
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIST<STRING> (для тегов и т.д.)
    // ═══════════════════════════════════════════════════════════════════════════

    @TypeConverter
    fun fromStringList(list: List<String>?): String? = list?.joinToString(SEPARATOR)

    @TypeConverter
    fun toStringList(value: String?): List<String>? = value?.split(SEPARATOR)?.filter { it.isNotEmpty() }

    companion object {
        private const val SEPARATOR = "|||"
    }
}
