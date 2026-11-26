package com.example.overlay

import com.example.policy.AgentPromptRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionControllerTest {

    @Test
    fun `pause actually suspends execution until resumed`() = runBlocking {
        val controller = AgentExecutionController()
        controller.setPaused(true)

        var reachedAfterPause = false
        val job = async {
            controller.awaitIfPaused()
            reachedAfterPause = true
        }

        delay(80L)
        assertFalse("Coroutine must remain suspended while paused", reachedAfterPause)

        controller.setPaused(false)
        job.await()
        assertTrue("Coroutine must resume cleanly after unpausing", reachedAfterPause)
    }

    @Test
    fun `cancelling paused job immediately aborts execution`() = runBlocking {
        val controller = AgentExecutionController()
        controller.setPaused(true)

        var reachedAfterPause = false
        val job = async {
            controller.awaitIfPaused()
            reachedAfterPause = true
        }

        delay(60L)
        job.cancel(CancellationException("user stopped"))
        try {
            job.await()
        } catch (_: CancellationException) {
            // متوقع عند الإيقاف الفوري أو «أكمل بنفسي»
        }

        assertFalse(reachedAfterPause)
    }

    @Test
    fun `AgentPromptRules contains all six required behavioral instructions`() {
        val rules = AgentPromptRules.SYSTEM_BEHAVIOR_RULES_AR
        assertTrue(rules.contains("العربية"))
        assertTrue(rules.contains("الشاشة الحقيقية"))
        assertTrue(rules.contains("فعل حساس"))
        assertTrue(rules.contains("خطوة بخطوة"))
        assertTrue(rules.contains("كالإنسان"))
        assertTrue(rules.contains("لست لحظياً"))
        assertTrue(rules.contains("غير متأكد"))
        assertTrue(rules.contains("التوضيح"))

        val enriched = AgentPromptRules.enrichGameContextSummary("عنصر: ابدأ")
        assertTrue(enriched.contains("عنصر: ابدأ"))
        assertTrue(enriched.contains("غير متأكد"))
        assertEquals(2, AgentPromptRules.enrichScreenReadLines("شاشة").size)
    }
}
