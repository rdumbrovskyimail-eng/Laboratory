package com.opuside.app.feature.analyzer.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import com.opuside.app.core.network.github.model.WorkflowRun

@Composable
fun AnalyzerScreen(
    viewModel: AnalyzerViewModel = hiltViewModel(),
    sensitiveFeatureDisabled: Boolean = false
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val currentStreamingText by viewModel.currentStreamingText.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    val tokensUsed by viewModel.tokensUsedInSession.collectAsState()
    
    val workflowRuns by viewModel.workflowRuns.collectAsState()
    val actionsLoading by viewModel.actionsLoading.collectAsState()

    var userInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size, currentStreamingText) {
        if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.size)
    }

    LaunchedEffect(Unit) { 
        viewModel.loadWorkflowRuns() 
    }

    if (sensitiveFeatureDisabled) {
        SensitiveFeaturesDisabledScreen()
        return
    }

    Column(Modifier.fillMaxSize()) {
        // CHAT AREA
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (chatMessages.isEmpty() && !isStreaming) {
                item {
                    WelcomeCard()
                }
            }

            items(chatMessages) { msg -> 
                ChatBubble(msg) 
            }

            if (isStreaming && currentStreamingText.isNotEmpty()) {
                item {
                    ChatBubble(ChatMessageEntity(
                        sessionId = "", 
                        role = MessageRole.ASSISTANT,
                        content = currentStreamingText, 
                        isStreaming = true
                    ))
                }
            }
        }

        chatError?.let {
            ErrorBanner(message = it, onDismiss = viewModel::clearChatError)
        }

        if (tokensUsed > 0) {
            Text(
                "Session tokens: ~$tokensUsed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        ChatInput(
            value = userInput,
            onValueChange = { userInput = it },
            onSend = {
                if (userInput.isNotBlank()) {
                    viewModel.sendMessage(userInput)
                    userInput = ""
                }
            },
            isStreaming = isStreaming,
            onCancel = viewModel::cancelStreaming,
            modifier = Modifier.padding(16.dp)
        )

        ActionsPanel(
            runs = workflowRuns,
            isLoading = actionsLoading,
            onRefresh = viewModel::loadWorkflowRuns,
            onSelectRun = viewModel::selectWorkflowRun
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SENSITIVE FEATURES DISABLED SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SensitiveFeaturesDisabledScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Analyzer Disabled",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Sensitive features are disabled due to root access on this device.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "The following functions are unavailable:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                
                listOf(
                    "Chat with Claude AI",
                    "API key storage",
                    "Secure data handling",
                    "GitHub Actions integration"
                ).forEach { feature ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Close,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            feature,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "To use these features, restart the app and choose 'Proceed Anyway' (not recommended for security reasons).",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UI COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun WelcomeCard() {
    Card(
        Modifier.fillMaxWidth(), 
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology, 
                    null, 
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Claude Analyzer", 
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Ready! Ask me anything about your project. I have direct access to your repository through Cloud API.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessageEntity) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM
    
    if (isSystem) {
        Card(
            Modifier.fillMaxWidth(), 
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Text(
                message.content, 
                Modifier.padding(12.dp), 
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    Row(
        Modifier.fillMaxWidth(), 
        if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                16.dp, 16.dp, 
                if (isUser) 4.dp else 16.dp, 
                if (isUser) 16.dp else 4.dp
            ),
            colors = CardDefaults.cardColors(
                if (isUser) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    message.content,
                    color = if (isUser) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (message.isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(12.dp), 
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "typing...", 
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInput(
    value: String, 
    onValueChange: (String) -> Unit, 
    onSend: () -> Unit,
    isStreaming: Boolean, 
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value, 
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { 
                Text("Ask Claude about your project...")
            },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            enabled = !isStreaming,
            shape = RoundedCornerShape(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        if (isStreaming) {
            IconButton(
                onClick = onCancel, 
                colors = IconButtonDefaults.iconButtonColors(
                    MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Icon(
                    Icons.Default.Stop, 
                    "Cancel", 
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                colors = IconButtonDefaults.iconButtonColors(
                    MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Default.Send, 
                    "Send", 
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            Modifier.padding(12.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning, 
                null, 
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message, 
                Modifier.weight(1f), 
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = onDismiss, Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Close, 
                    "Dismiss", 
                    Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionsPanel(
    runs: List<WorkflowRun>, 
    isLoading: Boolean,
    onRefresh: () -> Unit, 
    onSelectRun: (WorkflowRun) -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(), 
                Arrangement.SpaceBetween, 
                Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PlayCircle, 
                        null, 
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "GitHub Actions", 
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp), 
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            }
            if (runs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                runs.take(3).forEach { run ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        Arrangement.SpaceBetween, 
                        Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (run.conclusion) {
                                            "success" -> Color(0xFF22C55E)
                                            "failure" -> Color(0xFFEF4444)
                                            null -> Color(0xFFF59E0B)
                                            else -> Color(0xFF6B7280)
                                        }
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                run.name ?: "Workflow", 
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, 
                                overflow = TextOverflow.Ellipsis, 
                                modifier = Modifier.widthIn(max = 180.dp)
                            )
                        }
                        Text(
                            run.headBranch, 
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}