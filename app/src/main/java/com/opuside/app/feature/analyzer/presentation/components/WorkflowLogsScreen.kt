package com.opuside.app.feature.analyzer.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opuside.app.core.network.github.model.WorkflowJob
import com.opuside.app.core.network.github.model.WorkflowRun
import com.opuside.app.core.network.github.model.WorkflowStep

/**
 * Экран детальной информации о workflow run.
 */
@Composable
fun WorkflowDetailsScreen(
    run: WorkflowRun,
    jobs: List<WorkflowJob>,
    logs: String?,
    isLoadingLogs: Boolean,
    onBack: () -> Unit,
    onLoadLogs: (Long) -> Unit,
    onRerun: () -> Unit,
    onCancel: () -> Unit,
    onDownloadArtifacts: () -> Unit
) {
    var selectedJobId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // ═══════════════════════════════════════════════════════════════════════
        // TOP BAR
        // ═══════════════════════════════════════════════════════════════════════

        Surface(tonalElevation = 2.dp) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = run.name ?: "Workflow Run",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "#${run.id} • ${run.headBranch}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Status badge
                    WorkflowStatusBadge(
                        status = run.status,
                        conclusion = run.conclusion
                    )
                }

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (run.status == "completed") {
                        OutlinedButton(
                            onClick = onRerun,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Re-run")
                        }
                    } else if (run.status == "in_progress" || run.status == "queued") {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Cancel, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }

                    if (run.conclusion == "success") {
                        Button(
                            onClick = onDownloadArtifacts,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Artifacts")
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // JOBS LIST
        // ═══════════════════════════════════════════════════════════════════════

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(jobs) { job ->
                JobCard(
                    job = job,
                    isSelected = selectedJobId == job.id,
                    onClick = {
                        selectedJobId = if (selectedJobId == job.id) null else job.id
                        if (selectedJobId != null) {
                            onLoadLogs(job.id)
                        }
                    }
                )
            }

            // Logs section
            if (selectedJobId != null) {
                item {
                    LogsSection(
                        logs = logs,
                        isLoading = isLoadingLogs
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowStatusBadge(status: String, conclusion: String?) {
    val (color, icon, text) = when {
        status == "queued" -> Triple(Color(0xFF6B7280), Icons.Default.Schedule, "Queued")
        status == "in_progress" -> Triple(Color(0xFFF59E0B), Icons.Default.Sync, "Running")
        conclusion == "success" -> Triple(Color(0xFF22C55E), Icons.Default.CheckCircle, "Success")
        conclusion == "failure" -> Triple(Color(0xFFEF4444), Icons.Default.Cancel, "Failed")
        conclusion == "cancelled" -> Triple(Color(0xFF6B7280), Icons.Default.Block, "Cancelled")
        else -> Triple(Color(0xFF6B7280), Icons.Default.HelpOutline, conclusion ?: status)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
private fun JobCard(
    job: WorkflowJob,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    JobStatusIcon(status = job.status, conclusion = job.conclusion)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = job.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Icon(
                    imageVector = if (isSelected) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Steps (если развёрнуто)
            if (isSelected && job.steps != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                job.steps.forEach { step ->
                    StepRow(step = step)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun JobStatusIcon(status: String, conclusion: String?) {
    val (color, icon) = when {
        status == "queued" -> Color(0xFF6B7280) to Icons.Default.Schedule
        status == "in_progress" -> Color(0xFFF59E0B) to Icons.Default.Sync
        conclusion == "success" -> Color(0xFF22C55E) to Icons.Default.CheckCircle
        conclusion == "failure" -> Color(0xFFEF4444) to Icons.Default.Cancel
        conclusion == "skipped" -> Color(0xFF6B7280) to Icons.Default.RemoveCircleOutline
        else -> Color(0xFF6B7280) to Icons.Default.HelpOutline
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
    }
}

@Composable
private fun StepRow(step: WorkflowStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = when (step.conclusion) {
            "success" -> Color(0xFF22C55E)
            "failure" -> Color(0xFFEF4444)
            "skipped" -> Color(0xFF6B7280)
            null -> Color(0xFFF59E0B)
            else -> Color(0xFF6B7280)
        }

        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )

        Spacer(Modifier.width(8.dp))

        Text(
            text = "${step.number}. ${step.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LogsSection(
    logs: String?,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Logs",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (logs != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = logs,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = Color(0xFFD4D4D4)
                    )
                }
            } else if (!isLoading) {
                Text(
                    text = "Tap to load logs",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280)
                )
            }
        }
    }
}
