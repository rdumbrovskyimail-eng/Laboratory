package com.opuside.app.navigation

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════════════════════════
// DATA
// ═══════════════════════════════════════════════════════════════════════════════

data class QuickNavButton(
    val id: Int,
    val name: String,
    val path: String
)

/** 5 постоянных кнопок — редактируемые, но всегда присутствуют */
val DEFAULT_QUICK_NAV_BUTTONS = listOf(
    QuickNavButton(0, "Navigation",  "app/src/main/java/com/opuside/app/navigation"),
    QuickNavButton(1, "Creator",     "app/src/main/java/com/opuside/app/feature/creator"),
    QuickNavButton(2, "Analyzer",    "app/src/main/java/com/opuside/app/feature/analyzer"),
    QuickNavButton(3, "Scratch",     "app/src/main/java/com/opuside/app/feature/scratch"),
    QuickNavButton(4, "Core UI",     "app/src/main/java/com/opuside/app/core/ui")
)

// ═══════════════════════════════════════════════════════════════════════════════
// PERSISTENCE  (SharedPreferences)
// ═══════════════════════════════════════════════════════════════════════════════

private const val PREFS_NAME = "quick_nav_prefs"
private const val PREFS_KEY  = "quick_nav_v1"

fun loadQuickNavButtons(context: Context): List<QuickNavButton> {
    val raw = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREFS_KEY, null)
        ?: return DEFAULT_QUICK_NAV_BUTTONS
    return try {
        raw.split(";;").mapIndexed { index, entry ->
            val sep = entry.indexOf("||")
            QuickNavButton(index, entry.substring(0, sep), entry.substring(sep + 2))
        }.takeIf { it.size == 5 } ?: DEFAULT_QUICK_NAV_BUTTONS
    } catch (_: Exception) {
        DEFAULT_QUICK_NAV_BUTTONS
    }
}

fun saveQuickNavButtons(context: Context, buttons: List<QuickNavButton>) {
    val raw = buttons.joinToString(";;") { "${it.name}||${it.path}" }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(PREFS_KEY, raw).apply()
}

// ═══════════════════════════════════════════════════════════════════════════════
// OVERLAY
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickNavOverlay(
    buttons: List<QuickNavButton>,
    onDismiss: () -> Unit,
    onButtonUpdate: (QuickNavButton) -> Unit,
    onButtonClick: (QuickNavButton) -> Unit  // → вызывает navigateToFolder(button.path)
) {
    var editingButton by remember { mutableStateOf<QuickNavButton?>(null) }

    // Полупрозрачный скрим — тап вне карточки закрывает
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            )
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 84.dp)
                .combinedClickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {}
                ),
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(min = 280.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "⚡ Quick Nav  •  hold to edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )

                buttons.forEach { button ->
                    QuickNavButtonRow(
                        button = button,
                        onClick = {
                            onButtonClick(button)   // navigateToFolder + переключение вкладки
                            onDismiss()
                        },
                        onLongClick = { editingButton = button }
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }

    editingButton?.let { btn ->
        QuickNavEditDialog(
            button = btn,
            onSave = { updated ->
                onButtonUpdate(updated)
                editingButton = null
            },
            onDismiss = { editingButton = null }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BUTTON ROW
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickNavButtonRow(
    button: QuickNavButton,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = button.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = button.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EDIT DIALOG
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun QuickNavEditDialog(
    button: QuickNavButton,
    onSave: (QuickNavButton) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(button.id) { mutableStateOf(button.name) }
    var path by remember(button.id) { mutableStateOf(button.path) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Quick Nav") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Button name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Path in repository") },
                    placeholder = { Text("app/src/main/java/com/opuside/app/…") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(button.copy(name = name.trim(), path = path.trim())) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
