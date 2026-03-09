package com.opuside.app.core.ui.components

import android.graphics.Rect
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.core.util.SyntaxHighlighter
import kotlinx.coroutines.*
import java.util.LinkedList
import kotlin.math.min

/**
 * ULTIMATE CODE EDITOR - FLAGSHIP OPTIMIZED
 */

@Stable
data class EditorConfig(
    val showLineNumbers: Boolean = true,
    val fontSize: Int = 14,
    val tabSize: Int = 4,
    val readOnly: Boolean = false,
    val autoIndent: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    val enableBracketMatching: Boolean = true,
    val maxUndoSteps: Int = 100,
    val wordWrap: Boolean = false
)

@Stable
data class EditorTheme(
    val background: Color = Color(0xFFFAFAFA),
    val text: Color = Color(0xFF212121),
    val lineNumbersBackground: Color = Color(0xFFF5F5F5),
    val lineNumberText: Color = Color(0xFF9E9E9E),
    val lineNumberCurrent: Color = Color(0xFF424242),
    val currentLineBackground: Color = Color(0x1A2196F3),
    val divider: Color = Color(0xFFE0E0E0),
    val cursor: Color = Color(0xFF000000),
    val selection: Color = Color(0x4064B5F6),
    val selectionHandle: Color = Color(0xFF2196F3),
    val bracketMatch: Color = Color(0x4081C784)
)

@Composable
fun VirtualizedCodeEditor(
    content: String,
    onContentChange: (String) -> Unit,
    language: String,
    modifier: Modifier = Modifier,
    config: EditorConfig = EditorConfig(),
    theme: EditorTheme = EditorTheme(),
    onCursorPositionChanged: ((line: Int, column: Int) -> Unit)? = null,
    readOnly: Boolean = config.readOnly,
    showLineNumbers: Boolean = config.showLineNumbers,
    fontSize: Int = config.fontSize
) {
    val finalConfig = remember(config, readOnly, showLineNumbers, fontSize) {
        config.copy(
            readOnly = readOnly,
            showLineNumbers = showLineNumbers,
            fontSize = fontSize
        )
    }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(
            text = content,
            selection = TextRange(content.length)
        ))
    }

    var highlightedText by remember { mutableStateOf(AnnotatedString(content)) }
    var highlightJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(textFieldValue.text, language) {
        if (highlightedText.text == textFieldValue.text) return@LaunchedEffect

        highlightJob?.cancel()

        highlightJob = launch {
            delay(100)

            highlightedText = withContext(Dispatchers.Default) {
                try {
                    val lines = textFieldValue.text.lines()

                    buildAnnotatedString {
                        lines.forEachIndexed { i, line ->
                            if (i % 50 == 0 && !isActive) return@withContext highlightedText

                            append(SyntaxHighlighter.highlight(line, language))
                            if (i < lines.size - 1) append("\n")
                        }
                    }
                } catch (e: Exception) {
                    AnnotatedString(textFieldValue.text)
                }
            }
        }
    }

    val undoManager = rememberSaveable(
        saver = DiffUndoManager.Saver,
        init = { DiffUndoManager(content, finalConfig.maxUndoSteps) }
    )

    LaunchedEffect(content) {
        if (textFieldValue.text != content) {
            val cursorPosition = textFieldValue.selection.start.coerceIn(0, content.length)
            textFieldValue = TextFieldValue(
                text = content,
                selection = TextRange(cursorPosition),
                composition = null
            )
            undoManager.reset(content)
        }
    }

    LaunchedEffect(textFieldValue.text) {
        delay(400)
        if (textFieldValue.text != undoManager.getCurrentText()) {
            undoManager.recordChange(textFieldValue.text)
        }
    }

    LaunchedEffect(textFieldValue.text) {
        if (textFieldValue.text != content) {
            onContentChange(textFieldValue.text)
        }
    }

    val cursorPos by remember {
        derivedStateOf {
            calculateCursorPosition(textFieldValue.text, textFieldValue.selection.start)
        }
    }

    LaunchedEffect(cursorPos) {
        onCursorPositionChanged?.invoke(cursorPos.line, cursorPos.column)
    }

    val keyHandler = remember(finalConfig) {
        Modifier.onPreviewKeyEvent { event ->
            handleKeyEvent(
                event = event,
                config = finalConfig,
                textFieldValue = textFieldValue,
                undoManager = undoManager,
                onValueChange = { textFieldValue = it }
            )
        }
    }

    val lines by remember {
        derivedStateOf {
            textFieldValue.text.lines().ifEmpty { listOf("") }
        }
    }

    val lineNumberWidth by remember {
        derivedStateOf {
            calculateLineNumberWidth(lines.size, finalConfig.fontSize)
        }
    }

    val vScrollState = rememberScrollState()
    val hScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    val customSelectionColors = remember(theme) {
        TextSelectionColors(
            handleColor = theme.selectionHandle,
            backgroundColor = theme.selection
        )
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr,
        LocalTextSelectionColors provides customSelectionColors
    ) {
        Surface(modifier = modifier.then(keyHandler), color = theme.background) {
            Row(Modifier.fillMaxSize()) {
                if (finalConfig.showLineNumbers) {
                    key("line-numbers") {
                        LineNumbers(
                            count = lines.size,
                            currentLine = cursorPos.line - 1,
                            fontSize = finalConfig.fontSize,
                            width = lineNumberWidth,
                            scrollState = vScrollState,
                            theme = theme
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.fillMaxHeight().width(1.dp),
                        color = theme.divider
                    )
                }

                key("editor") {
                    Editor(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            textFieldValue = newValue
                        },
                        highlightedText = highlightedText,
                        currentLine = cursorPos.line - 1,
                        fontSize = finalConfig.fontSize,
                        readOnly = finalConfig.readOnly,
                        focusRequester = focusRequester,
                        vScrollState = vScrollState,
                        hScrollState = hScrollState,
                        theme = theme,
                        config = finalConfig
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// EDITOR COMPONENT
// ═══════════════════════════════════════════════════════════════

@Composable
private fun Editor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    highlightedText: AnnotatedString,
    currentLine: Int,
    fontSize: Int,
    readOnly: Boolean,
    focusRequester: FocusRequester,
    vScrollState: androidx.compose.foundation.ScrollState,
    hScrollState: androidx.compose.foundation.ScrollState,
    theme: EditorTheme,
    config: EditorConfig
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var isCursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(value.selection) { isCursorVisible = true }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            isCursorVisible = !isCursorVisible
        }
    }

    // ── Keyboard height via ViewTreeObserver (works on all Activities) ──
    val view = LocalView.current
    val density = LocalDensity.current
    var keyboardHeightPx by remember { mutableIntStateOf(0) }

    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            keyboardHeightPx = (screenHeight - rect.bottom).coerceAtLeast(0)
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    val keyboardPadding = with(density) { keyboardHeightPx.toDp() }

    // ── Scroll cursor ~1cm above keyboard on cursor move or keyboard appear ──
    LaunchedEffect(value.selection.start, keyboardHeightPx) {
        delay(100) // let layout settle after keyboard animation

        val layout = textLayoutResult ?: return@LaunchedEffect
        val viewportSize = vScrollState.viewportSize
        if (viewportSize <= 0) return@LaunchedEffect

        try {
            val offset = value.selection.start.coerceIn(0, value.text.length)
            val cursorRect = layout.getCursorRect(offset)

            // ~1cm margin above the keyboard
            val marginPx = with(density) { 40.dp.toPx() }

            // Effective visible bottom = scroll position + viewport - keyboard
            val visibleBottom = vScrollState.value + viewportSize - keyboardHeightPx
            val visibleTop = vScrollState.value.toFloat()

            if (cursorRect.bottom > visibleBottom - marginPx) {
                // Cursor hidden by keyboard — scroll so cursor is marginPx above keyboard
                val target = (cursorRect.bottom - viewportSize + keyboardHeightPx + marginPx).toInt()
                vScrollState.animateScrollTo(target.coerceAtLeast(0))
            } else if (cursorRect.top < visibleTop + marginPx) {
                // Cursor above visible area — scroll up
                val target = (cursorRect.top - marginPx).toInt()
                vScrollState.animateScrollTo(target.coerceAtLeast(0))
            }
        } catch (_: Exception) {}
    }

    val bracketMatch by remember {
        derivedStateOf {
            if (!config.enableBracketMatching) null
            else findMatchingBracket(value.text, value.selection.start)
        }
    }

    val displayText by remember {
        derivedStateOf {
            if (value.text == highlightedText.text && highlightedText.spanStyles.isNotEmpty()) {
                highlightedText
            } else {
                AnnotatedString(value.text)
            }
        }
    }

    val textStyle = remember(fontSize, displayText) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.5).sp,
            color = if (displayText.spanStyles.isNotEmpty()) Color.Unspecified else theme.text
        )
    }

    val currentValue by rememberUpdatedState(value)
    val currentLineState by rememberUpdatedState(currentLine)
    val currentBracketMatch by rememberUpdatedState(bracketMatch)
    val currentCursorVisible by rememberUpdatedState(isCursorVisible)

    val drawDecorations = remember(theme, config) {
        { scope: DrawScope, layout: TextLayoutResult ->
            with(scope) {
                drawEditorDecorations(
                    layout = layout,
                    value = currentValue,
                    currentLine = currentLineState,
                    bracketMatch = currentBracketMatch,
                    isCursorVisible = currentCursorVisible,
                    readOnly = readOnly,
                    theme = theme,
                    config = config
                )
            }
        }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vScrollState)
            .horizontalScroll(hScrollState)
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp + keyboardPadding)
            .focusRequester(focusRequester)
            .drawBehind {
                textLayoutResult?.let { layout ->
                    drawDecorations(this, layout)
                }
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.None
        ),
        readOnly = readOnly,
        onTextLayout = { textLayoutResult = it },
        decorationBox = @Composable { innerTextField ->
            innerTextField()
        },
        visualTransformation = remember(displayText) {
            VisualTransformation { text ->
                if (displayText.text == text.text && displayText.spanStyles.isNotEmpty()) {
                    TransformedText(displayText, OffsetMapping.Identity)
                } else {
                    TransformedText(AnnotatedString(text.text), OffsetMapping.Identity)
                }
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════
// DRAWING DECORATIONS
// ═══════════════════════════════════════════════════════════════

private fun DrawScope.drawEditorDecorations(
    layout: TextLayoutResult,
    value: TextFieldValue,
    currentLine: Int,
    bracketMatch: Int?,
    isCursorVisible: Boolean,
    readOnly: Boolean,
    theme: EditorTheme,
    config: EditorConfig
) {
    val hasSelection = !value.selection.collapsed
    val hasCursor = isCursorVisible && !readOnly && value.selection.collapsed

    if (!config.highlightCurrentLine && !hasSelection && !hasCursor && bracketMatch == null) {
        return
    }

    if (config.highlightCurrentLine && currentLine >= 0 && currentLine < layout.lineCount) {
        try {
            val top = layout.getLineTop(currentLine)
            val bottom = layout.getLineBottom(currentLine)
            drawRect(
                color = theme.currentLineBackground,
                topLeft = Offset(0f, top),
                size = Size(size.width, bottom - top)
            )
        } catch (_: Exception) {}
    }

    if (hasSelection) {
        try {
            val start = value.selection.min
            val end = value.selection.max
            val startLine = layout.getLineForOffset(start)
            val endLine = layout.getLineForOffset(end)

            for (line in startLine..endLine) {
                val lineStart = layout.getLineStart(line)
                val lineEnd = layout.getLineEnd(line)
                val selStart = maxOf(start, lineStart)
                val selEnd = minOf(end, lineEnd)

                if (selStart < selEnd) {
                    val leftX = layout.getHorizontalPosition(selStart, true)
                    val rightX = layout.getHorizontalPosition(selEnd, true)
                    val topY = layout.getLineTop(line)
                    val bottomY = layout.getLineBottom(line)

                    drawRect(
                        color = theme.selection,
                        topLeft = Offset(leftX, topY),
                        size = Size(rightX - leftX, bottomY - topY)
                    )
                }
            }
        } catch (_: Exception) {}
    }

    bracketMatch?.let { pos ->
        try {
            val box = layout.getBoundingBox(pos)
            drawRect(
                color = theme.bracketMatch,
                topLeft = box.topLeft,
                size = box.size
            )
        } catch (_: Exception) {}
    }

    if (hasCursor) {
        try {
            val offset = value.selection.start.coerceIn(0, value.text.length)
            val cursorRect = layout.getCursorRect(offset)

            drawLine(
                color = theme.cursor,
                start = Offset(cursorRect.left, cursorRect.top),
                end = Offset(cursorRect.left, cursorRect.bottom),
                strokeWidth = 2.dp.toPx()
            )
        } catch (_: Exception) {}
    }
}

// ═══════════════════════════════════════════════════════════════
// KEYBOARD HANDLER
// ═══════════════════════════════════════════════════════════════

private fun handleKeyEvent(
    event: KeyEvent,
    config: EditorConfig,
    textFieldValue: TextFieldValue,
    undoManager: DiffUndoManager,
    onValueChange: (TextFieldValue) -> Unit
): Boolean {
    if (event.type != KeyEventType.KeyDown || config.readOnly) return false

    return when {
        event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> {
            undoManager.undo()?.let { text ->
                onValueChange(TextFieldValue(
                    text = text,
                    selection = TextRange(text.length)
                ))
            }
            true
        }

        (event.isCtrlPressed && event.isShiftPressed && event.key == Key.Z) ||
        (event.isCtrlPressed && event.key == Key.Y) -> {
            undoManager.redo()?.let { text ->
                onValueChange(TextFieldValue(
                    text = text,
                    selection = TextRange(text.length)
                ))
            }
            true
        }

        event.key == Key.Tab && !event.isShiftPressed -> {
            val indent = " ".repeat(config.tabSize)
            val selection = textFieldValue.selection
            val newText = textFieldValue.text.replaceRange(
                selection.start,
                selection.end,
                indent
            )
            onValueChange(TextFieldValue(
                text = newText,
                selection = TextRange(selection.start + indent.length)
            ))
            true
        }

        event.key == Key.Enter && config.autoIndent -> {
            val cursorPos = textFieldValue.selection.start
            val textBefore = textFieldValue.text.take(cursorPos)
            val currentLine = textBefore.substringAfterLast('\n')

            val baseIndent = currentLine.takeWhile { it.isWhitespace() }
            val lastChar = currentLine.trimEnd().lastOrNull()
            val extraIndent = if (lastChar != null && lastChar in setOf('{', '(', '[')) {
                " ".repeat(config.tabSize)
            } else {
                ""
            }

            val insertion = "\n$baseIndent$extraIndent"
            val newText = textFieldValue.text.replaceRange(
                textFieldValue.selection.start,
                textFieldValue.selection.end,
                insertion
            )

            onValueChange(TextFieldValue(
                text = newText,
                selection = TextRange(cursorPos + insertion.length)
            ))
            true
        }

        else -> false
    }
}

// ═══════════════════════════════════════════════════════════════
// LINE NUMBERS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun LineNumbers(
    count: Int,
    currentLine: Int,
    fontSize: Int,
    width: androidx.compose.ui.unit.Dp,
    scrollState: androidx.compose.foundation.ScrollState,
    theme: EditorTheme
) {
    val lineHeight = with(LocalDensity.current) { (fontSize * 1.5).sp.toDp() }

    val normalStyle = remember(fontSize, theme) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.5).sp,
            color = theme.lineNumberText
        )
    }

    val currentStyle = remember(fontSize, theme) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.5).sp,
            color = theme.lineNumberCurrent
        )
    }

    Column(
        modifier = Modifier
            .width(width)
            .verticalScroll(scrollState, enabled = false, flingBehavior = null)
            .background(theme.lineNumbersBackground)
    ) {
        repeat(count) { index ->
            Text(
                text = (index + 1).toString(),
                modifier = Modifier
                    .height(lineHeight)
                    .fillMaxWidth()
                    .padding(end = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                style = if (index == currentLine) currentStyle else normalStyle
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// UNDO/REDO MANAGER
// ═══════════════════════════════════════════════════════════════

@Stable
private class DiffUndoManager(
    initialText: String,
    private val maxSteps: Int = 100
) {
    private data class Patch(val pos: Int, val deleted: String, val inserted: String)

    private var baseText = initialText
    private val patches = LinkedList<Patch>()
    private var currentIndex = -1

    fun getCurrentText() = baseText

    fun reset(text: String) {
        baseText = text
        patches.clear()
        currentIndex = -1
    }

    fun recordChange(newText: String) {
        if (newText == baseText) return

        val patch = createPatch(baseText, newText)

        while (patches.size > currentIndex + 1) {
            patches.removeLast()
        }

        patches.add(patch)
        currentIndex++

        if (patches.size > maxSteps) {
            baseText = applyPatch(baseText, patches.removeFirst())
            currentIndex--
        }

        baseText = newText
    }

    fun undo(): String? {
        if (currentIndex < 0) return null
        baseText = reversePatch(baseText, patches[currentIndex])
        currentIndex--
        return baseText
    }

    fun redo(): String? {
        if (currentIndex >= patches.size - 1) return null
        currentIndex++
        baseText = applyPatch(baseText, patches[currentIndex])
        return baseText
    }

    private fun createPatch(old: String, new: String): Patch {
        val prefixLen = old.commonPrefixWith(new).length
        val suffixLen = old.commonSuffixWith(new).length
        val maxSuffix = min(old.length, new.length) - prefixLen
        val safeSuffix = min(suffixLen, maxSuffix.coerceAtLeast(0))

        val deleted = old.substring(prefixLen, old.length - safeSuffix)
        val inserted = new.substring(prefixLen, new.length - safeSuffix)

        return Patch(prefixLen, deleted, inserted)
    }

    private fun applyPatch(text: String, patch: Patch) =
        text.replaceRange(patch.pos, patch.pos + patch.deleted.length, patch.inserted)

    private fun reversePatch(text: String, patch: Patch) =
        text.replaceRange(patch.pos, patch.pos + patch.inserted.length, patch.deleted)

    companion object {
        val Saver = Saver<DiffUndoManager, Bundle>(
            save = { Bundle().apply { putString("t", it.baseText) } },
            restore = { DiffUndoManager(it.getString("t") ?: "") }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// UTILITY FUNCTIONS
// ═══════════════════════════════════════════════════════════════

private fun calculateCursorPosition(text: String, offset: Int): CursorPosition {
    val safeOffset = offset.coerceIn(0, text.length)
    val before = text.take(safeOffset)
    val line = before.count { it == '\n' } + 1
    val column = safeOffset - (before.lastIndexOf('\n') + 1)
    return CursorPosition(line, column)
}

private fun calculateLineNumberWidth(lineCount: Int, fontSize: Int): androidx.compose.ui.unit.Dp {
    val digits = lineCount.toString().length
    return (digits * 9 + 16).dp
}

private fun findMatchingBracket(text: String, index: Int): Int? {
    if (index !in text.indices) return null

    val char = text[index]
    val pairs = mapOf('(' to ')', '{' to '}', '[' to ']')
    val reversePairs = pairs.entries.associate { (k, v) -> v to k }

    return when {
        char in pairs -> {
            var depth = 0
            for (i in (index + 1) until text.length) {
                val c = text[i]
                if (c == char) depth++
                else if (c == pairs[char]) {
                    if (depth == 0) return i
                    depth--
                }
            }
            null
        }
        char in reversePairs -> {
            var depth = 0
            for (i in (index - 1) downTo 0) {
                val c = text[i]
                if (c == char) depth++
                else if (c == reversePairs[char]) {
                    if (depth == 0) return i
                    depth--
                }
            }
            null
        }
        else -> null
    }
}

private data class CursorPosition(val line: Int, val column: Int)
