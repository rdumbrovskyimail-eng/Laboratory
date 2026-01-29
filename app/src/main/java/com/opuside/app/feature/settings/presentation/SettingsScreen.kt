package com.opuside.app.feature.settings.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val gitHubConfig by viewModel.gitHubConfig.collectAsState()
    val githubStatus by viewModel.githubStatus.collectAsState()
    val repoInfo by viewModel.repoInfo.collectAsState()
    val claudeStatus by viewModel.claudeStatus.collectAsState()
    
    val githubOwnerInput by viewModel.githubOwnerInput.collectAsState()
    val githubRepoInput by viewModel.githubRepoInput.collectAsState()
    val githubTokenInput by viewModel.githubTokenInput.collectAsState()
    val githubBranchInput by viewModel.githubBranchInput.collectAsState()
    val anthropicKeyInput by viewModel.anthropicKeyInput.collectAsState()
    val claudeModelInput by viewModel.claudeModelInput.collectAsState()
    val cacheTimeoutInput by viewModel.cacheTimeoutInput.collectAsState()
    val maxCacheFilesInput by viewModel.maxCacheFilesInput.collectAsState()
    val autoClearCacheInput by viewModel.autoClearCacheInput.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            // GITHUB SETTINGS
            SettingsSection(title = "GitHub Repository", icon = Icons.Default.Code) {
                OutlinedTextField(
                    value = githubOwnerInput,
                    onValueChange = viewModel::updateGitHubOwner,
                    label = { Text("Owner / Organization") },
                    placeholder = { Text("username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, null) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubRepoInput,
                    onValueChange = viewModel::updateGitHubRepo,
                    label = { Text("Repository Name") },
                    placeholder = { Text("my-android-app") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Folder, null) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubBranchInput,
                    onValueChange = viewModel::updateGitHubBranch,
                    label = { Text("Default Branch") },
                    placeholder = { Text("main") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.CallSplit, null) }
                )
                Spacer(Modifier.height(8.dp))
                
                var showToken by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = githubTokenInput,
                    onValueChange = viewModel::updateGitHubToken,
                    label = { Text("Personal Access Token") },
                    placeholder = { Text("ghp_xxxxxxxxxxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    ConnectionStatusBadge(status = githubStatus)
                    Row {
                        TextButton(onClick = viewModel::testGitHubConnection) { Text("Test") }
                        Button(onClick = viewModel::saveGitHubSettings, enabled = !isSaving) { Text("Save") }
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

            // ANTHROPIC SETTINGS
            SettingsSection(title = "Claude API", icon = Icons.Default.Psychology) {
                var showApiKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = anthropicKeyInput,
                    onValueChange = viewModel::updateAnthropicKey,
                    label = { Text("API Key") },
                    placeholder = { Text("sk-ant-api03-xxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    }
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
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) }
                    )
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        listOf("claude-opus-4-5-20251101", "claude-sonnet-4-5-20250929", "claude-haiku-4-5-20251001").forEach { model ->
                            DropdownMenuItem(text = { Text(model) }, onClick = { viewModel.updateClaudeModel(model); modelExpanded = false })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    ConnectionStatusBadge(status = claudeStatus)
                    Row {
                        TextButton(onClick = viewModel::testClaudeConnection) { Text("Test") }
                        Button(onClick = viewModel::saveAnthropicSettings, enabled = !isSaving) { Text("Save") }
                    }
                }
            }

            // CACHE SETTINGS
            SettingsSection(title = "Cache & Timer", icon = Icons.Default.Timer) {
                Text("Cache keeps files for Claude analysis. Timer resets when adding files.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                
                Text("Cache Timeout: $cacheTimeoutInput minutes")
                Slider(value = cacheTimeoutInput.toFloat(), onValueChange = { viewModel.updateCacheTimeout(it.toInt()) },
                    valueRange = 1f..30f, steps = 28, modifier = Modifier.fillMaxWidth())
                
                Spacer(Modifier.height(8.dp))
                Text("Max Files: $maxCacheFilesInput")
                Slider(value = maxCacheFilesInput.toFloat(), onValueChange = { viewModel.updateMaxCacheFiles(it.toInt()) },
                    valueRange = 5f..50f, steps = 44, modifier = Modifier.fillMaxWidth())
                
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("Auto-clear on timeout")
                        Text("Automatically clear cache when timer expires",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoClearCacheInput, onCheckedChange = viewModel::updateAutoClearCache)
                }
                
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::saveCacheSettings, modifier = Modifier.align(Alignment.End), enabled = !isSaving) {
                    Text("Save Cache Settings")
                }
            }

            // APP INFO
            SettingsSection(title = "About", icon = Icons.Default.Info) {
                SettingsRow("Version", viewModel.appVersion)
                SettingsRow("Build", viewModel.buildType)
                SettingsRow("Target SDK", "36 (Android 16)")
                Spacer(Modifier.height(8.dp))
                Text("OpusIDE — AI-powered mobile development environment.\nUses Claude Opus 4.5 for intelligent code analysis.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ACTIONS
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::resetToDefaults, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
                Button(onClick = viewModel::saveAllSettings, modifier = Modifier.weight(1f), enabled = !isSaving) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save All")
                }
            }

            // HINT
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lightbulb, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("How it works", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("1. Set your GitHub repo and API keys above\n2. In Creator tab: browse files, edit, commit\n3. Select files and add to Cache for analysis\n4. In Analyzer tab: chat with Claude about cached files\n5. Timer shows cache validity (5 min default)\n6. When timer expires, add files again",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
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
    if (status is ConnectionStatus.Error) {
        Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 20.dp))
    }
}
