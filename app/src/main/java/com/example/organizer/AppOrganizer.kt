package com.example.organizer

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.data.local.AppOrganizationDao
import com.example.data.local.AppOrganizationEntity
import com.example.model.AppFolder
import com.example.service.ScreenStateBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val folder: AppFolder,
    val isHidden: Boolean,
    val lastUsedMs: Long,
    val isUnused: Boolean,
    val lastSummary: String?
)

class AppOrganizer(
    private val context: Context,
    private val appOrganizationDao: AppOrganizationDao,
    private val actionLogRepository: ActionLogRepository,
    private val geminiRepository: GeminiRepository
) {
    val organizationsFlow: Flow<List<AppOrganizationEntity>> =
        appOrganizationDao.observeAllOrganizations()

    suspend fun loadInstalledApps(
        savedOrganizations: Map<String, AppOrganizationEntity>
    ): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val usageMap = queryRecentUsageMap()
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L

        val seenPackages = HashSet<String>()
        val result = mutableListOf<InstalledAppInfo>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName || !seenPackages.add(pkg)) continue

            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            val saved = savedOrganizations[pkg]
            val folder = if (saved != null) {
                AppFolder.fromKey(saved.folderKey)
            } else {
                guessFolderHeuristic(pkg, label)
            }
            val lastUsed = usageMap[pkg] ?: 0L
            val isUnused = usageMap.isNotEmpty() && lastUsed < thirtyDaysAgo

            result.add(
                InstalledAppInfo(
                    packageName = pkg,
                    label = label,
                    icon = icon,
                    folder = folder,
                    isHidden = saved?.isHidden ?: false,
                    lastUsedMs = lastUsed,
                    isUnused = isUnused,
                    lastSummary = saved?.lastSummary
                )
            )
        }

        // حفظ التصنيفات الأولية محلياً لأول مرة
        val missing = result.filter { !savedOrganizations.containsKey(it.packageName) }
        if (missing.isNotEmpty()) {
            appOrganizationDao.upsertAll(
                missing.map {
                    AppOrganizationEntity(
                        packageName = it.packageName,
                        folderKey = it.folder.name,
                        isHidden = false
                    )
                }
            )
        }

        result.sortedBy { it.label }
    }

    suspend fun autoCategorizeWithGemini(apps: List<InstalledAppInfo>) {
        val pairs = apps.map { it.packageName to it.label }
        val mapping = geminiRepository.categorizeApps(pairs)
        if (mapping.isNotEmpty()) {
            val entities = apps.mapNotNull { app ->
                val folderKey = mapping[app.packageName] ?: return@mapNotNull null
                AppOrganizationEntity(
                    packageName = app.packageName,
                    folderKey = folderKey,
                    isHidden = app.isHidden,
                    lastSummary = app.lastSummary
                )
            }
            appOrganizationDao.upsertAll(entities)
            actionLogRepository.logAction(
                title = "تصنيف التطبيقات تلقائياً",
                details = "تم تصنيف ${entities.size} تطبيقاً إلى مجلداتها باستخدام Gemini.",
                status = ActionStatus.SUCCESS,
                actionType = "AUTO_CATEGORIZE"
            )
        }
    }

    suspend fun moveAppToFolder(app: InstalledAppInfo, targetFolder: AppFolder) {
        val previousFolder = app.folder.name
        val existing = appOrganizationDao.getOrganization(app.packageName)
        appOrganizationDao.upsertOrganization(
            AppOrganizationEntity(
                packageName = app.packageName,
                folderKey = targetFolder.name,
                isHidden = existing?.isHidden ?: app.isHidden,
                lastSummary = existing?.lastSummary ?: app.lastSummary
            )
        )
        val undoPayload = JSONObject().apply {
            put("type", "UNDO_MOVE_APP")
            put("packageName", app.packageName)
            put("previousFolder", previousFolder)
        }.toString()

        actionLogRepository.logAction(
            title = "نقل «${app.label}» إلى مجلد ${targetFolder.titleAr}",
            details = "نُقل التطبيق من «${app.folder.titleAr}» إلى «${targetFolder.titleAr}».",
            status = ActionStatus.SUCCESS,
            actionType = "MOVE_APP",
            canUndo = true,
            undoPayload = undoPayload
        )
    }

    suspend fun toggleHideApp(app: InstalledAppInfo, hide: Boolean) {
        val existing = appOrganizationDao.getOrganization(app.packageName)
        appOrganizationDao.upsertOrganization(
            AppOrganizationEntity(
                packageName = app.packageName,
                folderKey = existing?.folderKey ?: app.folder.name,
                isHidden = hide,
                lastSummary = existing?.lastSummary ?: app.lastSummary
            )
        )
        val undoPayload = JSONObject().apply {
            put("type", "UNDO_HIDE_APP")
            put("packageName", app.packageName)
            put("wasHidden", !hide)
        }.toString()

        actionLogRepository.logAction(
            title = if (hide) "إخفاء «${app.label}»" else "إظهار «${app.label}»",
            details = if (hide) "تم إخفاء التطبيق غير المستخدم من القائمة الرئيسية." else "تمت إعادة إظهار التطبيق في القائمة.",
            status = ActionStatus.SUCCESS,
            actionType = "HIDE_APP",
            canUndo = true,
            undoPayload = undoPayload
        )
    }

    suspend fun restoreAppFolder(packageName: String, folderKey: String) {
        appOrganizationDao.updateFolder(packageName, folderKey)
    }

    suspend fun restoreAppHiddenState(packageName: String, isHidden: Boolean) {
        appOrganizationDao.updateHidden(packageName, isHidden)
    }

    suspend fun tryAndSummarizeApp(app: InstalledAppInfo): String {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: throw IllegalStateException("لا يمكن فتح التطبيق «${app.label}».")

        context.startActivity(launchIntent)
        val capturedScreens = mutableListOf<String>()
        val service = ScreenStateBridge.getService()

        if (service != null) {
            delay(1800L)
            service.captureCurrentScreen()?.let { snap ->
                if (!snap.hasSensitiveSecurityField) {
                    capturedScreens.add(snap.toCompactPromptText())
                }
            }
            // التمرير بأمان لقراءة الشاشة الثانية إن أمكن
            if (service.scrollForward()) {
                delay(1200L)
                service.captureCurrentScreen()?.let { snap ->
                    if (!snap.hasSensitiveSecurityField) {
                        capturedScreens.add(snap.toCompactPromptText())
                    }
                }
            }
        }

        if (capturedScreens.isEmpty()) {
            val truthfulNotice = "تم فتح تطبيق «${app.label}» بنجاح، لكن خدمة إمكانية الوصول غير مفعلة حالياً لقراءة عناصر شاشته الفعلية.\n\nلتجربة التطبيق وتلخيص محتواه بدقة دون تخمين، فعّل «خدمة إمكانية الوصول» من تبويب الأذونات ثم أعد المحاولة."
            actionLogRepository.logAction(
                title = "فتح «${app.label}» (تعذر التلخيص لعدم تفعيل الوصول)",
                details = truthfulNotice,
                status = ActionStatus.PENDING_APPROVAL,
                actionType = "SUMMARIZE_APP"
            )
            return truthfulNotice
        }

        val summary = geminiRepository.summarizeAppScreens(
            appName = app.label,
            packageName = app.packageName,
            capturedScreenTexts = capturedScreens
        )

        val existing = appOrganizationDao.getOrganization(app.packageName)
        appOrganizationDao.upsertOrganization(
            AppOrganizationEntity(
                packageName = app.packageName,
                folderKey = existing?.folderKey ?: app.folder.name,
                isHidden = existing?.isHidden ?: app.isHidden,
                lastSummary = summary
            )
        )

        actionLogRepository.logAction(
            title = "تجربة وتلخيص «${app.label}»",
            details = summary.take(180),
            status = ActionStatus.SUCCESS,
            actionType = "SUMMARIZE_APP"
        )
        return summary
    }

    private fun queryRecentUsageMap(): Map<String, Long> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyMap()
            val end = System.currentTimeMillis()
            val start = end - 60L * 24L * 60L * 60L * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY, start, end)
                ?: return emptyMap()
            stats.associate { it.packageName to it.lastTimeUsed }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun guessFolderHeuristic(pkg: String, label: String): AppFolder {
        val text = "$pkg $label".lowercase()
        return when {
            listOf("whatsapp", "telegram", "messenger", "signal", "viber", "chat", "sms", "dialer", "phone", "contacts", "discord", "واتس", "تليجرام", "اتصال", "رسائل")
                .any { text.contains(it) } -> AppFolder.COMMUNICATION
            listOf("docs", "sheets", "drive", "gmail", "outlook", "office", "slack", "teams", "zoom", "meet", "calendar", "notes", "keep", "عمل", "بريد", "ملاحظات", "تقويم")
                .any { text.contains(it) } -> AppFolder.WORK
            listOf("game", "pubg", "chess", "puzzle", "roblox", "minecraft", "clash", "unity", "ألعاب", "لعبة")
                .any { text.contains(it) } -> AppFolder.GAMES
            listOf("amazon", "noon", "aliexpress", "ebay", "shop", "store", "market", "shein", "talabat", "hungerstation", "تسوق", "متجر", "طلبات")
                .any { text.contains(it) } -> AppFolder.SHOPPING
            listOf("youtube", "spotify", "netflix", "music", "video", "camera", "gallery", "photos", "tiktok", "instagram", "twitch", "وسائط", "فيديو", "موسيقى", "كاميرا", "صور")
                .any { text.contains(it) } -> AppFolder.MEDIA
            else -> AppFolder.TOOLS
        }
    }
}
