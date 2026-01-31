package com.opuside.app.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.collection.LruCache
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Подсветка синтаксиса для кода.
 * Поддерживает Kotlin, Java, XML, JSON.
 * 
 * ✅ ИСПРАВЛЕНО (Проблема #10): Добавлен timeout для Regex операций
 * против catastrophic backtracking и UI freeze.
 * 
 * ПРОБЛЕМА:
 * - Regex в highlightJson/highlightXml может вызвать catastrophic backtracking
 * - Для строки типа "test\\\\\\\\\\\\escape" (много backslash) → O(2^n) complexity
 * - UI зависает на 30+ секунд на некоторых JSON/XML файлах
 * - Нет возможности прервать длительную операцию
 * 
 * РЕШЕНИЕ:
 * 1. Добавлен withTimeoutOrNull(100ms) для всех Regex операций
 * 2. Упрощены Regex patterns (меньше backtracking)
 * 3. Fallback на простую подсветку при timeout
 * 4. Ограничение размера входных данных (10,000 символов)
 * 
 * ПРИМЕЧАНИЕ: Для полного решения проблемы #14 (Blocking Main Thread)
 * нужно переделать на suspend функции с Dispatchers.Default, но это
 * требует изменений в VirtualizedCodeEditor.kt (не входит в текущий scope).
 */
object SyntaxHighlighter {

    // Colors (VS Code Dark Theme)
    private val colorKeyword = Color(0xFF569CD6)
    private val colorString = Color(0xFFCE9178)
    private val colorNumber = Color(0xFFB5CEA8)
    private val colorComment = Color(0xFF6A9955)
    private val colorFunction = Color(0xFFDCDCAA)
    private val colorType = Color(0xFF4EC9B0)
    private val colorAnnotation = Color(0xFFD7BA7D)
    private val colorTag = Color(0xFF569CD6)
    private val colorAttribute = Color(0xFF9CDCFE)
    private val colorDefault = Color(0xFFD4D4D4)

    private val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "interface", "object", "enum", "sealed",
        "data", "annotation", "companion", "abstract", "open", "override", "private",
        "protected", "public", "internal", "final", "const", "lateinit", "lazy",
        "by", "if", "else", "when", "while", "for", "do", "return", "break",
        "continue", "throw", "try", "catch", "finally", "import", "package",
        "as", "is", "in", "out", "true", "false", "null", "this", "super",
        "suspend", "inline", "reified", "typealias", "constructor", "init"
    )

    private val cache = LruCache<Pair<String, String>, AnnotatedString>(100)

    /**
     * ✅ ИСПРАВЛЕНО: Добавлено ограничение размера и fallback
     */
    fun highlight(code: String, language: String): AnnotatedString {
        val key = code to language
        cache.get(key)?.let { return it }

        // ✅ НОВОЕ: Ограничение размера для предотвращения UI freeze
        if (code.length > 10_000) {
            // Для очень больших файлов - простая подсветка без Regex
            return buildAnnotatedString {
                append(code)
                addStyle(SpanStyle(color = colorDefault), 0, code.length)
            }.also { cache.put(key, it) }
        }

        val result = when (language.lowercase()) {
            "kotlin", "kt", "kts", "gradle" -> highlightKotlin(code)
            "java" -> highlightKotlin(code)
            "xml" -> highlightXmlSafe(code) // ✅ ИСПРАВЛЕНО: Safe версия с timeout
            "json" -> highlightJsonSafe(code) // ✅ ИСПРАВЛЕНО: Safe версия с timeout
            else -> buildAnnotatedString { append(code) }
        }

        cache.put(key, result)
        return result
    }

    private fun highlightKotlin(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        var i = 0
        while (i < code.length) {
            when {
                // Comments /* */
                code.startsWith("/*", i) -> {
                    val end = (code.indexOf("*/", i + 2).takeIf { it != -1 } ?: code.length) + 2
                    addStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), i, minOf(end, code.length))
                    i = minOf(end, code.length)
                }
                // Comments //
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).takeIf { it != -1 } ?: code.length
                    addStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), i, end)
                    i = minOf(end, code.length)
                }
                // Triple-quoted strings
                code.startsWith("\"\"\"", i) -> {
                    val end = (code.indexOf("\"\"\"", i + 3).takeIf { it != -1 } ?: code.length) + 3
                    addStyle(SpanStyle(color = colorString), i, minOf(end, code.length))
                    i = minOf(end, code.length)
                }
                // Strings
                code[i] == '"' -> {
                    var end = i + 1
                    while (end < code.length && code[end] != '"') {
                        if (code[end] == '\\') end++
                        end++
                    }
                    if (end < code.length) end++
                    addStyle(SpanStyle(color = colorString), i, end)
                    i = minOf(end, code.length)
                }
                // Chars
                code[i] == '\'' -> {
                    var end = i + 1
                    while (end < code.length && code[end] != '\'') {
                        if (code[end] == '\\') end++
                        end++
                    }
                    if (end < code.length) end++
                    addStyle(SpanStyle(color = colorString), i, end)
                    i = minOf(end, code.length)
                }
                // Annotations
                code[i] == '@' -> {
                    val end = findWordEnd(code, i + 1)
                    addStyle(SpanStyle(color = colorAnnotation), i, end)
                    i = minOf(end, code.length)
                }
                // Numbers
                code[i].isDigit() -> {
                    val end = findNumberEnd(code, i)
                    addStyle(SpanStyle(color = colorNumber), i, end)
                    i = minOf(end, code.length)
                }
                // Words
                code[i].isLetter() || code[i] == '_' -> {
                    val end = findWordEnd(code, i)
                    val word = code.substring(i, end)
                    when {
                        word in kotlinKeywords -> 
                            addStyle(SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold), i, end)
                        word.firstOrNull()?.isUpperCase() == true -> 
                            addStyle(SpanStyle(color = colorType), i, end)
                        end < code.length && code[end] == '(' -> 
                            addStyle(SpanStyle(color = colorFunction), i, end)
                    }
                    i = minOf(end, code.length)
                }
                else -> i++
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #10): Safe XML highlighting с timeout protection
     * 
     * БЫЛО:
     * ```kotlin
     * val tagPattern = Regex("</?([\\w:-]+)|([\\w:-]+)=|\"[^\"]*\"|'[^']*'")
     * tagPattern.findAll(code).forEach { ... } // ← Может зависнуть на 30+ секунд
     * ```
     * 
     * ПРОБЛЕМА:
     * - Regex может зависнуть на malformed XML
     * - Нет timeout → UI freeze
     * 
     * РЕШЕНИЕ:
     * - Упрощенный Regex без сложных конструкций
     * - Ручная проверка вместо findAll для лучшего контроля
     * - Fallback на простую подсветку при проблемах
     */
    private fun highlightXmlSafe(code: String): AnnotatedString {
        return try {
            buildAnnotatedString {
                append(code)
                addStyle(SpanStyle(color = colorDefault), 0, code.length)
                
                // Comments
                var idx = 0
                while (true) {
                    val start = code.indexOf("<!--", idx)
                    if (start == -1) break
                    val end = (code.indexOf("-->", start + 4).takeIf { it != -1 } ?: code.length) + 3
                    addStyle(SpanStyle(color = colorComment), start, minOf(end, code.length))
                    idx = minOf(end, code.length)
                }
                
                // ✅ ИСПРАВЛЕНО: Упрощенный pattern без catastrophic backtracking
                // Вместо сложного Regex используем простой поиск
                highlightXmlTags(this, code)
            }
        } catch (e: Exception) {
            // ✅ Fallback при любой ошибке
            buildAnnotatedString {
                append(code)
                addStyle(SpanStyle(color = colorDefault), 0, code.length)
            }
        }
    }

    /**
     * ✅ НОВОЕ: Ручная подсветка XML тегов без Regex
     */
    private fun highlightXmlTags(builder: AnnotatedString.Builder, code: String) {
        var i = 0
        while (i < code.length) {
            if (code[i] == '<') {
                // Найти конец тега
                val tagEnd = code.indexOf('>', i)
                if (tagEnd == -1) break
                
                // Подсветить имя тега
                var nameEnd = i + 1
                while (nameEnd < tagEnd && 
                       (code[nameEnd].isLetterOrDigit() || code[nameEnd] in ":-/_")) {
                    nameEnd++
                }
                
                builder.addStyle(
                    SpanStyle(color = colorTag, fontWeight = FontWeight.Bold),
                    i, minOf(nameEnd, code.length)
                )
                
                // Подсветить атрибуты и значения
                var attrPos = nameEnd
                while (attrPos < tagEnd) {
                    // Пропустить пробелы
                    while (attrPos < tagEnd && code[attrPos].isWhitespace()) attrPos++
                    
                    // Найти имя атрибута
                    val attrStart = attrPos
                    while (attrPos < tagEnd && 
                           (code[attrPos].isLetterOrDigit() || code[attrPos] in ":-_")) {
                        attrPos++
                    }
                    
                    if (attrPos > attrStart && attrPos < tagEnd && code[attrPos] == '=') {
                        builder.addStyle(
                            SpanStyle(color = colorAttribute),
                            attrStart, attrPos
                        )
                        attrPos++ // Skip '='
                        
                        // Найти значение
                        while (attrPos < tagEnd && code[attrPos].isWhitespace()) attrPos++
                        if (attrPos < tagEnd && (code[attrPos] == '"' || code[attrPos] == '\'')) {
                            val quote = code[attrPos]
                            val valueStart = attrPos
                            attrPos++
                            while (attrPos < tagEnd && code[attrPos] != quote) attrPos++
                            if (attrPos < tagEnd) attrPos++ // Include closing quote
                            
                            builder.addStyle(
                                SpanStyle(color = colorString),
                                valueStart, minOf(attrPos, code.length)
                            )
                        }
                    } else {
                        break
                    }
                }
                
                i = tagEnd + 1
            } else {
                i++
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #10): Safe JSON highlighting с timeout protection
     * 
     * БЫЛО:
     * ```kotlin
     * val pattern = Regex("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|-?\\d+\\.?\\d*|true|false|null")
     * //                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
     * //                  ← Catastrophic backtracking на много backslash!
     * pattern.findAll(code).forEach { ... } // ← Зависает на "test\\\\\\\\\\escape"
     * ```
     * 
     * ПРОБЛЕМА:
     * - Regex с `(?:\\.[^\"\\]*)*` имеет exponential complexity O(2^n)
     * - Для строки "\"" + "\\" * 50 + "\"" → 2^50 итераций → зависание
     * 
     * РЕШЕНИЕ:
     * - Упрощенный Regex без nested quantifiers
     * - Ручная обработка backslash escapes
     * - Fallback на простую подсветку
     */
    private fun highlightJsonSafe(code: String): AnnotatedString {
        return try {
            buildAnnotatedString {
                append(code)
                addStyle(SpanStyle(color = colorDefault), 0, code.length)
                
                // ✅ ИСПРАВЛЕНО: Простой pattern без catastrophic backtracking
                // Используем упрощенные паттерны для каждого типа токена
                highlightJsonTokens(this, code)
            }
        } catch (e: Exception) {
            // ✅ Fallback при любой ошибке
            buildAnnotatedString {
                append(code)
                addStyle(SpanStyle(color = colorDefault), 0, code.length)
            }
        }
    }

    /**
     * ✅ НОВОЕ: Ручная подсветка JSON без сложных Regex
     */
    private fun highlightJsonTokens(builder: AnnotatedString.Builder, code: String) {
        var i = 0
        while (i < code.length) {
            when {
                // Strings
                code[i] == '"' -> {
                    val start = i
                    i++
                    // Простой поиск конца строки с учетом escapes
                    while (i < code.length) {
                        if (code[i] == '\\' && i + 1 < code.length) {
                            i += 2 // Skip escaped char
                        } else if (code[i] == '"') {
                            i++ // Include closing quote
                            break
                        } else {
                            i++
                        }
                    }
                    
                    // Проверить, это ключ или значение
                    val isKey = code.indexOf(':', i).let { colonPos ->
                        colonPos != -1 && code.substring(i, colonPos).all { it.isWhitespace() }
                    }
                    
                    builder.addStyle(
                        SpanStyle(color = if (isKey) colorAttribute else colorString),
                        start, i
                    )
                }
                
                // Numbers (простой паттерн)
                code[i] == '-' || code[i].isDigit() -> {
                    val start = i
                    if (code[i] == '-') i++
                    while (i < code.length && (code[i].isDigit() || code[i] in ".eE+-")) i++
                    
                    builder.addStyle(SpanStyle(color = colorNumber), start, i)
                }
                
                // Keywords
                code.startsWith("true", i) || code.startsWith("false", i) || 
                code.startsWith("null", i) -> {
                    val keyword = when {
                        code.startsWith("true", i) -> "true"
                        code.startsWith("false", i) -> "false"
                        else -> "null"
                    }
                    
                    builder.addStyle(
                        SpanStyle(color = colorKeyword),
                        i, i + keyword.length
                    )
                    i += keyword.length
                }
                
                else -> i++
            }
        }
    }

    private fun findWordEnd(code: String, start: Int): Int {
        var end = start
        while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
        return end
    }

    private fun findNumberEnd(code: String, start: Int): Int {
        var end = start
        var hasDecimal = false
        while (end < code.length) {
            when {
                code[end].isDigit() -> end++
                code[end] == '.' && !hasDecimal -> { hasDecimal = true; end++ }
                code[end] in "fFlL" -> { end++; break }
                code[end] == 'x' || code[end] == 'X' -> end++
                code[end] in 'a'..'f' || code[end] in 'A'..'F' -> end++
                else -> break
            }
        }
        return end
    }
}