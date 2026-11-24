package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.ActionLogEntity
import com.example.data.local.ActionStatus
import com.example.ui.components.CyberPanelCard
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusCancelledContainer
import com.example.ui.theme.StatusCyanInfoContainer
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer
import com.example.ui.viewmodel.ActionLogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActionLogScreen(
    viewModel: ActionLogViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.logs_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = CyberElectricCyan
                )
                if (state.logs.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearAllLogs() },
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = StatusCancelled)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.logs_clear), color = StatusCancelled)
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = state.selectedFilter == null,
                        onClick = { viewModel.setFilter(null) },
                        shape = MaterialTheme.shapes.small,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CyberSurfaceNavy,
                            labelColor = CyberTextPrimary,
                            selectedContainerColor = CyberElectricBlue,
                            selectedLabelColor = Color.White
                        ),
                        border = BorderStroke(1.dp, if (state.selectedFilter == null) CyberElectricCyan else CyberBorderGlow),
                        label = { Text("الكل") }
                    )
                }
                val primaryStatuses = listOf(
                    ActionStatus.SUCCESS,
                    ActionStatus.PENDING_APPROVAL,
                    ActionStatus.CANCELLED,
                    ActionStatus.FAILED
                )
                items(primaryStatuses) { status ->
                    val selected = state.selectedFilter == status
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setFilter(status) },
                        shape = MaterialTheme.shapes.small,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CyberSurfaceNavy,
                            labelColor = CyberTextPrimary,
                            selectedContainerColor = CyberElectricBlue,
                            selectedLabelColor = Color.White
                        ),
                        border = BorderStroke(1.dp, if (selected) CyberElectricCyan else CyberBorderGlow),
                        label = { Text(status.labelAr) }
                    )
                }
            }

            state.feedbackMessage?.let { msg ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = StatusCyanInfoContainer,
                    border = BorderStroke(1.dp, CyberElectricCyan)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium, color = CyberTextPrimary)
                        TextButton(onClick = { viewModel.clearFeedback() }) {
                            Text("إغلاق", color = CyberElectricCyan)
                        }
                    }
                }
            }

            if (state.logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = CyberElectricCyan.copy(alpha = 0.7f)
                        )
                        Text(
                            text = stringResource(R.string.logs_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = CyberTextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.logs, key = { it.id }) { log ->
                        ActionLogItemCard(
                            log = log,
                            onUndo = { viewModel.undoAction(log) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionLogItemCard(
    log: ActionLogEntity,
    onUndo: () -> Unit
) {
    val status = ActionStatus.fromName(log.status)
    val (bgColor, fgColor) = when (status) {
        ActionStatus.SUCCESS -> StatusSuccessContainer to StatusSuccess
        ActionStatus.PENDING_APPROVAL -> StatusPendingContainer to StatusPending
        ActionStatus.CANCELLED, ActionStatus.FAILED -> StatusCancelledContainer to StatusCancelled
        ActionStatus.UNDONE -> CyberSurfaceElevated to CyberTextSecondary
    }

    val formattedTime = remember(log.timestamp) {
        SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.forLanguageTag("ar"))
            .format(Date(log.timestamp))
    }

    CyberPanelCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = log.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = bgColor,
                    border = BorderStroke(1.dp, fgColor.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = status.labelAr,
                        style = MaterialTheme.typography.labelMedium,
                        color = fgColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (log.details.isNotBlank()) {
                Text(
                    text = log.details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextSecondary
                )

                if (log.canUndo && !log.isUndone) {
                    OutlinedButton(
                        onClick = onUndo,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, CyberElectricCyan),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("undo_action_${log.id}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Undo,
                            contentDescription = null,
                            tint = CyberElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.logs_undo), color = CyberElectricCyan)
                    }
                }
            }
        }
    }
}
