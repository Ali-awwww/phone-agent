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
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.PhoneAgentAccessibilityService
import com.example.service.ScreenStateBridge

enum class AgentPermissionType {
    ACCESSIBILITY,
    QUERY_ALL_PACKAGES,
    USAGE_ACCESS,
    MICROPHONE,
    NOTIFICATIONS,
    EXACT_ALARMS
}

data class AgentPermissionItem(
    val type: AgentPermissionType,
    val titleAr: String,
    val explanationAr: String,
    val isGranted: Boolean,
    val isRuntimePermission: Boolean,
    val isInstallTimePermission: Boolean = false
)

object PermissionHelper {

    fun getAllPermissionStatuses(context: Context): List<AgentPermissionItem> {
        return listOf(
            AgentPermissionItem(
                type = AgentPermissionType.ACCESSIBILITY,
                titleAr = "خدمة إمكانية الوصول (Accessibility)",
                explanationAr = "تمكّن الوكيل من قراءة محتوى الشاشة الحالية والضغط على الأزرار والتمرير نيابةً عنك للتحقق من كل خطوة وتلخيص التطبيقات، مع التوقف التلقائي عند كلمات المرور والدفع.",
                isGranted = isAccessibilityEnabled(context),
                isRuntimePermission = false
            ),
            AgentPermissionItem(
                type = AgentPermissionType.QUERY_ALL_PACKAGES,
                titleAr = "قراءة قائمة التطبيقات (QUERY_ALL_PACKAGES)",
                explanationAr = "ضرورية لعرض جميع التطبيقات المثبتة على هاتفك وتصنيفها في مجلدات (تواصل، عمل، ألعاب...) والبحث فيها وفتحها بأمرك.",
                isGranted = isQueryAllPackagesGranted(context),
                isRuntimePermission = false,
                isInstallTimePermission = true
            ),
            AgentPermissionItem(
                type = AgentPermissionType.USAGE_ACCESS,
                titleAr = "إحصائيات استخدام التطبيقات",
                explanationAr = "تساعد الوكيل على معرفة التطبيقات التي لم تستخدمها منذ فترة طويلة لاقتراح إخفائها وتنظيم شاشتك.",
                isGranted = isUsageAccessGranted(context),
                isRuntimePermission = false
            ),
            AgentPermissionItem(
                type = AgentPermissionType.MICROPHONE,
                titleAr = "الميكروفون (الأوامر الصوتية)",
                explanationAr = "يسمح لك بالتحدث إلى «وكيل الهاتف» بالعربية وإجراء محادثات صوتية مباشرة دون الحاجة للكتابة.",
                isGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED,
                isRuntimePermission = true
            ),
            AgentPermissionItem(
                type = AgentPermissionType.NOTIFICATIONS,
                titleAr = "الإشعارات",
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
                titleAr = "المنبهات والتذكيرات الدقيقة",
                explanationAr = "تتيح للوكيل ضبط المنبهات والتذكيرات في الوقت الدقيق الذي تحدده دون تأخير.",
                isGranted = isExactAlarmGranted(context),
                isRuntimePermission = false
            )
        )
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

    fun isQueryAllPackagesGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.QUERY_ALL_PACKAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
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

    fun buildSettingsIntent(context: Context, type: AgentPermissionType): Intent {
        return when (type) {
            AgentPermissionType.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            AgentPermissionType.USAGE_ACCESS ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            AgentPermissionType.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    )
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                }
            AgentPermissionType.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                }
            else ->
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
