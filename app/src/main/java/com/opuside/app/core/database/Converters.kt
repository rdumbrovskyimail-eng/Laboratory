package com.opuside.app.core.database

import android.util.Log
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
 * ✅ ИСПРАВЛЕНО (Проблема #13): Добавлена защита от corrupted data в enum converter.
 * При невалидном значении возвращается SYSTEM вместо краша.
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
    // MESSAGE ROLE ENUM (Проблема №19 + Исправление #13)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ Конвертирует MessageRole enum в String для безопасного хранения.
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
     * ✅ ИСПРАВЛЕНО (Проблема #13): Безопасный fallback при corrupted data.
     * 
     * ПРОБЛЕМА:
     * - MessageRole.valueOf("INVALID") → IllegalArgumentException → CRASH
     * - При повреждении БД или неправильной миграции приложение падает
     * - При добавлении новых enum значений старые версии app не могут их прочитать
     * 
     * РЕШЕНИЕ:
     * - Используем try-catch для обработки неизвестных значений
     * - При ошибке возвращаем MessageRole.SYSTEM как безопасный fallback
     * - Логируем warning для отладки
     * 
     * ПРИМЕРЫ ПРОБЛЕМ БЕЗ ЭТОГО:
     * 1. БД повреждена → значение "INVALID" → CRASH
     * 2. Добавили MessageRole.TOOL в новой версии → старая версия видит "TOOL" → CRASH
     * 3. Ручная миграция БД с ошибкой → невалидные значения → CRASH
     */
    @TypeConverter
    fun toMessageRole(name: String): MessageRole {
        return try {
            MessageRole.valueOf(name)
        } catch (e: IllegalArgumentException) {
            // Неизвестное значение enum - используем fallback
            Log.w(TAG, "Unknown MessageRole: '$name', using SYSTEM as fallback", e)
            MessageRole.SYSTEM
        }
    }

    companion object {
        private const val TAG = "Converters"
        private const val SEPARATOR = "|||"
    }
}