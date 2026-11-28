package com.example.files

import android.content.Context
import android.os.Environment
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.permissions.PermissionHelper
import com.example.policy.AgentPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PhoneFileManager(
    private val context: Context,
    private val actionLogRepository: ActionLogRepository,
    private val agentPolicy: AgentPolicy,
    private val scanner: PhoneFileScanner = PhoneFileScanner(),
    private val trashRootDir: File = File(context.applicationContext.filesDir, "wakeel_trash"),
    private val trashManager: FileTrashManager = FileTrashManager(trashRootDir, context.packageName)
) {

    fun hasAllFilesPermission(): Boolean = PermissionHelper.isAllFilesAccessGranted(context)

    fun isTrashQuery(query: String): Boolean {
        val lower = query.lowercase()
        return lower.contains("سلة المهملات") ||
            lower.contains("السلة") ||
            lower.contains("استعادة") ||
            lower.contains("استرجاع") ||
            lower.contains("trash") ||
            lower.contains("restore")
    }

    fun detectRequestedFocus(query: String): Pair<FileScanCategory?, Boolean> {
        val lower = query.lowercase()
        val hasEmpty = lower.contains("فارغ") || lower.contains("empty")
        val hasTemp = lower.contains("مؤقت") || lower.contains("temp")
        val hasCache = lower.contains("كاش") || lower.contains("تخزين مؤقت") || lower.contains("cache")
        val hasDup = lower.contains("مكرر") || lower.contains("duplicate")
        val hasLarge = lower.contains("كبير") || lower.contains("large")
        val asksFullScan = lower.contains("فحص الهاتف") ||
            lower.contains("فحص الملفات") ||
            lower.contains("فحص التخزين") ||
            lower.contains("مسح الهاتف") ||
            lower.contains("تنظيف الهاتف") ||
            lower.contains("تنظيف التخزين") ||
            lower.contains("scan")

        return when {
            hasEmpty -> FileScanCategory.EMPTY_FOLDER to !asksFullScan
            hasTemp -> FileScanCategory.TEMPORARY to !asksFullScan
            hasCache -> FileScanCategory.CACHE to !asksFullScan
            hasDup -> FileScanCategory.DUPLICATE to !asksFullScan
            hasLarge -> FileScanCategory.LARGE to !asksFullScan
            else -> FileScanCategory.EMPTY_FOLDER to false
        }
    }

    suspend fun scanDeviceFiles(useScopedFallback: Boolean = false): FileScanReport {
        val report = withContext(Dispatchers.IO) {
            trashManager.purgeExpired()
            val canUseAllFiles = !useScopedFallback && hasAllFilesPermission()
            val accessMode = if (canUseAllFiles) {
                StorageAccessMode.ALL_FILES
            } else {
                StorageAccessMode.SCOPED_ONLY
            }
            val roots = resolveStorageRoots(accessMode)
            scanner.scan(
                roots = roots,
                accessMode = accessMode,
                appPackageName = context.packageName,
                trashRootPath = trashRootDir.absolutePath
            )
        }

        actionLogRepository.logAction(
            title = "فحص ملفات الهاتف (${report.accessMode.name})",
            details = "عناصر مرصودة: ${report.items.size} | مجلدات فارغة: ${report.emptyFoldersCount} | مستبعد من النظام: ${report.excludedSystemCount}",
            status = ActionStatus.SUCCESS,
            actionType = "FILE_SCAN"
        )
        return report
    }

    fun buildApprovalBatches(
        report: FileScanReport,
        focusCategory: FileScanCategory? = null,
        focusOnly: Boolean = false,
        batchSize: Int = 15
    ): List<FileCleanupBatch> {
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val categoriesOrder = buildList {
            if (focusCategory != null) add(focusCategory)
            if (!focusOnly) {
                FileScanCategory.entries.forEach { cat ->
                    if (cat != focusCategory) add(cat)
                }
            }
        }

        val rawGroups = mutableListOf<Pair<FileScanCategory, List<ScannedFileItem>>>()
        for (category in categoriesOrder) {
            val catItems = report.itemsByCategory(category)
            if (catItems.isNotEmpty()) {
                catItems.chunked(safeBatchSize).forEach { chunk ->
                    rawGroups.add(category to chunk)
                }
            }
        }

        val totalBatches = rawGroups.size
        return rawGroups.mapIndexed { idx, (category, chunk) ->
            FileCleanupBatch(
                batchIndex = idx + 1,
                totalBatches = totalBatches,
                category = category,
                titleAr = "دفعة ${idx + 1}/$totalBatches — ${category.titleAr}",
                items = chunk
            )
        }
    }

    suspend fun executeApprovedBatch(
        batch: FileCleanupBatch,
        approvedByUser: Boolean
    ): BatchDeletionOutcome {
        val policyDecision = agentPolicy.validateFileCleanupBatch(
            paths = batch.items.map { it.path },
            approvedByUser = approvedByUser,
            appPackageName = context.packageName,
            trashRootPath = trashRootDir.absolutePath
        )
        if (!policyDecision.allowed) {
            actionLogRepository.logAction(
                title = "منع تنفيذ دفعة تنظيف ملفات",
                details = "${batch.titleAr} | ${policyDecision.reasonAr}",
                status = ActionStatus.CANCELLED,
                actionType = "POLICY_BLOCKED_FILE_CLEANUP"
            )
            return BatchDeletionOutcome(
                movedToTrashCount = 0,
                skippedNonEmptyCount = 0,
                skippedExcludedOrMissingCount = batch.items.size,
                failedCount = 0,
                freedBytes = 0L,
                detailsAr = listOf(policyDecision.reasonAr)
            )
        }

        val outcome = withContext(Dispatchers.IO) {
            trashManager.purgeExpired()
            var moved = 0
            var skippedNonEmpty = 0
            var skippedExcludedOrMissing = 0
            var failed = 0
            var freed = 0L
            val details = mutableListOf<String>()

            for (item in batch.items) {
                val res = trashManager.moveToTrash(item)
                if (res.isSuccess) {
                    moved++
                    freed += item.sizeBytes
                } else {
                    val msg = res.exceptionOrNull()?.message.orEmpty()
                    when {
                        msg.contains("لم يعد فارغاً") -> {
                            skippedNonEmpty++
                            details.add("تم تخطي «${item.name}» لأنه لم يعد فارغاً لحظة الحذف.")
                        }
                        msg.contains("غير موجود") || msg.contains("مسار محمي") -> {
                            skippedExcludedOrMissing++
                            details.add("تم تخطي «${item.name}»: $msg")
                        }
                        else -> {
                            failed++
                            details.add("تعذّر نقل «${item.name}»: $msg")
                        }
                    }
                }
            }

            BatchDeletionOutcome(
                movedToTrashCount = moved,
                skippedNonEmptyCount = skippedNonEmpty,
                skippedExcludedOrMissingCount = skippedExcludedOrMissing,
                failedCount = failed,
                freedBytes = freed,
                detailsAr = details
            )
        }

        actionLogRepository.logAction(
            title = "تنفيذ ${batch.titleAr} بعد موافقة المستخدم",
            details = "نُقل للسلة: ${outcome.movedToTrashCount} | تخطّي: ${outcome.skippedNonEmptyCount + outcome.skippedExcludedOrMissingCount} | فشل: ${outcome.failedCount} | المساحة: ${formatFileSize(outcome.freedBytes)}",
            status = if (outcome.movedToTrashCount > 0 || outcome.failedCount == 0) ActionStatus.SUCCESS else ActionStatus.FAILED,
            actionType = "FILE_TRASH_BATCH"
        )
        return outcome
    }

    suspend fun listTrashItems(): List<TrashItemRecord> = withContext(Dispatchers.IO) {
        trashManager.listActiveItems()
    }

    suspend fun restoreTrashItem(id: String): TrashRestoreOutcome {
        val outcome = withContext(Dispatchers.IO) {
            trashManager.restoreItem(id)
        }
        actionLogRepository.logAction(
            title = if (outcome.restored) "استعادة عنصر من سلة المهملات" else "فشل استعادة عنصر من السلة",
            details = outcome.messageAr,
            status = if (outcome.restored) ActionStatus.SUCCESS else ActionStatus.FAILED,
            actionType = "FILE_TRASH_RESTORE"
        )
        return outcome
    }

    suspend fun permanentlyDeleteTrashItem(id: String): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            trashManager.permanentlyDeleteItem(id)
        }
        if (deleted) {
            actionLogRepository.logAction(
                title = "حذف نهائي من سلة المهملات",
                details = "تم الحذف النهائي للعنصر $id بناءً على طلب المستخدم.",
                status = ActionStatus.SUCCESS,
                actionType = "FILE_TRASH_PERMANENT_DELETE"
            )
        }
        return deleted
    }

    suspend fun purgeExpiredTrash(): TrashPurgeOutcome = withContext(Dispatchers.IO) {
        trashManager.purgeExpired()
    }

    private fun resolveStorageRoots(accessMode: StorageAccessMode): List<File> {
        if (accessMode == StorageAccessMode.ALL_FILES) {
            val primary = Environment.getExternalStorageDirectory()
            val extDirs = context.getExternalFilesDirs(null)
                ?.mapNotNull { dir ->
                    // استخراج جذر وحدات التخزين الإضافية إن وجدت
                    val path = dir?.absolutePath ?: return@mapNotNull null
                    val androidIdx = path.indexOf("/Android/")
                    if (androidIdx > 0) File(path.substring(0, androidIdx)) else null
                }
                .orEmpty()
            return (listOf(primary) + extDirs)
                .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
                .filter { it.exists() && it.isDirectory }
        }

        val publicTypes = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_MOVIES
        )
        return publicTypes
            .map { type -> Environment.getExternalStoragePublicDirectory(type) }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .filter { it.exists() && it.isDirectory }
    }
}
