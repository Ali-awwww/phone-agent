package com.example.data.ai

import com.example.model.AgentPlanStep
import com.example.model.StepActionType
import com.example.model.StepVerificationStatus
import com.example.security.AppCheckTokenClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiPhase2TruthTest {

    private fun createRepo(
        client: GeminiModelClient,
        overloadDelays: List<Long> = listOf(10L, 10L),
        totalChainTimeoutMs: Long = TOTAL_GEMINI_COMMAND_TIMEOUT_MS
    ): GeminiRepository {
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clearStoredTokens() {}
        }
        return GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = client,
            overloadRetryDelaysMs = overloadDelays,
            totalChainTimeoutMs = totalChainTimeoutMs
        )
    }

    private fun dummyClient(verificationReply: String = "{}"): GeminiModelClient = object : GeminiModelClient {
        override suspend fun generatePlanJson(prompt: String) = "{}"
        override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
        override suspend fun generateVerificationJson(prompt: String) = verificationReply
        override suspend fun generateCategorizationJson(prompt: String) = "{}"
        override suspend fun generateGeneralArabicText(prompt: String) = "{}"
    }

    @Test
    fun `F02 parseVerificationResponse fails by default on blank, malformed, or missing verified key`() {
        val repo = createRepo(dummyClient())

        val blankRes = repo.parseVerificationResponse("")
        assertFalse(blankRes.verified)
        assertEquals(StepVerificationStatus.VERIFICATION_FAILED, blankRes.status)

        val badJson = repo.parseVerificationResponse("تم التنفيذ بنجاح")
        assertFalse(badJson.verified)
        assertEquals(StepVerificationStatus.VERIFICATION_FAILED, badJson.status)

        val missingKey = repo.parseVerificationResponse("""{"explanationAr":"لا يوجد حقل التحقق"}""")
        assertFalse(missingKey.verified)
        assertEquals(StepVerificationStatus.VERIFICATION_FAILED, missingKey.status)

        val explicitFalse = repo.parseVerificationResponse("""{"verified":false,"explanationAr":"العنصر غير ظاهر"}""")
        assertFalse(explicitFalse.verified)
        assertEquals(StepVerificationStatus.VERIFICATION_FAILED, explicitFalse.status)

        val explicitTrue = repo.parseVerificationResponse("""{"verified":true,"explanationAr":"ظهر التغيير على الشاشة"}""")
        assertTrue(explicitTrue.verified)
        assertEquals(StepVerificationStatus.VERIFIED_ON_SCREEN, explicitTrue.status)
    }

    @Test
    fun `F03 verifyScreenAfterStep with null snapshot returns UNABLE_TO_VERIFY and verified=false`() = runBlocking {
        val repo = createRepo(dummyClient("""{"verified":true}"""))
        val step = AgentPlanStep(
            stepNumber = 1,
            titleAr = "ضبط منبه",
            descriptionAr = "فتح المنبه",
            actionType = StepActionType.SET_ALARM
        )
        val result = repo.verifyScreenAfterStep(step, snapshot = null)
        assertFalse(result.verified)
        assertEquals(StepVerificationStatus.UNABLE_TO_VERIFY, result.status)
    }

    @Test
    fun `F17 StepActionType fromString and parsePlanJson reject unknown action types instead of falling back to SPEAK_ANSWER`() {
        assertEquals(StepActionType.OPEN_APP, StepActionType.fromString("open_app"))

        try {
            StepActionType.fromString("UNKNOWN_MAGIC_ACTION")
            fail("Expected IllegalArgumentException for unknown actionType")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        try {
            StepActionType.fromString("   ")
            fail("Expected IllegalArgumentException for blank actionType")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        val repo = createRepo(dummyClient())
        val planJsonWithUnknownAction = """
            {
              "understandingSummaryAr": "تجربة",
              "spokenReplyAr": "حسناً",
              "steps": [
                {
                  "stepNumber": 1,
                  "titleAr": "خطوة غير صالحة",
                  "descriptionAr": "وصف",
                  "actionType": "NON_EXISTENT_TYPE"
                }
              ]
            }
        """.trimIndent()
        try {
            repo.parsePlanJson("أمر اختبار", JSONObject(planJsonWithUnknownAction))
            fail("Expected parsePlanJson to reject unknown actionType")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `F16 enforces a single overall timeout across the entire fallback model chain`() = runBlocking {
        val modelsStarted = mutableListOf<String>()
        val slowClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String): String {
                modelsStarted.add("primary")
                delay(180L)
                throw RuntimeException("503 UNAVAILABLE")
            }

            override suspend fun generatePlanJsonFallback(prompt: String): String {
                modelsStarted.add("lite")
                delay(180L)
                throw RuntimeException("503 UNAVAILABLE")
            }

            override suspend fun generatePlanJsonThird(prompt: String): String {
                modelsStarted.add("third")
                delay(180L)
                return "{}"
            }

            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = "{}"
        }

        val repo = createRepo(
            client = slowClient,
            overloadDelays = listOf(10L, 10L),
            totalChainTimeoutMs = 260L
        )

        val startMs = System.currentTimeMillis()
        try {
            repo.generateCommandPlan("افتح الإعدادات", listOf("الإعدادات"), "الشاشة")
            fail("Expected TimeoutCancellationException across combined fallback chain")
        } catch (_: TimeoutCancellationException) {
            val elapsed = System.currentTimeMillis() - startMs
            assertTrue("Elapsed should be near single chain cap (260ms), was ${elapsed}ms", elapsed < 520L)
            assertFalse("Third model should not have completed within 260ms cap", modelsStarted.size > 2)
        }
    }

    @Test
    fun `F16 external coroutine cancellation cancels immediately without invoking fallback models`() = runBlocking {
        val modelsCalled = mutableListOf<String>()
        val hangingClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String): String {
                modelsCalled.add("primary")
                delay(5_000L)
                return "{}"
            }

            override suspend fun generatePlanJsonFallback(prompt: String): String {
                modelsCalled.add("lite")
                return "{}"
            }

            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = "{}"
        }

        val repo = createRepo(client = hangingClient, totalChainTimeoutMs = 10_000L)
        val job = async {
            repo.generateCommandPlan("افتح الخرائط", emptyList(), "")
        }
        delay(50L)
        job.cancel(CancellationException("User pressed stop"))

        try {
            job.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            assertEquals(listOf("primary"), modelsCalled)
        }
    }
}
