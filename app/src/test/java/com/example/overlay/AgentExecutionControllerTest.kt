package com.example.overlay

import com.example.policy.AgentPromptRules
import com.example.ui.components.ChatLiveExecutionActions
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
    fun `in-bubble stop and cancel handlers terminate active execution immediately`() = runBlocking {
        val controller = AgentExecutionController()
        controller.setPaused(true)

        val job1 = async {
            controller.awaitIfPaused()
        }
        var stoppedCount = 0
        var cancelledCount = 0
        val actions = ChatLiveExecutionActions(
            onStop = {
                stoppedCount++
                job1.cancel(CancellationException("stopped from chat bubble"))
                controller.reset()
            },
            onCancel = {
                cancelledCount++
                controller.reset()
            }
        )

        actions.onStop()
        try {
            job1.await()
        } catch (_: CancellationException) {
            // Expected cancellation on stop
        }
        assertEquals(1, stoppedCount)
        assertFalse(controller.isPaused.value)

        actions.onCancel()
        assertEquals(1, cancelledCount)
        assertFalse(controller.isPaused.value)
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

    @Test
    fun `handover pauses and marks handedOver then resume clears both without losing state`() = runBlocking {
        val controller = AgentExecutionController()
        controller.setHandedOver(true)

        assertTrue(controller.isHandedOver.value)
        assertTrue(controller.isPaused.value)

        var resumed = false
        val job = async {
            controller.awaitIfPaused()
            resumed = true
        }

        delay(70L)
        assertFalse("Execution must remain paused during user handover", resumed)

        controller.setHandedOver(false)
        job.await()
        assertTrue("Execution must resume once user taps Resume Agent", resumed)
        assertFalse(controller.isHandedOver.value)
        assertFalse(controller.isPaused.value)
    }
}
