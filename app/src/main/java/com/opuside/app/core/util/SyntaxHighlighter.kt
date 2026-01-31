package com.opuside.app.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.collection.LruCache

/**
 * Подсветка синтаксиса для кода.
 * Поддерживает Kotlin, Java, XML, JSON.
 * 
 * 🔴 ПРОБЛЕМА #14: Blocking Main Thread
 * Подсветка синтаксиса выполняется синхронно в UI thread.
 * Для больших файлов (1000+ строк) может вызвать ANR (Application Not Responding).
 * 
 * Должно быть:
 * - Async подсветка в background thread (Dispatchers.Default)
 * - Прогрессивная подсветка (видимые строки сначала)
 * - Cancellable корутины
 * 
 * Сейчас:
 * - Синхронный вызов из Composable
 * - Блокирует UI thread на 100-500ms для файлов 1000+ строк
 * - Нет возможности отменить длительную операцию
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
     * 🔴 ПРОБЛЕМА #14: Blocking Main Thread (строка 35+)
     * 
     * Этот метод вызывается СИНХРОННО из Composable функций:
     * 
     * ```kotlin
     * @Composable
     * fun CodeLine(...) {
     *     val highlighted = SyntaxHighlighter.highlight(line, language) // ← БЛОКИРУЕТ UI THREAD!
     *     Text(text = highlighted)
     * }
     * ```
     * 
     * Проблемы:
     * 1. Для строки из 200 символов: ~2-5ms обработки
     * 2. Для файла из 1000 строк: 1000 * 3ms = 3 секунды БЛОКИРОВКИ UI
     * 3. LazyColumn рендерит ~20 строк сразу при скролле = 60ms задержки
     * 4. Regex операции (highlightXml, highlightJson) особенно медленные
     * 5. Нет возможности отменить операцию при быстром скролле
     * 
     * РЕШЕНИЕ (которое НЕ реализовано):
     * ```kotlin
     * suspend fun highlightAsync(code: String, language: String): AnnotatedString {
     *     return withContext(Dispatchers.Default) {
     *         // ... подсветка в background thread
     *     }
     * }
     * ```
     * 
     * Но сейчас это обычная синхронная функция!
     */
    fun highlight(code: String, language: String): AnnotatedString {
        val key = code to language
        cache.get(key)?.let { return it }

        // 🔴 Вся обработка происходит СИНХРОННО в вызывающем thread
        // Если вызвано из UI thread (Composable) -> блокирует UI
        val result = when (language.lowercase()) {
            "kotlin", "kt", "kts", "gradle" -> highlightKotlin(code)
            "java" -> highlightKotlin(code)
            "xml" -> highlightXml(code)  // 🔴 Особенно медленно - Regex
            "json" -> highlightJson(code) // 🔴 Особенно медленно - Regex
            else -> buildAnnotatedString { append(code) }
        }

        cache.put(key, result)
        return result
    }

    /**
     * 🔴 Сложность: O(n) где n = длина кода
     * Для строки 200 символов: ~50-100 итераций цикла while
     * Каждая итерация: string operations, indexOf, substring
     */
    private fun highlightKotlin(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        var i = 0
        // 🔴 Цикл выполняется синхронно, может быть тысячи итераций
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
                    val word = code.substring(i, end) // 🔴 String allocation
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
     * 🔴 ОСОБЕННО МЕДЛЕННО: Regex.findAll() на больших строках
     * Для XML строки в 500 символов: ~10-20ms
     * Для 20 видимых строк в LazyColumn: 200-400ms блокировки UI
     */
    private fun highlightXml(code: String): AnnotatedString = buildAnnotatedString {
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
        
        // 🔴 Regex - самая медленная часть
        // Создает iterator, проходит по всей строке, создает Match объекты
        val tagPattern = Regex("</?([\\w:-]+)|([\\w:-]+)=|\"[^\"]*\"|'[^']*'")
        tagPattern.findAll(code).forEach { match ->
            val value = match.value
            when {
                value.startsWith("<") -> 
                    addStyle(SpanStyle(color = colorTag, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
                value.endsWith("=") -> 
                    addStyle(SpanStyle(color = colorAttribute), match.range.first, match.range.last)
                value.startsWith("\"") || value.startsWith("'") -> 
                    addStyle(SpanStyle(color = colorString), match.range.first, match.range.last + 1)
            }
        }
    }

    /**
     * 🔴 ОСОБЕННО МЕДЛЕННО: Regex на JSON
     * JSON может быть очень длинным (minified JSON в одну строку = 10k+ символов)
     * Regex по 10k строке = 50-100ms блокировки UI
     */
    private fun highlightJson(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        // 🔴 Сложный Regex pattern с backtracking
        val pattern = Regex("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|-?\\d+\\.?\\d*|true|false|null")
        pattern.findAll(code).forEach { match ->
            val value = match.value
            val color = when {
                value.startsWith("\"") && match.range.last + 1 < code.length && code[match.range.last + 1] == ':' -> colorAttribute
                value.startsWith("\"") -> colorString
                value == "true" || value == "false" || value == "null" -> colorKeyword
                else -> colorNumber
            }
            addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
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