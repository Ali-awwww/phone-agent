package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryTest {

    @Test
    fun testFormatHistoryLimit() {
        val turns = (1..35).map { i ->
            ChatTurn(if (i % 2 == 1) "user" else "model", "رسالة رقم $i")
        }
        val formatted = ConversationMemory.formatHistory(turns, maxTurns = 5)
        assertTrue(formatted.contains("رسالة رقم 26"))
        assertFalse(formatted.contains("رسالة رقم 1"))
    }

    @Test
    fun testIsInstalledAppQuestion() {
        assertTrue(ConversationMemory.isInstalledAppQuestion("هو لدي الان؟"))
        assertTrue(ConversationMemory.isInstalledAppQuestion("هل هي مثبتة على جهازي؟"))
        assertTrue(ConversationMemory.isInstalledAppQuestion("هل لدي تطبيق واتساب؟"))
        assertFalse(ConversationMemory.isInstalledAppQuestion("ما حالة الطقس اليوم؟"))
    }

    @Test
    fun testFindAppInHistoryMatch() {
        val history = "المستخدم: هل لدي لعبة ببجي؟\nالمساعد: دعني أتحقق."
        val installed = listOf("WhatsApp", "PUBG MOBILE", "Telegram")
        val found = ConversationMemory.findAppInHistory(history, installed)
        assertEquals("PUBG MOBILE", found)
    }
}
