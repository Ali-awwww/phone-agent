package com.example.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.permissions.AgentPermissionType
import com.example.permissions.PermissionHelper
import com.example.model.AppFolder
import com.example.organizer.InstalledAppInfo
import com.example.ui.components.CyberPanelCard
import com.example.ui.components.MarkdownText
import com.example.ui.components.PlayFeatureInfoDialog
import com.example.ui.components.PlayWithAgentPromoCard
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberOnCyan
import com.example.ui.theme.CyberSoftCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusCyanInfoContainer
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer
import com.example.ui.viewmodel.AppsViewModel
import com.example.ui.viewmodel.SummarizeBlockedReason

@Composable
fun AppsScreen(
    viewModel: AppsViewModel,
    onStartGameplayFromApps: () -> Unit = {},
    onOpenPermissionsTab: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var moveDialogApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var showPlayFeatureDialog by remember { mutableStateOf(false) }

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
                Column {
                    Text(
                        text = stringResource(R.string.apps_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = CyberElectricCyan
                    )
                    Text(
                        text = "${state.filteredApps.size} تطبيق",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary
                    )
                }

                FilterChip(
                    selected = state.showHiddenApps,
                    onClick = { viewModel.toggleShowHidden() },
                    modifier = Modifier.testTag("toggle_hidden_apps"),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CyberSurfaceNavy,
                        labelColor = CyberTextPrimary,
                        selectedContainerColor = CyberElectricBlue,
                        selectedLabelColor = Color.White
                    ),
                    border = BorderStroke(0.8.dp, if (state.showHiddenApps) CyberElectricCyan else CyberBorderGlow),
                    leadingIcon = {
                        Icon(
                            imageVector = if (state.showHiddenApps) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = {
                        Text(
                            if (state.showHiddenApps) stringResource(R.string.apps_hide_hidden)
                            else stringResource(R.string.apps_show_hidden)
                        )
                    }
                )
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("apps_search_input"),
                placeholder = { Text(stringResource(R.string.apps_search_hint), color = CyberTextSecondary) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = CyberElectricCyan) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberElectricCyan,
                    unfocusedBorderColor = CyberBorderGlow.copy(alpha = 0.7f),
                    focusedContainerColor = CyberSurfaceNavy,
                    unfocusedContainerColor = CyberSurfaceNavy,
                    focusedTextColor = CyberTextPrimary,
                    unfocusedTextColor = CyberTextPrimary
                ),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )

            Button(
                onClick = { viewModel.autoCategorizeWithGemini() },
                enabled = !state.isAutoCategorizing && state.allApps.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("auto_categorize_button"),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberElectricBlue,
                    contentColor = CyberOnCyan
                )
            ) {
                if (state.isAutoCategorizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = CyberOnCyan,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("جاري تصنيف التطبيقات بالذكاء الاصطناعي...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.apps_auto_categorize), fontWeight = FontWeight.Bold)
                }
            }

            state.statusMessage?.let { msg ->
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = StatusCyanInfoContainer,
                    border = BorderStroke(0.8.dp, CyberElectricCyan.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearStatusMessage() }) {
                            Text("حسناً", color = CyberElectricCyan)
                        }
                    }
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.selectedFolder == null,
                        onClick = { viewModel.selectFolder(null) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CyberSurfaceNavy,
                            labelColor = CyberTextPrimary,
                            selectedContainerColor = CyberElectricBlue,
                            selectedLabelColor = Color.White
                        ),
                        border = BorderStroke(0.8.dp, if (state.selectedFolder == null) CyberElectricCyan else CyberBorderGlow),
                        leadingIcon = {
                            Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = { Text("الكل") }
                    )
                }
                items(AppFolder.entries) { folder ->
                    val count = state.folderCounts[folder] ?: 0
                    val selected = state.selectedFolder == folder
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.selectFolder(folder) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CyberSurfaceNavy,
                            labelColor = CyberTextPrimary,
                            selectedContainerColor = CyberElectricBlue,
                            selectedLabelColor = Color.White
                        ),
                        border = BorderStroke(0.8.dp, if (selected) CyberElectricCyan else CyberBorderGlow),
                        leadingIcon = {
                            Icon(folder.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = { Text("${folder.titleAr} ($count)") }
                    )
                }
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CyberElectricCyan)
                }
            } else if (state.filteredApps.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PlayWithAgentPromoCard(
                        onTakeOverPlayClick = onStartGameplayFromApps,
                        onExplainFeatureClick = { showPlayFeatureDialog = true }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد تطبيقات مطابقة لبحثك في هذا المجلد.",
                            style = MaterialTheme.typography.bodyLarge,
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
                    item(key = "apps_play_feature_spotlight") {
                        PlayWithAgentPromoCard(
                            onTakeOverPlayClick = onStartGameplayFromApps,
                            onExplainFeatureClick = { showPlayFeatureDialog = true }
                        )
                    }
                    items(state.filteredApps, key = { it.packageName }) { app ->
                        AppItemCard(
                            app = app,
                            onOpen = { viewModel.openApp(context, app) },
                            onTryAndSummarize = {
                                if (!PermissionHelper.isAccessibilityEnabled(context)) {
                                    viewModel.setSummarizeBlockedReason(SummarizeBlockedReason.ACCESSIBILITY)
                                } else if (!PermissionHelper.isOverlayPermissionGranted(context)) {
                                    viewModel.setSummarizeBlockedReason(SummarizeBlockedReason.OVERLAY)
                                } else {
                                    viewModel.tryAndSummarizeApp(app)
                                }
                            },
                            onMoveClick = { moveDialogApp = app },
                            onToggleHide = { viewModel.toggleHideApp(app) }
                        )
                    }
                }
            }
        }
    }

    moveDialogApp?.let { app ->
        AlertDialog(
            onDismissRequest = { moveDialogApp = null },
            containerColor = CyberSurfaceNavy,
            shape = MaterialTheme.shapes.large,
            title = { Text("نقل «${app.label}» إلى مجلد", color = CyberElectricCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFolder.entries.forEach { folder ->
                        FilledTonalButton(
                            onClick = {
                                viewModel.moveAppToFolder(app, folder)
                                moveDialogApp = null
                            },
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = CyberSurfaceElevated,
                                contentColor = CyberTextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(folder.icon, contentDescription = null, tint = CyberElectricCyan)
                            Spacer(Modifier.width(8.dp))
                            Text(folder.titleAr)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moveDialogApp = null }) {
                    Text("إغلاق", color = CyberElectricCyan)
                }
            }
        )
    }

    state.summarizeBlockedReason?.let { blockedReason ->
        val title = when (blockedReason) {
            SummarizeBlockedReason.ACCESSIBILITY -> "إذن إمكانية الوصول مطلوب"
            SummarizeBlockedReason.OVERLAY -> "إذن العرض فوق التطبيقات مطلوب"
        }
        val message = when (blockedReason) {
            SummarizeBlockedReason.ACCESSIBILITY -> "فعّل إمكانية الوصول أولاً حتى يستطيع الوكيل قراءة شاشة التطبيق."
            SummarizeBlockedReason.OVERLAY -> "اسمح بالعرض فوق التطبيقات الأخرى حتى تظهر أزرار التحكم العائمة (إيقاف/استئناف/أكمل بنفسي) فوق التطبيق."
        }
        val buttonText = when (blockedReason) {
            SummarizeBlockedReason.ACCESSIBILITY -> "فتح إعدادات إمكانية الوصول"
            SummarizeBlockedReason.OVERLAY -> "فتح إعدادات العرض فوق التطبيقات"
        }
        AlertDialog(
            onDismissRequest = { viewModel.setSummarizeBlockedReason(null) },
            containerColor = CyberSurfaceNavy,
            shape = MaterialTheme.shapes.large,
            title = { Text(title, color = CyberElectricCyan) },
            text = { Text(message, color = CyberTextPrimary) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setSummarizeBlockedReason(null)
                        val type = when (blockedReason) {
                            SummarizeBlockedReason.ACCESSIBILITY -> AgentPermissionType.ACCESSIBILITY
                            SummarizeBlockedReason.OVERLAY -> AgentPermissionType.OVERLAY
                        }
                        PermissionHelper.openPermissionSettings(context, type)
                    },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberElectricCyan, contentColor = CyberOnCyan)
                ) {
                    Text(buttonText, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setSummarizeBlockedReason(null) }) {
                    Text("إلغاء", color = CyberElectricCyan)
                }
            }
        )
    }

    if (state.summarizingAppName != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelSummarizing() },
            containerColor = CyberSurfaceNavy,
            shape = MaterialTheme.shapes.large,
            title = { Text("جاري استكشاف وتلخيص «${state.summarizingAppName}»", color = CyberElectricCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = CyberElectricCyan)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = state.summarizingStep ?: "يفتح الوكيل التطبيق ويقرأ شاشته عبر Gemini لتلخيص محتواه بأمان...",
                                color = CyberTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "الزمن المنقضي: ${state.summarizingElapsedSeconds} ثانية",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberTextSecondary
                            )
                        }
                    }
                    Text(
                        text = "قد يستغرق التشغيل الكامل نحو دقيقتين، يمكنك الإلغاء في أي وقت.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberSoftCyan
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.cancelSummarizing() },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCancelled, contentColor = Color.White)
                ) {
                    Text("إلغاء", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    state.appSummaryResult?.let { (appName, summary) ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSummaryDialog() },
            containerColor = CyberSurfaceNavy,
            shape = MaterialTheme.shapes.large,
            icon = { Icon(Icons.Rounded.Summarize, contentDescription = null, tint = CyberElectricCyan) },
            title = { Text("ملخص تطبيق «$appName»", color = CyberElectricCyan) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    MarkdownText(text = summary, color = CyberTextPrimary)
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissSummaryDialog() },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberElectricCyan,
                        contentColor = CyberOnCyan
                    )
                ) {
                    Text("تم", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showPlayFeatureDialog) {
        PlayFeatureInfoDialog(
            onDismiss = { showPlayFeatureDialog = false },
            onStartGameplay = {
                showPlayFeatureDialog = false
                onStartGameplayFromApps()
            },
            onOpenPermissions = {
                showPlayFeatureDialog = false
                onOpenPermissionsTab()
            }
        )
    }
}

@Composable
private fun AppItemCard(
    app: InstalledAppInfo,
    onOpen: () -> Unit,
    onTryAndSummarize: () -> Unit,
    onMoveClick: () -> Unit,
    onToggleHide: () -> Unit
) {
    CyberPanelCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppDrawableIcon(icon = app.icon, label = app.label)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = StatusCyanInfoContainer,
                            border = BorderStroke(1.dp, CyberElectricCyan.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = app.folder.titleAr,
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberElectricCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (app.isUnused) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = StatusPendingContainer,
                                border = BorderStroke(1.dp, StatusPending.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "غير مستخدم",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StatusPending,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (app.isHidden) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = CyberSurfaceElevated,
                                border = BorderStroke(1.dp, CyberBorderGlow)
                            ) {
                                Text(
                                    text = "مخفي",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CyberTextSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (!app.lastSummary.isNullOrBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = CyberBgDeep,
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Text(
                        text = app.lastSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpen,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberElectricBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.apps_open), maxLines = 1)
                }

                FilledTonalButton(
                    onClick = onTryAndSummarize,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CyberSurfaceElevated,
                        contentColor = CyberElectricCyan
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Summarize, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.apps_try_and_summarize), maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onMoveClick,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, CyberBorderGlow),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp), tint = CyberTextPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.apps_move_folder), maxLines = 1, color = CyberTextPrimary)
                }

                OutlinedButton(
                    onClick = onToggleHide,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, CyberBorderGlow),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = if (app.isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = CyberTextPrimary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (app.isHidden) stringResource(R.string.apps_unhide) else stringResource(R.string.apps_hide),
                        maxLines = 1,
                        color = CyberTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun AppDrawableIcon(icon: Drawable?, label: String) {
    val bitmap = remember(icon) {
        runCatching { icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }
    val squircle = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "أيقونة $label",
            modifier = Modifier
                .size(50.dp)
                .clip(squircle)
                .border(0.75.dp, CyberBorderGlow, squircle)
        )
    } else {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(squircle)
                .background(CyberSurfaceElevated)
                .border(0.75.dp, CyberBorderGlow, squircle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Apps,
                contentDescription = "أيقونة $label",
                tint = CyberElectricCyan
            )
        }
    }
}
