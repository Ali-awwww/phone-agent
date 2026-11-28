package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.files.FileCleanupBatch
import com.example.files.TrashItemRecord
import com.example.files.formatFileSize
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
import com.example.ui.theme.StatusCancelledContainer
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer

@Composable
fun FilePermissionExplanationDialog(
    onRequestAllFilesPermission: () -> Unit,
    onContinueScopedStorage: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberSurfaceNavy,
        shape = RoundedCornerShape(22.dp),
        icon = {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = CyberElectricCyan
            )
        },
        title = {
            Text(
                text = "صلاحية فحص ملفات الهاتف",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CyberElectricCyan
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "لفحص تخزين الهاتف بالكامل والعثور على المجلدات الفارغة والملفات المؤقتة والمكررة والكبيرة، يحتاج الوكيل إلى إذن «الوصول إلى كل الملفات» (MANAGE_EXTERNAL_STORAGE).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StatusPendingContainer,
                    border = BorderStroke(0.8.dp, StatusPending.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "ضمانات الأمان الصارمة:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = StatusPending
                        )
                        Text(
                            text = "• يُستبعد مجلد Android/ ومجلدات النظام تلقائياً.\n" +
                                "• لن يُحذف أي عنصر دون موافقتك الصريحة على كل دفعة.\n" +
                                "• المحذوفات تُنقل إلى سلة المهملات لمدة 7 أيام ويمكنك استعادتها.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextPrimary
                        )
                    }
                }
                Text(
                    text = "إذا رفضت الصلاحية، سيتابع الوكيل الفحص داخل المجلدات المتاحة بالتخزين المحدود (التنزيلات والمستندات والوسائط).",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onRequestAllFilesPermission,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("grant_all_files_permission_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberElectricBlue,
                    contentColor = CyberOnCyan
                )
            ) {
                Text("منح صلاحية كل الملفات", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onContinueScopedStorage,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("fallback_scoped_storage_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CyberBorderGlow)
            ) {
                Text("متابعة بالتخزين المحدود", color = CyberTextPrimary)
            }
        }
    )
}

@Composable
fun FileCleanupBatchApprovalDialog(
    batch: FileCleanupBatch,
    onConfirmBatch: () -> Unit,
    onCancelBatch: () -> Unit
) {
    val totalSizeLabel = if (batch.items.all { it.isDirectory }) {
        "0 B (مجلدات فارغة)"
    } else {
        formatFileSize(batch.totalBytes)
    }

    AlertDialog(
        onDismissRequest = onCancelBatch,
        containerColor = CyberSurfaceNavy,
        shape = RoundedCornerShape(22.dp),
        icon = {
            Icon(
                imageVector = Icons.Rounded.FolderDelete,
                contentDescription = null,
                tint = StatusPending
            )
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "موافقة على حذف ${batch.titleAr}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberElectricCyan
                )
                Text(
                    text = "عدد العناصر: ${batch.items.size} • الحجم الإجمالي: $totalSizeLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberSoftCyan
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StatusPendingContainer,
                    border = BorderStroke(0.8.dp, StatusPending.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = StatusPending,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "ستُنقل العناصر الموافَق عليها إلى سلة المهملات لمدة 7 أيام، مع التحقق من فراغ المجلدات لحظة الحذف.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextPrimary
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(batch.items, key = { it.path }) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CyberSurfaceElevated,
                            border = BorderStroke(0.75.dp, CyberBorderGlow.copy(alpha = 0.65f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CyberTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (item.isDirectory) "مجلد فارغ" else formatFileSize(item.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberElectricCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = item.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmBatch,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("confirm_file_batch_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusCancelled,
                    contentColor = Color.White
                )
            ) {
                Text("تأكيد حذف الدفعة", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancelBatch,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("cancel_file_batch_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CyberBorderGlow)
            ) {
                Text("إلغاء الدفعة", color = CyberTextPrimary)
            }
        }
    )
}

@Composable
fun FileTrashDialog(
    items: List<TrashItemRecord>,
    onRestoreItem: (String) -> Unit,
    onPermanentDeleteItem: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberSurfaceNavy,
        shape = RoundedCornerShape(22.dp),
        icon = {
            Icon(
                imageVector = Icons.Rounded.DeleteSweep,
                contentDescription = null,
                tint = CyberElectricCyan
            )
        },
        title = {
            Text(
                text = "سلة المهملات (تُحفظ 7 أيام)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CyberElectricCyan
            )
        },
        text = {
            if (items.isEmpty()) {
                Text(
                    text = "سلة المهملات فارغة حالياً. تُحفظ العناصر المحذوفة هنا لمدة 7 أيام ثم تُحذف نهائياً تلقائياً.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CyberBgDeep,
                            border = BorderStroke(0.8.dp, CyberBorderGlow.copy(alpha = 0.7f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CyberTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "متبقي ${item.remainingDays()} أيام",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusPending,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "${if (item.isDirectory) "مجلد فارغ" else formatFileSize(item.sizeBytes)} • ${item.originalPath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTextSecondary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { onRestoreItem(item.id) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("restore_trash_item_${item.id}"),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CyberElectricBlue,
                                            contentColor = CyberOnCyan
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.RestoreFromTrash,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("استعادة", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { onPermanentDeleteItem(item.id) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("delete_trash_item_${item.id}"),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, StatusCancelled),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = StatusCancelledContainer,
                                            contentColor = StatusCancelled
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteForever,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("حذف نهائي", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("close_trash_dialog_button")
            ) {
                Text("إغلاق", color = CyberElectricCyan, fontWeight = FontWeight.Bold)
            }
        }
    )
}
