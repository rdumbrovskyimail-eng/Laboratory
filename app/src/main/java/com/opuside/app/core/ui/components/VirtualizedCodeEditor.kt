package com.opuside.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.core.util.SyntaxHighlighter
import kotlinx.coroutines.*

/**
 * ПРОФЕССИОНАЛЬНЫЙ CODE EDITOR
 * 
 * Архитектура на основе Sora Editor и лучших практик:
 * - Единый BasicTextField БЕЗ LazyColumn overlay (это источник конфликтов!)
 * - Column с verticalScroll для простого скроллинга
 * - Построчный TextLayoutResult для нумерации
 * - Прямой ввод текста без прокси-слоёв
 */
@Composable
fun VirtualizedCodeEditor(
    content: String,
    onContentChange: (String) -> Unit,
    language: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    showLineNumbers: Boolean = true,
    fontSize: Int = 12,
    onCursorPositionChanged: ((line: Int, column: Int) -> Unit)? = null
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(content)) }

    LaunchedEffect(content) {
        if (textFieldValue.text != content) {
            val newSelection = if (textFieldValue.selection.start <= content.length) {
                textFieldValue.selection
            } else {
                TextRange(content.length)
            }
            textFieldValue = TextFieldValue(text = content, selection = newSelection)
        }
    }

    // Асинхронная подсветка синтаксиса ЦЕЛОГО текста
    val highlightedText by produceState(
        initialValue = AnnotatedString(textFieldValue.text),
        key1 = textFieldValue.text,
        key2 = language
    ) {
        value = withContext(Dispatchers.Default) {
            try {
                // Подсветка всего текста целиком
                val lines = textFieldValue.text.lines()
                buildAnnotatedString {
                    lines.forEachIndexed { index, line ->
                        append(SyntaxHighlighter.highlight(line, language))
                        if (index < lines.size - 1) append("\n")
                    }
                }
            } catch (e: Exception) {
                AnnotatedString(textFieldValue.text)
            }
        }
    }

    val lines = remember(textFieldValue.text) {
        val result = textFieldValue.text.lines()
        if (result.isEmpty()) listOf("") else result
    }

    val cursorLine = remember(textFieldValue.selection.start, textFieldValue.text) {
        textFieldValue.text.take(textFieldValue.selection.start).count { it == '\n' }
    }

    val cursorColumn = remember(textFieldValue.selection.start, textFieldValue.text) {
        val beforeCursor = textFieldValue.text.take(textFieldValue.selection.start)
        beforeCursor.length - (beforeCursor.lastIndexOf('\n') + 1)
    }

    LaunchedEffect(cursorLine, cursorColumn) {
        onCursorPositionChanged?.invoke(cursorLine + 1, cursorColumn)
    }

    LaunchedEffect(textFieldValue.text) {
        if (textFieldValue.text != content) {
            onContentChange(textFieldValue.text)
        }
    }

    val undoRedoManager = remember { UndoRedoManager() }

    LaunchedEffect(textFieldValue.text) {
        delay(500)
        undoRedoManager.recordState(textFieldValue)
    }

    val keyboardHandler = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || readOnly) return@onKeyEvent false

        when {
            event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> {
                undoRedoManager.undo()?.let { textFieldValue = it }
                true
            }
            (event.isCtrlPressed && event.isShiftPressed && event.key == Key.Z) ||
            (event.isCtrlPressed && event.key == Key.Y) -> {
                undoRedoManager.redo()?.let { textFieldValue = it }
                true
            }
            event.key == Key.Tab -> {
                val selection = textFieldValue.selection
                val newText = textFieldValue.text.substring(0, selection.start) +
                        "    " +
                        textFieldValue.text.substring(selection.end)
                textFieldValue = TextFieldValue(
                    text = newText,
                    selection = TextRange(selection.start + 4)
                )
                true
            }
            else -> false
        }
    }

    val lineNumberWidth = remember(lines.size) {
        val maxDigits = lines.size.toString().length
        (maxDigits * 8 + 8).dp
    }

    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = modifier.then(keyboardHandler),
            color = EditorTheme.backgroundColor
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // НУМЕРАЦИЯ СТРОК
                if (showLineNumbers) {
                    LineNumbersColumn(
                        lineCount = lines.size,
                        currentLine = cursorLine,
                        fontSize = fontSize,
                        width = lineNumberWidth,
                        scrollState = verticalScrollState
                    )
                    VerticalDivider(color = EditorTheme.dividerColor)
                }

                // ЕДИНСТВЕННОЕ РЕДАКТИРУЕМОЕ ПОЛЕ
                SingleTextFieldEditor(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    highlightedText = highlightedText,
                    currentLine = cursorLine,
                    fontSize = fontSize,
                    readOnly = readOnly,
                    focusRequester = focusRequester,
                    verticalScrollState = verticalScrollState,
                    horizontalScrollState = horizontalScrollState,
                    onFocusChanged = { isFocused = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/**
 * Единое поле с прямым скроллингом (БЕЗ LazyColumn конфликтов!)
 */
@Composable
private fun SingleTextFieldEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    highlightedText: AnnotatedString,
    currentLine: Int,
    fontSize: Int,
    readOnly: Boolean,
    focusRequester: FocusRequester,
    verticalScrollState: androidx.compose.foundation.ScrollState,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var isCursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            isCursorVisible = !isCursorVisible
        }
    }

    LaunchedEffect(value.selection.start) {
        isCursorVisible = true
    }

    // Подсветка текущей строки
    val decoratedText = remember(highlightedText, currentLine) {
        buildAnnotatedString {
            val lines = highlightedText.text.lines()
            lines.forEachIndexed { index, line ->
                // Добавляем подсветку текущей строки
                if (index == currentLine) {
                    withStyle(SpanStyle(background = EditorTheme.currentLineBackground)) {
                        append(highlightedText.subSequence(
                            highlightedText.text.take(
                                lines.take(index).sumOf { it.length + 1 }
                            ).length,
                            highlightedText.text.take(
                                lines.take(index + 1).sumOf { it.length + 1 }
                            ).length.coerceAtMost(highlightedText.length)
                        ))
                    }
                } else {
                    append(highlightedText.subSequence(
                        highlightedText.text.take(
                            lines.take(index).sumOf { it.length + 1 }
                        ).length,
                        highlightedText.text.take(
                            lines.take(index + 1).sumOf { it.length + 1 }
                        ).length.coerceAtMost(highlightedText.length)
                    ))
                }
                if (index < lines.size - 1) append("\n")
            }
        }
    }

    BasicTextField(
        value = value.copy(annotatedString = decoratedText),
        onValueChange = onValueChange,
        modifier = modifier
            .verticalScroll(verticalScrollState)  // ПРЯМОЙ СКРОЛЛИНГ!
            .horizontalScroll(horizontalScrollState)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .drawBehind {
                // Кастомный курсор
                if (isCursorVisible) {
                    textLayoutResult?.let { layout ->
                        val cursorPos = value.selection.start.coerceIn(0, value.text.length)
                        try {
                            val cursorRect = layout.getCursorRect(cursorPos)
                            drawLine(
                                color = EditorTheme.cursorColor,
                                start = Offset(cursorRect.left, cursorRect.top),
                                end = Offset(cursorRect.left, cursorRect.bottom),
                                strokeWidth = 2.dp.toPx()
                            )
                        } catch (_: Exception) {}
                    }
                }
            },
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.4).sp,
            textDirection = TextDirection.Ltr,
            color = Color.Transparent // Текст невидим, видна только подсветка
        ),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.None
        ),
        readOnly = readOnly,
        onTextLayout = { textLayoutResult = it }
    )
}

/**
 * Нумерация строк с синхронизированным скроллингом
 */
@Composable
private fun LineNumbersColumn(
    lineCount: Int,
    currentLine: Int,
    fontSize: Int,
    width: androidx.compose.ui.unit.Dp,
    scrollState: androidx.compose.foundation.ScrollState
) {
    val lineHeight = with(LocalDensity.current) { (fontSize * 1.4).sp.toDp() }

    Column(
        modifier = Modifier
            .width(width)
            .verticalScroll(scrollState, enabled = false)  // Синхронизация
            .background(EditorTheme.lineNumbersBackground)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        repeat(lineCount) { index ->
            Box(
                modifier = Modifier
                    .height(lineHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "${index + 1}",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        color = if (index == currentLine)
                            EditorTheme.currentLineNumberColor
                        else
                            EditorTheme.lineNumberColor
                    )
                )
            }
        }
    }
}

@Composable
private fun VerticalDivider(modifier: Modifier = Modifier, color: Color = Color.Gray) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(color)
    )
}

private class UndoRedoManager {
    private data class LightweightState(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private val history = mutableListOf<LightweightState>()
    private var currentIndex = -1
    private val maxHistorySize = 50

    fun recordState(value: TextFieldValue) {
        if (currentIndex < history.size - 1) {
            history.subList(currentIndex + 1, history.size).clear()
        }

        history.add(LightweightState(value.text, value.selection.start, value.selection.end))
        currentIndex++

        if (history.size > maxHistorySize) {
            history.removeAt(0)
            currentIndex--
        }
    }

    fun undo(): TextFieldValue? {
        if (currentIndex > 0) {
            currentIndex--
            val state = history[currentIndex]
            return TextFieldValue(state.text, TextRange(state.selectionStart, state.selectionEnd))
        }
        return null
    }

    fun redo(): TextFieldValue? {
        if (currentIndex < history.size - 1) {
            currentIndex++
            val state = history[currentIndex]
            return TextFieldValue(state.text, TextRange(state.selectionStart, state.selectionEnd))
        }
        return null
    }
}

private object EditorTheme {
    val backgroundColor = Color(0xFFF5F5F5)
    val lineNumbersBackground = Color(0xFFEEEEEE)
    val currentLineBackground = Color(0xFFE8F4F8)
    val dividerColor = Color(0xFFBDBDBD)
    val lineNumberColor = Color(0xFF9E9E9E)
    val currentLineNumberColor = Color(0xFF424242)
    val scrollbarColor = Color(0xFFBDBDBD)
    val cursorColor = Color(0xFF000000)
}
