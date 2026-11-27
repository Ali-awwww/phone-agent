package com.example.data.ai

import com.example.security.AppCheckTokenClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniChatDirectiveParserTest {

    private fun createRepo(responseText: String = "{}"): GeminiRepository {
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clearStoredTokens() {}
        }
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = "{}"
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = responseText
        }
        return GeminiRepository(appCheckClient = fakeAppCheck, modelClient = fakeClient)
    }

    @Test
    fun `parses valid JSON mini chat directive with clamped coordinates and one-line reply`() {
        val repo = createRepo()
        val raw = """
            ```json
            {
              "shouldAct": true,
              "action": "tap_text",
              "text": "ابدأ",
              "inputText": "",
              "x": 1.5,
              "y": -0.2,
              "endX": 0.5,
              "endY": 0.3,
              "continuePlaying": true,
              "replyOneLineAr": "ضغطت على «ابدأ»\nوسأتابع اللعب."
            }
            ```
        """.trimIndent()

        val parsed = repo.parseMiniChatDirectiveJson(raw)
        assertTrue(parsed.shouldAct)
        assertEquals("TAP_TEXT", parsed.action)
        assertEquals("ابدأ", parsed.targetText)
        assertEquals(0.98f, parsed.x, 0.001f)
        assertEquals(0.02f, parsed.y, 0.001f)
        assertTrue(parsed.continuePlaying)
        assertFalse(parsed.replyOneLineAr.contains("\n"))
    }

    @Test
    fun `falls back gracefully when response is plain Arabic text`() {
        val repo = createRepo()
        val parsed = repo.parseMiniChatDirectiveJson("هذا شرح سريع لما في الشاشة.")
        assertFalse(parsed.shouldAct)
        assertEquals("NONE", parsed.action)
        assertEquals("هذا شرح سريع لما في الشاشة.", parsed.replyOneLineAr)
    }

    @Test
    fun `analyzeMiniChatDirective includes conversation history and screen context`() = runBlocking {
        var capturedPrompt = ""
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clearStoredTokens() {}
        }
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = "{}"
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String): String {
                capturedPrompt = prompt
                return """{"shouldAct":true,"action":"SCROLL","text":"","inputText":"","x":0.5,"y":0.5,"endX":0.5,"endY":0.2,"continuePlaying":true,"replyOneLineAr":"مرّرت الشاشة للأسفل الآن."}"""
            }
        }
        val repo = GeminiRepository(appCheckClient = fakeAppCheck, modelClient = fakeClient)

        val meta = repo.analyzeMiniChatDirective(
            userCommand = "انزل للأسفل",
            screenSummary = "تطبيق المتجر - قائمة الألعاب",
            history = "المستخدم: افتح المتجر\nالمساعد: تم فتحه.",
            wasGameplayRunning = true
        )

        assertTrue(capturedPrompt.contains("افتح المتجر"))
        assertTrue(capturedPrompt.contains("تطبيق المتجر"))
        assertTrue(meta.value.shouldAct)
        assertEquals("SCROLL", meta.value.action)
        assertEquals("مرّرت الشاشة للأسفل الآن.", meta.value.replyOneLineAr)
    }
}
