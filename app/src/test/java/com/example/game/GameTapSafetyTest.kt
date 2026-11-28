package com.example.game

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GeminiModelClient
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogDao
import com.example.data.local.ActionLogEntity
import com.example.data.local.ActionLogRepository
import com.example.model.ScreenSnapshot
import com.example.security.AppCheckTokenClient
import com.example.service.GameElementBound
import com.example.service.GameScreenObservation
import com.example.service.ScreenStateBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameTapSafetyTest {

    private fun createEngine(): GeneralGameEngine {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clearStoredTokens() {}
        }
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = "{}"
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = "{}"
        }
        val fakeDao = object : ActionLogDao {
            override fun observeAllLogs(): Flow<List<ActionLogEntity>> = flowOf(emptyList())
            override fun observePagedLogs(limit: Int): Flow<List<ActionLogEntity>> = flowOf(emptyList())
            override fun observeLogsCount(): Flow<Int> = flowOf(0)
            override suspend fun insertLog(log: ActionLogEntity): Long = 1L
            override suspend fun updateStatus(id: Long, status: String, canUndo: Boolean) {}
            override suspend fun markUndone(id: Long, status: String) {}
            override suspend fun getLogsSince(sinceTimestamp: Long): List<ActionLogEntity> = emptyList()
            override suspend fun clearAllLogs() {}
        }
        return GeneralGameEngine(
            context = context,
            geminiRepository = GeminiRepository(fakeAppCheck, fakeClient),
            actionLogRepository = ActionLogRepository(fakeDao)
        )
    }

    @Test
    fun `isNearEmptyTree returns true for Unity-like blank tree and false for real UI tree`() {
        val emptyObs = GameScreenObservation(
            snapshot = ScreenSnapshot(
                packageName = "com.unity.game",
                visibleTexts = emptyList(),
                clickableLabels = emptyList(),
                editableHints = emptyList(),
                hasSensitiveSecurityField = false
            ),
            elements = listOf(
                GameElementBound(
                    label = "عنصر تفاعلي",
                    centerXRatio = 0.5f,
                    centerYRatio = 0.5f,
                    isClickable = true
                )
            )
        )
        assertTrue(emptyObs.isNearEmptyTree())

        val richObs = GameScreenObservation(
            snapshot = ScreenSnapshot(
                packageName = "com.example.menu",
                visibleTexts = listOf("ابدأ اللعب"),
                clickableLabels = listOf("ابدأ اللعب"),
                editableHints = emptyList(),
                hasSensitiveSecurityField = false
            ),
            elements = listOf(
                GameElementBound(
                    label = "ابدأ اللعب",
                    centerXRatio = 0.5f,
                    centerYRatio = 0.72f,
                    isClickable = true
                )
            )
        )
        assertFalse(richObs.isNearEmptyTree())
    }

    @Test
    fun `resolveObservedCoordinatesForTap never falls back blindly to 0_5_0_5`() {
        val engine = createEngine()

        val noObsCoords = engine.resolveObservedCoordinatesForTap(
            observation = null,
            targetText = "ابدأ",
            requestedX = 0.5f,
            requestedY = 0.5f
        )
        assertNull(noObsCoords)

        val obsWithButton = GameScreenObservation(
            snapshot = ScreenSnapshot(
                packageName = "com.example.app",
                visibleTexts = listOf("إعدادات"),
                clickableLabels = listOf("إعدادات"),
                editableHints = emptyList(),
                hasSensitiveSecurityField = false
            ),
            elements = listOf(
                GameElementBound(
                    label = "إعدادات",
                    centerXRatio = 0.85f,
                    centerYRatio = 0.14f,
                    isClickable = true
                )
            )
        )

        val unmatched = engine.resolveObservedCoordinatesForTap(
            observation = obsWithButton,
            targetText = "ابدأ",
            requestedX = 0.5f,
            requestedY = 0.5f
        )
        assertNull(unmatched)

        val matchedByLabel = engine.resolveObservedCoordinatesForTap(
            observation = obsWithButton,
            targetText = "إعدادات",
            requestedX = 0.5f,
            requestedY = 0.5f
        )
        assertEquals(0.85f to 0.14f, matchedByLabel)
    }

    @Test
    fun `ScreenStateBridge tracks taskActive and screenReadActive states independently`() {
        ScreenStateBridge.setTaskActive(false)
        ScreenStateBridge.setScreenReadActive(false)
        assertFalse(ScreenStateBridge.isOperationActive())

        ScreenStateBridge.setTaskActive(true)
        assertTrue(ScreenStateBridge.isOperationActive())

        ScreenStateBridge.setTaskActive(false)
        assertFalse(ScreenStateBridge.isOperationActive())

        ScreenStateBridge.setScreenReadActive(true)
        assertTrue(ScreenStateBridge.isOperationActive())

        ScreenStateBridge.setScreenReadActive(false)
        assertFalse(ScreenStateBridge.isOperationActive())
    }
}
