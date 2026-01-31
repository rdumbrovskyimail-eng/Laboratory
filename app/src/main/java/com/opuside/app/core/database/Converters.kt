package com.opuside.app.core.database

import androidx.room.TypeConverter
import com.opuside.app.core.database.entity.MessageRole
import kotlinx.datetime.Instant

/**
 * Type converters для Room.
 * 
 * Конвертирует типы, которые Room не поддерживает нативно:
 * - Instant (kotlinx.datetime)
 * - List<String>
 * - MessageRole (enum) - ✅ ДОБАВЛЕНО (Проблема №19)
 * 
 * ✅ ОБНОВЛЕНО: Добавлен безопасный TypeConverter для MessageRole enum,
 * который использует String вместо ordinal для защиты от изменения порядка enum.
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

    // ═══════════════════════════════════════════════════════════════════════════
    // MESSAGE ROLE ENUM (Проблема №19)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ НОВОЕ: Конвертирует MessageRole enum в String для безопасного хранения.
     * 
     * Использует name вместо ordinal, чтобы избежать проблем при изменении
     * порядка значений enum в будущем.
     * 
     * ВАЖНО: Если изменить порядок значений в enum MessageRole и использовать
     * ordinal (по умолчанию Room), все существующие данные испортятся!
     */
    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    /**
     * ✅ НОВОЕ: Конвертирует String обратно в MessageRole enum.
     */
    @TypeConverter
    fun toMessageRole(name: String): MessageRole = MessageRole.valueOf(name)

    companion object {
        private const val SEPARATOR = "|||"
    }
}