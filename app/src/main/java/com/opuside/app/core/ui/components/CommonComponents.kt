package com.opuside.app.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════════════════
// LOADING STATES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Полноэкранный индикатор загрузки.
 */
@Composable
fun FullScreenLoading(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Overlay загрузки поверх контента.
 */
@Composable
fun LoadingOverlay(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
        
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 8.dp
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Индикатор загрузки в строке.
 */
@Composable
fun InlineLoading(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ERROR STATES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Полноэкранное сообщение об ошибке.
 */
@Composable
fun FullScreenError(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Error
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            
            onRetry?.let {
                Spacer(Modifier.height(8.dp))
                Button(onClick = it) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Try Again")
                }
            }
        }
    }
}

/**
 * Карточка с ошибкой (inline).
 */
@Composable
fun ErrorCard(
    message: String,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(Modifier.width(12.dp))
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            
            onRetry?.let {
                IconButton(onClick = it) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            onDismiss?.let {
                IconButton(onClick = it) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/**
 * Snackbar для ошибок.
 */
@Composable
fun ErrorSnackbar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Snackbar(
        modifier = modifier,
        action = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        },
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(message)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EMPTY STATES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Состояние пустого списка.
 */
@Composable
fun EmptyState(
    title: String,
    description: String? = null,
    icon: ImageVector = Icons.Default.Inbox,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            
            action?.let {
                Spacer(Modifier.height(8.dp))
                it()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NETWORK STATUS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Баннер отсутствия сети.
 */
@Composable
fun OfflineBanner(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "No internet connection",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONFIRMATION DIALOGS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Диалог подтверждения действия.
 */
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = if (isDestructive) {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.textButtonColors()
                }
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}
