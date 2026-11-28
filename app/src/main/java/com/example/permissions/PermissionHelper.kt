package com.example.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.R
import com.example.service.PhoneAgentAccessibilityService
import com.example.service.ScreenStateBridge

enum class AgentPermissionType {
    ACCESSIBILITY,
    OVERLAY,
    QUERY_ALL_PACKAGES,
    USAGE_ACCESS,
    MICROPHONE,
    NOTIFICATIONS,
    EXACT_ALARMS,
    STORAGE_ALL_FILES
}

data class AgentPermissionItem(
    val type: AgentPermissionType,
    val titleAr: String,
    val explanationAr: String,
    val isGranted: Boolean,
    val isRuntimePermission: Boolean
)

object PermissionHelper {

    const val OUR_APPS_SHOWCASE_URL: String = "https://apps-showcase.pages.dev"

    fun buildExternalBrowserIntent(url: String = OUR_APPS_SHOWCASE_URL): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openExternalBrowser(context: Context, url: String = OUR_APPS_SHOWCASE_URL): Boolean {
        return try {
            context.startActivity(buildExternalBrowserIntent(url))
            true
        } catch (_: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.our_apps_browser_error),
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    fun getAllPermissionStatuses(context: Context): List<AgentPermissionItem> {
        return listOf(
            AgentPermissionItem(
                type = AgentPermissionType.ACCESSIBILITY,
                titleAr = "خدمة إمكانية الوصول (Accessibility)",
                explanationAr = "تمكّن الوكيل من قراءة محتوى الشاشة الحالية والضغط على الأزرار والتمرير وتنفيذ إيماءات اللعب نيابةً عنك، مع التوقف التلقائي عند كلمات المرور والدفع.",
                isGranted = isAccessibilityEnabled(context),
                isRuntimePermission = false
            ),
            AgentPermissionItem(
                type = AgentPermissionType.OVERLAY,
                titleAr = "الظهور فوق التطبيقات (SYSTEM_ALERT_WINDOW)",
                explanationAr = "يسمح بإظهار أزرار تحكم عائمة فوق الألعاب والتطبيقات (إيقاف فوري، إيقاف مؤقت/استئناف، «أكمل بنفسي»، وتصغير) أثناء عمل الوكيل.",
                isGranted = isOverlayPermissionGranted(context),
                isRuntimePermission = false
            ),
            AgentPermissionItem(
                type = AgentPermissionType.QUERY_ALL_PACKAGES,
                titleAr = "قراءة قائمة التطبيقات (QUERY_ALL_PACKAGES)",
                explanationAr = "معلنة في التطبيق لعرض جميع التطبيقات المثبتة على هاتفك وتصنيفها في مجلدات (تواصل، عمل، ألعاب...) والبحث فيها وفتحها بأمرك.",
                isGranted = isQueryAllPackagesGranted(context),
                isRuntimePermission = false
            ),
            AgentPermissionItem(
                type = AgentPermissionType.USAGE_ACCESS,
                titleAr = "إحصائيات استخدام التطبيقات (PACKAGE_USAGE_STATS)",
                explanationAr = "تساعد الوكيل على معرفة التطبيقات التي لم تستخدمها منذ فترة طويلة لاقتراح إخفائها وتنظيم شاشتك.",
                isGranted = isUsageAccessGranted(context),
                isRuntimePermission = false
            ),
            AgentPermissionItem(
                type = AgentPermissionType.STORAGE_ALL_FILES,
                titleAr = "إدارة وفحص ملفات الهاتف (MANAGE_EXTERNAL_STORAGE)",
                explanationAr = "يتيح لأداة الملفات عند طلبها من المحادثة فحص المجلدات الفارغة والملفات المؤقتة والمكررة والكبيرة (باستثناء نظام Android)، مع طلب الموافقة قبل كل دفعة ونقل المحذوفات إلى سلة 7 أيام.",
                isGranted = isAllFilesAccessGranted(context),
                isRuntimePermission = false
            ),
            AgentPermissionItem(
                type = AgentPermissionType.MICROPHONE,
                titleAr = "الميكروفون (RECORD_AUDIO)",
                explanationAr = "يسمح لك بالتحدث إلى «وكيل الهاتف» بالعربية وإجراء محادثات صوتية مباشرة دون الحاجة للكتابة.",
                isGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED,
                isRuntimePermission = true
            ),
            AgentPermissionItem(
                type = AgentPermissionType.NOTIFICATIONS,
                titleAr = "الإشعارات (POST_NOTIFICATIONS)",
                explanationAr = "تُستخدم لإرسال التنبيهات والتذكيرات التي تطلب من الوكيل جدولتها لك في الوقت المحدد.",
                isGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED,
                isRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ),
            AgentPermissionItem(
                type = AgentPermissionType.EXACT_ALARMS,
                titleAr = "المنبهات والتذكيرات الدقيقة (SCHEDULE_EXACT_ALARM)",
                explanationAr = "تتيح للوكيل ضبط المنبهات والتذكيرات في الوقت الدقيق الذي تحدده دون تأخير.",
                isGranted = isExactAlarmGranted(context),
                isRuntimePermission = false
            )
        )
    }

    fun isAllFilesAccessGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun buildAllFilesAccessIntent(context: Context): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )
        } else {
            listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
        }
        return candidates.firstOrNull { intent ->
            intent.resolveActivity(context.packageManager) != null
        } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (ScreenStateBridge.isServiceConnected.value) return true
        val expectedComponent = ComponentName(context, PhoneAgentAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any {
            ComponentName.unflattenFromString(it) == expectedComponent
        }
    }

    @Suppress("DEPRECATION")
    fun isQueryAllPackagesGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.QUERY_ALL_PACKAGES
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return runCatching {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
            val perms = pkgInfo.requestedPermissions ?: return@runCatching false
            val flags = pkgInfo.requestedPermissionsFlags ?: return@runCatching false
            val idx = perms.indexOf(Manifest.permission.QUERY_ALL_PACKAGES)
            idx >= 0 && idx < flags.size &&
                (flags[idx] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }.getOrDefault(false)
    }

    fun isUsageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isExactAlarmGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    fun isOverlayPermissionGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun openPermissionSettings(context: Context, type: AgentPermissionType): Boolean {
        val candidates = buildSettingsCandidateIntents(context, type)
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
                // جرّب المسار الاحتياطي التالي
            }
        }
        return try {
            context.startActivity(buildAppDetailsIntent(context))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildSettingsCandidateIntents(
        context: Context,
        type: AgentPermissionType
    ): List<Intent> {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val list = when (type) {
            AgentPermissionType.ACCESSIBILITY -> listOf(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            AgentPermissionType.OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                buildAppDetailsIntent(context)
            )
            AgentPermissionType.USAGE_ACCESS -> listOf(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                buildAppDetailsIntent(context)
            )
            AgentPermissionType.STORAGE_ALL_FILES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                listOf(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    buildAppDetailsIntent(context)
                )
            } else {
                listOf(buildAppDetailsIntent(context))
            }
            AgentPermissionType.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri),
                    buildAppDetailsIntent(context)
                )
            } else {
                listOf(buildAppDetailsIntent(context))
            }
            AgentPermissionType.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    },
                    buildAppDetailsIntent(context)
                )
            } else {
                listOf(buildAppDetailsIntent(context))
            }
            AgentPermissionType.MICROPHONE,
            AgentPermissionType.QUERY_ALL_PACKAGES -> listOf(buildAppDetailsIntent(context))
        }
        return list.map { it.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } }
    }

    private fun buildAppDetailsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
