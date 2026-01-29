package com.opuside.app.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Подсветка синтаксиса для кода.
 * Поддерживает Kotlin, Java, XML, JSON.
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

    fun highlight(code: String, language: String): AnnotatedString {
        return when (language.lowercase()) {
            "kotlin", "kt", "kts", "gradle" -> highlightKotlin(code)
            "java" -> highlightKotlin(code) // Similar syntax
            "xml" -> highlightXml(code)
            "json" -> highlightJson(code)
            else -> buildAnnotatedString { append(code) }
        }
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
                    i = end
                }
                // Comments //
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).takeIf { it != -1 } ?: code.length
                    addStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), i, end)
                    i = end
                }
                // Triple-quoted strings
                code.startsWith("\"\"\"", i) -> {
                    val end = (code.indexOf("\"\"\"", i + 3).takeIf { it != -1 } ?: code.length) + 3
                    addStyle(SpanStyle(color = colorString), i, minOf(end, code.length))
                    i = end
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
                    i = end
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
                    i = end
                }
                // Annotations
                code[i] == '@' -> {
                    val end = findWordEnd(code, i + 1)
                    addStyle(SpanStyle(color = colorAnnotation), i, end)
                    i = end
                }
                // Numbers
                code[i].isDigit() -> {
                    val end = findNumberEnd(code, i)
                    addStyle(SpanStyle(color = colorNumber), i, end)
                    i = end
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
                    i = end
                }
                else -> i++
            }
        }
    }

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
            idx = end
        }
        
        // Tags and attributes
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

    private fun highlightJson(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
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
