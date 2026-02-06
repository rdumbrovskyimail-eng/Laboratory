package com.opuside.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.pointer.PointerInputChange
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.core.util.SyntaxHighlighter
import kotlinx.coroutines.*

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
    // STATE MANAGEMENT
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(content))
    }

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

    val lines = remember(textFieldValue.text) {
        val result = textFieldValue.text.lines()
        if (result.isEmpty() || (result.size == 1 && result[0].isEmpty())) {
            listOf("")
        } else {
            result
        }
    }

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

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val horizontalScrollState = rememberScrollState()

    // КОРРЕКТНЫЙ Auto-scroll к курсору
    LaunchedEffect(cursorLine) {
        if (cursorLine in 0 until lines.size) {
            delay(50)
            try {
                val layoutInfo = listState.layoutInfo
                val visibleItems = layoutInfo.visibleItemsInfo
                
                if (visibleItems.isNotEmpty()) {
                    val firstVisible = visibleItems.first().index
                    val lastVisible = visibleItems.last().index
                    
                    // Проверяем, виден ли курсор
                    if (cursorLine < firstVisible || cursorLine > lastVisible) {
                        // Центрируем курсор при скроллинге
                        val targetIndex = (cursorLine - 3).coerceAtLeast(0)
                        listState.animateScrollToItem(index = targetIndex)
                    }
                }
            } catch (e: Exception) { 
                // Игнорируем ошибки скроллинга
            }
        }
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

    // УЗКАЯ ШИРИНА НУМЕРАЦИИ
    val lineNumberWidth = remember(lines.size) {
        val maxDigits = lines.size.toString().length
        (maxDigits * 8 + 8).dp // Более узкая формула
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = modifier.then(keyboardHandler),
            color = EditorTheme.backgroundColor
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (showLineNumbers) {
                    LineNumbers(
                        lines = lines,
                        currentLine = cursorLine,
                        listState = listState,
                        fontSize = fontSize,
                        width = lineNumberWidth
                    )
                    VerticalDivider(color = EditorTheme.dividerColor)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // СЛОЙ 1: Подсветка (видимый, НЕ скроллится вручную)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(horizontalScrollState)
                            .padding(horizontal = 8.dp),
                        userScrollEnabled = false // ВАЖНО: отключаем user scroll
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

                    // СЛОЙ 2: Редактор (невидимый, НО скроллится и кликается)
                    if (!readOnly) {
                        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                        val coroutineScope = rememberCoroutineScope()
                        val velocityTracker = remember { VelocityTracker() }
                        
                        CustomLTRTextField(
                            value = textFieldValue,
                            onValueChange = { newValue -> textFieldValue = newValue },
                            modifier = Modifier
                                .matchParentSize()
                                .horizontalScroll(horizontalScrollState)
                                .padding(horizontal = 8.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { isFocused = it.isFocused }
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = { offset ->
                                            // КЛИК: точное позиционирование
                                            textLayoutResult?.let { layout ->
                                                try {
                                                    val position = layout.getOffsetForPosition(offset)
                                                    textFieldValue = textFieldValue.copy(
                                                        selection = TextRange(position)
                                                    )
                                                } catch (e: Exception) {
                                                    // Резервное позиционирование в конец строки
                                                    val lineHeight = layout.size.height.toFloat() / layout.lineCount
                                                    val lineIndex = (offset.y / lineHeight).toInt()
                                                        .coerceIn(0, layout.lineCount - 1)
                                                    val lineEnd = layout.getLineEnd(lineIndex)
                                                    textFieldValue = textFieldValue.copy(
                                                        selection = TextRange(lineEnd)
                                                    )
                                                }
                                            }
                                            
                                            try {
                                                focusRequester.requestFocus()
                                            } catch (_: Exception) {}
                                        }
                                    )
                                }
                                .pointerInput(Unit) {
                                    // ДИНАМИЧНЫЙ ВЕРТИКАЛЬНЫЙ СКРОЛЛИНГ С ИНЕРЦИЕЙ
                                    detectDragGestures(
                                        onDragStart = { 
                                            velocityTracker.resetTracking()
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            
                                            // Отслеживаем скорость для fling
                                            velocityTracker.addPosition(
                                                change.uptimeMillis,
                                                change.position
                                            )
                                            
                                            // Мгновенный скроллинг при драге
                                            coroutineScope.launch {
                                                listState.scrollBy(-dragAmount.y)
                                            }
                                        },
                                        onDragEnd = {
                                            // FLING: инерционный скроллинг после отпускания
                                            val velocity = velocityTracker.calculateVelocity()
                                            val velocityY = -velocity.y
                                            
                                            coroutineScope.launch {
                                                // Используем встроенный animateScrollBy с decay animation
                                                try {
                                                    listState.animateScrollBy(
                                                        value = velocityY * 0.5f, // Коэффициент инерции
                                                    )
                                                } catch (e: Exception) {
                                                    // Игнорируем ошибки анимации
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            velocityTracker.resetTracking()
                                        }
                                    )
                                },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                color = Color.Transparent, // НЕВИДИМЫЙ
                                lineHeight = (fontSize * 1.4).sp,
                                textDirection = TextDirection.Ltr
                            ),
                            cursorColor = EditorTheme.cursorColor,
                            onTextLayoutChange = { textLayoutResult = it }
                        )
                    }

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

// CUSTOM TEXT FIELD с улучшенным курсором
@Composable
private fun CustomLTRTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorColor: Color = Color.White,
    onTextLayoutChange: (TextLayoutResult) -> Unit = {}
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var isCursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(530) // Стандартная частота мигания
            isCursorVisible = !isCursorVisible
        }
    }
    
    LaunchedEffect(value.selection.start) {
        isCursorVisible = true
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.drawBehind {
            // ТОЧНОЕ рисование курсора
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
                    } catch (_: Exception) {
                        // Игнорируем ошибки отрисовки
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
        onTextLayout = { 
            textLayoutResult = it
            onTextLayoutChange(it)
        }
    )
}

// LINE NUMBERS с узким паддингом
@Composable
private fun LineNumbers(
    lines: List<String>, 
    currentLine: Int, 
    listState: LazyListState, 
    fontSize: Int,
    width: androidx.compose.ui.unit.Dp
) {
    val lineHeight = with(LocalDensity.current) { (fontSize * 1.4).sp.toDp() }
    
    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(width)
            .background(EditorTheme.lineNumbersBackground)
            .padding(horizontal = 4.dp), // Уменьшенный паддинг
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

// CODE LINE
@Composable
private fun CodeLine(
    line: String, 
    highlightedText: AnnotatedString, 
    isCurrentLine: Boolean, 
    fontSize: Int
) {
    val lineHeight = with(LocalDensity.current) { (fontSize * 1.4).sp.toDp() }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(lineHeight)
            .background(if (isCurrentLine) EditorTheme.currentLineBackground else Color.Transparent)
    ) {
        Text(
            text = highlightedText,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.4).sp,
                textDirection = TextDirection.Ltr
            ),
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

// SCROLLBAR INDICATOR
@Composable
private fun ScrollbarIndicator(
    listState: LazyListState, 
    totalItems: Int, 
    modifier: Modifier = Modifier
) {
    val firstVisibleItem by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    
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
                .background(EditorTheme.scrollbarColor, MaterialTheme.shapes.small)
        )
    }
}

// VERTICAL DIVIDER
@Composable
private fun VerticalDivider(modifier: Modifier = Modifier, color: Color = Color.Gray) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(color)
    )
}

// UNDO/REDO MANAGER
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

// THEME
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
