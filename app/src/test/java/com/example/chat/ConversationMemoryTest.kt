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

    @Test
    fun testOldestFirstTruncationPreservesNewestMessages() {
        val turns = listOf(
            ChatTurn("user", "أقدم رسالة أولى يجب إسقاطها عند تجاوز الحد الأقصى للأحرف"),
            ChatTurn("model", "رد قديم جداً يجب إسقاطه عند الاقتطاع"),
            ChatTurn("user", "سؤال أحدث نسبياً"),
            ChatTurn("model", "أحدث رد يجب الاحتفاظ به دائماً")
        )
        val formatted = ConversationMemory.formatHistory(
            turns = turns,
            maxTurns = 10,
            maxCharsPerMessage = 200,
            maxTotalChars = 75
        )
        assertTrue(formatted.contains("أحدث رد يجب الاحتفاظ به دائماً"))
        assertFalse(formatted.contains("أقدم رسالة أولى"))
    }

    @Test
    fun testFormatHistoryWithSummariesAndDeterministicSummary() {
        val turns = listOf(
            ChatTurn("user", "رتّب تطبيقاتي"),
            ChatTurn("model", "تم ترتيب التطبيقات في 3 مجلدات.")
        )
        val summary = ConversationMemory.buildDeterministicTurnSummary(turns)
        assertTrue(summary.contains("رتّب تطبيقاتي"))

        val combined = ConversationMemory.formatHistoryWithSummaries(
            summaries = listOf("ملخص سابق: المستخدم يفضل المنبه الصباحي"),
            turns = turns
        )
        assertTrue(combined.contains("ملخص الحوار السابق المحفوظ"))
        assertTrue(combined.contains("المستخدم يفضل المنبه الصباحي"))
        assertTrue(combined.contains("رتّب تطبيقاتي"))
    }
}
