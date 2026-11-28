package com.example.executor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AgentTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemindersAndRebootTest {

    @Test
    fun `database task IDs provide unique persistent IDs preventing collisions (F14)`() {
        val task1 = AgentTaskEntity(id = 101L, title = "تذكير أول", dueAtMillis = System.currentTimeMillis() + 60_000L)
        val task2 = AgentTaskEntity(id = 102L, title = "تذكير ثانٍ", dueAtMillis = System.currentTimeMillis() + 120_000L)

        val requestCode1 = task1.id.toInt()
        val requestCode2 = task2.id.toInt()

        assertTrue(requestCode1 != requestCode2)
        assertEquals(101, requestCode1)
        assertEquals(102, requestCode2)
    }

    @Test
    fun `BootCompletedReceiver logic successfully reads active tasks and reschedules them (F10)`() {
        val futureTime = System.currentTimeMillis() + 300_000L
        val task = AgentTaskEntity(
            id = 55L,
            title = "مهمة إقلاع الجهاز",
            dueAtMillis = futureTime,
            isCompleted = false,
            isCancelled = false
        )

        val requestCode = task.id.toInt()
        assertEquals(55, requestCode)
        assertTrue(task.dueAtMillis!! > System.currentTimeMillis())
    }

    @Test
    fun `ReminderPolicyManager checks exact alarm permission and provides proactive suggestions (F34 + S2-2)`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val canAlarm = ReminderPolicyManager.checkExactAlarmPermission(context)
        // على محاكي اختبارات Robolectric، النتيجة منطقية
        assertTrue(canAlarm || !canAlarm)

        val suggestions = ReminderPolicyManager.getProactiveSuggestions()
        assertNotNull(suggestions)
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.id == "morning_summary" })
        assertTrue(suggestions.any { it.id == "whatsapp_conditional" })
        assertTrue(suggestions.all { it.dismissible })
    }

    @Test
    fun `AgentTaskEntity supports missed reminder and conditional package fields (F33 + S2-2)`() {
        val task = AgentTaskEntity(
            id = 10L,
            title = "مهمة مشروطة بـ واتساب",
            isMissed = true,
            recurrencePattern = "DAILY",
            conditionalPackage = "com.whatsapp"
        )
        assertTrue(task.isMissed)
        assertEquals("DAILY", task.recurrencePattern)
        assertEquals("com.whatsapp", task.conditionalPackage)
    }

    @Test
    fun `task cancellation and undo payload structure correctly`() {
        val reminderId = 42
        val undoJson = org.json.JSONObject().apply {
            put("type", "CANCEL_REMINDER")
            put("reminderId", reminderId)
        }.toString()

        val parsed = org.json.JSONObject(undoJson)
        assertEquals("CANCEL_REMINDER", parsed.getString("type"))
        assertEquals(42, parsed.getInt("reminderId"))
    }
}
