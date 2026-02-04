package com.opuside.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.core.util.SyntaxHighlighter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * ✅ VirtualizedCodeEditor - Production-ready код-редактор для Android
 * 
 * Особенности:
 * - ✅ Правильное направление текста (LTR)
 * - ✅ Видимый курсор в нужном месте
 * - ✅ Подсветка синтаксиса
 * - ✅ Undo/Redo (Ctrl+Z / Ctrl+Shift+Z)
 * - ✅ Tab = 4 пробела
 * - ✅ Нумерация строк
 * - ✅ Виртуализация через LazyColumn
 * - ✅ Безопасный скролл без крашей
 * - ✅ Поддержка больших файлов
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
    // =================== STATE ===================
    
    var textFieldValue by remember(content) {
        mutableStateOf(TextFieldValue(content))
    }

    val lines = remember(textFieldValue.text) {
        val result = textFieldValue.text.lines()
        if (result.isEmpty() || (result.size == 1 && result[0].isEmpty())) {
            listOf("")
        } else {
            result
        }
    }

    // =================== SYNTAX HIGHLIGHTING ===================
    
    val highlightedLines by produceState(
        initialValue = lines.map { AnnotatedString(it) },
        key1 = lines.hashCode(),
        key2 = language
    ) {
        value = withContext(Dispatchers.Default) {
            lines.map { line ->
                try {
                    SyntaxHighlighter.highlight(line, language)
                } catch (e: Exception) {
                    AnnotatedString(line)
                }
            }
        }
    }

    // =================== CURSOR POSITION ===================
    
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

    // =================== CONTENT CHANGE ===================
    
    LaunchedEffect(textFieldValue.text) {
        if (textFieldValue.text != content) {
            onContentChange(textFieldValue.text)
        }
    }

    // =================== UNDO/REDO ===================
    
    val undoRedoManager = remember { UndoRedoManager() }
    
    LaunchedEffect(textFieldValue.text) {
        delay(500) // Debounce для производительности
        undoRedoManager.recordState(textFieldValue)
    }

    // =================== SCROLL STATE ===================
    
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val horizontalScrollState = rememberScrollState()

    // =================== AUTO-SCROLL TO CURSOR ===================
    
    LaunchedEffect(cursorLine) {
        // Ждём пока LazyColumn полностью готов
        snapshotFlow { listState.layoutInfo.totalItemsCount > 0 }
            .first { it }
        
        if (cursorLine in 0 until lines.size) {
            delay(50) // Минимальная задержка для стабильности
            
            try {
                val targetIndex = (cursorLine - 5).coerceAtLeast(0)
                if (targetIndex < listState.layoutInfo.totalItemsCount) {
                    listState.animateScrollToItem(
                        index = targetIndex,
                        scrollOffset = 0
                    )
                }
            } catch (e: Exception) {
                // Игнорируем ошибки скролла
            }
        }
    }

    // =================== KEYBOARD SHORTCUTS ===================
    
    val keyboardHandler = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || readOnly) return@onKeyEvent false

        when {
            // Ctrl+Z - Undo
            event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> {
                undoRedoManager.undo()?.let { textFieldValue = it }
                true
            }
            
            // Ctrl+Shift+Z или Ctrl+Y - Redo
            (event.isCtrlPressed && event.isShiftPressed && event.key == Key.Z) ||
            (event.isCtrlPressed && event.key == Key.Y) -> {
                undoRedoManager.redo()?.let { textFieldValue = it }
                true
            }
            
            // Tab - 4 пробела
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

    // =================== UI LAYOUT ===================
    
    // КРИТИЧНО: Принудительно устанавливаем LTR для всего редактора
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = modifier.then(keyboardHandler),
            color = EditorTheme.backgroundColor
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                
                // =================== LINE NUMBERS ===================
                
                if (showLineNumbers) {
                    LineNumbers(
                        lines = lines,
                        currentLine = cursorLine,
                        listState = listState,
                        fontSize = fontSize
                    )
                    VerticalDivider(color = EditorTheme.dividerColor)
                }

                // =================== CODE AREA ===================
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // ========== VISUAL LAYER (подсветка синтаксиса) ==========
                    
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(horizontalScrollState)
                            .padding(horizontal = 8.dp),
                        userScrollEnabled = true
                    ) {
                        itemsIndexed(
                            items = lines,
                            key = { index, line -> "$index-${line.hashCode()}" }
                        ) { index, line ->
                            CodeLine(
                                line = line,
                                highlightedText = highlightedLines.getOrNull(index) 
                                    ?: AnnotatedString(line),
                                isCurrentLine = index == cursorLine,
                                fontSize = fontSize
                            )
                        }
                    }

                    // ========== INPUT LAYER (невидимый TextField с курсором) ==========
                    
                    if (!readOnly) {
                        CustomLTRTextField(
                            value = textFieldValue,
                            onValueChange = { newValue -> 
                                textFieldValue = newValue 
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(horizontalScrollState)
                                .padding(horizontal = 8.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    isFocused = focusState.isFocused
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        try {
                                            focusRequester.requestFocus()
                                        } catch (_: IllegalStateException) {
                                            // Layout ещё не готов - игнорируем
                                        }
                                    }
                                },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                color = Color.Transparent, // Текст невидим
                                lineHeight = (fontSize * 1.5).sp
                            ),
                            cursorColor = EditorTheme.cursorColor
                        )
                    }

                    // ========== SCROLLBAR (для больших файлов) ==========
                    
                    if (lines.size > 100) {
                        ScrollbarIndicator(
                            listState = listState,
                            totalItems = lines.size,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }
            }
        }
    }
}

// ==================================================================================
// =================== CUSTOM LTR TEXT FIELD (главная магия) ======================
// ==================================================================================

/**
 * Кастомный TextField с принудительным LTR и ручным рендерингом курсора
 * Это решает проблему RTL в стандартном BasicTextField
 */
@Composable
private fun CustomLTRTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorColor: Color = Color.White
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var isCursorVisible by remember { mutableStateOf(true) }

    // Мигание курсора (500ms on/off)
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            isCursorVisible = !isCursorVisible
        }
    }

    // Сброс мигания при изменении позиции курсора
    LaunchedEffect(value.selection.start) {
        isCursorVisible = true
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.drawBehind {
            // Рисуем курсор вручную для полного контроля
            if (isCursorVisible) {
                textLayoutResult?.let { layout ->
                    val cursorPos = value.selection.start.coerceIn(0, value.text.length)
                    
                    try {
                        val cursorRect = layout.getCursorRect(cursorPos)
                        
                        drawLine(
                            color = cursorColor,
                            start = Offset(cursorRect.left, cursorRect.top),
                            end = Offset(cursorRect.left, cursorRect.bottom),
                            strokeWidth = 2.dp.toPx()
                        )
                    } catch (e: Exception) {
                        // Игнорируем ошибки рендеринга курсора
                    }
                }
            }
        },
        textStyle = textStyle,
        cursorBrush = SolidColor(Color.Transparent), // Скрываем стандартный курсор
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.None
        ),
        onTextLayout = { textLayoutResult = it }
    )
}

// ==================================================================================
// ========================== ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ ============================
// ==================================================================================

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
        itemsIndexed(
            items = lines,
            key = { index, line -> "$index-${line.hashCode()}" }
        ) { index, _ ->
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
private fun CodeLine(
    line: String,
    highlightedText: AnnotatedString,
    isCurrentLine: Boolean,
    fontSize: Int
) {
    val lineHeight = with(LocalDensity.current) { (fontSize * 1.5).sp.toDp() }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(lineHeight)
            .background(
                if (isCurrentLine) EditorTheme.currentLineBackground 
                else Color.Transparent
            )
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

@Composable
private fun ScrollbarIndicator(
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

@Composable
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

// ==================================================================================
// ============================ UNDO/REDO MANAGER ===================================
// ==================================================================================

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
        // Удаляем всё после текущей позиции (если был undo)
        if (currentIndex < history.size - 1) {
            history.subList(currentIndex + 1, history.size).clear()
        }
        
        // Добавляем новое состояние
        history.add(
            LightweightState(
                text = value.text,
                selectionStart = value.selection.start,
                selectionEnd = value.selection.end
            )
        )
        currentIndex++
        
        // Ограничиваем размер истории
        if (history.size > maxHistorySize) {
            history.removeAt(0)
            currentIndex--
        }
    }

    fun undo(): TextFieldValue? {
        if (currentIndex > 0) {
            currentIndex--
            val state = history[currentIndex]
            
            return TextFieldValue(
                text = state.text,
                selection = TextRange(state.selectionStart, state.selectionEnd)
            )
        }
        return null
    }

    fun redo(): TextFieldValue? {
        if (currentIndex < history.size - 1) {
            currentIndex++
            val state = history[currentIndex]
            
            return TextFieldValue(
                text = state.text,
                selection = TextRange(state.selectionStart, state.selectionEnd)
            )
        }
        return null
    }
}

// ==================================================================================
// ================================= THEME ==========================================
// ==================================================================================

private object EditorTheme {
    val backgroundColor = Color(0xFF1E1E1E)
    val lineNumbersBackground = Color(0xFF252526)
    val currentLineBackground = Color(0xFF2A2A2A)
    val dividerColor = Color(0xFF404040)
    val lineNumberColor = Color(0xFF858585)
    val currentLineNumberColor = Color(0xFFC6C6C6)
    val scrollbarColor = Color(0xFF424242)
    val cursorColor = Color(0xFFFFFFFF)
}