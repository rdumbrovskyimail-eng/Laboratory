package com.opuside.app.feature.creator.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.feature.creator.data.CreatorAIEditService

// ═══════════════════════════════════════════════════════════════════════════
// ЦВЕТОВАЯ ПАЛИТРА
// ═══════════════════════════════════════════════════════════════════════════

private object EditColors {
    val bg = Color(0xFF0D1117)
    val surface = Color(0xFF161B22)
    val surfaceElevated = Color(0xFF1C2128)
    val border = Color(0xFF30363D)

    val text1 = Color(0xFFE6EDF3)
    val text2 = Color(0xFF8B949E)
    val text3 = Color(0xFF6E7681)

    val green = Color(0xFF3FB950)
    val greenBg = Color(0xFF0D2818)
    val greenBorder = Color(0xFF238636)

    val red = Color(0xFFF85149)
    val redBg = Color(0xFF2D1214)
    val redBorder = Color(0xFFDA3633)

    val blue = Color(0xFF58A6FF)
    val blueBg = Color(0xFF0C2D6B)

    val yellow = Color(0xFFD29922)
    val yellowBg = Color(0xFF2D2200)

    val orange = Color(0xFFF0883E)
    val orangeBg = Color(0xFF2D1A00)

    // Gemini brand colors
    val geminiFlash = Color(0xFF81C995)
    val geminiFlashBg = Color(0xFF0D2418)
}

// ═══════════════════════════════════════════════════════════════════════════
// MODEL METADATA
// ═══════════════════════════════════════════════════════════════════════════

private val CreatorAIEditService.AiModel.accentColor: Color
    get() = when (this) {
        CreatorAIEditService.AiModel.GEMINI_3_FLASH -> EditColors.geminiFlash
    }

private val CreatorAIEditService.AiModel.accentBg: Color
    get() = when (this) {
        CreatorAIEditService.AiModel.GEMINI_3_FLASH -> EditColors.geminiFlashBg
    }

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AIEditScreen v2.4 — Gemini 3 Flash Preview
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Composable
fun AIEditScreen(
    fileName: String,
    fileContent: String,
    editStatus: CreatorAIEditService.EditStatus,
    selectedModel: CreatorAIEditService.AiModel,
    onModelChange: (CreatorAIEditService.AiModel) -> Unit,
    onProcess: (instructions: String) -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit
) {
    var instructions by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    val isProcessing = editStatus is CreatorAIEditService.EditStatus.Processing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditColors.bg)
    ) {
        // ═══════════════════════════════════════════════════════
        // HEADER
        // ═══════════════════════════════════════════════════════

        Surface(
            color = EditColors.surface,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = EditColors.text2)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AI EDIT",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = EditColors.text1,
                        letterSpacing = 1.sp
                    )
                    Text(
                        fileName,
                        fontSize = 12.sp,
                        color = EditColors.text3,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Model badge (static, since only one model)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = EditColors.geminiFlashBg,
                    border = BorderStroke(1.5.dp, EditColors.geminiFlash)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(EditColors.geminiFlash)
                        )
                        Text(
                            selectedModel.badge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = EditColors.geminiFlash,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = EditColors.border, thickness = 1.dp)

        // ═══════════════════════════════════════════════════════
        // MAIN CONTENT
        // ═══════════════════════════════════════════════════════

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InstructionsSection(
                instructions = instructions,
                onInstructionsChange = { instructions = it },
                onPaste = {
                    clipboardManager.getText()?.text?.let { pasted ->
                        instructions = if (instructions.isNotEmpty()) "$instructions\n$pasted" else pasted
                    }
                },
                onClear = { instructions = "" },
                enabled = !isProcessing
            )

            FileInfoChip(fileName = fileName, contentLength = fileContent.length)

            when (editStatus) {
                is CreatorAIEditService.EditStatus.Processing ->
                    ProcessingIndicator(model = selectedModel)
                is CreatorAIEditService.EditStatus.Success ->
                    EditResultSection(
                        result = editStatus.result,
                        newContent = editStatus.newContent,
                        originalContent = fileContent
                    )
                is CreatorAIEditService.EditStatus.Error ->
                    ErrorSection(message = editStatus.message)
                is CreatorAIEditService.EditStatus.Idle ->
                    HintSection()
            }
        }

        // ═══════════════════════════════════════════════════════
        // BOTTOM BAR
        // ═══════════════════════════════════════════════════════

        BottomBar(
            editStatus = editStatus,
            selectedModel = selectedModel,
            instructionsNotEmpty = instructions.isNotBlank(),
            onProcess = { onProcess(instructions) },
            onApply = onApply,
            onDiscard = onDiscard
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// INSTRUCTIONS INPUT
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun InstructionsSection(
    instructions: String,
    onInstructionsChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    enabled: Boolean
) {
    Surface(
        color = EditColors.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, EditColors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Edit, null, tint = EditColors.blue, modifier = Modifier.size(20.dp))
                    Text("Инструкции", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = EditColors.text1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onPaste, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentPaste, "Paste", tint = EditColors.text3, modifier = Modifier.size(18.dp))
                    }
                    if (instructions.isNotEmpty()) {
                        IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ClearAll, "Clear", tint = EditColors.text3, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = instructions,
                onValueChange = onInstructionsChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 400.dp),
                enabled = enabled,
                placeholder = {
                    Text(
                        "Опишите изменения. Примеры:\n\n" +
                                "• Замени className на newClassName\n" +
                                "• Удали функцию processData()\n" +
                                "• Добавь проверку null перед вызовом api\n" +
                                "• Измени параметр timeout с 30 на 60",
                        color = EditColors.text3,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                },
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = EditColors.text1,
                    lineHeight = 20.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EditColors.blue,
                    unfocusedBorderColor = EditColors.border,
                    cursorColor = EditColors.blue,
                    disabledBorderColor = EditColors.border.copy(alpha = 0.5f),
                    disabledTextColor = EditColors.text2
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    "${instructions.length} chars • ~${instructions.length / 4} tokens",
                    fontSize = 10.sp,
                    color = EditColors.text3,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// FILE INFO
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun FileInfoChip(fileName: String, contentLength: Int) {
    Surface(
        color = EditColors.surfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, EditColors.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Description, null, tint = EditColors.text3, modifier = Modifier.size(16.dp))
            Text(fileName, fontSize = 12.sp, color = EditColors.text2, fontFamily = FontFamily.Monospace)
            Text("•", color = EditColors.text3, fontSize = 12.sp)
            Text(
                "${contentLength / 1024}KB • ~${contentLength / 4} tokens",
                fontSize = 11.sp,
                color = EditColors.text3,
                fontFamily = FontFamily.Monospace
            )
            val lineCount = contentLength / 40
            if (lineCount > 300) {
                Text("•", color = EditColors.text3, fontSize = 12.sp)
                Text("📏 line numbers ON", fontSize = 10.sp, color = EditColors.yellow, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PROCESSING
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ProcessingIndicator(model: CreatorAIEditService.AiModel) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse"
    )

    Surface(
        color = model.accentBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, model.accentColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = model.accentColor.copy(alpha = alpha),
                strokeWidth = 3.dp
            )
            Column {
                Text(
                    "${model.displayName} обрабатывает...",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = model.accentColor
                )
                Text("Анализ кода и генерация блоков замен", fontSize = 12.sp, color = EditColors.text3)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// EDIT RESULT
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun EditResultSection(
    result: CreatorAIEditService.EditResult,
    newContent: String,
    originalContent: String
) {
    val hasFailedBlocks = result.blocks.any {
        it.matchStatus == CreatorAIEditService.EditBlock.MatchStatus.NOT_FOUND
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = if (hasFailedBlocks) EditColors.yellowBg else EditColors.greenBg,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, if (hasFailedBlocks) EditColors.yellow else EditColors.greenBorder)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (hasFailedBlocks) Icons.Default.Warning else Icons.Default.CheckCircle,
                    null,
                    tint = if (hasFailedBlocks) EditColors.yellow else EditColors.green,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        result.summary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasFailedBlocks) EditColors.yellow else EditColors.green
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${result.blocks.size} блок(ов)",
                            fontSize = 10.sp,
                            color = EditColors.text3,
                            fontFamily = FontFamily.Monospace
                        )
                        Text("•", fontSize = 10.sp, color = EditColors.text3)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = result.model.accentBg,
                            border = BorderStroke(1.dp, result.model.accentColor.copy(alpha = 0.3f))
                        ) {
                            Text(
                                result.model.badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = result.model.accentColor,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        result.blocks.forEachIndexed { index, block ->
            DiffBlockCard(index = index + 1, block = block)
        }
    }
}

@Composable
private fun DiffBlockCard(index: Int, block: CreatorAIEditService.EditBlock) {
    Surface(
        color = EditColors.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            when (block.matchStatus) {
                CreatorAIEditService.EditBlock.MatchStatus.NOT_FOUND -> EditColors.redBorder
                CreatorAIEditService.EditBlock.MatchStatus.FUZZY,
                CreatorAIEditService.EditBlock.MatchStatus.LINE_RANGE -> EditColors.yellow.copy(alpha = 0.5f)
                else -> EditColors.border
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Surface(color = EditColors.surfaceElevated, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = EditColors.blue.copy(alpha = 0.2f),
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("$index", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EditColors.blue)
                            }
                        }
                        Text("Block $index", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EditColors.text2)
                    }
                    MatchStatusBadge(status = block.matchStatus)
                }
            }

            HorizontalDivider(color = EditColors.border, thickness = 1.dp)

            if (block.search.isNotBlank()) {
                DiffCodeBlock(
                    label = "REMOVE", code = block.search,
                    bgColor = EditColors.redBg, labelColor = EditColors.red,
                    linePrefix = "−", prefixColor = EditColors.red
                )
            }

            if (block.search.isNotBlank() && block.replace.isNotBlank()) {
                HorizontalDivider(color = EditColors.border, thickness = 1.dp)
            }

            if (block.replace.isNotBlank()) {
                DiffCodeBlock(
                    label = if (block.search.isBlank()) "INSERT" else "ADD",
                    code = block.replace,
                    bgColor = EditColors.greenBg, labelColor = EditColors.green,
                    linePrefix = "+", prefixColor = EditColors.green
                )
            } else if (block.search.isNotBlank()) {
                Surface(color = EditColors.redBg, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "  (deleted)", modifier = Modifier.padding(8.dp),
                        fontSize = 11.sp, color = EditColors.red.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchStatusBadge(status: CreatorAIEditService.EditBlock.MatchStatus) {
    val (text, color, bgColor) = when (status) {
        CreatorAIEditService.EditBlock.MatchStatus.EXACT ->
            Triple("✓ EXACT", EditColors.green, EditColors.greenBg)
        CreatorAIEditService.EditBlock.MatchStatus.NORMALIZED ->
            Triple("✓ NORM", EditColors.green, EditColors.greenBg)
        CreatorAIEditService.EditBlock.MatchStatus.FUZZY ->
            Triple("~ FUZZY", EditColors.yellow, EditColors.yellowBg)
        CreatorAIEditService.EditBlock.MatchStatus.LINE_RANGE ->
            Triple("~ RANGE", EditColors.orange, EditColors.orangeBg)
        CreatorAIEditService.EditBlock.MatchStatus.NOT_FOUND ->
            Triple("✗ NOT FOUND", EditColors.red, EditColors.redBg)
        CreatorAIEditService.EditBlock.MatchStatus.PENDING ->
            Triple("⏳", EditColors.text3, EditColors.surfaceElevated)
    }

    Surface(
        shape = RoundedCornerShape(6.dp), color = bgColor,
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
            color = color, fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp
        )
    }
}

@Composable
private fun DiffCodeBlock(
    label: String, code: String, bgColor: Color, labelColor: Color,
    linePrefix: String, prefixColor: Color
) {
    Surface(color = bgColor, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                label, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                color = labelColor, letterSpacing = 0.5.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            val lines = code.lines()
            val displayLines = if (lines.size > 20) {
                lines.take(10) + listOf("... (${lines.size - 20} more lines) ...") + lines.takeLast(10)
            } else lines

            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    displayLines.forEach { line ->
                        Row {
                            Text(
                                "$linePrefix ",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = prefixColor.copy(alpha = 0.6f)
                            )
                            Text(
                                line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = labelColor.copy(alpha = 0.9f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ERROR / HINT
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ErrorSection(message: String) {
    Surface(
        color = EditColors.redBg, shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, EditColors.redBorder), modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Error, null, tint = EditColors.red, modifier = Modifier.size(24.dp))
            Text(message, fontSize = 13.sp, color = EditColors.red, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HintSection() {
    Surface(
        color = EditColors.surfaceElevated, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, EditColors.border), modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Lightbulb, null, tint = EditColors.yellow, modifier = Modifier.size(20.dp))
                Text("Как использовать", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = EditColors.text1)
            }
            listOf(
                "📝" to "Опишите изменения на любом языке",
                "📋" to "Скопируйте инструкции из чата AI и вставьте",
                "🔄" to "Несколько замен — AI создаст отдельные блоки",
                "⚡" to "Gemini 3 Flash Preview — быстро, точно, дёшево",
                "👁️" to "Превью diff перед применением + статус матчинга",
                "✅" to "Нажмите «Применить» после проверки блоков"
            ).forEach { (emoji, text) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(emoji, fontSize = 16.sp)
                    Text(text, fontSize = 12.sp, color = EditColors.text2, lineHeight = 18.sp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// BOTTOM BAR
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun BottomBar(
    editStatus: CreatorAIEditService.EditStatus,
    selectedModel: CreatorAIEditService.AiModel,
    instructionsNotEmpty: Boolean,
    onProcess: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit
) {
    val isSuccess = editStatus is CreatorAIEditService.EditStatus.Success
    val isProcessing = editStatus is CreatorAIEditService.EditStatus.Processing
    val isError = editStatus is CreatorAIEditService.EditStatus.Error

    val successResult = (editStatus as? CreatorAIEditService.EditStatus.Success)?.result
    val usedModel = successResult?.model ?: selectedModel

    val hasFailedBlocks = successResult?.blocks?.any {
        it.matchStatus == CreatorAIEditService.EditBlock.MatchStatus.NOT_FOUND
    } ?: false

    val statusColor = when {
        isSuccess && !hasFailedBlocks -> EditColors.green
        isSuccess && hasFailedBlocks -> EditColors.yellow
        isProcessing -> usedModel.accentColor
        isError -> EditColors.red
        else -> EditColors.text3
    }

    Surface(color = EditColors.surface, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(statusColor))

            if (isSuccess && successResult != null) {
                Surface(color = EditColors.surfaceElevated, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TokenChip("INPUT", "%,d".format(successResult.inputTokens), EditColors.blue)
                        Text("＋", fontSize = 12.sp, color = EditColors.text3)
                        TokenChip("OUTPUT", "%,d".format(successResult.outputTokens), EditColors.orange)
                        Text("＝", fontSize = 12.sp, color = EditColors.text3)
                        TokenChip(
                            "TOTAL",
                            "%,d".format(successResult.inputTokens + successResult.outputTokens),
                            EditColors.text1
                        )
                        Text("•", fontSize = 14.sp, color = EditColors.text3)
                        TokenChip("COST", "€${String.format("%.4f", successResult.costEUR)}", EditColors.green)
                    }
                }
                HorizontalDivider(color = EditColors.border, thickness = 1.dp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                    Text(
                        when {
                            isSuccess && !hasFailedBlocks -> "✅ Код обработан"
                            isSuccess && hasFailedBlocks -> "⚠️ Частично"
                            isProcessing -> "⏳ Обработка..."
                            isError -> "❌ Ошибка"
                            else -> "⏸ Ожидание"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSuccess) {
                        OutlinedButton(
                            onClick = onDiscard,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EditColors.text2),
                            border = BorderStroke(1.dp, EditColors.border),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Сброс", fontSize = 13.sp)
                        }

                        Button(
                            onClick = onApply,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasFailedBlocks) EditColors.yellow else EditColors.green,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Применить", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onProcess,
                            enabled = instructionsNotEmpty && !isProcessing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = selectedModel.accentColor,
                                contentColor = Color.White,
                                disabledContainerColor = EditColors.border,
                                disabledContentColor = EditColors.text3
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White
                                )
                            } else {
                                Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isProcessing) "Обработка..." else "Обработать",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color.copy(alpha = 0.6f), letterSpacing = 0.5.sp)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = color, fontFamily = FontFamily.Monospace)
    }
}
