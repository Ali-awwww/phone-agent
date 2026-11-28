package com.example.files

import java.util.Locale

enum class FileScanCategory(val titleAr: String, val badgeAr: String) {
    EMPTY_FOLDER("مجلدات فارغة", "مجلد فارغ"),
    LARGE("ملفات كبيرة", "حجم كبير"),
    DUPLICATE("ملفات مكررة", "مكرر"),
    TEMPORARY("ملفات مؤقتة", "مؤقت"),
    CACHE("ذاكرة تخزين مؤقت", "كاش")
}

enum class StorageAccessMode(val titleAr: String) {
    ALL_FILES("الوصول الكامل لجميع الملفات (MANAGE_EXTERNAL_STORAGE)"),
    SCOPED_ONLY("التخزين المحدود (المجلدات المتاحة بدون صلاحية كل الملفات)")
}

data class ScannedFileItem(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val category: FileScanCategory,
    val isDirectory: Boolean,
    val lastModifiedMillis: Long,
    val duplicateGroupKey: String? = null
)

data class FileScanReport(
    val accessMode: StorageAccessMode,
    val scannedRoots: List<String>,
    val items: List<ScannedFileItem>,
    val excludedSystemCount: Int,
    val scannedAtMillis: Long = System.currentTimeMillis()
) {
    val emptyFoldersCount: Int = items.count { it.category == FileScanCategory.EMPTY_FOLDER }
    val largeFilesCount: Int = items.count { it.category == FileScanCategory.LARGE }
    val duplicateFilesCount: Int = items.count { it.category == FileScanCategory.DUPLICATE }
    val temporaryFilesCount: Int = items.count { it.category == FileScanCategory.TEMPORARY }
    val cacheItemsCount: Int = items.count { it.category == FileScanCategory.CACHE }
    val totalReclaimableBytes: Long = items.sumOf { it.sizeBytes }

    fun itemsByCategory(category: FileScanCategory): List<ScannedFileItem> =
        items.filter { it.category == category }

    fun toArabicSummaryText(maxPreviewPerCategory: Int = 8): String = buildString {
        appendLine("تقرير فحص ملفات الهاتف (${accessMode.titleAr}):")
        appendLine("• المجلدات الفارغة: $emptyFoldersCount")
        appendLine("• الملفات الكبيرة: $largeFilesCount (${formatFileSize(itemsByCategory(FileScanCategory.LARGE).sumOf { it.sizeBytes })})")
        appendLine("• الملفات المكررة: $duplicateFilesCount (${formatFileSize(itemsByCategory(FileScanCategory.DUPLICATE).sumOf { it.sizeBytes })})")
        appendLine("• الملفات المؤقتة: $temporaryFilesCount (${formatFileSize(itemsByCategory(FileScanCategory.TEMPORARY).sumOf { it.sizeBytes })})")
        appendLine("• ذاكرة التخزين المؤقت: $cacheItemsCount (${formatFileSize(itemsByCategory(FileScanCategory.CACHE).sumOf { it.sizeBytes })})")
        appendLine("• المساحة القابلة للاسترداد إجمالاً: ${formatFileSize(totalReclaimableBytes)}")
        appendLine("• مسارات النظام وAndroid المستبعدة: $excludedSystemCount")

        if (items.isEmpty()) {
            appendLine("لم يُعثر على مجلدات فارغة أو ملفات زائدة ضمن النطاق المفحوص.")
        } else {
            FileScanCategory.entries.forEach { category ->
                val catItems = itemsByCategory(category)
                if (catItems.isNotEmpty()) {
                    appendLine()
                    appendLine("— ${category.titleAr} (${catItems.size}):")
                    catItems.take(maxPreviewPerCategory).forEach { item ->
                        val sizeText = if (item.isDirectory) "مجلد فارغ (0 B)" else formatFileSize(item.sizeBytes)
                        appendLine("  • ${item.name} [$sizeText]\n    ${item.path}")
                    }
                    if (catItems.size > maxPreviewPerCategory) {
                        appendLine("  ... و${catItems.size - maxPreviewPerCategory} عنصر إضافي")
                    }
                }
            }
            appendLine()
            append("لن يُحذف أي شيء تلقائياً؛ ستظهر لك الآن دفعات للموافقة الصريحة قبل النقل إلى سلة المهملات (تُحفظ 7 أيام).")
        }
    }.trim()
}

data class FileCleanupBatch(
    val batchIndex: Int,
    val totalBatches: Int,
    val category: FileScanCategory,
    val titleAr: String,
    val items: List<ScannedFileItem>
) {
    val totalBytes: Long = items.sumOf { it.sizeBytes }
}

data class TrashItemRecord(
    val id: String,
    val originalPath: String,
    val trashPath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val category: FileScanCategory,
    val trashedAtMillis: Long,
    val expiresAtMillis: Long
) {
    fun remainingDays(nowMillis: Long = System.currentTimeMillis()): Int {
        val diff = (expiresAtMillis - nowMillis).coerceAtLeast(0L)
        return ((diff + ONE_DAY_MILLIS - 1L) / ONE_DAY_MILLIS).toInt()
    }

    companion object {
        const val ONE_DAY_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}

data class BatchDeletionOutcome(
    val movedToTrashCount: Int,
    val skippedNonEmptyCount: Int,
    val skippedExcludedOrMissingCount: Int,
    val failedCount: Int,
    val freedBytes: Long,
    val detailsAr: List<String>
)

data class TrashPurgeOutcome(
    val purgedCount: Int,
    val freedBytes: Long
)

data class TrashRestoreOutcome(
    val restored: Boolean,
    val messageAr: String
)

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
