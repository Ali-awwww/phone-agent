package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GeminiModelClient
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogDao
import com.example.data.local.ActionLogEntity
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.model.ScreenSnapshot
import com.example.service.ScreenStateBridge
import com.example.voice.VoiceAssistantManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyModeAndLogRedactionTest {

    @Test
    fun `PrivacyPreferences persists toggle across instances and emits StateFlow changes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs1 = PrivacyPreferences(context)
        prefs1.setPrivacyModeEnabled(false)
        assertFalse(prefs1.isPrivacyModeEnabled())
        assertFalse(prefs1.privacyModeEnabled.value)

        prefs1.setPrivacyModeEnabled(true)
        assertTrue(prefs1.isPrivacyModeEnabled())
        assertTrue(prefs1.privacyModeEnabled.value)

        // التحقق من أن حالة التفضيل محفوظة في SharedPreferences وتظهر لنسخة جديدة
        val prefs2 = PrivacyPreferences(context)
        assertTrue(prefs2.isPrivacyModeEnabled())
        assertTrue(prefs2.privacyModeEnabled.value)

        // إعادة التعيين
        prefs2.setPrivacyModeEnabled(false)
        assertFalse(prefs1.isPrivacyModeEnabled())
    }

    @Test
    fun `ActionLogRepository scrubs PII when privacy mode is OFF and masks details when ON`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val privacyPrefs = PrivacyPreferences(context)
        privacyPrefs.setPrivacyModeEnabled(false)

        val insertedLogs = mutableListOf<ActionLogEntity>()
        val fakeDao = object : ActionLogDao {
            override fun observeAllLogs(): Flow<List<ActionLogEntity>> = flowOf(insertedLogs)
            override fun observePagedLogs(limit: Int): Flow<List<ActionLogEntity>> = flowOf(insertedLogs.take(limit))
            override fun observeLogsCount(): Flow<Int> = flowOf(insertedLogs.size)
            override suspend fun insertLog(log: ActionLogEntity): Long {
                insertedLogs.add(log)
                return insertedLogs.size.toLong()
            }
            override suspend fun updateStatus(id: Long, status: String, canUndo: Boolean) {}
            override suspend fun markUndone(id: Long, status: String) {}
            override suspend fun clearAllLogs() { insertedLogs.clear() }
        }

        val repository = ActionLogRepository(fakeDao, privacyPrefs)

        // 1. الوضع متوقف (Privacy Mode OFF) -> التنقية تعمل ولكن التفاصيل تظهر منقاة
        repository.logAction(
            title = "اتصال برقم 0501234567",
            details = "كلمة المرور: Secret123 للخدمة",
            status = ActionStatus.SUCCESS,
            actionType = "CALL"
        )
        val logOff = insertedLogs.last()
        assertTrue("Scrubber must mask phone in title", logOff.title.contains("[هاتف-محذوف]"))
        assertFalse("Raw phone must not appear", logOff.title.contains("0501234567"))
        assertTrue("Scrubber must mask password in details", logOff.details.contains("[كلمة-مرور-محذوفة]"))
        assertFalse("Raw password must not appear", logOff.details.contains("Secret123"))

        // 2. تفعيل وضع الخصوصية (Privacy Mode ON) -> تُحجب التفاصيل تماماً
        privacyPrefs.setPrivacyModeEnabled(true)
        repository.logAction(
            title = "أرسل رسالة إلى 0555987654",
            details = "نص المحادثة بالكامل سري جداً",
            status = ActionStatus.SUCCESS,
            actionType = "MESSAGE"
        )
        val logOn = insertedLogs.last()
        assertTrue("Scrubber masks title in privacy mode", logOn.title.contains("[هاتف-محذوف]"))
        assertTrue("Details must be masked with privacy message", logOn.details.contains("وضع الخصوصية مفعّل"))
        assertFalse("Sensitive details must be completely redacted", logOn.details.contains("نص المحادثة بالكامل سري جداً"))
    }

    @Test
    fun `ScreenStateBridge strips sensitive screen texts and clears snapshot on completion`() {
        val sensitiveSnapshot = ScreenSnapshot(
            packageName = "com.bank.app",
            visibleTexts = listOf("رصيدك الحالي: 100,000 ريال", "بطاقة مدى"),
            clickableLabels = listOf("تحويل سريع", "دفع فواتير"),
            editableHints = listOf("أدخل رقم الحساب"),
            hasSensitiveSecurityField = true
        )

        ScreenStateBridge.updateSnapshot(sensitiveSnapshot)
        val stored = ScreenStateBridge.latestSnapshot.value
        assertNotNull(stored)
        assertEquals("com.bank.app", stored?.packageName)
        assertTrue("Visible texts must be empty for sensitive security screens", stored?.visibleTexts?.isEmpty() == true)
        assertTrue("Clickable labels must be empty for sensitive security screens", stored?.clickableLabels?.isEmpty() == true)
        assertTrue("Editable hints must be empty for sensitive security screens", stored?.editableHints?.isEmpty() == true)
        assertTrue(stored?.hasSensitiveSecurityField == true)

        // التحقق من تفريغ لقطة الشاشة
        ScreenStateBridge.clearSnapshot()
        assertNull(ScreenStateBridge.latestSnapshot.value)

        // التحقق من تفريغ لقطة الشاشة تلقائياً عند انتهاء المهمة
        val normalSnapshot = ScreenSnapshot(
            packageName = "com.notes.app",
            visibleTexts = listOf("ملاحظاتي"),
            clickableLabels = listOf("إضافة"),
            editableHints = emptyList(),
            hasSensitiveSecurityField = false
        )
        ScreenStateBridge.updateSnapshot(normalSnapshot)
        assertNotNull(ScreenStateBridge.latestSnapshot.value)
        ScreenStateBridge.setTaskActive(false)
        assertNull("Snapshot must be cleared when task ends", ScreenStateBridge.latestSnapshot.value)
    }

    @Test
    fun `VoiceAssistantManager purges orphaned voice command audio files from cache`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val orphanAudio1 = File(context.cacheDir, "voice_cmd_test1.m4a").apply { writeText("audio-data-1") }
        val orphanAudio2 = File(context.cacheDir, "voice_cmd_test2.m4a").apply { writeText("audio-data-2") }
        val unrelatedFile = File(context.cacheDir, "unrelated_cache.txt").apply { writeText("keep-me") }

        assertTrue(orphanAudio1.exists())
        assertTrue(orphanAudio2.exists())
        assertTrue(unrelatedFile.exists())

        val voiceManager = VoiceAssistantManager(context)
        voiceManager.purgeOrphanedVoiceFiles()

        assertFalse("Orphaned audio file 1 must be deleted", orphanAudio1.exists())
        assertFalse("Orphaned audio file 2 must be deleted", orphanAudio2.exists())
        assertTrue("Unrelated cache files must not be deleted", unrelatedFile.exists())

        // تنظيف
        unrelatedFile.delete()
    }

    @Test
    fun `GeminiRepository drops conversation history and minimizes screen text when privacy mode is ON`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val privacyPrefs = PrivacyPreferences(context)
        privacyPrefs.setPrivacyModeEnabled(true)

        var capturedPrompt = ""
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String): String = "{}"
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String): String = "{}"
            override suspend fun generateVerificationJson(prompt: String): String = "{}"
            override suspend fun generateCategorizationJson(prompt: String): String = "{}"
            override suspend fun generateGeneralArabicText(prompt: String): String {
                capturedPrompt = prompt
                return "رد توضيحي"
            }
            override suspend fun generateLiteGeneralArabicText(prompt: String): String {
                capturedPrompt = prompt
                return "رد توضيحي خفيف"
            }
        }

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clearStoredTokens() {}
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeClient,
            privacyPreferences = privacyPrefs
        )

        repository.conversationalReplyWithMeta(
            userMessage = "ماذا ترى على الشاشة الآن؟ هاتف: 0501234567",
            contextSummary = "النصوص الظاهرة:\nرصيد الحساب البنكي 50000 ريال\nزر تحويل",
            history = "سجل محادثة سابق يحتوي على رسائل سرية"
        )

        // 1. تحقق من تنقية رقم الهاتف
        assertTrue(capturedPrompt.contains("[هاتف-محذوف]"))
        assertFalse(capturedPrompt.contains("0501234567"))

        // 2. تحقق من إسقاط سجل المحادثة بالكامل في وضع الخصوصية
        assertFalse("Conversation history must be dropped in Privacy Mode", capturedPrompt.contains("سجل محادثة سابق"))
        assertFalse("Sensitive history must not appear", capturedPrompt.contains("رسائل سرية"))

        // 3. تحقق من تقليص سياق الشاشة
        assertFalse("Full visible screen text must be excluded in Privacy Mode", capturedPrompt.contains("رصيد الحساب البنكي"))
    }
}
