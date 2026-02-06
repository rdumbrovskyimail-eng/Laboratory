package com.opuside.app.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.collection.LruCache

object SyntaxHighlighter {

    // ✅ ПРОБЛЕМА 9: Темные цвета для светлого фона
    private val colorKeyword = Color(0xFF0000FF)          // Синий
    private val colorString = Color(0xFF008000)           // Зеленый
    private val colorNumber = Color(0xFFFF6600)           // Оранжевый
    private val colorComment = Color(0xFF808080)          // Серый
    private val colorFunction = Color(0xFF795E26)         // Коричневый
    private val colorType = Color(0xFF267F99)             // Бирюзовый
    private val colorAnnotation = Color(0xFFD7BA7D)       // Золотистый
    private val colorTag = Color(0xFF0000FF)              // Синий (XML)
    private val colorAttribute = Color(0xFF9C27B0)        // Фиолетовый (XML)
    private val colorDefault = Color(0xFF000000)          // Черный

    private val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "interface", "object", "enum", "sealed",
        "data", "annotation", "companion", "abstract", "open", "override", "private",
        "protected", "public", "internal", "final", "const", "lateinit", "lazy",
        "by", "if", "else", "when", "while", "for", "do", "return", "break",
        "continue", "throw", "try", "catch", "finally", "import", "package",
        "as", "is", "in", "out", "true", "false", "null", "this", "super",
        "suspend", "inline", "reified", "typealias", "constructor", "init",
        "where", "get", "set", "field", "it", "also", "apply", "let", "run",
        "with", "takeIf", "takeUnless", "repeat", "TODO", "require", "check",
        "error", "assert", "operator", "infix", "tailrec", "external", "actual",
        "expect", "crossinline", "noinline", "vararg"
    )

    private val cache = LruCache<Pair<String, String>, AnnotatedString>(200)

    fun highlight(code: String, language: String): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString("")
        
        val key = code to language
        cache.get(key)?.let { return it }

        if (code.length > 10_000) {
            return buildAnnotatedString {
                append(code)
                addStyle(SpanStyle(color = colorDefault), 0, code.length)
            }.also { cache.put(key, it) }
        }

        val result = try {
            when (language.lowercase()) {
                "kotlin", "kt", "kts" -> highlightKotlin(code)
                "java" -> highlightJava(code)
                "xml" -> highlightXml(code)
                "json" -> highlightJson(code)
                "gradle" -> highlightKotlin(code)
                "yaml", "yml" -> highlightYaml(code)
                "properties" -> highlightProperties(code)
                else -> buildAnnotatedString { 
                    append(code)
                    addStyle(SpanStyle(color = colorDefault), 0, code.length)
                }
            }
        } catch (e: Exception) {
            buildAnnotatedString { 
                append(code)
                addStyle(SpanStyle(color = colorDefault), 0, code.length)
            }
        }

        cache.put(key, result)
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ ПРОБЛЕМА 5: УЛУЧШЕННАЯ ПОДСВЕТКА KOTLIN С КОНТЕКСТОМ
    // ═══════════════════════════════════════════════════════════════════════════

    private fun highlightKotlin(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        val excludedRanges = ExcludedRanges()
        
        // ШАГ 1: Многострочные комментарии (высший приоритет)
        var i = 0
        while (i < code.length) {
            if (code.startsWith("/*", i)) {
                val end = code.indexOf("*/", i + 2)
                val finalEnd = if (end == -1) code.length else end + 2
                addStyle(
                    SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), 
                    i, 
                    finalEnd.coerceAtMost(code.length)
                )
                excludedRanges.add(i, finalEnd.coerceAtMost(code.length))
                i = finalEnd
            } else {
                i++
            }
        }
        
        // ШАГ 2: Однострочные комментарии
        i = 0
        while (i < code.length) {
            if (code.startsWith("//", i) && !excludedRanges.contains(i)) {
                val end = code.indexOf('\n', i)
                val finalEnd = if (end == -1) code.length else end
                addStyle(
                    SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), 
                    i, 
                    finalEnd
                )
                excludedRanges.add(i, finalEnd)
                i = finalEnd
            } else {
                i++
            }
        }
        
        // ШАГ 3: Тройные кавычки (raw strings)
        i = 0
        while (i < code.length) {
            if (code.startsWith("\"\"\"", i) && !excludedRanges.contains(i)) {
                val end = code.indexOf("\"\"\"", i + 3)
                val finalEnd = if (end == -1) code.length else end + 3
                addStyle(SpanStyle(color = colorString), i, finalEnd.coerceAtMost(code.length))
                excludedRanges.add(i, finalEnd.coerceAtMost(code.length))
                i = finalEnd
            } else {
                i++
            }
        }
        
        // ШАГ 4: Обычные строки
        i = 0
        while (i < code.length) {
            if (code[i] == '"' && !excludedRanges.contains(i)) {
                var end = i + 1
                while (end < code.length && !excludedRanges.contains(end)) {
                    if (code[end] == '\\' && end + 1 < code.length) {
                        end += 2
                    } else if (code[end] == '"') {
                        end++
                        break
                    } else {
                        end++
                    }
                }
                addStyle(SpanStyle(color = colorString), i, end.coerceAtMost(code.length))
                excludedRanges.add(i, end.coerceAtMost(code.length))
                i = end
            } else {
                i++
            }
        }
        
        // ШАГ 5: Символьные литералы
        i = 0
        while (i < code.length) {
            if (code[i] == '\'' && !excludedRanges.contains(i)) {
                var end = i + 1
                while (end < code.length && !excludedRanges.contains(end)) {
                    if (code[end] == '\\' && end + 1 < code.length) {
                        end += 2
                    } else if (code[end] == '\'') {
                        end++
                        break
                    } else {
                        end++
                    }
                }
                addStyle(SpanStyle(color = colorString), i, end.coerceAtMost(code.length))
                excludedRanges.add(i, end.coerceAtMost(code.length))
                i = end
            } else {
                i++
            }
        }
        
        // ШАГ 6: Аннотации (@Override, @Inject)
        i = 0
        while (i < code.length) {
            if (code[i] == '@' && !excludedRanges.contains(i) && 
                i + 1 < code.length && code[i + 1].isLetter()) {
                val end = findWordEnd(code, i + 1)
                addStyle(SpanStyle(color = colorAnnotation, fontWeight = FontWeight.Bold), i, end)
                excludedRanges.add(i, end)
                i = end
            } else {
                i++
            }
        }
        
        // ШАГ 7: Числа
        i = 0
        while (i < code.length) {
            if (!excludedRanges.contains(i) && 
                (code[i].isDigit() || (code[i] == '-' && i + 1 < code.length && code[i + 1].isDigit()))) {
                val end = findNumberEnd(code, i)
                addStyle(SpanStyle(color = colorNumber, fontWeight = FontWeight.Bold), i, end)
                excludedRanges.add(i, end)
                i = end
            } else {
                i++
            }
        }
        
        // ШАГ 8: Ключевые слова и идентификаторы
        i = 0
        while (i < code.length) {
            if (!excludedRanges.contains(i) && (code[i].isLetter() || code[i] == '_')) {
                val end = findWordEnd(code, i)
                val word = code.substring(i, end)
                
                when {
                    // Ключевые слова
                    word in kotlinKeywords -> {
                        addStyle(
                            SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold), 
                            i, 
                            end
                        )
                    }
                    // Типы (CamelCase)
                    word.firstOrNull()?.isUpperCase() == true -> {
                        addStyle(SpanStyle(color = colorType, fontWeight = FontWeight.SemiBold), i, end)
                    }
                    // Функции (слово перед '(')
                    end < code.length && code[end] == '(' -> {
                        addStyle(SpanStyle(color = colorFunction), i, end)
                    }
                }
                
                i = end
            } else {
                i++
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JAVA HIGHLIGHTING
    // ═══════════════════════════════════════════════════════════════════════════

    private fun highlightJava(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        val javaKeywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null"
        )
        
        val excludedRanges = ExcludedRanges()
        
        // Комментарии и строки (аналогично Kotlin)
        highlightCommentsAndStrings(code, excludedRanges)
        
        // Аннотации (@Override)
        var i = 0
        while (i < code.length) {
            if (code[i] == '@' && !excludedRanges.contains(i)) {
                val end = findWordEnd(code, i + 1)
                addStyle(SpanStyle(color = colorAnnotation), i, end)
                i = end
            } else {
                i++
            }
        }
        
        // Ключевые слова
        i = 0
        while (i < code.length) {
            if (!excludedRanges.contains(i) && code[i].isLetter()) {
                val end = findWordEnd(code, i)
                val word = code.substring(i, end)
                
                if (word in javaKeywords) {
                    addStyle(SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold), i, end)
                }
                i = end
            } else {
                i++
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // XML HIGHLIGHTING
    // ═══════════════════════════════════════════════════════════════════════════

    private fun highlightXml(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        val excludedRanges = ExcludedRanges()
        
        // Комментарии
        var idx = 0
        while (idx < code.length) {
            val commentStart = code.indexOf("<!--", idx)
            if (commentStart == -1) break
            
            val commentEnd = code.indexOf("-->", commentStart + 4)
            val finalEnd = if (commentEnd == -1) code.length else commentEnd + 3
            
            addStyle(
                SpanStyle(color = colorComment, fontStyle = FontStyle.Italic),
                commentStart,
                finalEnd.coerceAtMost(code.length)
            )
            excludedRanges.add(commentStart, finalEnd.coerceAtMost(code.length))
            idx = finalEnd
        }
        
        // Теги и атрибуты
        var i = 0
        while (i < code.length) {
            if (code[i] == '<' && !excludedRanges.contains(i) && 
                i + 1 < code.length && code[i + 1] != '!') {
                
                val tagEnd = code.indexOf('>', i)
                if (tagEnd == -1) break
                
                // Имя тега
                var nameStart = i + 1
                if (code[nameStart] == '/') nameStart++
                
                var nameEnd = nameStart
                while (nameEnd < tagEnd && 
                       (code[nameEnd].isLetterOrDigit() || code[nameEnd] in ":-_")) {
                    nameEnd++
                }
                
                if (nameEnd > nameStart) {
                    addStyle(
                        SpanStyle(color = colorTag, fontWeight = FontWeight.Bold),
                        i,
                        nameEnd.coerceAtMost(code.length)
                    )
                }
                
                // Атрибуты
                var attrPos = nameEnd
                while (attrPos < tagEnd) {
                    while (attrPos < tagEnd && code[attrPos].isWhitespace()) attrPos++
                    
                    val attrStart = attrPos
                    while (attrPos < tagEnd && 
                           (code[attrPos].isLetterOrDigit() || code[attrPos] in ":-_")) {
                        attrPos++
                    }
                    
                    if (attrPos > attrStart && attrPos < tagEnd && code[attrPos] == '=') {
                        addStyle(SpanStyle(color = colorAttribute), attrStart, attrPos)
                        attrPos++
                        
                        while (attrPos < tagEnd && code[attrPos].isWhitespace()) attrPos++
                        
                        if (attrPos < tagEnd && (code[attrPos] == '"' || code[attrPos] == '\'')) {
                            val quote = code[attrPos]
                            val valueStart = attrPos
                            attrPos++
                            
                            while (attrPos < tagEnd && code[attrPos] != quote) {
                                if (code[attrPos] == '\\' && attrPos + 1 < tagEnd) attrPos++
                                attrPos++
                            }
                            if (attrPos < tagEnd) attrPos++
                            
                            addStyle(
                                SpanStyle(color = colorString),
                                valueStart,
                                attrPos.coerceAtMost(code.length)
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

    // ═══════════════════════════════════════════════════════════════════════════
    // JSON HIGHLIGHTING
    // ═══════════════════════════════════════════════════════════════════════════

    private fun highlightJson(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        var i = 0
        while (i < code.length) {
            when {
                // Строки
                code[i] == '"' -> {
                    val start = i
                    i++
                    
                    while (i < code.length) {
                        if (code[i] == '\\' && i + 1 < code.length) {
                            i += 2
                        } else if (code[i] == '"') {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                    
                    // Проверяем, это ключ или значение
                    var j = i
                    while (j < code.length && code[j].isWhitespace()) j++
                    val isKey = j < code.length && code[j] == ':'
                    
                    addStyle(
                        SpanStyle(color = if (isKey) colorAttribute else colorString),
                        start,
                        i.coerceAtMost(code.length)
                    )
                }
                
                // Числа
                code[i] == '-' || code[i].isDigit() -> {
                    val start = i
                    if (code[i] == '-') i++
                    
                    while (i < code.length && (code[i].isDigit() || code[i] in ".eE+-")) i++
                    
                    addStyle(SpanStyle(color = colorNumber), start, i)
                }
                
                // Булевы значения и null
                code.startsWith("true", i) -> {
                    addStyle(SpanStyle(color = colorKeyword), i, i + 4)
                    i += 4
                }
                code.startsWith("false", i) -> {
                    addStyle(SpanStyle(color = colorKeyword), i, i + 5)
                    i += 5
                }
                code.startsWith("null", i) -> {
                    addStyle(SpanStyle(color = colorKeyword), i, i + 4)
                    i += 4
                }
                
                else -> i++
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // YAML HIGHLIGHTING
    // ═══════════════════════════════════════════════════════════════════════════

    private fun highlightYaml(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        code.lines().forEachIndexed { lineIndex, line ->
            val lineStart = code.lines().take(lineIndex).sumOf { it.length + 1 }
            
            // Комментарии
            val commentIndex = line.indexOf('#')
            if (commentIndex != -1) {
                addStyle(
                    SpanStyle(color = colorComment, fontStyle = FontStyle.Italic),
                    lineStart + commentIndex,
                    lineStart + line.length
                )
            }
            
            // Ключи (до ':')
            val colonIndex = line.indexOf(':')
            if (colonIndex != -1 && (commentIndex == -1 || colonIndex < commentIndex)) {
                addStyle(
                    SpanStyle(color = colorAttribute, fontWeight = FontWeight.Bold),
                    lineStart,
                    lineStart + colonIndex
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROPERTIES HIGHLIGHTING
    // ═══════════════════════════════════════════════════════════════════════════

    private fun highlightProperties(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        code.lines().forEachIndexed { lineIndex, line ->
            val lineStart = code.lines().take(lineIndex).sumOf { it.length + 1 }
            val trimmed = line.trim()
            
            when {
                trimmed.startsWith('#') || trimmed.startsWith('!') -> {
                    addStyle(
                        SpanStyle(color = colorComment, fontStyle = FontStyle.Italic),
                        lineStart,
                        lineStart + line.length
                    )
                }
                trimmed.contains('=') -> {
                    val eqIndex = line.indexOf('=')
                    addStyle(
                        SpanStyle(color = colorAttribute, fontWeight = FontWeight.Bold),
                        lineStart,
                        lineStart + eqIndex
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun AnnotatedString.Builder.highlightCommentsAndStrings(
        code: String, 
        excludedRanges: ExcludedRanges
    ) {
        // Многострочные комментарии
        var i = 0
        while (i < code.length) {
            if (code.startsWith("/*", i)) {
                val end = code.indexOf("*/", i + 2)
                val finalEnd = if (end == -1) code.length else end + 2
                addStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), i, finalEnd)
                excludedRanges.add(i, finalEnd)
                i = finalEnd
            } else {
                i++
            }
        }
        
        // Однострочные комментарии
        i = 0
        while (i < code.length) {
            if (code.startsWith("//", i) && !excludedRanges.contains(i)) {
                val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                addStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), i, end)
                excludedRanges.add(i, end)
                i = end
            } else {
                i++
            }
        }
        
        // Строки
        i = 0
        while (i < code.length) {
            if (code[i] == '"' && !excludedRanges.contains(i)) {
                var end = i + 1
                while (end < code.length) {
                    if (code[end] == '\\' && end + 1 < code.length) end += 2
                    else if (code[end] == '"') { end++; break }
                    else end++
                }
                addStyle(SpanStyle(color = colorString), i, end)
                excludedRanges.add(i, end)
                i = end
            } else {
                i++
            }
        }
    }

    private fun findWordEnd(code: String, start: Int): Int {
        var end = start
        while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) {
            end++
        }
        return end
    }

    private fun findNumberEnd(code: String, start: Int): Int {
        var end = start
        var hasDecimal = false
        
        if (end < code.length && code[end] == '-') end++
        
        while (end < code.length) {
            when {
                code[end].isDigit() -> end++
                code[end] == '.' && !hasDecimal -> { 
                    hasDecimal = true
                    end++ 
                }
                code[end] in "fFlLdD" -> { 
                    end++
                    break 
                }
                code[end] in "xX" && end == start + 1 -> end++
                code[end] in "abcdefABCDEF" && start < end - 1 && code[start + 1] in "xX" -> end++
                code[end] in "eE" -> end++
                else -> break
            }
        }
        return end
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXCLUDED RANGES (для предотвращения двойной подсветки)
    // ═══════════════════════════════════════════════════════════════════════════

    private class ExcludedRanges {
        private val ranges = mutableListOf<IntRange>()
        
        fun add(start: Int, end: Int) {
            ranges.add(start until end)
        }
        
        fun contains(index: Int): Boolean {
            return ranges.any { index in it }
        }
    }
}