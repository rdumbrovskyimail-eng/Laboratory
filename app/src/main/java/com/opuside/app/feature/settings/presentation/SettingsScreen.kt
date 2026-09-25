package com.opuside.app.feature.settings.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.ai.GeminiModelConfig
import com.opuside.app.core.security.GeminiKeyEntry
import com.opuside.app.core.security.SecureSettingsDataStore
import com.opuside.app.core.ui.theme.AppTheme

// ═══════════════════════════════════════════════════════════════════════════
// LIGHT PROFESSIONAL THEME (Samsung S23 Ultra AMOLED Optimized)
// ═══════════════════════════════════════════════════════════════════════════

private object SettingsTheme {
    val background = Color(0xFFF8F9FA)
    val surface = Color(0xFFFFFFFF)
    val surfaceSecondary = Color(0xFFF1F3F5)
    val border = Color(0xFFE2E8F0)
    val borderStrong = Color(0xFFCBD5E1)

    val textPrimary = Color(0xFF0F172A)
    val textSecondary = Color(0xFF475569)
    val textTertiary = Color(0xFF94A3B8)

    val blue = Color(0xFF2563EB)
    val blueSoft = Color(0xFFEFF6FF)
    val green = Color(0xFF059669)
    val greenSoft = Color(0xFFECFDF5)
    val amber = Color(0xFFD97706)
    val amberSoft = Color(0xFFFFFBEB)
    val red = Color(0xFFDC2626)
    val redSoft = Color(0xFFFEF2F2)
    val purple = Color(0xFF7C3AED)          // Исправлено: добавлен цвет purple
    val purpleSoft = Color(0xFFF5F3FF)      // Исправлено: добавлен цвет purpleSoft
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    sensitiveFeatureDisabled: Boolean = false,
    selectedTheme: AppTheme = AppTheme.GRAPHITE,
    onThemeChange: (AppTheme) -> Unit = {}
) {
    val context = LocalContext.current

    val gitHubConfig by viewModel.gitHubConfig.collectAsState(initial = SecureSettingsDataStore.GitHubConfig("", "", "main", ""))
    val githubStatus by viewModel.githubStatus.collectAsState()
    val repoInfo by viewModel.repoInfo.collectAsState()
    val geminiStatus by viewModel.geminiStatus.collectAsState()

    val githubOwnerInput by viewModel.githubOwnerInput.collectAsState()
    val githubRepoInput by viewModel.githubRepoInput.collectAsState()
    val githubTokenInput by viewModel.githubTokenInput.collectAsState()
    val githubBranchInput by viewModel.githubBranchInput.collectAsState()
    val geminiModelInput by viewModel.geminiModelInput.collectAsState()
    val geminiKeys by viewModel.geminiKeys.collectAsState()
    val geminiActiveKeyIndex by viewModel.geminiActiveKeyIndex.collectAsState()

    val isSaving by viewModel.isSaving.collectAsState()
    val message by viewModel.message.collectAsState()
    val biometricAuthRequest by viewModel.biometricAuthRequest.collectAsState()

    val isUnlocked by viewModel.isUnlocked.collectAsState()
    val unlockExpiration by viewModel.unlockExpiration.collectAsState()
    val timerTick by viewModel.timerTick.collectAsState()

    val activity = remember(context) {
        if (context is androidx.activity.ComponentActivity) context as? FragmentActivity else null
    }

    LaunchedEffect(Unit) {
        if (!sensitiveFeatureDisabled && !isUnlocked) {
            viewModel.requestUnlock()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (biometricAuthRequest) {
        LaunchedEffect(Unit) {
            viewModel.onBiometricSuccess()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.importConfigFromFile(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = SettingsTheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── ШАПКА И СТАТУС БЛОКИРОВКИ ──────────────────────────────────
            SettingsHeaderCard(
                isUnlocked = isUnlocked,
                unlockExpiration = unlockExpiration,
                timerTick = timerTick,
                sensitiveFeatureDisabled = sensitiveFeatureDisabled,
                onLockToggle = {
                    if (isUnlocked) viewModel.lock() else viewModel.requestUnlock()
                }
            )

            // Предупреждение о Root
            if (sensitiveFeatureDisabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SettingsTheme.redSoft),
                    border = BorderStroke(1.dp, SettingsTheme.red.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = SettingsTheme.red, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Обнаружен Root-доступ", style = MaterialTheme.typography.titleSmall, color = SettingsTheme.red, fontWeight = FontWeight.Bold)
                            Text("Редактирование API ключей ограничено в целях безопасности", style = MaterialTheme.typography.bodySmall, color = SettingsTheme.textSecondary)
                        }
                    }
                }
            }

            // Быстрый импорт настроек
            if (!sensitiveFeatureDisabled && isUnlocked) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SettingsTheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.UploadFile, null, tint = SettingsTheme.blue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Импорт конфигурации", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SettingsTheme.textPrimary)
                            }
                            Text("Быстрая загрузка настроек и токенов из .txt файла", style = MaterialTheme.typography.bodySmall, color = SettingsTheme.textSecondary)
                        }
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch("text/plain") },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SettingsTheme.borderStrong)
                        ) {
                            Text("Файл...", fontSize = 12.sp, color = SettingsTheme.textPrimary)
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════
            // НАСТРОЙКИ GITHUB
            // ═══════════════════════════════════════════════════════════════════════
            SettingsSectionCard(title = "GitHub Репозиторий", icon = Icons.Default.Code, iconTint = SettingsTheme.blue) {
                OutlinedTextField(
                    value = githubOwnerInput,
                    onValueChange = viewModel::updateGitHubOwner,
                    label = { Text("Владелец / Организация") },
                    placeholder = { Text("username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, null, tint = SettingsTheme.textTertiary) },
                    enabled = !sensitiveFeatureDisabled && isUnlocked,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsTheme.surfaceSecondary,
                        unfocusedContainerColor = SettingsTheme.surfaceSecondary
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubRepoInput,
                    onValueChange = viewModel::updateGitHubRepo,
                    label = { Text("Название репозитория") },
                    placeholder = { Text("my-android-app") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Folder, null, tint = SettingsTheme.textTertiary) },
                    enabled = !sensitiveFeatureDisabled && isUnlocked,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsTheme.surfaceSecondary,
                        unfocusedContainerColor = SettingsTheme.surfaceSecondary
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubBranchInput,
                    onValueChange = viewModel::updateGitHubBranch,
                    label = { Text("Ветка по умолчанию") },
                    placeholder = { Text("main") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.CallSplit, null, tint = SettingsTheme.textTertiary) },
                    enabled = isUnlocked,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsTheme.surfaceSecondary,
                        unfocusedContainerColor = SettingsTheme.surfaceSecondary
                    )
                )
                Spacer(Modifier.height(8.dp))

                var showToken by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = githubTokenInput,
                    onValueChange = viewModel::updateGitHubToken,
                    label = { Text("Personal Access Token (PAT)") },
                    placeholder = { Text("ghp_xxxxxxxxxxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showToken && isUnlocked) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, null, tint = SettingsTheme.textTertiary) },
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }, enabled = isUnlocked) {
                            Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = SettingsTheme.textTertiary)
                        }
                    },
                    enabled = !sensitiveFeatureDisabled && isUnlocked,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsTheme.surfaceSecondary,
                        unfocusedContainerColor = SettingsTheme.surfaceSecondary
                    )
                )
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    ConnectionBadge(status = githubStatus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::testGitHubConnection,
                            enabled = !sensitiveFeatureDisabled && githubStatus !is ConnectionStatus.Testing,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (githubStatus is ConnectionStatus.Testing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("Проверить", fontSize = 12.sp)
                        }
                        Button(
                            onClick = viewModel::saveGitHubSettings,
                            enabled = !isSaving && !sensitiveFeatureDisabled && isUnlocked,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.blue)
                        ) {
                            Text("Сохранить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                repoInfo?.let { repo ->
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = SettingsTheme.border, thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public, null, Modifier.size(16.dp), tint = SettingsTheme.textSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text(repo.fullName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = SettingsTheme.textPrimary)
                    }
                    repo.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = SettingsTheme.textSecondary)
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════
            // НАСТРОЙКИ GEMINI API
            // ═══════════════════════════════════════════════════════════════════════
            SettingsSectionCard(title = "Google Gemini API", icon = Icons.Default.AutoAwesome, iconTint = SettingsTheme.green) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("API Ключи (${geminiKeys.size}/10)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SettingsTheme.textPrimary)
                    if (isUnlocked && geminiKeys.size < 10) {
                        var showAddDialog by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Добавить", fontSize = 11.sp)
                        }

                        if (showAddDialog) {
                            AddGeminiKeyDialog(
                                onDismiss = { showAddDialog = false },
                                onAdd = { label, key ->
                                    viewModel.addGeminiKey(label, key)
                                    showAddDialog = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (geminiKeys.isEmpty()) {
                    Text("Ключи API не настроены. Добавьте хотя бы один ключ.", style = MaterialTheme.typography.bodySmall, color = SettingsTheme.textSecondary)
                } else {
                    geminiKeys.forEachIndexed { index, entry ->
                        val isActive = index == geminiActiveKeyIndex
                        Surface(
                            onClick = { viewModel.setActiveGeminiKey(index) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isActive) SettingsTheme.blueSoft else SettingsTheme.surfaceSecondary,
                            border = BorderStroke(if (isActive) 1.5.dp else 0.5.dp, if (isActive) SettingsTheme.blue else SettingsTheme.border),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isActive,
                                    onClick = { viewModel.setActiveGeminiKey(index) },
                                    colors = RadioButtonDefaults.colors(selectedColor = SettingsTheme.blue)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(entry.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (isActive) SettingsTheme.blue else SettingsTheme.textPrimary)
                                    Text(
                                        "${entry.key.take(8)}...${entry.key.takeLast(4)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = SettingsTheme.textSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                if (isUnlocked) {
                                    IconButton(onClick = { viewModel.removeGeminiKey(index) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Delete, null, tint = SettingsTheme.red, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Выбор модели Gemini (строго 3.5 и 3.1 Flash-Lite)
                Text("Модель по умолчанию", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SettingsTheme.textPrimary)
                Spacer(Modifier.height(6.dp))

                var geminiModelExpanded by remember { mutableStateOf(false) }
                val availableModels = listOf(
                    GeminiModelConfig.GeminiModel.GEMINI_3_5_FLASH_LITE,
                    GeminiModelConfig.GeminiModel.GEMINI_3_1_FLASH_LITE
                )

                ExposedDropdownMenuBox(
                    expanded = geminiModelExpanded,
                    onExpandedChange = { if (isUnlocked) geminiModelExpanded = it }
                ) {
                    val activeDisplay = availableModels.find { it.modelId == geminiModelInput }?.let {
                        "${it.emoji} ${it.displayName} (${it.forcedThinkingLevel.displayName} Thinking)"
                    } ?: "${GeminiModelConfig.GeminiModel.GEMINI_3_5_FLASH_LITE.emoji} 3.5 Flash-Lite (Low Thinking)"

                    OutlinedTextField(
                        value = activeDisplay,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(geminiModelExpanded) },
                        leadingIcon = { Text("⚡", fontSize = 16.sp) },
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SettingsTheme.surfaceSecondary,
                            unfocusedContainerColor = SettingsTheme.surfaceSecondary
                        ),
                        enabled = !sensitiveFeatureDisabled && isUnlocked
                    )
                    ExposedDropdownMenu(
                        expanded = geminiModelExpanded,
                        onDismissRequest = { geminiModelExpanded = false },
                        modifier = Modifier.background(SettingsTheme.surface)
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("${model.emoji} ${model.displayName}", fontWeight = FontWeight.Bold, color = SettingsTheme.textPrimary)
                                        Text(
                                            "Штатный режим: ${model.forcedThinkingLevel.displayName} thinking · \$${model.inputPricePerM}/\$${model.outputPricePerM} per 1M",
                                            fontSize = 10.sp, color = SettingsTheme.textSecondary
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.updateGeminiModel(model.modelId)
                                    geminiModelExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = SettingsTheme.greenSoft)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Bolt, null, tint = SettingsTheme.green, modifier = Modifier.size(22.dp))
                        Column {
                            Text("Оптимальные Flash-Lite модели Google", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SettingsTheme.green)
                            Text(
                                "3.5 Flash-Lite (Low Thinking) и 3.1 Flash-Lite (Medium Thinking) настроены аппаратно без ручных задержек.",
                                fontSize = 10.sp, color = SettingsTheme.textSecondary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    ConnectionBadge(status = geminiStatus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::testGeminiConnection,
                            enabled = !sensitiveFeatureDisabled && geminiStatus !is ConnectionStatus.Testing,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (geminiStatus is ConnectionStatus.Testing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("Проверить", fontSize = 12.sp)
                        }
                        Button(
                            onClick = viewModel::saveGeminiSettings,
                            enabled = !isSaving && !sensitiveFeatureDisabled && isUnlocked,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.green)
                        ) {
                            Text("Сохранить", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════
            // ИНФОРМАЦИЯ О ПРИЛОЖЕНИИ
            // ═══════════════════════════════════════════════════════════════════════
            SettingsSectionCard(title = "О приложении", icon = Icons.Default.Info, iconTint = SettingsTheme.purple) {
                InfoItemRow("Версия", viewModel.appVersion)
                InfoItemRow("Тип сборки", viewModel.buildType)
                InfoItemRow("Целевой Android", "API 36 (Android 16)")
                InfoItemRow("Оптимизация", "Samsung Galaxy S23 Ultra")
                Spacer(Modifier.height(6.dp))
                Text(
                    "OpusIDE — мобильная среда разработки с Gemini Flash-Lite, Git-пайплайном и системой отката.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsTheme.textSecondary
                )
            }

            // ═══════════════════════════════════════════════════════════════════════
            // ГЛОБАЛЬНЫЕ ДЕЙСТВИЯ (СБРОС И СОХРАНИТЬ ВСЁ)
            // ═══════════════════════════════════════════════════════════════════════
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = viewModel::resetToDefaults,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SettingsTheme.borderStrong)
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Сброс", fontSize = 13.sp, color = SettingsTheme.textPrimary)
                }
                Button(
                    onClick = viewModel::saveAllSettings,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = !isSaving && !sensitiveFeatureDisabled && isUnlocked,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.blue)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Сохранить всё", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsHeaderCard(
    isUnlocked: Boolean,
    unlockExpiration: Long?,
    timerTick: Long,
    sensitiveFeatureDisabled: Boolean,
    onLockToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SettingsTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SettingsTheme.textPrimary)
                val statusText = if (isUnlocked) "Разблокировано" else "Заблокировано"
                val statusColor = if (isUnlocked) SettingsTheme.green else SettingsTheme.red
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor, fontWeight = FontWeight.SemiBold)
            }

            if (!sensitiveFeatureDisabled) {
                OutlinedButton(
                    onClick = onLockToggle,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isUnlocked) SettingsTheme.green else SettingsTheme.blue),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (isUnlocked) SettingsTheme.greenSoft else SettingsTheme.blueSoft)
                ) {
                    Icon(
                        if (isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                        null,
                        tint = if (isUnlocked) SettingsTheme.green else SettingsTheme.blue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isUnlocked) "Заблокировать" else "Разблокировать",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isUnlocked) SettingsTheme.green else SettingsTheme.blue
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SettingsTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = iconTint.copy(alpha = 0.1f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = SettingsTheme.textPrimary)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun InfoItemRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = SettingsTheme.textSecondary, fontSize = 12.sp)
        Text(value, color = SettingsTheme.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ConnectionBadge(status: ConnectionStatus) {
    val (color, icon, text) = when (status) {
        is ConnectionStatus.Unknown -> Triple(SettingsTheme.textTertiary, Icons.Default.HelpOutline, "Не проверено")
        is ConnectionStatus.Testing -> Triple(SettingsTheme.amber, Icons.Default.Sync, "Проверка...")
        is ConnectionStatus.Connected -> Triple(SettingsTheme.green, Icons.Default.CheckCircle, "Подключено")
        is ConnectionStatus.Error -> Triple(SettingsTheme.red, Icons.Default.Error, "Ошибка")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun AddGeminiKeyDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить ключ Gemini", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Метка (Label)") },
                    placeholder = { Text("например: Проект Alpha") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Ключ") },
                    placeholder = { Text("AIzaSy-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(label.trim(), key.trim()) },
                enabled = key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.blue)
            ) {
                Text("Добавить", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        containerColor = SettingsTheme.surface
    )
}