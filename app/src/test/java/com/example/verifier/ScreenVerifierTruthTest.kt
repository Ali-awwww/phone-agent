package com.example.verifier

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GeminiModelClient
import com.example.data.ai.GeminiRepository
import com.example.model.AgentPlanStep
import com.example.model.ScreenSnapshot
import com.example.model.StepActionType
import com.example.model.StepVerificationStatus
import com.example.security.AppCheckTokenClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenVerifierTruthTest {

    private fun createVerifier(verificationJson: String = """{"verified":true}"""): ScreenVerifier {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clearStoredTokens() {}
        }
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = "{}"
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = verificationJson
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = "{}"
        }
        return ScreenVerifier(
            context = context,
            geminiRepository = GeminiRepository(appCheckClient = fakeAppCheck, modelClient = fakeClient)
        )
    }

    @Test
    fun `verifyAfterStep never marks VERIFIED_ON_SCREEN when postSnapshot is null`() = runBlocking {
        val verifier = createVerifier("""{"verified":true}""")
        val actionsToCheck = listOf(
            StepActionType.SET_ALARM,
            StepActionType.SET_REMINDER,
            StepActionType.SEND_MESSAGE,
            StepActionType.CHANGE_SETTING,
            StepActionType.OPEN_APP,
            StepActionType.SPEAK_ANSWER
        )

        for (action in actionsToCheck) {
            val step = AgentPlanStep(
                stepNumber = 1,
                titleAr = "اختبار $action",
                descriptionAr = "وصف",
                actionType = action
            )
            val result = verifier.verifyAfterStep(
                step = step,
                executed = true,
                executionMessageAr = "تم الفتح",
                preSnapshot = null,
                postSnapshot = null
            )
            assertFalse("Expected verified=false when snapshot is null for $action", result.verified)
            assertEquals(StepVerificationStatus.UNABLE_TO_VERIFY, result.status)
            assertNotEquals("تم التحقق من الشاشة", result.status.labelAr)
            assertEquals("تعذّر التحقق", result.status.labelAr)
        }
    }

    @Test
    fun `verifyAfterStep returns VERIFICATION_FAILED when Gemini verdict is unparseable or false`() = runBlocking {
        val verifier = createVerifier("not-json-at-all")
        val step = AgentPlanStep(
            stepNumber = 1,
            titleAr = "فتح الساعة",
            descriptionAr = "فتح تطبيق الساعة",
            actionType = StepActionType.OPEN_APP,
            verifiedAppPackage = "com.android.deskclock"
        )
        val snapshot = ScreenSnapshot(
            packageName = "com.android.deskclock",
            visibleTexts = listOf("المنبه"),
            clickableLabels = listOf("إضافة"),
            editableHints = emptyList(),
            hasSensitiveSecurityField = false
        )

        val result = verifier.verifyAfterStep(
            step = step,
            executed = true,
            executionMessageAr = "تم فتح الساعة",
            preSnapshot = null,
            postSnapshot = snapshot
        )

        assertFalse(result.verified)
        assertEquals(StepVerificationStatus.VERIFICATION_FAILED, result.status)
        assertTrue(result.explanationAr.isNotBlank())
    }
}
