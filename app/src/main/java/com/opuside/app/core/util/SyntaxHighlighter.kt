package com.opuside.app.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.collection.LruCache

/**
 * ✅ ПОЛНОСТЬЮ ИСПРАВЛЕНО - Безопасная подсветка синтаксиса
 */
object SyntaxHighlighter {

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
                "java" -> highlightKotlin(code)
                "xml" -> highlightXml(code)
                "json" -> highlightJson(code)
                "gradle" -> highlightKotlin(code)
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

    private fun highlightKotlin(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2)
                    val finalEnd = if (end == -1) code.length else end + 2
                    addStyle(
                        SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), 
                        i, 
                        finalEnd.coerceAtMost(code.length)
                    )
                    i = finalEnd
                }
                
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i)
                    val finalEnd = if (end == -1) code.length else end
                    addStyle(
                        SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), 
                        i, 
                        finalEnd
                    )
                    i = finalEnd
                }
                
                code.startsWith("\"\"\"", i) -> {
                    val end = code.indexOf("\"\"\"", i + 3)
                    val finalEnd = if (end == -1) code.length else end + 3
                    addStyle(SpanStyle(color = colorString), i, finalEnd.coerceAtMost(code.length))
                    i = finalEnd
                }
                
                code[i] == '"' -> {
                    var end = i + 1
                    while (end < code.length && code[end] != '"') {
                        if (code[end] == '\\' && end + 1 < code.length) end++
                        end++
                    }
                    if (end < code.length) end++
                    addStyle(SpanStyle(color = colorString), i, end.coerceAtMost(code.length))
                    i = end
                }
                
                code[i] == '\'' -> {
                    var end = i + 1
                    while (end < code.length && code[end] != '\'') {
                        if (code[end] == '\\' && end + 1 < code.length) end++
                        end++
                    }
                    if (end < code.length) end++
                    addStyle(SpanStyle(color = colorString), i, end.coerceAtMost(code.length))
                    i = end
                }
                
                code[i] == '@' && (i + 1 < code.length && code[i + 1].isLetter()) -> {
                    val end = findWordEnd(code, i + 1)
                    addStyle(SpanStyle(color = colorAnnotation), i, end)
                    i = end
                }
                
                code[i].isDigit() || (code[i] == '-' && i + 1 < code.length && code[i + 1].isDigit()) -> {
                    val end = findNumberEnd(code, i)
                    addStyle(SpanStyle(color = colorNumber), i, end)
                    i = end
                }
                
                code[i].isLetter() || code[i] == '_' -> {
                    val end = findWordEnd(code, i)
                    val word = code.substring(i, end)
                    
                    when {
                        word in kotlinKeywords -> {
                            addStyle(
                                SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold), 
                                i, 
                                end
                            )
                        }
                        word.firstOrNull()?.isUpperCase() == true -> {
                            addStyle(SpanStyle(color = colorType), i, end)
                        }
                        end < code.length && code[end] == '(' -> {
                            addStyle(SpanStyle(color = colorFunction), i, end)
                        }
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
            idx = finalEnd
        }
        
        var i = 0
        while (i < code.length) {
            if (code[i] == '<' && i + 1 < code.length && code[i + 1] != '!') {
                val tagEnd = code.indexOf('>', i)
                if (tagEnd == -1) break
                
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

    private fun highlightJson(code: String): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = colorDefault), 0, code.length)
        
        var i = 0
        while (i < code.length) {
            when {
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
                    
                    var j = i
                    while (j < code.length && code[j].isWhitespace()) j++
                    val isKey = j < code.length && code[j] == ':'
                    
                    addStyle(
                        SpanStyle(color = if (isKey) colorAttribute else colorString),
                        start,
                        i.coerceAtMost(code.length)
                    )
                }
                
                code[i] == '-' || code[i].isDigit() -> {
                    val start = i
                    if (code[i] == '-') i++
                    
                    while (i < code.length && (code[i].isDigit() || code[i] in ".eE+-")) i++
                    
                    addStyle(SpanStyle(color = colorNumber), start, i)
                }
                
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
                code[end] in "abcdefABCDEF" -> end++
                code[end] in "eE" -> end++
                else -> break
            }
        }
        return end
    }
}