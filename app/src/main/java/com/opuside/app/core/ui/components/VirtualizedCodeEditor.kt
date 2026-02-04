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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.core.util.SyntaxHighlighter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

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

    // Полная подсветка всего текста сразу
    val highlightedText by produceState(
        initialValue = AnnotatedString(textFieldValue.text),
        key1 = textFieldValue.text,
        key2 = language
    ) {
        value = withContext(Dispatchers.Default) {
            try {
                buildAnnotatedString {
                    lines.forEachIndexed { index, line ->
                        append(SyntaxHighlighter.highlight(line, language))
                        if (index < lines.size - 1) {
                            append("\n")
                        }
                    }
                }
            } catch (e: Exception) {
                AnnotatedString(textFieldValue.text)
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

    LaunchedEffect(cursorLine) {
        snapshotFlow { listState.layoutInfo.totalItemsCount > 0 }
            .first { it }
        
        if (cursorLine in 0 until lines.size) {
            delay(50)
            
            try {
                val targetIndex = (cursorLine - 5).coerceAtLeast(0)
                if (targetIndex < listState.layoutInfo.totalItemsCount) {
                    listState.animateScrollToItem(
                        index = targetIndex,
                        scrollOffset = 0
                    )
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
    }

    val keyboardHandler = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

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

    // КРИТИЧНО: Принудительно LTR
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
                        fontSize = fontSize
                    )
                    VerticalDivider(color = EditorTheme.dividerColor)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // Подсвеченный текст (только отображение)
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
                            val lineStart = lines.take(index).sumOf { it.length + 1 }
                            val lineEnd = lineStart + line.length
                            
                            val lineText = highlightedText.subSequence(
                                lineStart.coerceIn(0, highlightedText.length),
                                lineEnd.coerceIn(0, highlightedText.length)
                            )
                            
                            CodeLine(
                                line = lineText,
                                isCurrentLine = index == cursorLine,
                                fontSize = fontSize
                            )
                        }
                    }

                    if (!readOnly) {
                        // Настоящий TextField (невидимый, но с курсором)
                        BasicTextField(
                            value = TextFieldValue(
                                annotatedString = highlightedText,
                                selection = textFieldValue.selection
                            ),
                            onValueChange = { newValue ->
                                textFieldValue = TextFieldValue(
                                    text = newValue.text,
                                    selection = newValue.selection
                                )
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(horizontalScrollState)
                                .padding(horizontal = 8.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { isFocused = it.isFocused }
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        try {
                                            focusRequester.requestFocus()
                                        } catch (_: IllegalStateException) {}
                                    }
                                },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                color = Color.Transparent,
                                lineHeight = (fontSize * 1.5).sp
                            ),
                            cursorBrush = SolidColor(EditorTheme.cursorColor),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                autoCorrect = false
                            )
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
    line: AnnotatedString,
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
            text = line,
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
        
        history.add(LightweightState(
            text = value.text,
            selectionStart = value.selection.start,
            selectionEnd = value.selection.end
        ))
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