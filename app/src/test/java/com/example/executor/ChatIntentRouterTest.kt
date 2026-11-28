package com.example.executor

import com.example.data.local.AgentTaskType
import com.example.model.AppFolder
import com.example.policy.AgentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatIntentRouterTest {

    @Test
    fun `show my games routes to ShowApps with GAMES folder and not StartGameplay`() {
        val intent = ChatIntentRouter.classifyIntent("اعرض ألعابي")
        assertTrue(intent is ChatRouteIntent.ShowApps)
        assertEquals(AppFolder.GAMES, (intent as ChatRouteIntent.ShowApps).folderFilter)
    }

    @Test
    fun `show my apps routes to ShowApps with null filter`() {
        val intent = ChatIntentRouter.classifyIntent("اعرض تطبيقاتي")
        assertTrue(intent is ChatRouteIntent.ShowApps)
        assertEquals(null, (intent as ChatRouteIntent.ShowApps).folderFilter)
    }

    @Test
    fun `what is on my screen routes to ReadScreen`() {
        assertEquals(ChatRouteIntent.ReadScreen, ChatIntentRouter.classifyIntent("ماذا في شاشتي؟"))
        assertEquals(ChatRouteIntent.ReadScreen, ChatIntentRouter.classifyIntent("اقرأ الشاشة الآن"))
    }

    @Test
    fun `create reminder supports arabic indic digits`() {
        val intent = ChatIntentRouter.classifyIntent("ذكّرني بعد ١٠ دقائق بشرب الماء")
        assertTrue(intent is ChatRouteIntent.CreateTaskOrReminder)
        val reminder = intent as ChatRouteIntent.CreateTaskOrReminder
        assertEquals(AgentTaskType.REMINDER, reminder.taskType)
        assertEquals(10, reminder.delayMinutes)
        assertEquals("شرب الماء", reminder.title)
    }

    @Test
    fun `create alarm parses evening clock time`() {
        val intent = ChatIntentRouter.classifyIntent("اضبط منبهاً للساعة 7:30 مساءً")
        assertTrue(intent is ChatRouteIntent.CreateTaskOrReminder)
        val alarm = intent as ChatRouteIntent.CreateTaskOrReminder
        assertEquals(AgentTaskType.ALARM, alarm.taskType)
        assertEquals(19, alarm.hour)
        assertEquals(30, alarm.minute)
    }

    @Test
    fun `list and cancel tasks route properly`() {
        assertEquals(ChatRouteIntent.ListTasksAndReminders, ChatIntentRouter.classifyIntent("اعرض مهامي"))
        assertEquals(ChatRouteIntent.ListTasksAndReminders, ChatIntentRouter.classifyIntent("تذكيراتي"))

        val cancel = ChatIntentRouter.classifyIntent("ألغِ تذكير شرب الماء")
        assertTrue(cancel is ChatRouteIntent.CancelTaskOrReminder)
        assertEquals("شرب الماء", (cancel as ChatRouteIntent.CancelTaskOrReminder).queryText)
    }

    @Test
    fun `gameplay phrases including take over play route to StartGameplay`() {
        assertTrue(ChatIntentRouter.classifyIntent("العب معي هذه المرحلة") is ChatRouteIntent.StartGameplay)
        assertTrue(ChatIntentRouter.classifyIntent("تولّي اللعب") is ChatRouteIntent.StartGameplay)
        assertTrue(ChatIntentRouter.classifyIntent("تولي اللعب الآن") is ChatRouteIntent.StartGameplay)
    }

    @Test
    fun `computeDueMillis rolls past time to next day`() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 10, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val due = ChatIntentRouter.computeDueMillis(
            delayMinutes = null,
            hour = 9,
            minute = 30,
            nowMillis = nowCal.timeInMillis
        )
        val dueCal = Calendar.getInstance().apply { timeInMillis = due }
        assertEquals(11, dueCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, dueCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, dueCal.get(Calendar.MINUTE))
    }

    @Test
    fun `parseGameActionDecision parses fenced json and fallback`() {
        val fenced = """
            ```json
            {"action":"SWIPE","text":"","x":0.2,"y":0.8,"endX":0.8,"endY":0.3,"reasonAr":"اسحب للشاشة التالية"}
            ```
        """.trimIndent()
        val parsed = ChatIntentRouter.parseGameActionDecision(fenced)
        assertEquals(GameGestureAction.SWIPE, parsed.action)
        assertEquals(0.2f, parsed.x, 0.001f)
        assertEquals(0.8f, parsed.y, 0.001f)
        assertEquals(0.8f, parsed.endX, 0.001f)
        assertEquals(0.3f, parsed.endY, 0.001f)
        assertEquals("اسحب للشاشة التالية", parsed.reasonAr)

        val fallback = ChatIntentRouter.parseGameActionDecision("خطوة غير منسقة")
        assertEquals(GameGestureAction.WAIT, fallback.action)

        val unknownAction = ChatIntentRouter.parseGameActionDecision(
            """{"action":"TELEPORT_MAGIC","x":0.5,"y":0.5,"reasonAr":"محاولة غير صالحة"}"""
        )
        assertEquals(GameGestureAction.WAIT, unknownAction.action)
        assertTrue(unknownAction.reasonAr.contains("غير معروف"))

        val clampedAndQuoted = ChatIntentRouter.parseGameActionDecision(
            """{"action":"TAP_XY","x":2.5,"y":-1.0,"reasonAr":"نص \"مقتبس\" بالداخل"}"""
        )
        assertEquals(GameGestureAction.TAP_XY, clampedAndQuoted.action)
        assertEquals(0.98f, clampedAndQuoted.x, 0.001f)
        assertEquals(0.02f, clampedAndQuoted.y, 0.001f)
        assertEquals("نص \"مقتبس\" بالداخل", clampedAndQuoted.reasonAr)
    }

    @Test
    fun `what did i do routes to ShowActionHistory and bare remind me routes to PromptReminderDetails`() {
        assertEquals(ChatRouteIntent.ShowActionHistory, ChatIntentRouter.classifyIntent("ماذا فعلت؟"))
        assertEquals(ChatRouteIntent.ShowActionHistory, ChatIntentRouter.classifyIntent("اعرض السجل"))
        assertEquals(ChatRouteIntent.PromptReminderDetails, ChatIntentRouter.classifyIntent("ذكّرني"))
        assertEquals(ChatRouteIntent.PromptReminderDetails, ChatIntentRouter.classifyIntent("ذكرني"))
    }

    @Test
    fun `classifies general questions and discussions as Lite and phone usage commands as Flash`() {
        val generalPrompts = listOf(
            "ما هي عاصمة فرنسا؟",
            "اشرح لي نظرية النسبية",
            "اكتب لي قصيدة جميلة عن البحر",
            "كيف أبدأ تعلم البرمجة بنجاح",
            "أخبرني نصيحة لتنظيم وقتي",
            "مرحباً، كيف حالك اليوم؟"
        )
        for (prompt in generalPrompts) {
            assertTrue(
                "Expected ConversationalChat for: $prompt",
                ChatIntentRouter.classifyIntent(prompt, listOf("واتساب", "الساعة")) is ChatRouteIntent.ConversationalChat
            )
            assertEquals(
                "Expected LITE tier for general prompt: $prompt",
                ModelRouteTier.LITE,
                ChatIntentRouter.classifyModelTier(prompt, listOf("واتساب", "الساعة"))
            )
        }

        val phonePrompts = listOf(
            "افتح الإعدادات",
            "شغّل تطبيق الساعة",
            "رتّب تطبيقاتي في مجلدات",
            "أرسل رسالة لوالدي عبر واتساب",
            "تولّي اللعب",
            "ماذا في شاشتي؟",
            "ارفع مستوى الصوت",
            "افتح واتساب"
        )
        for (prompt in phonePrompts) {
            assertEquals(
                "Expected FLASH tier for phone command: $prompt",
                ModelRouteTier.FLASH,
                ChatIntentRouter.classifyModelTier(prompt, listOf("واتساب", "الساعة"))
            )
        }
    }

    @Test
    fun `AgentPolicy permits general questions and phone commands while blocking sensitive actions`() {
        val policy = AgentPolicy()
        assertTrue(policy.evaluateCommand("اعرض ألعابي", emptyList()).allowed)
        assertTrue(policy.evaluateCommand("ماذا في شاشتي؟", emptyList()).allowed)
        assertTrue(policy.evaluateCommand("ماذا فعلت؟", emptyList()).allowed)
        assertTrue(policy.evaluateCommand("ذكّرني", emptyList()).allowed)
        assertTrue(policy.evaluateCommand("ذكّرني بعد 10 دقائق بشرب الماء", emptyList()).allowed)
        assertTrue(policy.evaluateCommand("العب مع الوكيل", emptyList()).allowed)
        assertTrue(policy.evaluateCommand("مرحبا", emptyList()).allowed)
        assertTrue(policy.evaluateCommand("أخبرني عن عاصمة فرنسا", emptyList()).allowed)

        assertNotNull(policy.findForbiddenCategory("تجاوز قفل الشاشة بدون بصمة"))
    }

    @Test
    fun `file management requests route to FileManagement and play game phrase routes to StartGameplay`() {
        assertTrue(ChatIntentRouter.classifyIntent("افحص الملفات والمجلدات الفارغة") is ChatRouteIntent.FileManagement)
        assertTrue(ChatIntentRouter.classifyIntent("تنظيف الملفات المؤقتة") is ChatRouteIntent.FileManagement)
        assertTrue(ChatIntentRouter.classifyIntent("افتح سلة المهملات") is ChatRouteIntent.FileManagement)
        assertTrue(ChatIntentRouter.classifyIntent("العب لعبة") is ChatRouteIntent.StartGameplay)
        assertTrue(ChatIntentRouter.classifyIntent("اعرض تطبيقاتي") is ChatRouteIntent.ShowApps)
    }
}
