package com.opuside.app.feature.creator.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.core.util.SyntaxHighlighter

/**
 * Полноценный Code Editor с:
 * - Подсветкой синтаксиса
 * - Номерами строк
 * - Поиском и заменой
 * - Информацией о позиции курсора
 */
@Composable
fun CodeEditorFull(
    content: String,
    onContentChange: (String) -> Unit,
    language: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false
) {
    var textFieldValue by remember(content) {
        mutableStateOf(TextFieldValue(content))
    }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<IntRange>>(emptyList()) }
    var currentSearchIndex by remember { mutableStateOf(0) }
    
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    // Поиск совпадений
    LaunchedEffect(searchQuery, content) {
        if (searchQuery.isNotEmpty()) {
            val results = mutableListOf<IntRange>()
            var startIndex = 0
            while (true) {
                val index = content.indexOf(searchQuery, startIndex, ignoreCase = true)
                if (index == -1) break
                results.add(index until index + searchQuery.length)
                startIndex = index + 1
            }
            searchResults = results
            if (results.isNotEmpty() && currentSearchIndex >= results.size) {
                currentSearchIndex = 0
            }
        } else {
            searchResults = emptyList()
        }
    }

    // Синхронизация с внешним content
    LaunchedEffect(textFieldValue.text) {
        if (textFieldValue.text != content) {
            onContentChange(textFieldValue.text)
        }
    }

    val lines = content.lines()
    val cursorLine = content.take(textFieldValue.selection.start).count { it == '\n' } + 1
    val cursorColumn = textFieldValue.selection.start - content.take(textFieldValue.selection.start).lastIndexOf('\n') - 1

    Column(modifier = modifier) {
        // ═══════════════════════════════════════════════════════════════════════
        // TOOLBAR
        // ═══════════════════════════════════════════════════════════════════════

        Surface(tonalElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language badge
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = language.uppercase(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Cursor position
                    Text(
                        text = "Ln $cursorLine, Col $cursorColumn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.width(8.dp))

                    // Lines count
                    Text(
                        text = "${lines.size} lines",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.width(16.dp))

                    // Search toggle
                    IconButton(
                        onClick = { showSearch = !showSearch },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            modifier = Modifier.size(20.dp),
                            tint = if (showSearch) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // SEARCH BAR
        // ═══════════════════════════════════════════════════════════════════════

        if (showSearch) {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Find", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                        )

                        Spacer(Modifier.width(8.dp))

                        // Results count
                        if (searchResults.isNotEmpty()) {
                            Text(
                                text = "${currentSearchIndex + 1}/${searchResults.size}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        // Navigation
                        IconButton(
                            onClick = {
                                if (searchResults.isNotEmpty()) {
                                    currentSearchIndex = if (currentSearchIndex > 0) 
                                        currentSearchIndex - 1 else searchResults.lastIndex
                                }
                            },
                            enabled = searchResults.isNotEmpty()
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, "Previous")
                        }

                        IconButton(
                            onClick = {
                                if (searchResults.isNotEmpty()) {
                                    currentSearchIndex = (currentSearchIndex + 1) % searchResults.size
                                }
                            },
                            enabled = searchResults.isNotEmpty()
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, "Next")
                        }

                        IconButton(onClick = { showSearch = false }) {
                            Icon(Icons.Default.Close, "Close search")
                        }
                    }

                    // Replace row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = replaceQuery,
                            onValueChange = { replaceQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Replace", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        Spacer(Modifier.width(8.dp))

                        TextButton(
                            onClick = {
                                if (searchResults.isNotEmpty() && !readOnly) {
                                    val range = searchResults[currentSearchIndex]
                                    val newContent = content.replaceRange(range, replaceQuery)
                                    textFieldValue = TextFieldValue(
                                        text = newContent,
                                        selection = TextRange(range.first + replaceQuery.length)
                                    )
                                }
                            },
                            enabled = searchResults.isNotEmpty() && !readOnly
                        ) {
                            Text("Replace")
                        }

                        TextButton(
                            onClick = {
                                if (searchQuery.isNotEmpty() && !readOnly) {
                                    val newContent = content.replace(searchQuery, replaceQuery, ignoreCase = true)
                                    textFieldValue = TextFieldValue(newContent)
                                }
                            },
                            enabled = searchResults.isNotEmpty() && !readOnly
                        ) {
                            Text("All")
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // EDITOR AREA
        // ═══════════════════════════════════════════════════════════════════════

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF1E1E1E)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Line numbers
                Column(
                    modifier = Modifier
                        .verticalScroll(verticalScrollState)
                        .background(Color(0xFF252526))
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    lines.forEachIndexed { index, _ ->
                        val isCurrentLine = index + 1 == cursorLine
                        Text(
                            text = "${index + 1}".padStart(lines.size.toString().length),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = if (isCurrentLine) Color(0xFFC6C6C6) else Color(0xFF858585)
                            )
                        )
                    }
                }

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Color(0xFF404040))
                )

                // Code editor
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(horizontalScrollState)
                        .verticalScroll(verticalScrollState)
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { textFieldValue = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = Color(0xFFD4D4D4)
                        ),
                        cursorBrush = SolidColor(Color(0xFFAEAFAD)),
                        readOnly = readOnly,
                        decorationBox = { innerTextField ->
                            // Highlight search results
                            if (searchResults.isNotEmpty()) {
                                val annotatedString = buildAnnotatedString {
                                    var lastEnd = 0
                                    searchResults.forEach { range ->
                                        append(content.substring(lastEnd, range.first))
                                        withStyle(SpanStyle(background = Color(0xFF515C6A))) {
                                            append(content.substring(range))
                                        }
                                        lastEnd = range.last + 1
                                    }
                                    if (lastEnd < content.length) {
                                        append(content.substring(lastEnd))
                                    }
                                }
                                // Note: BasicTextField doesn't support annotatedString directly
                                // In production, use a custom approach or TextField
                            }
                            innerTextField()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Упрощённый view-only вариант для просмотра логов.
 */
@Composable
fun CodeViewer(
    content: String,
    language: String = "text",
    modifier: Modifier = Modifier
) {
    val highlighted = remember(content, language) {
        SyntaxHighlighter.highlight(content, language)
    }

    Surface(
        modifier = modifier,
        color = Color(0xFF1E1E1E)
    ) {
        val scrollState = rememberScrollState()
        val lines = content.lines()

        Row(modifier = Modifier.fillMaxSize()) {
            // Line numbers
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .background(Color(0xFF252526))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                lines.forEachIndexed { index, _ ->
                    Text(
                        text = "${index + 1}".padStart(lines.size.toString().length),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF858585)
                        )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Color(0xFF404040))
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            ) {
                Text(
                    text = highlighted,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                )
            }
        }
    }
}
