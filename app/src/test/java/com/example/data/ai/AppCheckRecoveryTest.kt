package com.example.data.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.example.executor.ModelRouteTier
import com.example.security.APP_CHECK_PREFS_NAME
import com.example.security.APP_CHECK_STORE_NAME
import com.example.security.AppCheckTokenClient
import com.example.security.AppCheckVerificationException
import com.example.security.DEBUG_SECRET_KEY
import com.example.security.DEBUG_STORE_NAME
import com.example.security.DisconnectionCategory
import com.example.security.ERROR_APP_CHECK_EXHAUSTED_AR
import com.example.security.classifyModelError
import com.example.security.clearStoredAppCheckTokens
import com.example.security.getEffectiveDebugSecret
import com.example.security.isAppCheckTokenError
import com.example.security.isModelOverloadError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppCheckRecoveryTest {

    @Test
    fun `awaits token before Gemini call then clears cache forces refresh and succeeds on second attempt`() = runBlocking {
        val events = mutableListOf<String>()
        var tokenFetchedBeforeGemini = false

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String {
                // Simulate asynchronous token retrieval that must be awaited before Gemini call
                delay(15L)
                events.add("getToken(forceRefresh=$forceRefresh)")
                tokenFetchedBeforeGemini = true
                return if (forceRefresh) "fresh-play-integrity-token" else "stale-cached-token"
            }

            override suspend fun clearStoredTokens() {
                events.add("clearStoredTokens")
            }
        }

        var geminiCalls = 0
        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = ""
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String): String {
                assertTrue("App Check token must be awaited before sending Gemini request", tokenFetchedBeforeGemini)
                geminiCalls++
                events.add("geminiCall($geminiCalls)")
                if (geminiCalls == 1) {
                    throw IllegalStateException("Firebase App Check token is invalid.")
                }
                return "ملخص التطبيق: تطبيق الساعة لضبط المنبهات."
            }
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient,
            maxAppCheckAttempts = 3,
            retryDelayMs = 25L,
            delayFn = { ms -> events.add("delay($ms)") }
        )

        val summary = repository.summarizeAppScreens(
            appName = "الساعة",
            packageName = "com.android.deskclock",
            capturedScreenTexts = listOf("منبه الساعة 7:00")
        )

        assertEquals("ملخص التطبيق: تطبيق الساعة لضبط المنبهات.", summary)
        assertEquals(2, geminiCalls)
        assertEquals(
            listOf(
                "getToken(forceRefresh=false)",
                "geminiCall(1)",
                "clearStoredTokens",
                "delay(25)",
                "getToken(forceRefresh=true)",
                "geminiCall(2)"
            ),
            events
        )
    }

    @Test
    fun `exhausts 3 attempts when Gemini keeps rejecting token and throws clear Arabic error`() = runBlocking {
        val events = mutableListOf<String>()

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String {
                events.add("getToken(forceRefresh=$forceRefresh)")
                return "non-empty-unaccepted-token"
            }

            override suspend fun clearStoredTokens() {
                events.add("clearStoredTokens")
            }
        }

        var geminiAttempts = 0
        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String): String {
                geminiAttempts++
                events.add("geminiCall($geminiAttempts)")
                throw RuntimeException("403 Firebase App Check token is invalid")
            }

            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = ""
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient,
            maxAppCheckAttempts = 3,
            retryDelayMs = 20L,
            delayFn = { ms -> events.add("delay($ms)") }
        )

        try {
            repository.generateCommandPlan(
                userCommand = "افتح الإعدادات",
                installedAppNames = listOf("الإعدادات"),
                currentScreenSummary = "الشاشة الرئيسية"
            )
            fail("Expected AppCheckVerificationException when all 3 attempts fail")
        } catch (e: AppCheckVerificationException) {
            assertEquals(ERROR_APP_CHECK_EXHAUSTED_AR, e.message)
            assertTrue(e.message!!.contains("Firebase App Check"))
            assertTrue(e.message!!.contains("3 محاولات"))
        }

        assertEquals(3, geminiAttempts)
        assertEquals(
            listOf(
                "getToken(forceRefresh=false)",
                "geminiCall(1)",
                "clearStoredTokens",
                "delay(20)",
                "getToken(forceRefresh=true)",
                "geminiCall(2)",
                "clearStoredTokens",
                "delay(40)",
                "getToken(forceRefresh=true)",
                "geminiCall(3)"
            ),
            events
        )
    }

    @Test
    fun `recovers when initial getToken fails before Gemini call`() = runBlocking {
        val events = mutableListOf<String>()
        var tokenCalls = 0

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String {
                tokenCalls++
                events.add("getToken($tokenCalls,force=$forceRefresh)")
                if (tokenCalls == 1) {
                    throw IllegalStateException("Play Integrity attestation failed")
                }
                return "recovered-token"
            }

            override suspend fun clearStoredTokens() {
                events.add("clearStoredTokens")
            }
        }

        var geminiCalls = 0
        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = ""
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String): String {
                geminiCalls++
                events.add("geminiCall($geminiCalls)")
                return "ملخص التطبيق بالعربية"
            }
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient,
            maxAppCheckAttempts = 3,
            retryDelayMs = 15L,
            delayFn = { ms -> events.add("delay($ms)") }
        )

        val summary = repository.summarizeAppScreens(
            appName = "الساعة",
            packageName = "com.android.deskclock",
            capturedScreenTexts = listOf("منبه")
        )

        assertEquals("ملخص التطبيق بالعربية", summary)
        assertEquals(
            listOf(
                "getToken(1,force=false)",
                "clearStoredTokens",
                "delay(15)",
                "getToken(2,force=true)",
                "geminiCall(1)"
            ),
            events
        )
    }

    @Test
    fun `does not clear cache or retry when Gemini fails with non-AppCheck error`() = runBlocking {
        var clearCalls = 0
        var refreshCalls = 0
        var geminiCalls = 0

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String {
                if (forceRefresh) refreshCalls++
                return "valid-token"
            }

            override suspend fun clearStoredTokens() {
                clearCalls++
            }
        }

        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = ""
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String): String {
                geminiCalls++
                throw IllegalArgumentException("Invalid request payload")
            }
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient,
            maxAppCheckAttempts = 3,
            retryDelayMs = 10L
        )

        try {
            repository.conversationalReply("مرحبا", "سياق")
            fail("Expected IllegalArgumentException to be rethrown")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid request payload", e.message)
        }

        assertEquals(1, geminiCalls)
        assertEquals(0, clearCalls)
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `rethrows CancellationException without swallowing or retrying`() = runBlocking {
        var clearCalls = 0
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String = "valid-token"
            override suspend fun clearStoredTokens() {
                clearCalls++
            }
        }
        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String): String {
                throw CancellationException("Cancelled by caller")
            }

            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = ""
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient
        )

        try {
            repository.generateCommandPlan("افتح الخرائط", emptyList(), "")
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected
        }
        assertEquals(0, clearCalls)
    }

    @Test
    fun `clearStoredAppCheckTokens clears debug store and all App Check preferences synchronously`() {
        val tempDir = Files.createTempDirectory("appcheck_prefs_test").toFile()
        try {
            val sharedPrefsDir = File(tempDir, "shared_prefs").apply { mkdirs() }
            File(sharedPrefsDir, "com.google.firebase.appcheck.store.DEFAULT_KEY.xml").writeText("<map/>")
            File(sharedPrefsDir, "com.google.firebase.appcheck.debug.store.DEFAULT_KEY.xml").writeText("<map/>")
            File(sharedPrefsDir, "unrelated_prefs.xml").writeText("<map/>")

            val stores = mutableMapOf<String, InMemorySharedPreferences>()
            fun store(name: String) = stores.getOrPut(name) { InMemorySharedPreferences() }

            store(DEBUG_STORE_NAME).putDirect(DEBUG_SECRET_KEY, "stale-debug-secret")
            store(APP_CHECK_STORE_NAME).putDirect("com.google.firebase.appcheck.APP_CHECK_TOKEN", "stale-token")
            store(APP_CHECK_PREFS_NAME).putDirect("play_integrity_token_refreshed_v1", true)
            store("com.google.firebase.appcheck.store.DEFAULT_KEY").putDirect("token", "stale-default-token")
            store("com.google.firebase.appcheck.debug.store.DEFAULT_KEY").putDirect(DEBUG_SECRET_KEY, "stale-secret")
            store("unrelated_prefs").putDirect("keep", "yes")

            val fakeAppInfo = ApplicationInfo().apply {
                dataDir = tempDir.absolutePath
            }
            val fakeContext = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
                override fun getApplicationInfo(): ApplicationInfo = fakeAppInfo
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = store(name)
            }

            val clearedCount = clearStoredAppCheckTokens(fakeContext)

            assertTrue(clearedCount >= 5)
            assertTrue(store(DEBUG_STORE_NAME).all.isEmpty())
            assertTrue(store(DEBUG_STORE_NAME).commitCalled)
            assertTrue(store(APP_CHECK_STORE_NAME).all.isEmpty())
            assertTrue(store(APP_CHECK_STORE_NAME).commitCalled)
            assertTrue(store(APP_CHECK_PREFS_NAME).all.isEmpty())
            assertTrue(store(APP_CHECK_PREFS_NAME).commitCalled)
            assertTrue(store("com.google.firebase.appcheck.store.DEFAULT_KEY").all.isEmpty())
            assertTrue(store("com.google.firebase.appcheck.debug.store.DEFAULT_KEY").all.isEmpty())
            assertEquals("yes", store("unrelated_prefs").getString("keep", null))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `isAppCheckTokenError detects direct, nested, and Play Integrity errors safely`() {
        assertTrue(isAppCheckTokenError(Exception("Firebase App Check token is invalid.")))
        assertTrue(isAppCheckTokenError(Exception("HTTP 403", RuntimeException("app-check attestation failed"))))
        assertTrue(isAppCheckTokenError(Exception("Play Integrity check failed")))
        assertFalse(isAppCheckTokenError(Exception("Socket timeout")))
        assertFalse(isAppCheckTokenError(null))

        // Cyclic cause chain should not hang
        val e1 = RuntimeException("unrelated 1")
        val e2 = RuntimeException("unrelated 2", e1)
        e1.initCause(e2)
        assertFalse(isAppCheckTokenError(e2))
    }

    @Test
    fun `pingModelTest succeeds and returns latency details when model responds`() = runBlocking {
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String = "valid-token"
            override suspend fun clearStoredTokens() {}
        }
        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = ""
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = "متصل"
            override suspend fun pingModelTest() = "متصل"
        }
        val repository = GeminiRepository(appCheckClient = fakeAppCheck, modelClient = fakeModelClient)
        val (ok, details) = repository.pingModelTest()
        assertTrue(ok)
        assertTrue(details.contains("متصل بنجاح"))
    }

    @Test
    fun `classifyModelError distinguishes App Check, network, and model errors`() {
        val appCheckErr = classifyModelError(RuntimeException("Firebase App Check token is invalid"))
        assertEquals(DisconnectionCategory.APP_CHECK_INVALID, appCheckErr.category)
        assertTrue(appCheckErr.explanationAr.contains("Firebase App Check"))

        val netErr = classifyModelError(java.net.UnknownHostException("Unable to resolve host"))
        assertEquals(DisconnectionCategory.NETWORK_ERROR, netErr.category)
        assertTrue(netErr.explanationAr.contains("شبكة الإنترنت"))

        val modelErr = classifyModelError(RuntimeException("RESOURCE_EXHAUSTED: quota exceeded"))
        assertEquals(DisconnectionCategory.MODEL_ERROR, modelErr.category)
        assertTrue(modelErr.explanationAr.contains("نموذج"))

        val busyErr = classifyModelError(
            RuntimeException("503 UNAVAILABLE: This model is currently experiencing high demand. Please try again later.")
        )
        assertEquals(DisconnectionCategory.MODEL_BUSY, busyErr.category)
        assertTrue(busyErr.explanationAr.contains("503"))
        assertTrue(isModelOverloadError(RuntimeException("This model is currently experiencing high demand")))
        assertFalse(isModelOverloadError(RuntimeException("Firebase App Check token is invalid")))
    }

    @Test
    fun `Lite chain tries gemini-3-5-flash-lite then gemini-3-8-flash then gemini-4-hydrogen with 1s and 2s delays`() = runBlocking {
        val events = mutableListOf<String>()
        val statusNotices = mutableListOf<String>()

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String = "valid-token"
            override suspend fun clearStoredTokens() {}
        }

        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = ""
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateLiteGeneralArabicText(prompt: String): String {
                events.add(FALLBACK_LITE_GEMINI_MODEL)
                throw RuntimeException("503 UNAVAILABLE: This model is currently experiencing high demand.")
            }

            override suspend fun generateGeneralArabicText(prompt: String): String {
                events.add(PRIMARY_GEMINI_MODEL)
                throw RuntimeException("503 UNAVAILABLE: high demand. Please try again later.")
            }

            override suspend fun generateGeneralArabicTextThird(prompt: String): String {
                events.add(THIRD_FALLBACK_GEMINI_MODEL)
                return "رد مستقر من الهيدروجين"
            }
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient,
            overloadRetryDelaysMs = DEFAULT_OVERLOAD_BACKOFF_MS,
            delayFn = { ms -> events.add("delay($ms)") }
        )

        val meta = repository.conversationalReplyWithMeta(
            userMessage = "ما هي عاصمة المغرب؟",
            contextSummary = "عام",
            onStatusUpdate = { msg -> statusNotices.add(msg) },
            modelTier = ModelRouteTier.LITE
        )

        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-4-hydrogen"), getModelChainForTier(ModelRouteTier.LITE))
        assertEquals(THIRD_FALLBACK_GEMINI_MODEL, meta.modelUsed)
        assertTrue(meta.usedFallback)
        assertEquals("رد مستقر من الهيدروجين", meta.value)
        assertEquals(listOf("النموذج مشغول الآن، أعيد المحاولة..."), statusNotices)
        assertEquals(
            listOf(
                "gemini-3.5-flash-lite",
                "delay(1000)",
                "gemini-3.8-flash",
                "delay(2000)",
                "gemini-4-hydrogen"
            ),
            events
        )
    }

    @Test
    fun `Flash chain tries gemini-3-8-flash then gemini-3-5-flash-lite then gemini-4-hydrogen with 1s and 2s delays`() = runBlocking {
        val events = mutableListOf<String>()
        val statusNotices = mutableListOf<String>()

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String = "valid-token"
            override suspend fun clearStoredTokens() {}
        }

        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = ""
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String): String {
                events.add(PRIMARY_GEMINI_MODEL)
                throw RuntimeException("503 overloaded: high demand")
            }

            override suspend fun generateLiteGeneralArabicText(prompt: String): String {
                events.add(FALLBACK_LITE_GEMINI_MODEL)
                throw RuntimeException("503 UNAVAILABLE")
            }

            override suspend fun generateGeneralArabicTextThird(prompt: String): String {
                events.add(THIRD_FALLBACK_GEMINI_MODEL)
                return "خطة تنفيذ المهمة جاهزة من الهيدروجين"
            }
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient,
            overloadRetryDelaysMs = DEFAULT_OVERLOAD_BACKOFF_MS,
            delayFn = { ms -> events.add("delay($ms)") }
        )

        val meta = repository.conversationalReplyWithMeta(
            userMessage = "افتح الإعدادات الآن",
            contextSummary = "هاتف",
            onStatusUpdate = { msg -> statusNotices.add(msg) },
            modelTier = ModelRouteTier.FLASH
        )

        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-hydrogen"), getModelChainForTier(ModelRouteTier.FLASH))
        assertEquals(ModelRouteTier.FLASH, meta.tier)
        assertEquals(THIRD_FALLBACK_GEMINI_MODEL, meta.modelUsed)
        assertTrue(meta.usedFallback)
        assertEquals("خطة تنفيذ المهمة جاهزة من الهيدروجين", meta.value)
        assertEquals(listOf("النموذج مشغول الآن، أعيد المحاولة..."), statusNotices)
        assertEquals(
            listOf(
                "gemini-3.8-flash",
                "delay(1000)",
                "gemini-3.5-flash-lite",
                "delay(2000)",
                "gemini-4-hydrogen"
            ),
            events
        )
    }

    @Test
    fun `503 across all three models ends cleanly with single short Arabic error and never uses blocked models`() = runBlocking {
        val allModels = (getModelChainForTier(ModelRouteTier.LITE) + getModelChainForTier(ModelRouteTier.FLASH)).toSet()
        val forbiddenModels = setOf(
            "gemini-4-neon",
            "gemini-4-carbon",
            "gemini-4-argon",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite"
        )
        assertTrue(allModels.intersect(forbiddenModels).isEmpty())
        assertEquals(setOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-4-hydrogen"), allModels)

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String = "valid-token"
            override suspend fun clearStoredTokens() {}
        }

        val tried = mutableListOf<String>()
        val statusNotices = mutableListOf<String>()
        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = ""
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String): String {
                tried.add(PRIMARY_GEMINI_MODEL)
                throw RuntimeException("503 UNAVAILABLE: high demand")
            }

            override suspend fun pingModelTest(): String {
                tried.add(PRIMARY_GEMINI_MODEL)
                throw RuntimeException("503 UNAVAILABLE: high demand")
            }

            override suspend fun pingModelTestFallback(): String {
                tried.add(FALLBACK_LITE_GEMINI_MODEL)
                throw RuntimeException("503 UNAVAILABLE: high demand")
            }

            override suspend fun pingModelTestThird(): String {
                tried.add(THIRD_FALLBACK_GEMINI_MODEL)
                throw RuntimeException("503 UNAVAILABLE: high demand")
            }
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient,
            overloadRetryDelaysMs = listOf(10L, 20L),
            delayFn = {}
        )

        try {
            repository.pingModelTest(onStatusUpdate = { statusNotices.add(it) })
            fail("Expected 503 exception after all 3 models fail")
        } catch (e: Exception) {
            val classified = classifyModelError(e)
            assertEquals(DisconnectionCategory.MODEL_BUSY, classified.category)
            assertFalse(classified.explanationAr.contains("\n"))
            assertTrue(classified.explanationAr.contains("503"))
        }

        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-hydrogen"), tried)
        assertEquals(listOf(MSG_MODEL_BUSY_RETRYING_AR), statusNotices)
    }

    @Test
    fun `routes general chat to Lite model and phone or gameplay tasks to Flash with bidirectional fallback`() = runBlocking {
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean): String = "valid-token"
            override suspend fun clearStoredTokens() {}
        }

        val calls = mutableListOf<String>()
        var failLiteUnavailable = false

        val fakeModelClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = ""
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = ""
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String): String {
                calls.add("flash")
                return "رد من Flash"
            }

            override suspend fun generateLiteGeneralArabicText(prompt: String): String {
                calls.add("lite")
                if (failLiteUnavailable) {
                    throw RuntimeException("404 NOT_FOUND: model not found")
                }
                return "رد من Lite"
            }
        }

        val repository = GeminiRepository(
            appCheckClient = fakeAppCheck,
            modelClient = fakeModelClient,
            overloadRetryDelaysMs = listOf(10L, 20L),
            delayFn = {}
        )

        // 1. General discussion uses Lite (gemini-3.5-flash-lite)
        val liteMeta = repository.conversationalReplyWithMeta(
            userMessage = "ما هي عاصمة فرنسا؟",
            contextSummary = "عام",
            modelTier = ModelRouteTier.LITE
        )
        assertEquals(ModelRouteTier.LITE, liteMeta.tier)
        assertEquals(FALLBACK_LITE_GEMINI_MODEL, liteMeta.modelUsed)
        assertFalse(liteMeta.usedFallback)
        assertEquals("رد من Lite", liteMeta.value)

        // 2. Gameplay / phone usage uses Flash (gemini-3.8-flash)
        val flashMeta = repository.conversationalReplyWithMeta(
            userMessage = "تولّي اللعب",
            contextSummary = "لعب",
            modelTier = ModelRouteTier.FLASH
        )
        assertEquals(ModelRouteTier.FLASH, flashMeta.tier)
        assertEquals(PRIMARY_GEMINI_MODEL, flashMeta.modelUsed)
        assertFalse(flashMeta.usedFallback)
        assertEquals("رد من Flash", flashMeta.value)

        // 3. If Lite model is unavailable (404), safely falls back to Flash immediately
        failLiteUnavailable = true
        val recoveredFromLiteMeta = repository.conversationalReplyWithMeta(
            userMessage = "اشرح لي نظرية النسبية",
            contextSummary = "عام",
            modelTier = ModelRouteTier.LITE
        )
        assertEquals(PRIMARY_GEMINI_MODEL, recoveredFromLiteMeta.modelUsed)
        assertTrue(recoveredFromLiteMeta.usedFallback)
        assertEquals("رد من Flash", recoveredFromLiteMeta.value)
        assertEquals(listOf("lite", "flash", "lite", "flash"), calls)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        var commitCalled = false
            private set

        fun putDirect(key: String, value: Any?) {
            data[key] = value
        }

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private var clearPending = false
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearPending = true }

            override fun commit(): Boolean {
                commitCalled = true
                if (clearPending) data.clear()
                removals.forEach { data.remove(it) }
                data.putAll(pending)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
