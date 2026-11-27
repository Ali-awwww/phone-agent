package com.example.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppPolicyTest {

    private val agentPkg = "com.aistudio.phoneagent.wakeel"

    @Test
    fun `hides floating overlay when foreground app is the agent itself`() {
        assertFalse(ForegroundAppPolicy.shouldShowFloatingOverlay(agentPkg, agentPkg))
    }

    @Test
    fun `hides floating overlay when foreground package is blank`() {
        assertFalse(ForegroundAppPolicy.shouldShowFloatingOverlay("", agentPkg))
        assertFalse(ForegroundAppPolicy.shouldShowFloatingOverlay("   ", agentPkg))
    }

    @Test
    fun `shows floating overlay when foreground package is an external app or game`() {
        assertTrue(ForegroundAppPolicy.shouldShowFloatingOverlay("com.tencent.ig", agentPkg))
        assertTrue(ForegroundAppPolicy.shouldShowFloatingOverlay("com.whatsapp", agentPkg))
        assertTrue(ForegroundAppPolicy.shouldShowFloatingOverlay("com.android.settings", agentPkg))
    }
}
