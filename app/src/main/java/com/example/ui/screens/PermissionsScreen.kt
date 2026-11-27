package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.permissions.AgentPermissionItem
import com.example.permissions.AgentPermissionType
import com.example.permissions.PermissionHelper
import com.example.ui.components.CyberPanelCard
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberOnCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.StatusCyanInfoContainer
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer
import com.example.ui.viewmodel.PermissionsViewModel

@Composable
fun PermissionsScreen(
    viewModel: PermissionsViewModel,
    modifier: Modifier = Modifier
) {
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingRuntimeType by remember { mutableStateOf<AgentPermissionType?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && pendingRuntimeType != null) {
            // إذا تم رفض الإذن نهائياً نفتح إعدادات التطبيق مباشرة ليتمكن المستخدم من تفعيله
            PermissionHelper.openPermissionSettings(context, pendingRuntimeType!!)
        }
        pendingRuntimeType = null
        viewModel.refresh()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.permissions_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = CyberElectricCyan
                        )
                        Text(
                            text = stringResource(R.string.permissions_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary
                        )
                    }

                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.testTag("refresh_permissions_button")
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "تحديث حالة الأذونات",
                            tint = CyberElectricCyan
                        )
                    }
                }
            }

            item {
                CyberPanelCard(
                    containerColor = CyberSurfaceNavy,
                    borderColor = CyberElectricCyan.copy(alpha = 0.45f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Apps,
                                contentDescription = null,
                                tint = CyberElectricCyan,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.our_apps_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTextPrimary
                                )
                                Text(
                                    text = stringResource(R.string.our_apps_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyberTextSecondary
                                )
                            }
                        }

                        Button(
                            onClick = {
                                PermissionHelper.openExternalBrowser(
                                    context,
                                    PermissionHelper.OUR_APPS_SHOWCASE_URL
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("our_apps_button"),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = com.example.ui.theme.CyberElectricBlue,
                                contentColor = CyberOnCyan
                            )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.our_apps_button),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                CyberPanelCard(
                    containerColor = StatusCyanInfoContainer,
                    borderColor = CyberElectricCyan.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = CyberElectricCyan,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = "خصوصيتك أولاً: تحفظ سجلاتك وإعدادات المجلدات محلياً على جهازك، ولا يتم تنفيذ أي إجراء حساس دون موافقتك.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextPrimary
                        )
                    }
                }
            }

            items(permissions, key = { it.type.name }) { item ->
                PermissionItemCard(
                    item = item,
                    onEnableClick = {
                        when {
                            item.type == AgentPermissionType.MICROPHONE && !item.isGranted -> {
                                pendingRuntimeType = AgentPermissionType.MICROPHONE
                                runtimePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            item.type == AgentPermissionType.NOTIFICATIONS &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !item.isGranted -> {
                                pendingRuntimeType = AgentPermissionType.NOTIFICATIONS
                                runtimePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            else -> {
                                PermissionHelper.openPermissionSettings(context, item.type)
                            }
                        }
                    }
                )
            }

            item {
                CyberPanelCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "كيف تفعّل خدمة إمكانية الوصول خطوة بخطوة؟",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberElectricCyan
                        )
                        Text(
                            text = "1. اضغط على «تفعيل الآن» بجانب خدمة إمكانية الوصول.\n" +
                                "2. اختر «التطبيقات المثبتة» أو ابحث عن «وكيل الهاتف - المساعد الذكي».\n" +
                                "3. فعّل المفتاح ثم اضغط «سماح» للتأكيد.\n" +
                                "4. عد للتطبيق وستتغير الحالة تلقائياً إلى «مفعّل».",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItemCard(
    item: AgentPermissionItem,
    onEnableClick: () -> Unit
) {
    val icon = when (item.type) {
        AgentPermissionType.ACCESSIBILITY -> Icons.Rounded.AccessibilityNew
        AgentPermissionType.OVERLAY -> Icons.Rounded.Layers
        AgentPermissionType.QUERY_ALL_PACKAGES -> Icons.Rounded.Apps
        AgentPermissionType.USAGE_ACCESS -> Icons.Rounded.BarChart
        AgentPermissionType.MICROPHONE -> Icons.Rounded.Mic
        AgentPermissionType.NOTIFICATIONS -> Icons.Rounded.NotificationsActive
        AgentPermissionType.EXACT_ALARMS -> Icons.Rounded.Alarm
    }

    CyberPanelCard(
        borderColor = if (item.isGranted) StatusSuccess.copy(alpha = 0.6f) else CyberBorderGlow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = CyberElectricCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = item.titleAr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextPrimary
                    )
                }

                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (item.isGranted) StatusSuccessContainer else StatusPendingContainer,
                    border = BorderStroke(
                        0.8.dp,
                        if (item.isGranted) StatusSuccess.copy(alpha = 0.7f) else StatusPending.copy(alpha = 0.7f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (item.isGranted) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = StatusSuccess,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = if (item.isGranted) {
                                stringResource(R.string.permission_enabled)
                            } else {
                                stringResource(R.string.permission_disabled)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (item.isGranted) StatusSuccess else StatusPending,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Text(
                text = item.explanationAr,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary
            )

            if (!item.isGranted) {
                Button(
                    onClick = onEnableClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("enable_perm_${item.type.name}"),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = com.example.ui.theme.CyberElectricBlue,
                        contentColor = CyberOnCyan
                    )
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.permission_enable_button), fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onEnableClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_perm_${item.type.name}"),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Text(stringResource(R.string.permission_settings_button), color = CyberElectricCyan)
                }
            }
        }
    }
}
