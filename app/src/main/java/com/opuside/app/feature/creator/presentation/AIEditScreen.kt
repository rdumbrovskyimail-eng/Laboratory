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
// LIGHT PROFESSIONAL THEME (Samsung S23 Ultra AMOLED Optimized)
// ═══════════════════════════════════════════════════════════════════════════

private object EditColorsLight {
    val bg = Color(0xFFF8F9FA)
    val surface = Color(0xFFFFFFFF)
    val surfaceElevated = Color(0xFFF1F3F5)
    val border = Color(0xFFE2E8F0)
    val borderStrong = Color(0xFFCBD5E1)

    val textPrimary = Color(0xFF0F172A)
    val textSecondary = Color(0xFF475569)
    val textTertiary = Color(0xFF94A3B8)

    val blue = Color(0xFF2563EB)
    val blueBg = Color(0xFFEFF6FF)

    val green = Color(0xFF059669)
    val greenBg = Color(0xFFECFDF5)
    val greenText = Color(0xFF065F46)
    val greenBorder = Color(0xFFA7F3D0)

    val red = Color(0xFFDC2626)
    val redBg = Color(0xFFFEF2F2)
    val redText = Color(0xFF991B1B)
    val redBorder = Color(0xFFFECACA)

    val amber = Color(0xFFD97706)
    val amberBg = Color(0xFFFFFBEB)
    val amberText = Color(0xFF92400E)
}

private val CreatorAIEditService.AiModel.accentColor: Color
    get() = when (this) {
        CreatorAIEditService.AiModel.GEMINI_3_5_FLASH_LITE -> EditColorsLight.blue
        CreatorAIEditService.AiModel.GEMINI_3_1_FLASH_LITE -> EditColorsLight.green
    }

private val CreatorAIEditService.AiModel.accentBg: Color
    get() = when (this) {
        CreatorAIEditService.AiModel.GEMINI_3_5_FLASH_LITE -> EditColorsLight.blueBg
        CreatorAIEditService.AiModel.GEMINI_3_1_FLASH_LITE -> EditColorsLight.greenBg
    }

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
            .background(EditColorsLight.bg)
    ) {
        // ── ШАПКА ЭКРАНА ───────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = EditColorsLight.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = EditColorsLight.textSecondary)
                }
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AI Редактор файла",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = EditColorsLight.textPrimary
                    )
                    Text(
                        fileName,
                        fontSize = 11.sp,
                        color = EditColorsLight.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Переключатель активной модели (3.5 Lite <-> 3.1 Lite)
                Surface(
                    onClick = {
                        val next = if (selectedModel == CreatorAIEditService.AiModel.GEMINI_3_5_FLASH_LITE)
                            CreatorAIEditService.AiModel.GEMINI_3_1_FLASH_LITE
                        else
                            CreatorAIEditService.AiModel.GEMINI_3_5_FLASH_LITE
                        onModelChange(next)
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = selectedModel.accentBg,
                    border = BorderStroke(1.dp, selectedModel.accentColor)
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
                                .background(selectedModel.accentColor)
                        )
                        Text(
                            selectedModel.badge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = selectedModel.accentColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // ── ОСНОВНОЙ КОНТЕНТ ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InstructionsSectionLight(
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

            FileInfoChipLight(
                fileName = fileName,
                contentLength = fileContent.length,
                lineCount = fileContent.lines().size
            )

            when (editStatus) {
                is CreatorAIEditService.EditStatus.Processing ->
                    ProcessingIndicatorLight(model = selectedModel)
                is CreatorAIEditService.EditStatus.Success ->
                    EditResultSectionLight(result = editStatus.result)
                is CreatorAIEditService.EditStatus.Error ->
                    ErrorSectionLight(message = editStatus.message)
                is CreatorAIEditService.EditStatus.Idle ->
                    HintSectionLight()
            }
        }

        // ── НИЖНЯЯ ПАНЕЛЬ ДЕЙСТВИЙ И ТОКЕНОВ ──────────────────────────
        BottomBarLight(
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
// ВВОД ИНСТРУКЦИЙ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun InstructionsSectionLight(
    instructions: String,
    onInstructionsChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EditColorsLight.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.EditNote, null, tint = EditColorsLight.blue, modifier = Modifier.size(20.dp))
                    Text("Инструкции для AI", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EditColorsLight.textPrimary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onPaste, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentPaste, "Вставить", tint = EditColorsLight.textSecondary, modifier = Modifier.size(16.dp))
                    }
                    if (instructions.isNotEmpty()) {
                        IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ClearAll, "Очистить", tint = EditColorsLight.textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = instructions,
                onValueChange = onInstructionsChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 340.dp),
                enabled = enabled,
                placeholder = {
                    Text(
                        "Опишите правки простыми словами:\n\n" +
                                "• Замени имя метода foo() на bar()\n" +
                                "• Добавь проверку на null перед вызовом api\n" +
                                "• Перепиши тело функции loadData на корутины",
                        color = EditColorsLight.textTertiary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                },
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = EditColorsLight.textPrimary,
                    lineHeight = 18.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = EditColorsLight.surfaceElevated,
                    unfocusedContainerColor = EditColorsLight.surfaceElevated,
                    focusedBorderColor = EditColorsLight.blue,
                    unfocusedBorderColor = EditColorsLight.border
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
                    "${instructions.length} симв. · ~${instructions.length / 4} токенов",
                    fontSize = 10.sp,
                    color = EditColorsLight.textSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun FileInfoChipLight(
    fileName: String,
    contentLength: Int,
    lineCount: Int
) {
    Surface(
        color = EditColorsLight.surfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.5.dp, EditColorsLight.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.Description, null, tint = EditColorsLight.textSecondary, modifier = Modifier.size(15.dp))
            Text(fileName, fontSize = 11.sp, color = EditColorsLight.textPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text("·", color = EditColorsLight.textTertiary, fontSize = 11.sp)
            Text(
                "${contentLength / 1024} KB · $lineCount строк",
                fontSize = 10.sp,
                color = EditColorsLight.textSecondary,
                fontFamily = FontFamily.Monospace
            )
            if (lineCount > 300) {
                Spacer(Modifier.weight(1f))
                Text("📏 Line markers ON", fontSize = 9.sp, color = EditColorsLight.amber, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ИНДИКАТОР ВЫПОЛНЕНИЯ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ProcessingIndicatorLight(model: CreatorAIEditService.AiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = model.accentBg),
        border = BorderStroke(1.dp, model.accentColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = model.accentColor,
                strokeWidth = 2.5.dp
            )
            Column {
                Text(
                    "${model.displayName} анализирует код...",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = model.accentColor
                )
                Text(
                    "Генерация блоков замен (режим ${model.forcedThinkingLevel} thinking)",
                    fontSize = 11.sp,
                    color = EditColorsLight.textSecondary
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// РЕЗУЛЬТАТ ЗАМЕН
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun EditResultSectionLight(result: CreatorAIEditService.EditResult) {
    val hasFailedBlocks = result.blocks.any {
        it.matchStatus == CreatorAIEditService.EditBlock.MatchStatus.NOT_FOUND
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (hasFailedBlocks) EditColorsLight.amberBg else EditColorsLight.greenBg
            ),
            border = BorderStroke(
                1.dp,
                if (hasFailedBlocks) EditColorsLight.amber.copy(alpha = 0.4f) else EditColorsLight.greenBorder
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (hasFailedBlocks) Icons.Default.Warning else Icons.Default.CheckCircle,
                    null,
                    tint = if (hasFailedBlocks) EditColorsLight.amber else EditColorsLight.green,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        result.summary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasFailedBlocks) EditColorsLight.amberText else EditColorsLight.greenText
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${result.blocks.size} блок(ов) · ${result.model.displayName}",
                        fontSize = 10.sp,
                        color = EditColorsLight.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        result.blocks.forEachIndexed { index, block ->
            DiffBlockCardLight(index = index + 1, block = block)
        }
    }
}

@Composable
private fun DiffBlockCardLight(index: Int, block: CreatorAIEditService.EditBlock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = EditColorsLight.surface),
        border = BorderStroke(
            1.dp,
            when (block.matchStatus) {
                CreatorAIEditService.EditBlock.MatchStatus.NOT_FOUND -> EditColorsLight.redBorder
                CreatorAIEditService.EditBlock.MatchStatus.FUZZY,
                CreatorAIEditService.EditBlock.MatchStatus.LINE_RANGE -> EditColorsLight.amber.copy(alpha = 0.4f)
                else -> EditColorsLight.border
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EditColorsLight.surfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = EditColorsLight.blue.copy(alpha = 0.15f),
                        modifier = Modifier.size(20.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("$index", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EditColorsLight.blue)
                        }
                    }
                    Text("Блок $index", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EditColorsLight.textPrimary)
                }
                MatchStatusBadgeLight(status = block.matchStatus)
            }

            HorizontalDivider(color = EditColorsLight.border, thickness = 0.5.dp)

            if (block.search.isNotBlank()) {
                DiffCodeBlockLight(
                    label = "УДАЛИТЬ",
                    code = block.search,
                    bgColor = EditColorsLight.redBg,
                    labelColor = EditColorsLight.red,
                    textColor = EditColorsLight.redText,
                    prefix = "−"
                )
            }

            if (block.search.isNotBlank() && block.replace.isNotBlank()) {
                HorizontalDivider(color = EditColorsLight.border, thickness = 0.5.dp)
            }

            if (block.replace.isNotBlank()) {
                DiffCodeBlockLight(
                    label = if (block.search.isBlank()) "ВСТАВИТЬ" else "ДОБАВИТЬ",
                    code = block.replace,
                    bgColor = EditColorsLight.greenBg,
                    labelColor = EditColorsLight.green,
                    textColor = EditColorsLight.greenText,
                    prefix = "+"
                )
            } else if (block.search.isNotBlank()) {
                Surface(color = EditColorsLight.redBg, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "  (код будет полностью удалён)",
                        modifier = Modifier.padding(8.dp),
                        fontSize = 11.sp,
                        color = EditColorsLight.redText,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchStatusBadgeLight(status: CreatorAIEditService.EditBlock.MatchStatus) {
    val (text, color, bgColor) = when (status) {
        CreatorAIEditService.EditBlock.MatchStatus.EXACT ->
            Triple("✓ ТОЧНОЕ", EditColorsLight.green, EditColorsLight.greenBg)
        CreatorAIEditService.EditBlock.MatchStatus.NORMALIZED ->
            Triple("✓ NORM", EditColorsLight.green, EditColorsLight.greenBg)
        CreatorAIEditService.EditBlock.MatchStatus.FUZZY ->
            Triple("~ FUZZY", EditColorsLight.amber, EditColorsLight.amberBg)
        CreatorAIEditService.EditBlock.MatchStatus.LINE_RANGE ->
            Triple("~ RANGE", EditColorsLight.amber, EditColorsLight.amberBg)
        CreatorAIEditService.EditBlock.MatchStatus.NOT_FOUND ->
            Triple("✗ НЕ НАЙДЕН", EditColorsLight.red, EditColorsLight.redBg)
        CreatorAIEditService.EditBlock.MatchStatus.PENDING ->
            Triple("⏳", EditColorsLight.textTertiary, EditColorsLight.surfaceElevated)
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DiffCodeBlockLight(
    label: String,
    code: String,
    bgColor: Color,
    labelColor: Color,
    textColor: Color,
    prefix: String
) {
    Surface(color = bgColor, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = labelColor,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            val lines = code.lines()
            val displayLines = if (lines.size > 24) {
                lines.take(12) + listOf("... (скрыто ${lines.size - 24} строк) ...") + lines.takeLast(12)
            } else lines

            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    displayLines.forEach { line ->
                        Row {
                            Text(
                                "$prefix ",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = labelColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = textColor,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorSectionLight(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = EditColorsLight.redBg),
        border = BorderStroke(1.dp, EditColorsLight.redBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Error, null, tint = EditColorsLight.red, modifier = Modifier.size(20.dp))
            Text(message, fontSize = 12.sp, color = EditColorsLight.redText, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HintSectionLight() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EditColorsLight.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Lightbulb, null, tint = EditColorsLight.amber, modifier = Modifier.size(18.dp))
                Text("Как работает AI редактор", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EditColorsLight.textPrimary)
            }
            listOf(
                "📝" to "Опишите задачу — модель вернёт только точные блоки замен.",
                "⚡" to "3.5 Flash-Lite (Low Thinking) обеспечивает максимальную скорость.",
                "💨" to "3.1 Flash-Lite (Medium Thinking) даёт повышенную точность рассуждений.",
                "🔍" to "Перед применением проверьте превью зелёных и красных блоков."
            ).forEach { (emoji, text) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(emoji, fontSize = 14.sp)
                    Text(text, fontSize = 11.sp, color = EditColorsLight.textSecondary)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// НИЖНЯЯ ПАНЕЛЬ И ДЕЙСТВИЯ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun BottomBarLight(
    editStatus: CreatorAIEditService.EditStatus,
    selectedModel: CreatorAIEditService.AiModel,
    instructionsNotEmpty: Boolean,
    onProcess: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit
) {
    val isSuccess = editStatus is CreatorAIEditService.EditStatus.Success
    val isProcessing = editStatus is CreatorAIEditService.EditStatus.Processing
    val successResult = (editStatus as? CreatorAIEditService.EditStatus.Success)?.result

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = EditColorsLight.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column {
            if (isSuccess && successResult != null) {
                Surface(color = EditColorsLight.surfaceElevated, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TokenChipLight("ВХОД", "%,d".format(successResult.inputTokens), EditColorsLight.blue)
                        Text("+", fontSize = 11.sp, color = EditColorsLight.textTertiary)
                        TokenChipLight("ВЫХОД", "%,d".format(successResult.outputTokens), EditColorsLight.amber)
                        Text("=", fontSize = 11.sp, color = EditColorsLight.textTertiary)
                        TokenChipLight("ИТОГО", "%,d".format(successResult.inputTokens + successResult.outputTokens), EditColorsLight.textPrimary)
                        Text("·", fontSize = 12.sp, color = EditColorsLight.textTertiary)
                        TokenChipLight("СТОИМОСТЬ", "€${String.format(java.util.Locale.US, "%.4f", successResult.costEUR)}", EditColorsLight.green)
                    }
                }
                HorizontalDivider(color = EditColorsLight.border, thickness = 0.5.dp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val statusDotColor = when {
                        isSuccess -> EditColorsLight.green
                        isProcessing -> selectedModel.accentColor
                        else -> EditColorsLight.textTertiary
                    }
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusDotColor))
                    Text(
                        when {
                            isSuccess -> "Готово к применению"
                            isProcessing -> "Обработка..."
                            else -> "Ожидание"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = EditColorsLight.textPrimary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSuccess) {
                        OutlinedButton(
                            onClick = onDiscard,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, EditColorsLight.borderStrong)
                        ) {
                            Text("Сброс", fontSize = 12.sp, color = EditColorsLight.textSecondary)
                        }

                        Button(
                            onClick = onApply,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EditColorsLight.green)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("Применить", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = onProcess,
                            enabled = instructionsNotEmpty && !isProcessing,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = selectedModel.accentColor,
                                disabledContainerColor = EditColorsLight.border
                            )
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Генерация...", fontSize = 12.sp, color = Color.White)
                            } else {
                                Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("Обработать", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenChipLight(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = EditColorsLight.textTertiary)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
    }
}