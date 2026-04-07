package com.opuside.app.feature.settings.presentation

import android.widget.Toast
import com.opuside.app.core.ui.theme.AppTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.opuside.app.core.security.BiometricAuthHelper
import com.opuside.app.core.security.GeminiKeyEntry
import com.opuside.app.core.security.SecureSettingsDataStore
import com.opuside.app.core.security.SecurityUtils
import com.opuside.app.core.util.CrashTestUtil
import com.opuside.app.core.util.LogContentDialog
import com.opuside.app.core.util.LogFile
import com.opuside.app.core.util.LogViewerDialog
import com.opuside.app.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    sensitiveFeatureDisabled: Boolean = false,
    selectedTheme: AppTheme = AppTheme.GRAPHITE,
    onThemeChange: (AppTheme) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val secureSettings = remember { SecureSettingsDataStore(context) }

    val gitHubConfig by viewModel.gitHubConfig.collectAsState(initial = SecureSettingsDataStore.GitHubConfig("", "", "main", ""))
    val githubStatus by viewModel.githubStatus.collectAsState()
    val repoInfo by viewModel.repoInfo.collectAsState()
    val claudeStatus by viewModel.claudeStatus.collectAsState()
    val deepSeekStatus by viewModel.deepSeekStatus.collectAsState()
    val geminiStatus by viewModel.geminiStatus.collectAsState()

    val githubOwnerInput by viewModel.githubOwnerInput.collectAsState()
    val githubRepoInput by viewModel.githubRepoInput.collectAsState()
    val githubTokenInput by viewModel.githubTokenInput.collectAsState()
    val githubBranchInput by viewModel.githubBranchInput.collectAsState()
    val anthropicKeyInput by viewModel.anthropicKeyInput.collectAsState()
    val claudeModelInput by viewModel.claudeModelInput.collectAsState()
    val deepSeekKeyInput by viewModel.deepSeekKeyInput.collectAsState()
    val geminiKeyInput by viewModel.geminiKeyInput.collectAsState()
    val geminiModelInput by viewModel.geminiModelInput.collectAsState()

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
        if (activity == null) {
            android.util.Log.w("SettingsScreen", "⚠️ FragmentActivity not available")
        } else {
            android.util.Log.d("SettingsScreen", "✅ FragmentActivity: ${activity.javaClass.simpleName}")
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (biometricAuthRequest && activity != null) {
        val currentActivity: FragmentActivity = activity
        LaunchedEffect(Unit) {
            BiometricAuthHelper.authenticate(
                activity = currentActivity,
                title = "Unlock Settings",
                subtitle = "Authentication required to access sensitive settings",
                onSuccess = { viewModel.onBiometricSuccess() },
                onError = { error -> viewModel.onBiometricError(error) }
            )
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.importConfigFromFile(it) } }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ═══════════════════════════════════════════════════════════════════════════
            // HEADER
            // ═══════════════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val indicatorColor by animateColorAsState(
                        targetValue = if (isUnlocked) Color(0xFF22C55E) else Color(0xFFEF4444),
                        label = "lock_indicator"
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null,
                            tint = indicatorColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = if (isUnlocked) "Unlocked" else "Locked",
                                style = MaterialTheme.typography.labelMedium,
                                color = indicatorColor
                            )
                            if (isUnlocked && unlockExpiration != null) {
                                val remainingTime = remember(timerTick, unlockExpiration) {
                                    derivedStateOf {
                                        val now = System.currentTimeMillis()
                                        val remaining = (unlockExpiration!! - now) / 1000
                                        if (remaining > 0) {
                                            val minutes = remaining / 60
                                            val seconds = remaining % 60
                                            "${minutes}:${seconds.toString().padStart(2, '0')}"
                                        } else "0:00"
                                    }
                                }
                                Text(
                                    text = remainingTime.value,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (!sensitiveFeatureDisabled) {
                        IconButton(
                            onClick = {
                                if (isUnlocked) viewModel.lock() else viewModel.requestUnlock()
                            }
                        ) {
                            Icon(
                                imageVector = if (isUnlocked) Icons.Default.Lock else Icons.Default.Fingerprint,
                                contentDescription = if (isUnlocked) "Lock Settings" else "Unlock Settings",
                                tint = if (isUnlocked) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Root warning
            if (sensitiveFeatureDisabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Root Access Detected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Text("Sensitive settings are disabled for security", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // Import config
            if (!sensitiveFeatureDisabled && isUnlocked) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Upload, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.width(8.dp))
                                Text("Import Configuration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Load all settings from a .txt file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Button(
                            onClick = { filePickerLauncher.launch("text/plain") },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Browse")
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // GITHUB SETTINGS
            // ═══════════════════════════════════════════════════════════════════════════
            SettingsSection(title = "GitHub Repository", icon = Icons.Default.Code) {
                OutlinedTextField(
                    value = githubOwnerInput,
                    onValueChange = viewModel::updateGitHubOwner,
                    label = { Text("Owner / Organization") },
                    placeholder = { Text("username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    enabled = !sensitiveFeatureDisabled && isUnlocked
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubRepoInput,
                    onValueChange = viewModel::updateGitHubRepo,
                    label = { Text("Repository Name") },
                    placeholder = { Text("my-android-app") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                    enabled = !sensitiveFeatureDisabled && isUnlocked
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubBranchInput,
                    onValueChange = viewModel::updateGitHubBranch,
                    label = { Text("Default Branch") },
                    placeholder = { Text("main") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.CallSplit, null) },
                    enabled = isUnlocked
                )
                Spacer(Modifier.height(8.dp))

                var showToken by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = githubTokenInput,
                    onValueChange = viewModel::updateGitHubToken,
                    label = {
                        Text(
                            if (sensitiveFeatureDisabled) "Personal Access Token (Disabled - Root Access)"
                            else if (!isUnlocked) "Personal Access Token (Locked)"
                            else "Personal Access Token"
                        )
                    },
                    placeholder = { Text("ghp_xxxxxxxxxxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showToken && isUnlocked) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }, enabled = isUnlocked) {
                            Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    enabled = !sensitiveFeatureDisabled && isUnlocked
                )
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    ConnectionStatusBadge(status = githubStatus)
                    Row {
                        TextButton(onClick = viewModel::testGitHubConnection, enabled = !sensitiveFeatureDisabled) { Text("Test") }
                        Button(onClick = viewModel::saveGitHubSettings, enabled = !isSaving && !sensitiveFeatureDisabled && isUnlocked) { Text("Save") }
                    }
                }

                repoInfo?.let { repo ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(repo.fullName, style = MaterialTheme.typography.titleSmall)
                    }
                    repo.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // ANTHROPIC / CLAUDE SETTINGS
            // ═══════════════════════════════════════════════════════════════════════════
            SettingsSection(title = "Claude API", icon = Icons.Default.Psychology) {
                var showApiKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = anthropicKeyInput,
                    onValueChange = viewModel::updateAnthropicKey,
                    label = {
                        Text(
                            if (sensitiveFeatureDisabled) "API Key (Disabled - Root Access)"
                            else if (!isUnlocked) "API Key (Locked)"
                            else "API Key"
                        )
                    },
                    placeholder = { Text("sk-ant-api03-xxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showApiKey && isUnlocked) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }, enabled = isUnlocked) {
                            Icon(if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    enabled = !sensitiveFeatureDisabled && isUnlocked
                )
                Spacer(Modifier.height(8.dp))

                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                    OutlinedTextField(
                        value = claudeModelInput,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                        enabled = !sensitiveFeatureDisabled && isUnlocked
                    )
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        com.opuside.app.core.ai.ClaudeModelConfig.ClaudeModel.getAllModelsWithNames().forEach { (modelId, displayName) ->
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = { viewModel.updateClaudeModel(modelId); modelExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    ConnectionStatusBadge(status = claudeStatus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = viewModel::testClaudeConnection,
                            enabled = !sensitiveFeatureDisabled && claudeStatus !is ConnectionStatus.Testing
                        ) {
                            if (claudeStatus is ConnectionStatus.Testing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("Test")
                        }
                        Button(
                            onClick = viewModel::saveAnthropicSettings,
                            enabled = !isSaving && !sensitiveFeatureDisabled && isUnlocked
                        ) { Text("Save") }
                    }
                }

                when (val status = claudeStatus) {
                    is ConnectionStatus.Connected -> {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Connection successful!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    is ConnectionStatus.Error -> {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Test Failed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    else -> {}
                }

                if (!sensitiveFeatureDisabled) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                activity == null -> MaterialTheme.colorScheme.errorContainer
                                !SecurityUtils.isDeviceSecure(context) -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.primaryContainer
                            }
                        )
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when {
                                    activity == null -> Icons.Default.Warning
                                    !SecurityUtils.isDeviceSecure(context) -> Icons.Default.Lock
                                    else -> Icons.Default.Fingerprint
                                },
                                null,
                                modifier = Modifier.size(20.dp),
                                tint = when {
                                    activity == null -> MaterialTheme.colorScheme.error
                                    !SecurityUtils.isDeviceSecure(context) -> MaterialTheme.colorScheme.onTertiaryContainer
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    when {
                                        activity == null -> "⚠️ Biometric unavailable (restart app)"
                                        !SecurityUtils.isDeviceSecure(context) -> "⚠️ Set up lock screen first"
                                        else -> "✅ Biometric protection enabled"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        activity == null -> MaterialTheme.colorScheme.onErrorContainer
                                        !SecurityUtils.isDeviceSecure(context) -> MaterialTheme.colorScheme.onTertiaryContainer
                                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                                    }
                                )
                                if (activity != null && SecurityUtils.isDeviceSecure(context)) {
                                    Text(
                                        "API key protected by fingerprint/face",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // DEEPSEEK SETTINGS
            // ═══════════════════════════════════════════════════════════════════════════
            SettingsSection(title = "DeepSeek API", icon = Icons.Default.AutoAwesome) {
                var showDeepSeekKey by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = deepSeekKeyInput,
                    onValueChange = viewModel::updateDeepSeekKey,
                    label = {
                        Text(
                            if (sensitiveFeatureDisabled) "API Key (Disabled - Root Access)"
                            else if (!isUnlocked) "API Key (Locked)"
                            else "API Key"
                        )
                    },
                    placeholder = { Text("sk-xxxxxxxxxxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showDeepSeekKey && isUnlocked) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { showDeepSeekKey = !showDeepSeekKey }, enabled = isUnlocked) {
                            Icon(if (showDeepSeekKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    enabled = !sensitiveFeatureDisabled && isUnlocked
                )

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2030))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("🐋", style = MaterialTheme.typography.titleMedium)
                        Column {
                            Text(
                                "DeepSeek Chat & R1 Reasoner",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF4E9BCD)
                            )
                            Text(
                                "Получить ключ: platform.deepseek.com",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4E9BCD).copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically
                ) {
                    ConnectionStatusBadge(status = deepSeekStatus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = viewModel::testDeepSeekConnection,
                            enabled = !sensitiveFeatureDisabled && deepSeekStatus !is ConnectionStatus.Testing
                        ) {
                            if (deepSeekStatus is ConnectionStatus.Testing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("Test")
                        }
                        Button(
                            onClick = viewModel::saveDeepSeekSettings,
                            enabled = !isSaving && !sensitiveFeatureDisabled && isUnlocked,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E9BCD))
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("Save")
                        }
                    }
                }

                when (val status = deepSeekStatus) {
                    is ConnectionStatus.Connected -> {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2030))
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4E9BCD))
                                Spacer(Modifier.width(8.dp))
                                Text("DeepSeek connected!", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4E9BCD))
                            }
                        }
                    }
                    is ConnectionStatus.Error -> {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Test Failed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    else -> {}
                }
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // GEMINI SETTINGS
            // ═══════════════════════════════════════════════════════════════════════════
            SettingsSection(title = "Gemini API", icon = Icons.Default.AutoAwesomeMotion) {
                // Собираем state
                val geminiKeys by viewModel.geminiKeys.collectAsState()
                val geminiActiveKeyIndex by viewModel.geminiActiveKeyIndex.collectAsState()

                // Заголовок
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("API Keys (${geminiKeys.size}/10)", style = MaterialTheme.typography.titleSmall)
                    if (isUnlocked && geminiKeys.size < 10) {
                        var showAddDialog by remember { mutableStateOf(false) }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, "Add Key", tint = MaterialTheme.colorScheme.primary)
                        }
                        if (showAddDialog) {
                            var newLabel by remember { mutableStateOf("") }
                            var newKey by remember { mutableStateOf("") }
                            AlertDialog(
                                onDismissRequest = { showAddDialog = false },
                                title = { Text("Add Gemini Key") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = newLabel,
                                            onValueChange = { newLabel = it },
                                            label = { Text("Label") },
                                            placeholder = { Text("e.g. Project Alpha") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = newKey,
                                            onValueChange = { newKey = it },
                                            label = { Text("API Key") },
                                            placeholder = { Text("AIzaSy-...") },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(onClick = {
                                        viewModel.addGeminiKey(newLabel, newKey)
                                        showAddDialog = false
                                    }) { Text("Add") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                if (geminiKeys.isEmpty()) {
                    Text(
                        "No API keys configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    geminiKeys.forEachIndexed { index, entry ->
                        val isActive = index == geminiActiveKeyIndex
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setActiveGeminiKey(index) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) Color(0xFF1A2744) else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            border = if (isActive) BorderStroke(1.5.dp, Color(0xFF8AB4F8)) else null
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isActive,
                                    onClick = { viewModel.setActiveGeminiKey(index) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF8AB4F8))
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isActive) Color(0xFF8AB4F8) else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "${entry.key.take(8)}...${entry.key.takeLast(4)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isUnlocked) {
                                    IconButton(
                                        onClick = { viewModel.removeGeminiKey(index) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete, "Remove",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Выбор модели Gemini
                var geminiModelExpanded by remember { mutableStateOf(false) }
                val geminiModels = com.opuside.app.core.ai.GeminiModelConfig.GeminiModel.getActiveModels()
                    .map { it.modelId to "${it.emoji} ${it.displayName}" }
                ExposedDropdownMenuBox(
                    expanded = geminiModelExpanded,
                    onExpandedChange = { geminiModelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = geminiModels.firstOrNull { it.first == geminiModelInput }?.second ?: geminiModelInput,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default Model") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(geminiModelExpanded) },
                        leadingIcon = { Text("✨", style = MaterialTheme.typography.bodyMedium) },
                        enabled = !sensitiveFeatureDisabled && isUnlocked
                    )
                    ExposedDropdownMenu(
                        expanded = geminiModelExpanded,
                        onDismissRequest = { geminiModelExpanded = false }
                    ) {
                        geminiModels.forEach { (modelId, displayName) ->
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = {
                                    viewModel.updateGeminiModel(modelId)
                                    geminiModelExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F3A))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("✨", style = MaterialTheme.typography.titleMedium)
                        Column {
                            Text(
                                "Google Gemini — 4 версии моделей",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF8AB4F8)
                            )
                            Text(
                                "Получить ключ: aistudio.google.com/apikey",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8AB4F8).copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically
                ) {
                    ConnectionStatusBadge(status = geminiStatus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = viewModel::testGeminiConnection,
                            enabled = !sensitiveFeatureDisabled && geminiStatus !is ConnectionStatus.Testing
                        ) {
                            if (geminiStatus is ConnectionStatus.Testing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("Test")
                        }
                        Button(
                            onClick = viewModel::saveGeminiSettings,
                            enabled = !isSaving && !sensitiveFeatureDisabled && isUnlocked,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("Save", color = Color(0xFF0D1117))
                        }
                    }
                }

                when (val status = geminiStatus) {
                    is ConnectionStatus.Connected -> {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F3A))
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF8AB4F8))
                                Spacer(Modifier.width(8.dp))
                                Text("Gemini connected!", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8AB4F8))
                            }
                        }
                    }
                    is ConnectionStatus.Error -> {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Test Failed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    else -> {}
                }
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // DEVELOPER TOOLS
            // ═══════════════════════════════════════════════════════════════════════════
            SettingsSection(title = "Developer Tools", icon = Icons.Default.BugReport) {
                Text(
                    "Debug and testing tools for development",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                var showRootDialogOnStartup by remember {
                    mutableStateOf(
                        runBlocking {
                            context.dataStore.data.map { prefs ->
                                prefs[booleanPreferencesKey("show_root_dialog_on_startup")] ?: true
                            }.first()
                        }
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Root Dialog on Startup", style = MaterialTheme.typography.titleSmall)
                            Text("Display security check dialog when app starts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = showRootDialogOnStartup,
                            onCheckedChange = { enabled ->
                                showRootDialogOnStartup = enabled
                                lifecycleOwner.lifecycleScope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[booleanPreferencesKey("show_root_dialog_on_startup")] = enabled
                                    }
                                    Toast.makeText(
                                        context,
                                        if (enabled) "Root dialog will show on next startup" else "Root dialog disabled",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (SecurityUtils.isDeviceRooted()) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (SecurityUtils.isDeviceRooted()) Icons.Default.Warning else Icons.Default.CheckCircle,
                            null, Modifier.size(32.dp),
                            tint = if (SecurityUtils.isDeviceRooted()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (SecurityUtils.isDeviceRooted()) "Root Detected" else "No Root Detected",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (SecurityUtils.isDeviceRooted()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (SecurityUtils.isDeviceRooted()) "Sensitive features may be compromised" else "Device is secure",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (SecurityUtils.isDeviceRooted()) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                var crashStats by remember { mutableStateOf<com.opuside.app.core.util.LogStats?>(null) }
                LaunchedEffect(Unit) { crashStats = CrashTestUtil.getLogStats() }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("Logger Statistics", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(crashStats?.toString() ?: "Loading...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(12.dp))

                var showLogViewer by remember { mutableStateOf(false) }
                var selectedLogForViewing by remember { mutableStateOf<LogFile?>(null) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { CrashTestUtil.triggerTestCrash() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                    ) {
                        Icon(Icons.Default.Warning, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test Crash")
                    }
                    Button(
                        onClick = {
                            val file = CrashTestUtil.saveLogCatErrors(context)
                            if (file != null) {
                                Toast.makeText(context, "✅ LogCat errors saved!\n${file.name}", Toast.LENGTH_LONG).show()
                                crashStats = CrashTestUtil.getLogStats()
                            } else {
                                Toast.makeText(context, "❌ Failed to save LogCat", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save LogCat")
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showLogViewer = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("View Logs")
                    }
                    OutlinedButton(
                        onClick = { CrashTestUtil.shareLatestCrashLog(context) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share Log")
                    }
                }

                if (showLogViewer) {
                    LogViewerDialog(
                        onDismiss = { showLogViewer = false; selectedLogForViewing = null },
                        onLogSelected = { logFile -> selectedLogForViewing = logFile }
                    )
                }
                selectedLogForViewing?.let { logFile ->
                    LogContentDialog(logFile = logFile, onDismiss = { selectedLogForViewing = null })
                }

                Spacer(Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = Color(0xFFD97706))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Info", style = MaterialTheme.typography.titleSmall, color = Color(0xFFD97706))
                            Text(
                                "• \"Test Crash\" will immediately crash the app\n• Crash logs auto-save when app crashes\n• \"Save LogCat\" saves only errors from logcat\n• \"View Logs\" shows all crash & logcat logs with red error highlighting",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF92400E)
                            )
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // THEME
            // ═══════════════════════════════════════════════════════════════════════════
            SettingsSection(title = "Тема интерфейса", icon = Icons.Default.Palette) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { theme ->
                        val isSelected = theme == selectedTheme
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onThemeChange(theme) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(Modifier.size(16.dp).clip(CircleShape).background(theme.config.bg))
                                    Box(Modifier.size(16.dp).clip(CircleShape).background(theme.config.surface))
                                    Box(Modifier.size(16.dp).clip(CircleShape).background(theme.config.accent))
                                }
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(theme.config.emoji, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            theme.config.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(theme.config.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // APP INFO
            // ═══════════════════════════════════════════════════════════════════════════
            SettingsSection(title = "About", icon = Icons.Default.Info) {
                SettingsRow("Version", viewModel.appVersion)
                SettingsRow("Build", viewModel.buildType)
                SettingsRow("Target SDK", "36 (Android 16)")
                Spacer(Modifier.height(8.dp))
                Text(
                    "OpusIDE — AI-powered mobile development environment.\nSupports Claude, DeepSeek and Gemini models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // ACTIONS
            // ═══════════════════════════════════════════════════════════════════════════
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::resetToDefaults, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
                Button(
                    onClick = viewModel::saveAllSettings,
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving && !sensitiveFeatureDisabled && isUnlocked
                ) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save All")
                }
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // HINT
            // ═══════════════════════════════════════════════════════════════════════════
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lightbulb, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("How it works", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "🔐 SECURITY:\n" +
                        "• Click 🔓 Unlock button to edit sensitive settings\n" +
                        "• Auto-locks after 5 minutes of inactivity\n" +
                        "• Biometric protection is ALWAYS ENABLED for API keys\n\n" +
                        "📥 QUICK SETUP:\n" +
                        "• Click \"Import Config\" button to load settings from .txt file\n\n" +
                        "🐋 DEEPSEEK:\n" +
                        "• Зарегистрируйтесь на platform.deepseek.com\n" +
                        "• Вставьте ключ в секцию DeepSeek API выше\n\n" +
                        "✨ GEMINI:\n" +
                        "• Получите ключ на aistudio.google.com/apikey\n" +
                        "• Выберите одну из 4 версий модели\n" +
                        "• Переключайте модель в Creator → AI Edit\n\n" +
                        "📱 USAGE:\n" +
                        "1. Set your GitHub repo and API keys above\n" +
                        "2. In Creator tab: browse files, edit, commit\n" +
                        "3. In Analyzer tab: chat with Claude about your project\n" +
                        "4. Use Developer Tools to test crash logger",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun ConnectionStatusBadge(status: ConnectionStatus) {
    val (color, icon, text) = when (status) {
        is ConnectionStatus.Unknown -> Triple(Color.Gray, Icons.Default.HelpOutline, "Not tested")
        is ConnectionStatus.Testing -> Triple(Color(0xFFF59E0B), Icons.Default.Sync, "Testing...")
        is ConnectionStatus.Connected -> Triple(Color(0xFF22C55E), Icons.Default.CheckCircle, "Connected")
        is ConnectionStatus.Error -> Triple(Color(0xFFEF4444), Icons.Default.Error, "Error")
    }
    val animatedColor by animateColorAsState(color, label = "status")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = animatedColor)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = animatedColor)
    }
}