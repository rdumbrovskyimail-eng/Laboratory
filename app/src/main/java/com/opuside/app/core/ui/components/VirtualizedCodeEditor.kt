package com.opuside.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.core.util.SyntaxHighlighter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 2026-уровневый Code Editor с виртуализацией и async подсветкой.
 * 
 * Особенности:
 * - Виртуализация: рендерятся только видимые строки (LazyColumn)
 * - Async highlighting: подсветка синтаксиса в background thread
 * - Smart cursor: автоматический скролл к курсору
 * - Undo/Redo: встроенная история изменений
 * - Performance: оптимизирован для файлов 10k+ строк
 * 
 * 🔴 ПРОБЛЕМА #6: Memory Leak в Composables
 * UndoRedoManager хранит полные TextFieldValue объекты с AnnotatedString
 * При длительной работе может накопить сотни MB памяти из-за spans/styling
 */
@Composable
fun VirtualizedCodeEditor(
    content: String,
    onContentChange: (String) -> Unit,
    language: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    showLineNumbers: Boolean = true,
    fontSize: Int = 14,
    onCursorPositionChanged: ((line: Int, column: Int) -> Unit)? = null
) {
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════

    var textFieldValue by remember(content) {
        mutableStateOf(TextFieldValue(content))
    }

    val lines = remember(textFieldValue.text) {
        val result = textFieldValue.text.lines()
        if (result.size == 1 && result[0].isEmpty()) {
            listOf("")
        } else {
            result
        }
    }

    // 🔴 ПРОБЛЕМА #6: Memory Leak - produceState сохраняет все промежуточные состояния
    // highlightedLines содержит AnnotatedString с spans, которые не очищаются
    val highlightedLines = produceState(
        initialValue = lines.map { AnnotatedString(it) },
        lines, 
        language
    ) {
        value = withContext(Dispatchers.Default) {
            lines.map { line ->
                SyntaxHighlighter.highlight(line, language)
            }
        }
    }.value

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

    // 🔴 ПРОБЛЕМА #6: Memory Leak - UndoRedoManager хранит TextFieldValue
    // TextFieldValue содержит AnnotatedString с spans, selection, composition
    // При большой истории (100+ действий) может накопить сотни MB
    val undoRedoManager = remember { UndoRedoManager() }
    
    LaunchedEffect(textFieldValue.text) {
        delay(500)
        // 🔴 Сохраняем весь TextFieldValue со всеми spans и styling
        undoRedoManager.recordState(textFieldValue)
    }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val isFocused = remember { mutableStateOf(false) }
    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(cursorLine) {
        if (cursorLine in 0 until lines.size) {
            listState.animateScrollToItem(
                index = (cursorLine - 5).coerceAtLeast(0),
                scrollOffset = 0
            )
        }
    }

    DisposableEffect(Unit) {
        if (isFocused.value) {
            focusRequester.requestFocus()
        }
        onDispose { }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KEYBOARD SHORTCUTS (DeX Support)
    // ═══════════════════════════════════════════════════════════════════════════

    val keyboardHandler = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

        when {
            // Ctrl+Z - Undo
            event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> {
                undoRedoManager.undo()?.let { textFieldValue = it }
                true
            }
            // Ctrl+Shift+Z or Ctrl+Y - Redo
            (event.isCtrlPressed && event.isShiftPressed && event.key == Key.Z) ||
            (event.isCtrlPressed && event.key == Key.Y) -> {
                undoRedoManager.redo()?.let { textFieldValue = it }
                true
            }
            // Tab - Insert 4 spaces
            event.key == Key.Tab && !readOnly -> {
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

    // ═══════════════════════════════════════════════════════════════════════════
    // UI
    // ═══════════════════════════════════════════════════════════════════════════

    Surface(
        modifier = modifier.then(keyboardHandler),
        color = EditorTheme.backgroundColor
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // LINE NUMBERS
            if (showLineNumbers) {
                LineNumbers(
                    lines = lines,
                    currentLine = cursorLine,
                    listState = listState,
                    fontSize = fontSize
                )
                
                VerticalDivider(color = EditorTheme.dividerColor)
            }

            // CODE AREA
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScrollState)
                        .padding(horizontal = 8.dp)
                        .focusRequester(focusRequester)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                focusRequester.requestFocus()
                            }
                        },
                    userScrollEnabled = true
                ) {
                    itemsIndexed(
                        items = lines,
                        key = { index, _ -> index }
                    ) { index, line ->
                        CodeLine(
                            line = line,
                            lineNumber = index,
                            highlightedText = highlightedLines.getOrNull(index) ?: AnnotatedString(line),
                            isCurrentLine = index == cursorLine,
                            fontSize = fontSize,
                            onLineClick = { offset ->
                                val beforeLines = lines.take(index).sumOf { it.length + 1 }
                                val newPosition = beforeLines + offset
                                textFieldValue = textFieldValue.copy(
                                    selection = TextRange(newPosition)
                                )
                                focusRequester.requestFocus()
                            }
                        )
                    }
                }

                // Невидимый TextField для обработки ввода
                if (!readOnly) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            textFieldValue = newValue
                        },
                        modifier = Modifier
                            .size(0.dp) // 🔴 ПРОБЛЕМА: Потеря фокуса при recomposition
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused.value = it.isFocused },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            color = Color.Transparent
                        ),
                        cursorBrush = SolidColor(Color.Transparent)
                    )
                }
            }
        }

        if (lines.size > 100) {
            ScrollbarIndicator(
                listState = listState,
                totalItems = lines.size,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!readOnly) {
            focusRequester.requestFocus()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LINE NUMBERS COLUMN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun LineNumbers(
    lines: List<String>,
    currentLine: Int,
    listState: LazyListState,
    fontSize: Int
) {
    val lineHeight = with(LocalDensity.current) { (fontSize * 1.5).sp.toDp() }
    
    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(56.dp)
            .background(EditorTheme.lineNumbersBackground)
            .padding(horizontal = 8.dp),
        userScrollEnabled = false
    ) {
        itemsIndexed(lines) { index, _ ->
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

// ═══════════════════════════════════════════════════════════════════════════════
// SINGLE CODE LINE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CodeLine(
    line: String,
    lineNumber: Int,
    highlightedText: AnnotatedString,
    isCurrentLine: Boolean,
    fontSize: Int,
    onLineClick: (offset: Int) -> Unit
) {
    val lineHeight = with(LocalDensity.current) { (fontSize * 1.5).sp.toDp() }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(lineHeight)
            .background(
                if (isCurrentLine) 
                    EditorTheme.currentLineBackground 
                else 
                    Color.Transparent
            )
            .pointerInput(lineNumber) {
                detectTapGestures { offset ->
                    val charWidth = fontSize * 0.6f
                    val clickedChar = (offset.x / charWidth).toInt().coerceIn(0, line.length)
                    onLineClick(clickedChar)
                }
            }
    ) {
        Text(
            text = highlightedText,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.5).sp
            ),
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SCROLLBAR INDICATOR
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun BoxScope.ScrollbarIndicator(
    listState: LazyListState,
    totalItems: Int,
    modifier: Modifier = Modifier
) {
    val firstVisibleItem by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(4.dp)
            .background(Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.1f)
                .align(Alignment.TopEnd)
                .offset(y = ((firstVisibleItem.toFloat() / totalItems) * 400).dp)
                .background(
                    color = EditorTheme.scrollbarColor,
                    shape = MaterialTheme.shapes.small
                )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UNDO/REDO MANAGER
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 🔴 ПРОБЛЕМА #6: Memory Leak в Composables
 * 
 * Хранит полные TextFieldValue объекты в истории.
 * TextFieldValue содержит:
 * - text: String
 * - selection: TextRange
 * - composition: TextRange?
 * - annotatedString: AnnotatedString (с spans для стилизации)
 * 
 * При работе с подсветкой синтаксиса каждый TextFieldValue может занимать
 * 100+ KB из-за spans. История из 50 элементов = 5+ MB утечки памяти.
 * 
 * РЕШЕНИЕ: Хранить только text: String, восстанавливать TextFieldValue при undo/redo
 */
private class UndoRedoManager {
    // 🔴 Хранит полные TextFieldValue со всеми spans и composition state
    private val history = mutableListOf<TextFieldValue>()
    private var currentIndex = -1
    
    // 🔴 Слишком большой размер истории (50 действий * 100KB = 5MB+)
    private val maxHistorySize = 50

    fun recordState(value: TextFieldValue) {
        if (currentIndex < history.size - 1) {
            history.subList(currentIndex + 1, history.size).clear()
        }

        // 🔴 Сохраняем весь объект с AnnotatedString spans
        history.add(value)
        currentIndex++

        if (history.size > maxHistorySize) {
            history.removeAt(0)
            currentIndex--
        }
    }

    fun undo(): TextFieldValue? {
        if (currentIndex > 0) {
            currentIndex--
            return history[currentIndex]
        }
        return null
    }

    fun redo(): TextFieldValue? {
        if (currentIndex < history.size - 1) {
            currentIndex++
            return history[currentIndex]
        }
        return null
    }

    fun canUndo() = currentIndex > 0
    fun canRedo() = currentIndex < history.size - 1
}

// ═══════════════════════════════════════════════════════════════════════════════
// EDITOR THEME (VS Code Dark)
// ═══════════════════════════════════════════════════════════════════════════════

private object EditorTheme {
    val backgroundColor = Color(0xFF1E1E1E)
    val lineNumbersBackground = Color(0xFF252526)
    val currentLineBackground = Color(0xFF2A2A2A)
    val dividerColor = Color(0xFF404040)
    val lineNumberColor = Color(0xFF858585)
    val currentLineNumberColor = Color(0xFFC6C6C6)
    val scrollbarColor = Color(0xFF424242)
}

// ═══════════════════════════════════════════════════════════════════════════════
// EXTENSIONS
// ═══════════════════════════════════════════════════════════════════════════════

private fun VerticalDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.Gray
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(color)
    )
}