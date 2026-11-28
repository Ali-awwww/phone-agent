package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.CommandsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionLogAndTruthReportTest {

    private lateinit var db: PhoneAgentDatabase
    private lateinit var actionLogDao: ActionLogDao
    private lateinit var chatMessageDao: ChatMessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhoneAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        actionLogDao = db.actionLogDao()
        chatMessageDao = db.chatMessageDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `F22 actionLogDao excludes MODEL_ROUTE telemetry and paginates by limit`() = runBlocking {
        actionLogDao.insertLog(
            ActionLogEntity(
                title = "تبديل النموذج: gemini-3.8-flash",
                details = "telemetry",
                status = ActionStatus.SUCCESS.name,
                actionType = "MODEL_ROUTE_FLASH",
                timestamp = 1000L
            )
        )
        actionLogDao.insertLog(
            ActionLogEntity(
                title = "تبديل النموذج: gemini-3.5-flash-lite",
                details = "telemetry",
                status = ActionStatus.SUCCESS.name,
                actionType = "MODEL_ROUTE_LITE",
                timestamp = 2000L
            )
        )
        actionLogDao.insertLog(
            ActionLogEntity(
                title = "فتح تطبيق الساعة",
                details = "ok",
                status = ActionStatus.SUCCESS.name,
                actionType = "OPEN_APP",
                timestamp = 3000L
            )
        )
        actionLogDao.insertLog(
            ActionLogEntity(
                title = "إلغاء مهمة",
                details = "ok",
                status = ActionStatus.UNDONE.name,
                actionType = "CHAT_CANCEL_TASK",
                timestamp = 4000L
            )
        )
        actionLogDao.insertLog(
            ActionLogEntity(
                title = "تنظيف مجلدات فارغة",
                details = "ok",
                status = ActionStatus.SUCCESS.name,
                actionType = "FILE_TRASH_BATCH",
                timestamp = 5000L
            )
        )

        val all = actionLogDao.observeAllLogs().first()
        assertEquals(3, all.size)
        assertTrue(all.none { it.actionType.startsWith("MODEL_ROUTE_") })
        assertEquals(3, actionLogDao.observeLogsCount().first())

        val page1 = actionLogDao.observePagedLogs(2).first()
        assertEquals(2, page1.size)
        assertEquals("FILE_TRASH_BATCH", page1[0].actionType)
        assertEquals("CHAT_CANCEL_TASK", page1[1].actionType)
    }

    @Test
    fun `F22 chatMessageDao paginates latest messages while preserving chronological ASC order`() = runBlocking {
        for (i in 1..5) {
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    sender = ChatSender.USER.name,
                    text = "رسالة $i",
                    createdAt = i * 1000L
                )
            )
        }

        assertEquals(5, chatMessageDao.observeMessagesCount().first())

        val recentTwo = chatMessageDao.observeRecentMessages(2).first()
        assertEquals(2, recentTwo.size)
        assertEquals("رسالة 4", recentTwo[0].text)
        assertEquals("رسالة 5", recentTwo[1].text)
    }

    @Test
    fun `S2-5b buildTruthReport renders all 4 structured sections cleanly and returns null when empty`() {
        assertNull(CommandsViewModel.buildTruthReport(emptyList(), emptyList(), emptyList(), emptyList()))

        val report = CommandsViewModel.buildTruthReport(
            succeeded = listOf("الخطوة 1: فتح الساعة"),
            unverified = listOf("الخطوة 2: تعذّر التحقق من الشاشة"),
            failed = listOf("الخطوة 3: العنصر غير ظاهر"),
            rolledBack = listOf("الخطوة 3: تم التراجع التلقائي الآمن")
        )!!

        assertTrue(report.contains("✅ ما نجح فعلياً:"))
        assertTrue(report.contains("⚠️ ما تعذّر التحقق منه:"))
        assertTrue(report.contains("❌ ما فشل ولماذا:"))
        assertTrue(report.contains("↩️ ما تم التراجع عنه:"))

        val partial = CommandsViewModel.buildTruthReport(
            succeeded = listOf("تم النقل للسلة")
        )!!
        assertTrue(partial.contains("✅ ما نجح فعلياً:"))
        assertFalse(partial.contains("⚠️ ما تعذّر التحقق منه:"))
        assertFalse(partial.contains("❌ ما فشل ولماذا:"))
        assertFalse(partial.contains("↩️ ما تم التراجع عنه:"))
    }
}
