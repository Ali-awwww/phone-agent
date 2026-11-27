package com.example.data.ai

import com.example.executor.ModelRouteTier
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.AppFolder
import com.example.model.ScreenSnapshot
import com.example.model.ScreenVerificationResult
import com.example.model.StepActionType
import com.example.security.AppCheckTokenClient
import com.example.security.AppCheckVerificationException
import com.example.security.DEFAULT_APP_CHECK_RETRY_DELAY_MS
import com.example.security.ERROR_APP_CHECK_EXHAUSTED_AR
import com.example.security.FirebaseAppCheckClient
import com.example.security.MAX_APP_CHECK_ATTEMPTS
import com.example.security.isAppCheckTokenError
import com.example.security.isModelOverloadError
import com.example.security.isModelUnavailableError
import com.example.security.safeLogE
import com.example.security.safeLogI
import com.example.security.safeLogW
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiRepository"

// ملاحظة توثيقية (كتالوج Firebase AI Logic الرسمي 2026-11-18):
// - نماذج Gemini 2.5 موقوفة (2026-10-20)، ونماذج 3.5-flash/3.6-flash/3.7-flash معاد توجيهها/موقوفة.
// - نستخدم حصراً النماذج الثلاثة المستقرة المجانية بلا فوترة بالترتيب التالي:
//   * لمهام الهاتف (Flash): gemini-3.8-flash -> gemini-3.5-flash-lite -> gemini-4-hydrogen
//   * للطلبات العامة (Lite): gemini-3.5-flash-lite -> gemini-3.8-flash -> gemini-4-hydrogen
const val PRIMARY_GEMINI_MODEL = "gemini-3.8-flash"
const val FALLBACK_LITE_GEMINI_MODEL = "gemini-3.5-flash-lite"
const val THIRD_FALLBACK_GEMINI_MODEL = "gemini-4-hydrogen"
const val MSG_MODEL_BUSY_RETRYING_AR = "النموذج مشغول الآن، أعيد المحاولة..."
val DEFAULT_OVERLOAD_BACKOFF_MS: List<Long> = listOf(1_000L, 2_000L)

fun getModelChainForTier(tier: ModelRouteTier): List<String> = when (tier) {
    ModelRouteTier.LITE -> listOf(
        FALLBACK_LITE_GEMINI_MODEL,
        PRIMARY_GEMINI_MODEL,
        THIRD_FALLBACK_GEMINI_MODEL
    )
    ModelRouteTier.FLASH -> listOf(
        PRIMARY_GEMINI_MODEL,
        FALLBACK_LITE_GEMINI_MODEL,
        THIRD_FALLBACK_GEMINI_MODEL
    )
}

data class ModelResponseMetadata<T>(
    val value: T,
    val tier: ModelRouteTier,
    val intendedModel: String,
    val modelUsed: String,
    val usedFallback: Boolean
)

internal interface GeminiModelClient {
    suspend fun generatePlanJson(prompt: String): String
    suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String): String
    suspend fun generateVerificationJson(prompt: String): String
    suspend fun generateCategorizationJson(prompt: String): String
    suspend fun generateGeneralArabicText(prompt: String): String
    suspend fun pingModelTest(): String = generateGeneralArabicText("اختبار اتصال سريع: رد بكلمة واحدة «متصل».")

    suspend fun generatePlanJsonFallback(prompt: String): String = generatePlanJson(prompt)
    suspend fun generateAudioPlanJsonFallback(audioBytes: ByteArray, mimeType: String, prompt: String): String =
        generateAudioPlanJson(audioBytes, mimeType, prompt)
    suspend fun generateVerificationJsonFallback(prompt: String): String = generateVerificationJson(prompt)
    suspend fun generateCategorizationJsonFallback(prompt: String): String = generateCategorizationJson(prompt)
    suspend fun generateGeneralArabicTextFallback(prompt: String): String = generateGeneralArabicText(prompt)
    suspend fun generateLiteGeneralArabicText(prompt: String): String = generateGeneralArabicTextFallback(prompt)
    suspend fun pingModelTestFallback(): String =
        generateGeneralArabicTextFallback("اختبار اتصال سريع: رد بكلمة واحدة «متصل».")

    suspend fun generatePlanJsonThird(prompt: String): String = generatePlanJsonFallback(prompt)
    suspend fun generateAudioPlanJsonThird(audioBytes: ByteArray, mimeType: String, prompt: String): String =
        generateAudioPlanJsonFallback(audioBytes, mimeType, prompt)
    suspend fun generateVerificationJsonThird(prompt: String): String = generateVerificationJsonFallback(prompt)
    suspend fun generateCategorizationJsonThird(prompt: String): String = generateCategorizationJsonFallback(prompt)
    suspend fun generateGeneralArabicTextThird(prompt: String): String = generateGeneralArabicTextFallback(prompt)
    suspend fun pingModelTestThird(): String =
        generateGeneralArabicTextThird("اختبار اتصال سريع: رد بكلمة واحدة «متصل».")
}

private class FirebaseGeminiModelClient : GeminiModelClient {

    private fun buildStepsArraySchema(): Schema = Schema.array(
        items = Schema.obj(
            properties = mapOf(
                "stepNumber" to Schema.integer("رقم الخطوة التسلسلي يبدأ من 1"),
                "titleAr" to Schema.string("عنوان واضح للخطوة بالعربية"),
                "descriptionAr" to Schema.string("وصف تفصيلي واضح للخطوة بالعربية"),
                "actionType" to Schema.enumeration(
                    values = StepActionType.entries.map { it.name },
                    description = "نوع الإجراء البرمجي"
                ),
                "targetAppName" to Schema.string("اسم التطبيق المستهدف إن وجد أو فارغ"),
                "targetQueryOrText" to Schema.string("نص البحث أو الرسالة أو العنصر المطلوب النقر عليه أو نوع الإعداد"),
                "expectedScreenHint" to Schema.string("ما المتوقع ظهوره على الشاشة للتحقق من نجاح الخطوة"),
                "requiresApproval" to Schema.boolean("true لأي إجراء حساس مثل الإرسال أو الحذف أو الدفع أو تغيير إعداد مهم"),
                "safetyReasonAr" to Schema.string("سبب طلب الموافقة بالعربية إذا كان requiresApproval=true"),
                "hour" to Schema.integer("الساعة بنظام 24 إن كان أمراً لمنبه أو تذكير، وإلا -1"),
                "minute" to Schema.integer("الدقيقة إن كان أمراً لمنبه أو تذكير، وإلا -1")
            ),
            optionalProperties = listOf("hour", "minute", "safetyReasonAr", "targetAppName", "targetQueryOrText")
        ),
        description = "قائمة الخطوات المتسلسلة لتنفيذ الطلب"
    )

    private val planSchema = Schema.obj(
        properties = mapOf(
            "understandingSummaryAr" to Schema.string("ملخص قصير بالعربية لفهم طلب المستخدم"),
            "spokenReplyAr" to Schema.string("رد طبيعي قصير وودود بالعربية ليُقرأ صوتياً للمستخدم"),
            "steps" to buildStepsArraySchema()
        )
    )

    private val verificationSchema = Schema.obj(
        properties = mapOf(
            "verified" to Schema.boolean("هل تحقق الشرط المتوقع على الشاشة للانتقال للخطوة التالية؟"),
            "explanationAr" to Schema.string("توضيح مختصر بالعربية لما تم رصده على الشاشة"),
            "suggestedFixAr" to Schema.string("اقتراح بالعربية في حال عدم التحقق"),
            "sensitiveUiDetected" to Schema.boolean("هل توجد شاشة حساسة مثل كلمة مرور أو دفع أو بصمة؟")
        )
    )

    private val categorizationSchema = Schema.obj(
        properties = mapOf(
            "assignments" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "packageName" to Schema.string("معرّف الحزمة كما ورد"),
                        "folderKey" to Schema.enumeration(
                            values = AppFolder.entries.map { it.name },
                            description = "المجلد المناسب للتطبيق"
                        )
                    )
                )
            )
        )
    )

    private val audioCommandSchema = Schema.obj(
        properties = mapOf(
            "transcriptAr" to Schema.string("النص العربي الدقيق لما قاله المستخدم في التسجيل الصوتي"),
            "understandingSummaryAr" to Schema.string("ملخص قصير لفهم الطلب"),
            "spokenReplyAr" to Schema.string("رد طبيعي قصير بالعربية ليُقرأ صوتياً"),
            "steps" to buildStepsArraySchema()
        )
    )

    private val phoneExecutionSystemInstruction: String = """
        أنت «وكيل الهاتف»، مساعد ذكي يتحدث العربية الفصحى البسيطة، يجمع بين الإجابة المفيدة عن الأسئلة العامة والنقاشات وتنفيذ مهام الهاتف بدقة وأمان.
        أسلوبك الدائم:
        - للأسئلة العامة أو النقاش أو الاستشارة: أجب مباشرة بوضوح ولطف وفائدة دون قيود نطاق غير مبررة.
        - لمهام الهاتف: اشرح للمستخدم باختصار ما ستفعله قبل أن تفعله، نفّذ خطوة بخطوة، ثم أخبره بالنتيجة الحقيقية بعدها دون إطالة.

        قواعد السلامة ومنع الهلوسة للأفعال على الجهاز:
        - ارفض فقط الأفعال الخطرة الثمانية: كلمات المرور، البصمة، الدفع، الشراء، إرسال الأموال، حذف البيانات، تغيير إعدادات الحسابات، وتجاوز قفل الشاشة. (أما الأسئلة المعرفية العامة فأجب عنها بشكل طبيعي).
        - لا تخترع أبداً أي أسماء تطبيقات غير موجودة في قائمة التطبيقات المثبتة، ولا تخترع أسماء أزرار أو عناصر غير مقروءة من الشاشة الفعلية، ولا تخترع أرقام هواتف.
        - تحقق من الشاشة الفعلية عبر ScreenVerifier قبل كل خطوة تنفيذية وبعدها؛ وعند غياب العنصر المطلوب على الشاشة قُل بوضوح: «لم أجد هذا العنصر على الشاشة» وتوقف دون اختراع بدائل.
        - اعتمد واجهات أندرويد الرسمية أولاً ثم خدمة إمكانية الوصول عند الحاجة، واجعل requiresApproval = true لأي إجراء حساس مثل الإرسال أو الحذف أو تعديل إعداد مهم.
    """.trimIndent()

    private val generalAssistantSystemInstruction: String = """
        أنت «وكيل الهاتف»، مساعد ذكي متكامل يتحدث العربية الفصحى البسيطة ويجيب عن جميع الأسئلة العادية والنقاشات والمعرفة العامة والاستشارات بوضوح ودقة وأدب، كما يساعد في مهام الهاتف.
        - أجب عن الأسئلة والنقاشات العامة بشكل مباشر ومفيد ومرتب دون تقييد نفسك بأوامر الهاتف فقط.
        - إذا طلب المستخدم في المحادثة فعلاً على الجهاز، طبّق قواعد الأمان: تحقق من الشاشة الفعلية، لا تخترع أسماء تطبيقات أو أزرار، واطلب موافقته الصريحة قبل أي فعل حساس (حذف، إرسال، تغيير إعدادات، دفع).
        - عندما لا تكون متأكداً من معلومة أو عنصر على الشاشة، صرّح بذلك بصدق ووضوح بدل التخمين.
    """.trimIndent()

    private fun createPlannerModel(modelName: String) = Firebase.ai.generativeModel(
        modelName = modelName,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = planSchema
        },
        systemInstruction = content { text(phoneExecutionSystemInstruction) }
    )

    private fun createAudioPlannerModel(modelName: String) = Firebase.ai.generativeModel(
        modelName = modelName,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = audioCommandSchema
        },
        systemInstruction = content { text(phoneExecutionSystemInstruction) }
    )

    private fun createVerifierModel(modelName: String) = Firebase.ai.generativeModel(
        modelName = modelName,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = verificationSchema
        },
        systemInstruction = content {
            text(
                "أنت وحدة تحقق صارمة للشاشة (ScreenVerifier). لا تخترع أي عنصر غير موجود في النصوص والأزرار المقروءة فعلياً. إن غاب العنصر أعد verified=false واذكر بوضوح «لم أجد هذا العنصر على الشاشة»."
            )
        }
    )

    private fun createGeneralArabicModel(modelName: String, systemText: String) = Firebase.ai.generativeModel(
        modelName = modelName,
        systemInstruction = content { text(systemText) }
    )

    private val plannerModel by lazy { createPlannerModel(PRIMARY_GEMINI_MODEL) }
    private val fallbackPlannerModel by lazy { createPlannerModel(FALLBACK_LITE_GEMINI_MODEL) }
    private val thirdPlannerModel by lazy { createPlannerModel(THIRD_FALLBACK_GEMINI_MODEL) }

    private val audioPlannerModel by lazy { createAudioPlannerModel(PRIMARY_GEMINI_MODEL) }
    private val fallbackAudioPlannerModel by lazy { createAudioPlannerModel(FALLBACK_LITE_GEMINI_MODEL) }
    private val thirdAudioPlannerModel by lazy { createAudioPlannerModel(THIRD_FALLBACK_GEMINI_MODEL) }

    private val verifierModel by lazy { createVerifierModel(PRIMARY_GEMINI_MODEL) }
    private val fallbackVerifierModel by lazy { createVerifierModel(FALLBACK_LITE_GEMINI_MODEL) }
    private val thirdVerifierModel by lazy { createVerifierModel(THIRD_FALLBACK_GEMINI_MODEL) }

    private val categorizerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FALLBACK_LITE_GEMINI_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = categorizationSchema
            }
        )
    }

    private val fallbackCategorizerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = PRIMARY_GEMINI_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = categorizationSchema
            }
        )
    }

    private val thirdCategorizerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = THIRD_FALLBACK_GEMINI_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = categorizationSchema
            }
        )
    }

    private val generalArabicModel by lazy {
        createGeneralArabicModel(PRIMARY_GEMINI_MODEL, phoneExecutionSystemInstruction)
    }
    private val liteGeneralArabicModel by lazy {
        createGeneralArabicModel(FALLBACK_LITE_GEMINI_MODEL, generalAssistantSystemInstruction)
    }
    private val thirdGeneralArabicModel by lazy {
        createGeneralArabicModel(THIRD_FALLBACK_GEMINI_MODEL, generalAssistantSystemInstruction)
    }

    override suspend fun generatePlanJson(prompt: String): String {
        val response = plannerModel.generateContent(prompt)
        return response.text ?: throw IllegalStateException("لم يُرجع النموذج استجابة صالحة.")
    }

    override suspend fun generatePlanJsonFallback(prompt: String): String {
        val response = fallbackPlannerModel.generateContent(prompt)
        return response.text ?: throw IllegalStateException("لم يُرجع النموذج استجابة صالحة.")
    }

    override suspend fun generatePlanJsonThird(prompt: String): String {
        val response = thirdPlannerModel.generateContent(prompt)
        return response.text ?: throw IllegalStateException("لم يُرجع النموذج استجابة صالحة.")
    }

    override suspend fun generateAudioPlanJson(
        audioBytes: ByteArray,
        mimeType: String,
        prompt: String
    ): String {
        val response = audioPlannerModel.generateContent(
            content {
                inlineData(audioBytes, mimeType)
                text(prompt)
            }
        )
        return response.text ?: throw IllegalStateException("لم يتم استلام رد من النموذج الصوتي.")
    }

    override suspend fun generateAudioPlanJsonFallback(
        audioBytes: ByteArray,
        mimeType: String,
        prompt: String
    ): String {
        val response = fallbackAudioPlannerModel.generateContent(
            content {
                inlineData(audioBytes, mimeType)
                text(prompt)
            }
        )
        return response.text ?: throw IllegalStateException("لم يتم استلام رد من النموذج الصوتي.")
    }

    override suspend fun generateAudioPlanJsonThird(
        audioBytes: ByteArray,
        mimeType: String,
        prompt: String
    ): String {
        val response = thirdAudioPlannerModel.generateContent(
            content {
                inlineData(audioBytes, mimeType)
                text(prompt)
            }
        )
        return response.text ?: throw IllegalStateException("لم يتم استلام رد من النموذج الصوتي.")
    }

    override suspend fun generateVerificationJson(prompt: String): String {
        val response = verifierModel.generateContent(prompt)
        return response.text ?: "{}"
    }

    override suspend fun generateVerificationJsonFallback(prompt: String): String {
        val response = fallbackVerifierModel.generateContent(prompt)
        return response.text ?: "{}"
    }

    override suspend fun generateVerificationJsonThird(prompt: String): String {
        val response = thirdVerifierModel.generateContent(prompt)
        return response.text ?: "{}"
    }

    override suspend fun generateCategorizationJson(prompt: String): String {
        val response = categorizerModel.generateContent(prompt)
        return response.text ?: "{}"
    }

    override suspend fun generateCategorizationJsonFallback(prompt: String): String {
        val response = fallbackCategorizerModel.generateContent(prompt)
        return response.text ?: "{}"
    }

    override suspend fun generateCategorizationJsonThird(prompt: String): String {
        val response = thirdCategorizerModel.generateContent(prompt)
        return response.text ?: "{}"
    }

    override suspend fun generateGeneralArabicText(prompt: String): String {
        val response = generalArabicModel.generateContent(prompt)
        return response.text?.trim() ?: throw IllegalStateException("لم يُرجع النموذج استجابة.")
    }

    override suspend fun generateGeneralArabicTextFallback(prompt: String): String {
        return generateLiteGeneralArabicText(prompt)
    }

    override suspend fun generateLiteGeneralArabicText(prompt: String): String {
        val response = liteGeneralArabicModel.generateContent(prompt)
        return response.text?.trim() ?: throw IllegalStateException("لم يُرجع النموذج استجابة.")
    }

    override suspend fun generateGeneralArabicTextThird(prompt: String): String {
        val response = thirdGeneralArabicModel.generateContent(prompt)
        return response.text?.trim() ?: throw IllegalStateException("لم يُرجع النموذج استجابة.")
    }

    override suspend fun pingModelTest(): String {
        return generateGeneralArabicText("اختبار اتصال سريع: رد بكلمة واحدة «متصل».")
    }

    override suspend fun pingModelTestFallback(): String {
        return generateLiteGeneralArabicText("اختبار اتصال سريع: رد بكلمة واحدة «متصل».")
    }

    override suspend fun pingModelTestThird(): String {
        return generateGeneralArabicTextThird("اختبار اتصال سريع: رد بكلمة واحدة «متصل».")
    }
}

class GeminiRepository internal constructor(
    private val appCheckClient: AppCheckTokenClient,
    private val modelClient: GeminiModelClient,
    private val maxAppCheckAttempts: Int = MAX_APP_CHECK_ATTEMPTS,
    private val retryDelayMs: Long = DEFAULT_APP_CHECK_RETRY_DELAY_MS,
    private val overloadRetryDelaysMs: List<Long> = DEFAULT_OVERLOAD_BACKOFF_MS,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    constructor(
        appCheckClient: AppCheckTokenClient = FirebaseAppCheckClient()
    ) : this(
        appCheckClient = appCheckClient,
        modelClient = FirebaseGeminiModelClient()
    )

    /**
     * Self-healing Firebase App Check wrapper for every Gemini call:
     * 1. Awaits App Check token completion synchronously before sending any Gemini request.
     * 2. Treats a non-empty token as unverified until Gemini accepts the call; if Gemini fails
     *    with an App Check error (or token acquisition fails), it is treated as rejection.
     * 3. Clears stored App Check caches (debug store & App Check preferences), delays briefly,
     *    and retries with forced `getToken(true)` up to [maxAppCheckAttempts] (3) attempts.
     * 4. Never silently ignores exhaustion: throws [AppCheckVerificationException] with a clear
     *    Arabic user-facing message.
     */
    internal suspend fun <T> withAppCheckRecovery(block: suspend () -> T): T {
        require(maxAppCheckAttempts >= 1) { "maxAppCheckAttempts must be at least 1" }
        var lastError: Throwable? = null

        for (attempt in 1..maxAppCheckAttempts) {
            val forceRefresh = attempt > 1
            var failure: Throwable? = null

            val token = try {
                appCheckClient.getToken(forceRefresh = forceRefresh).also { fetched ->
                    if (fetched.isBlank()) {
                        throw AppCheckVerificationException("أعاد Firebase App Check رمزاً فارغاً.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                null
            }

            if (failure == null && token != null) {
                try {
                    return block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isAppCheckTokenError(e)) {
                        throw e
                    }
                    failure = e
                }
            }

            lastError = failure
            safeLogW(
                TAG,
                "App Check failed on attempt $attempt/$maxAppCheckAttempts: ${failure?.message}",
                failure
            )

            if (attempt < maxAppCheckAttempts) {
                try {
                    appCheckClient.clearStoredTokens()
                } catch (e: CancellationException) {
                    throw e
                } catch (clearErr: Exception) {
                    safeLogE(TAG, "Failed to clear stored App Check tokens before retry.", clearErr)
                }

                val waitMs = retryDelayMs * attempt
                if (waitMs > 0L) {
                    delayFn(waitMs)
                }
            }
        }

        safeLogE(
            TAG,
            "Exhausted all $maxAppCheckAttempts App Check recovery attempts.",
            lastError
        )
        throw AppCheckVerificationException(
            message = ERROR_APP_CHECK_EXHAUSTED_AR,
            cause = lastError
        )
    }

    internal data class ResilientResult<T>(
        val value: T,
        val modelUsed: String,
        val usedFallback: Boolean = false
    )

    internal data class ModelCallCandidate<T>(
        val modelName: String,
        val call: suspend () -> T
    )

    internal suspend fun <T> withModelChainResilience(
        timeoutMs: Long,
        candidates: List<ModelCallCandidate<T>>,
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): ResilientResult<T> {
        require(candidates.isNotEmpty()) { "candidates must not be empty" }
        var lastError: Exception? = null
        var busyStatusSent = false

        for ((index, candidate) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val result = withAppCheckRecovery {
                    withTimeout(timeoutMs) { candidate.call() }
                }
                return ResilientResult(
                    value = result,
                    modelUsed = candidate.modelName,
                    usedFallback = index > 0
                )
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                currentCoroutineContext().ensureActive()
                lastError = e
            } catch (e: Exception) {
                val canSwitch = isModelOverloadError(e) || isModelUnavailableError(e)
                if (!canSwitch) {
                    throw e
                }
                lastError = e
            }

            if (index < candidates.lastIndex) {
                val nextCandidate = candidates[index + 1]
                val isUnavailable = isModelUnavailableError(lastError)
                val waitMs = if (isUnavailable) {
                    0L
                } else {
                    overloadRetryDelaysMs.getOrElse(index) {
                        overloadRetryDelaysMs.lastOrNull() ?: 2_000L
                    }
                }
                safeLogW(
                    TAG,
                    "Model ${candidate.modelName} unavailable/overloaded; switching (${index + 1}/${candidates.lastIndex}) to ${nextCandidate.modelName} after ${waitMs}ms.",
                    lastError
                )
                if (!busyStatusSent) {
                    onStatusUpdate?.invoke(MSG_MODEL_BUSY_RETRYING_AR)
                    busyStatusSent = true
                }
                if (waitMs > 0L) {
                    delayFn(waitMs)
                }
            }
        }

        throw lastError ?: IllegalStateException("تعذر الوصول للنموذج بسبب الضغط المؤقت.")
    }

    internal suspend fun <T> withModelResilience(
        timeoutMs: Long,
        onStatusUpdate: (suspend (String) -> Unit)? = null,
        primaryModelName: String = PRIMARY_GEMINI_MODEL,
        fallbackModelName: String = FALLBACK_LITE_GEMINI_MODEL,
        thirdModelName: String = THIRD_FALLBACK_GEMINI_MODEL,
        primaryCall: suspend () -> T,
        fallbackCall: (suspend () -> T)? = null,
        thirdCall: (suspend () -> T)? = null
    ): ResilientResult<T> {
        val candidates = buildList {
            add(ModelCallCandidate(primaryModelName, primaryCall))
            if (fallbackCall != null) {
                add(ModelCallCandidate(fallbackModelName, fallbackCall))
            }
            if (thirdCall != null) {
                add(ModelCallCandidate(thirdModelName, thirdCall))
            }
        }
        return withModelChainResilience(
            timeoutMs = timeoutMs,
            candidates = candidates,
            onStatusUpdate = onStatusUpdate
        )
    }

    suspend fun generateCommandPlan(
        userCommand: String,
        installedAppNames: List<String>,
        currentScreenSummary: String,
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): AgentPlan {
        return generateCommandPlanWithMeta(
            userCommand = userCommand,
            installedAppNames = installedAppNames,
            currentScreenSummary = currentScreenSummary,
            onStatusUpdate = onStatusUpdate
        ).value
    }

    suspend fun generateCommandPlanWithMeta(
        userCommand: String,
        installedAppNames: List<String>,
        currentScreenSummary: String,
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): ModelResponseMetadata<AgentPlan> {
        try {
            val prompt = buildString {
                appendLine("طلب المستخدم: $userCommand")
                appendLine("أمثلة التطبيقات المثبتة على الجهاز: ${installedAppNames.take(50).joinToString("، ")}")
                appendLine("حالة الشاشة الحالية: $currentScreenSummary")
                appendLine("أنشئ خطة تنفيذ دقيقة خطوة بخطوة بصيغة JSON حسب المخطط المطلوب.")
            }

            val chain = getModelChainForTier(ModelRouteTier.FLASH)
            val result = withModelChainResilience(
                timeoutMs = 60_000L,
                onStatusUpdate = onStatusUpdate,
                candidates = listOf(
                    ModelCallCandidate(chain[0]) { modelClient.generatePlanJson(prompt) },
                    ModelCallCandidate(chain[1]) { modelClient.generatePlanJsonFallback(prompt) },
                    ModelCallCandidate(chain[2]) { modelClient.generatePlanJsonThird(prompt) }
                )
            )
            val plan = parsePlanJson(userCommand, JSONObject(result.value))
            safeLogI(
                TAG,
                "ModelRoute tier=FLASH intended=${chain[0]} actual=${result.modelUsed} fallback=${result.usedFallback} (commandPlan)"
            )
            return ModelResponseMetadata(
                value = plan,
                tier = ModelRouteTier.FLASH,
                intendedModel = chain[0],
                modelUsed = result.modelUsed,
                usedFallback = result.usedFallback
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            safeLogE(TAG, "Timed out generating command plan", e)
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Failed to generate command plan", e)
            throw e
        }
    }

    suspend fun transcribeAndPlanAudio(
        audioBytes: ByteArray,
        mimeType: String,
        installedAppNames: List<String>,
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): Pair<String, AgentPlan> {
        return transcribeAndPlanAudioWithMeta(
            audioBytes = audioBytes,
            mimeType = mimeType,
            installedAppNames = installedAppNames,
            onStatusUpdate = onStatusUpdate
        ).value
    }

    suspend fun transcribeAndPlanAudioWithMeta(
        audioBytes: ByteArray,
        mimeType: String,
        installedAppNames: List<String>,
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): ModelResponseMetadata<Pair<String, AgentPlan>> {
        try {
            val prompt = buildString {
                appendLine("استمع إلى التسجيل الصوتي للمستخدم باللغة العربية، اكتب النص المفهوم في transcriptAr، ثم أنشئ خطة الخطوات التنفيذية.")
                appendLine("التطبيقات المتاحة: ${installedAppNames.take(40).joinToString("، ")}")
            }
            val chain = getModelChainForTier(ModelRouteTier.FLASH)
            val result = withModelChainResilience(
                timeoutMs = 60_000L,
                onStatusUpdate = onStatusUpdate,
                candidates = listOf(
                    ModelCallCandidate(chain[0]) { modelClient.generateAudioPlanJson(audioBytes, mimeType, prompt) },
                    ModelCallCandidate(chain[1]) { modelClient.generateAudioPlanJsonFallback(audioBytes, mimeType, prompt) },
                    ModelCallCandidate(chain[2]) { modelClient.generateAudioPlanJsonThird(audioBytes, mimeType, prompt) }
                )
            )
            val json = JSONObject(result.value)
            val transcript = json.optString("transcriptAr", "").ifBlank { "أمر صوتي" }
            val plan = parsePlanJson(transcript, json)
            safeLogI(
                TAG,
                "ModelRoute tier=FLASH intended=${chain[0]} actual=${result.modelUsed} fallback=${result.usedFallback} (audioPlan)"
            )
            return ModelResponseMetadata(
                value = transcript to plan,
                tier = ModelRouteTier.FLASH,
                intendedModel = chain[0],
                modelUsed = result.modelUsed,
                usedFallback = result.usedFallback
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            safeLogE(TAG, "Timed out processing audio command", e)
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Failed to process audio command", e)
            throw e
        }
    }

    suspend fun verifyScreenAfterStep(
        step: AgentPlanStep,
        snapshot: ScreenSnapshot?
    ): ScreenVerificationResult {
        if (snapshot == null) {
            return ScreenVerificationResult(
                verified = true,
                explanationAr = "تم تنفيذ الخطوة عبر واجهة النظام الرسمية (لا تتوفر لقطة شاشة إضافية من خدمة الوصول)."
            )
        }
        if (snapshot.hasSensitiveSecurityField) {
            return ScreenVerificationResult(
                verified = false,
                explanationAr = "تم رصد حقل كلمة مرور أو بصمة أو دفع على الشاشة. توقف الوكيل لحماية أمانك.",
                suggestedFixAr = "يرجى إكمال هذه الخطوة يدوياً ثم تأكيد المتابعة.",
                sensitiveUiDetected = true
            )
        }
        return try {
            val prompt = buildString {
                appendLine("الخطوة المنفذة: ${step.titleAr} (${step.descriptionAr})")
                appendLine("التحقق المتوقع: ${step.expectedScreenHint}")
                appendLine("محتوى الشاشة المرصود الآن:")
                appendLine(snapshot.toCompactPromptText())
            }
            val chain = getModelChainForTier(ModelRouteTier.FLASH)
            val result = withModelChainResilience(
                timeoutMs = 45_000L,
                candidates = listOf(
                    ModelCallCandidate(chain[0]) { modelClient.generateVerificationJson(prompt) },
                    ModelCallCandidate(chain[1]) { modelClient.generateVerificationJsonFallback(prompt) },
                    ModelCallCandidate(chain[2]) { modelClient.generateVerificationJsonThird(prompt) }
                )
            )
            safeLogI(
                TAG,
                "ModelRoute tier=FLASH intended=${chain[0]} actual=${result.modelUsed} fallback=${result.usedFallback} (verifyScreen)"
            )
            val json = JSONObject(result.value)
            ScreenVerificationResult(
                verified = json.optBoolean("verified", true),
                explanationAr = json.optString("explanationAr", "تم فحص الشاشة بنجاح."),
                suggestedFixAr = json.optString("suggestedFixAr", ""),
                sensitiveUiDetected = json.optBoolean("sensitiveUiDetected", false)
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            ScreenVerificationResult(
                verified = false,
                explanationAr = "انتهت مهلة التحقق التلقائي من الشاشة.",
                suggestedFixAr = "تأكد من حالة الشاشة ثم اختر متابعة أو إلغاء."
            )
        } catch (e: Exception) {
            safeLogE(TAG, "Screen verification failed", e)
            ScreenVerificationResult(
                verified = false,
                explanationAr = "تعذر التحقق التلقائي من الشاشة مؤقتاً.",
                suggestedFixAr = "تأكد من حالة الشاشة ثم اختر متابعة أو إلغاء."
            )
        }
    }

    suspend fun summarizeAppScreens(
        appName: String,
        packageName: String,
        capturedScreenTexts: List<String>,
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): String {
        return summarizeAppScreensWithMeta(
            appName = appName,
            packageName = packageName,
            capturedScreenTexts = capturedScreenTexts,
            onStatusUpdate = onStatusUpdate
        ).value
    }

    suspend fun summarizeAppScreensWithMeta(
        appName: String,
        packageName: String,
        capturedScreenTexts: List<String>,
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): ModelResponseMetadata<String> {
        try {
            val joined = capturedScreenTexts.joinToString("\n---\n")
            val prompt = """
                قم بتجربة وفحص التطبيق التالي وقدّم ملخصاً عربياً واضحاً ومرتباً للمستخدم يشرح:
                1. ما وظيفة التطبيق الأساسية؟
                2. ما الأقسام والميزات الظاهرة في شاشاته؟
                3. نصيحة سريعة أو ملاحظة أمان إن وجدت.

                اسم التطبيق: $appName
                الحزمة: $packageName
                النصوص المرصودة من شاشات التطبيق:
                $joined
            """.trimIndent()

            val chain = getModelChainForTier(ModelRouteTier.FLASH)
            val result = withModelChainResilience(
                timeoutMs = 60_000L,
                onStatusUpdate = onStatusUpdate,
                candidates = listOf(
                    ModelCallCandidate(chain[0]) { modelClient.generateGeneralArabicText(prompt) },
                    ModelCallCandidate(chain[1]) { modelClient.generateLiteGeneralArabicText(prompt) },
                    ModelCallCandidate(chain[2]) { modelClient.generateGeneralArabicTextThird(prompt) }
                )
            )
            safeLogI(
                TAG,
                "ModelRoute tier=FLASH intended=${chain[0]} actual=${result.modelUsed} fallback=${result.usedFallback} (screenRead)"
            )
            return ModelResponseMetadata(
                value = result.value,
                tier = ModelRouteTier.FLASH,
                intendedModel = chain[0],
                modelUsed = result.modelUsed,
                usedFallback = result.usedFallback
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Failed to summarize app", e)
            throw e
        }
    }

    suspend fun categorizeApps(
        apps: List<Pair<String, String>>,
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): Map<String, String> {
        if (apps.isEmpty()) return emptyMap()
        try {
            val formatted = apps.take(80).joinToString("\n") { (pkg, label) ->
                "- packageName: $pkg | label: $label"
            }
            val prompt = """
                صنّف كل تطبيق إلى أحد المجلدات التالية فقط:
                COMMUNICATION (تواصل)، WORK (عمل)، GAMES (ألعاب)، SHOPPING (تسوق)، MEDIA (وسائط)، TOOLS (أدوات).
                التطبيقات:
                $formatted
            """.trimIndent()

            val chain = getModelChainForTier(ModelRouteTier.LITE)
            val result = withModelChainResilience(
                timeoutMs = 60_000L,
                onStatusUpdate = onStatusUpdate,
                candidates = listOf(
                    ModelCallCandidate(chain[0]) { modelClient.generateCategorizationJson(prompt) },
                    ModelCallCandidate(chain[1]) { modelClient.generateCategorizationJsonFallback(prompt) },
                    ModelCallCandidate(chain[2]) { modelClient.generateCategorizationJsonThird(prompt) }
                )
            )
            safeLogI(
                TAG,
                "ModelRoute tier=LITE intended=${chain[0]} actual=${result.modelUsed} fallback=${result.usedFallback} (categorizeApps)"
            )
            val json = JSONObject(result.value)
            val array = json.optJSONArray("assignments") ?: return emptyMap()
            val resultMap = mutableMapOf<String, String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val pkg = item.optString("packageName")
                val folder = item.optString("folderKey")
                if (pkg.isNotBlank() && folder.isNotBlank()) {
                    resultMap[pkg] = AppFolder.fromKey(folder).name
                }
            }
            return resultMap
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "App categorization failed", e)
            throw e
        }
    }

    suspend fun conversationalReply(
        userMessage: String,
        contextSummary: String,
        history: String = "",
        onStatusUpdate: (suspend (String) -> Unit)? = null,
        modelTier: ModelRouteTier = ModelRouteTier.LITE
    ): String {
        return conversationalReplyWithMeta(
            userMessage = userMessage,
            contextSummary = contextSummary,
            history = history,
            onStatusUpdate = onStatusUpdate,
            modelTier = modelTier
        ).value
    }

    suspend fun conversationalReplyWithMeta(
        userMessage: String,
        contextSummary: String,
        history: String = "",
        onStatusUpdate: (suspend (String) -> Unit)? = null,
        modelTier: ModelRouteTier = ModelRouteTier.LITE
    ): ModelResponseMetadata<String> {
        return try {
            val prompt = buildString {
                if (history.isNotBlank()) {
                    appendLine("سجل المحادثة السابق:")
                    appendLine(history)
                    appendLine("تعليمات هامة: استخدم سجل المحادثة لحل الضمائر (هو/هي/هذه/ذلك) ولا تبدأ بتحية جديدة إذا كان هناك سجل؛ أجب عن السؤال مباشرة بالرجوع إلى ما سبق.")
                    appendLine()
                }
                appendLine("رسالة المستخدم الحالية: $userMessage")
                appendLine("سياق الحالة الحالية: $contextSummary")
                append("أجب برد عربي طبيعي وواضح ومفيد كمساعد ذكي متكامل.")
            }
            val chain = getModelChainForTier(modelTier)
            val candidates = if (modelTier == ModelRouteTier.LITE) {
                listOf(
                    ModelCallCandidate(chain[0]) { modelClient.generateLiteGeneralArabicText(prompt) },
                    ModelCallCandidate(chain[1]) { modelClient.generateGeneralArabicText(prompt) },
                    ModelCallCandidate(chain[2]) { modelClient.generateGeneralArabicTextThird(prompt) }
                )
            } else {
                listOf(
                    ModelCallCandidate(chain[0]) { modelClient.generateGeneralArabicText(prompt) },
                    ModelCallCandidate(chain[1]) { modelClient.generateLiteGeneralArabicText(prompt) },
                    ModelCallCandidate(chain[2]) { modelClient.generateGeneralArabicTextThird(prompt) }
                )
            }

            val result = withModelChainResilience(
                timeoutMs = 45_000L,
                onStatusUpdate = onStatusUpdate,
                candidates = candidates
            )
            safeLogI(
                TAG,
                "ModelRoute tier=${modelTier.name} intended=${chain[0]} actual=${result.modelUsed} fallback=${result.usedFallback} (conversational)"
            )
            ModelResponseMetadata(
                value = result.value,
                tier = modelTier,
                intendedModel = chain[0],
                modelUsed = result.modelUsed,
                usedFallback = result.usedFallback
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Conversational reply failed", e)
            throw e
        }
    }

    data class MiniChatDirectiveResult(
        val shouldAct: Boolean,
        val action: String,
        val targetText: String,
        val inputText: String,
        val x: Float,
        val y: Float,
        val endX: Float,
        val endY: Float,
        val continuePlaying: Boolean,
        val replyOneLineAr: String
    )

    suspend fun analyzeMiniChatDirective(
        userCommand: String,
        screenSummary: String,
        history: String = "",
        wasGameplayRunning: Boolean = false
    ): ModelResponseMetadata<MiniChatDirectiveResult> {
        val prompt = buildString {
            if (history.isNotBlank()) {
                appendLine("سجل المحادثة السابق:")
                appendLine(history)
                appendLine()
            }
            appendLine("أنت تتحكم حالياً في الشاشة التالية عبر شريط الدردشة المصغّر:")
            appendLine(screenSummary.ifBlank { "لا توجد تفاصيل شاشة حالياً." })
            appendLine()
            appendLine("توجيه المستخدم الفوري: $userCommand")
            appendLine("هل كان وضع اللعب التلقائي يعمل؟ $wasGameplayRunning")
            appendLine("أجب بصيغة JSON فقط بدون شرح خارجي:")
            append("""{"shouldAct":true,"action":"TAP_TEXT|TAP_XY|SWIPE|SCROLL|INPUT_TEXT|BACK|NONE","text":"النص الدقيق على الشاشة إن وجد","inputText":"النص المراد كتابته عند INPUT_TEXT","x":0.5,"y":0.5,"endX":0.5,"endY":0.2,"continuePlaying":$wasGameplayRunning,"replyOneLineAr":"رد عربي قصير جداً في سطر واحد"}""")
        }

        val chain = getModelChainForTier(ModelRouteTier.FLASH)
        val result = withModelChainResilience(
            timeoutMs = 30_000L,
            candidates = listOf(
                ModelCallCandidate(chain[0]) { modelClient.generateGeneralArabicText(prompt) },
                ModelCallCandidate(chain[1]) { modelClient.generateLiteGeneralArabicText(prompt) },
                ModelCallCandidate(chain[2]) { modelClient.generateGeneralArabicTextThird(prompt) }
            )
        )
        val parsed = parseMiniChatDirectiveJson(result.value)
        return ModelResponseMetadata(
            value = parsed,
            tier = ModelRouteTier.FLASH,
            intendedModel = chain[0],
            modelUsed = result.modelUsed,
            usedFallback = result.usedFallback
        )
    }

    internal fun parseMiniChatDirectiveJson(raw: String): MiniChatDirectiveResult {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching {
                val obj = JSONObject(cleaned.substring(start, end + 1))
                val reply = obj.optString("replyOneLineAr", "").trim()
                    .replace('\n', ' ')
                    .take(120)
                return MiniChatDirectiveResult(
                    shouldAct = obj.optBoolean("shouldAct", false),
                    action = obj.optString("action", "NONE").uppercase(),
                    targetText = obj.optString("text", ""),
                    inputText = obj.optString("inputText", ""),
                    x = obj.optDouble("x", 0.5).toFloat().coerceIn(0.02f, 0.98f),
                    y = obj.optDouble("y", 0.5).toFloat().coerceIn(0.02f, 0.98f),
                    endX = obj.optDouble("endX", 0.5).toFloat().coerceIn(0.02f, 0.98f),
                    endY = obj.optDouble("endY", 0.2).toFloat().coerceIn(0.02f, 0.98f),
                    continuePlaying = obj.optBoolean("continuePlaying", false),
                    replyOneLineAr = reply.ifBlank { "تم استلام توجيهك وتنفيذه على الشاشة." }
                )
            }
        }
        val singleLine = cleaned.replace('\n', ' ').trim().take(120)
        return MiniChatDirectiveResult(
            shouldAct = false,
            action = "NONE",
            targetText = "",
            inputText = "",
            x = 0.5f,
            y = 0.5f,
            endX = 0.5f,
            endY = 0.2f,
            continuePlaying = false,
            replyOneLineAr = singleLine.ifBlank { "تم تنفيذ طلبك." }
        )
    }

    suspend fun pingModelTest(
        onStatusUpdate: (suspend (String) -> Unit)? = null
    ): Pair<Boolean, String> {
        val startTime = System.currentTimeMillis()
        val chain = getModelChainForTier(ModelRouteTier.FLASH)
        val result = withModelChainResilience(
            timeoutMs = 20_000L,
            onStatusUpdate = onStatusUpdate,
            candidates = listOf(
                ModelCallCandidate(chain[0]) { modelClient.pingModelTest() },
                ModelCallCandidate(chain[1]) { modelClient.pingModelTestFallback() },
                ModelCallCandidate(chain[2]) { modelClient.pingModelTestThird() }
            )
        )
        val elapsed = System.currentTimeMillis() - startTime
        val reply = result.value
        val modelTag = if (result.usedFallback) {
            " • نموذج احتياطي: ${result.modelUsed}"
        } else {
            ""
        }
        val detail = if (reply.isNotBlank()) {
            "متصل بنجاح ($reply) خلال ${elapsed}ms$modelTag"
        } else {
            "متصل بنجاح خلال ${elapsed}ms$modelTag"
        }
        return true to detail
    }

    private fun parsePlanJson(originalCommand: String, json: JSONObject): AgentPlan {
        val summary = json.optString("understandingSummaryAr", "تحليل الأمر وتجهيز خطوات التنفيذ")
        val spoken = json.optString("spokenReplyAr", "تم تجهيز الخطة، يمكنك مراجعتها والبدء بالتنفيذ.")
        val stepsArray = json.optJSONArray("steps")
        val steps = mutableListOf<AgentPlanStep>()

        if (stepsArray != null) {
            for (i in 0 until stepsArray.length()) {
                val obj = stepsArray.optJSONObject(i) ?: continue
                val actionType = StepActionType.fromString(obj.optString("actionType"))
                val rawRequiresApproval = obj.optBoolean("requiresApproval", false)
                val enforcedApproval = rawRequiresApproval ||
                    actionType == StepActionType.SEND_MESSAGE ||
                    actionType == StepActionType.CHANGE_SETTING ||
                    isSensitiveText(obj.optString("titleAr") + " " + obj.optString("descriptionAr"))

                val rawHour = obj.optInt("hour", -1)
                val rawMinute = obj.optInt("minute", -1)

                steps.add(
                    AgentPlanStep(
                        stepNumber = obj.optInt("stepNumber", i + 1),
                        titleAr = obj.optString("titleAr", "خطوة ${i + 1}"),
                        descriptionAr = obj.optString("descriptionAr", ""),
                        actionType = actionType,
                        targetAppName = obj.optString("targetAppName", ""),
                        targetQueryOrText = obj.optString("targetQueryOrText", ""),
                        expectedScreenHint = obj.optString("expectedScreenHint", "تحديث الشاشة بنجاح"),
                        requiresApproval = enforcedApproval,
                        safetyReasonAr = obj.optString(
                            "safetyReasonAr",
                            if (enforcedApproval) "يتطلب هذا الإجراء موافقتك الصريحة قبل التنفيذ حمايةً لخصوصيتك." else ""
                        ),
                        hour = rawHour.takeIf { it in 0..23 },
                        minute = rawMinute.takeIf { it in 0..59 }
                    )
                )
            }
        }

        if (steps.isEmpty()) {
            steps.add(
                AgentPlanStep(
                    stepNumber = 1,
                    titleAr = "الرد على الطلب",
                    descriptionAr = spoken,
                    actionType = StepActionType.SPEAK_ANSWER,
                    expectedScreenHint = "عرض الإجابة للمستخدم"
                )
            )
        }

        return AgentPlan(
            originalCommand = originalCommand,
            understandingSummaryAr = summary,
            spokenReplyAr = spoken,
            steps = steps
        )
    }

    private fun isSensitiveText(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("حذف", "إرسال", "دفع", "شراء", "تحويل", "كلمة المرور", "بصمة", "delete", "pay", "send")
            .any { lower.contains(it) }
    }
}
