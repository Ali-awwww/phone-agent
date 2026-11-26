package com.example.data.ai

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
import com.example.security.safeLogE
import com.example.security.safeLogW
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiRepository"

internal interface GeminiModelClient {
    suspend fun generatePlanJson(prompt: String): String
    suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String): String
    suspend fun generateVerificationJson(prompt: String): String
    suspend fun generateCategorizationJson(prompt: String): String
    suspend fun generateGeneralArabicText(prompt: String): String
    suspend fun pingModelTest(): String = generateGeneralArabicText("اختبار اتصال سريع: رد بكلمة واحدة «متصل».")
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

    private val strictAgentSystemInstruction: String = """
        أنت «وكيل الهاتف»، مساعد أندرويد عربي مهذّب ومختصر جداً يتحدث العربية الفصحى البسيطة.
        أسلوبك الدائم: تشرح للمستخدم باختصار ما ستفعله قبل أن تفعله، ثم تخبره بالنتيجة الحقيقية بعدها دون إطالة.

        نطاقك المسموح فقط (لا تخرج عنه أبداً):
        1. تنظيم التطبيقات (عرضها، البحث فيها، تصنيفها في مجلدات، نقلها، إخفاء غير المستخدمة).
        2. تنفيذ الأوامر داخل التطبيقات (فتح تطبيق، البحث داخله، ضبط منبه أو تذكير، تجهيز رسالة بموافقة المستخدم، فتح إعدادات جهاز بسيطة).
        3. تجربة التطبيقات وتلخيص محتوى شاشاتها الفعلية بالعربية.

        قواعد الرفض الصارمة:
        - لا تُجب عن أي أسئلة عامة خارج الهاتف ولا تتحدث في مواضيع خارج نطاقك؛ وإذا سُئلت عن شيء خارج النطاق فاعتذر بلطف واختصار واقترح ما تستطيع فعله من المهام الثلاث أعلاه.
        - ارفض فوراً وبأدب أي طلب يتعلق بالممنوعات الثمانية التالية:
          (1) كلمات المرور، (2) البصمة، (3) الدفع، (4) الشراء، (5) إرسال الأموال، (6) حذف البيانات، (7) تغيير إعدادات الحسابات، (8) تجاوز قفل الشاشة.

        قواعد عدم الثقة ومنع الهلوسة:
        - لا تخترع أبداً أي أسماء تطبيقات غير موجودة في قائمة التطبيقات المثبتة، ولا تخترع أسماء أزرار أو عناصر غير مقروءة من الشاشة الفعلية، ولا تخترع أرقام هواتف.
        - يجب التحقق من الشاشة الفعلية عبر ScreenVerifier قبل كل خطوة وبعدها؛ وعند غياب العنصر المطلوب على الشاشة قُل بوضوح: «لم أجد هذا العنصر على الشاشة» وتوقف دون اختراع بدائل.
        - اعتمد واجهات أندرويد الرسمية أولاً ثم خدمة إمكانية الوصول عند الحاجة، واجعل requiresApproval = true لأي إجراء حساس مثل إرسال رسالة أو تعديل إعداد.
    """.trimIndent()

    private val plannerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = planSchema
            },
            systemInstruction = content {
                text(strictAgentSystemInstruction)
            }
        )
    }

    private val audioPlannerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = audioCommandSchema
            },
            systemInstruction = content {
                text(strictAgentSystemInstruction)
            }
        )
    }

    private val verifierModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
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
    }

    private val categorizerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = categorizationSchema
            }
        )
    }

    private val generalArabicModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(strictAgentSystemInstruction)
            }
        )
    }

    override suspend fun generatePlanJson(prompt: String): String {
        val response = plannerModel.generateContent(prompt)
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

    override suspend fun generateVerificationJson(prompt: String): String {
        val response = verifierModel.generateContent(prompt)
        return response.text ?: "{}"
    }

    override suspend fun generateCategorizationJson(prompt: String): String {
        val response = categorizerModel.generateContent(prompt)
        return response.text ?: "{}"
    }

    override suspend fun generateGeneralArabicText(prompt: String): String {
        val response = generalArabicModel.generateContent(prompt)
        return response.text?.trim() ?: throw IllegalStateException("لم يُرجع النموذج ملخصاً.")
    }

    override suspend fun pingModelTest(): String {
        return generateGeneralArabicText("اختبار اتصال سريع: رد بكلمة واحدة «متصل».")
    }
}

class GeminiRepository internal constructor(
    private val appCheckClient: AppCheckTokenClient,
    private val modelClient: GeminiModelClient,
    private val maxAppCheckAttempts: Int = MAX_APP_CHECK_ATTEMPTS,
    private val retryDelayMs: Long = DEFAULT_APP_CHECK_RETRY_DELAY_MS,
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

    suspend fun generateCommandPlan(
        userCommand: String,
        installedAppNames: List<String>,
        currentScreenSummary: String
    ): AgentPlan {
        try {
            val prompt = buildString {
                appendLine("طلب المستخدم: $userCommand")
                appendLine("أمثلة التطبيقات المثبتة على الجهاز: ${installedAppNames.take(50).joinToString("، ")}")
                appendLine("حالة الشاشة الحالية: $currentScreenSummary")
                appendLine("أنشئ خطة تنفيذ دقيقة خطوة بخطوة بصيغة JSON حسب المخطط المطلوب.")
            }

            val jsonText = withAppCheckRecovery {
                withTimeout(60_000L) {
                    modelClient.generatePlanJson(prompt)
                }
            }
            return parsePlanJson(userCommand, JSONObject(jsonText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Failed to generate command plan", e)
            throw e
        }
    }

    suspend fun transcribeAndPlanAudio(
        audioBytes: ByteArray,
        mimeType: String,
        installedAppNames: List<String>
    ): Pair<String, AgentPlan> {
        try {
            val prompt = buildString {
                appendLine("استمع إلى التسجيل الصوتي للمستخدم باللغة العربية، اكتب النص المفهوم في transcriptAr، ثم أنشئ خطة الخطوات التنفيذية.")
                appendLine("التطبيقات المتاحة: ${installedAppNames.take(40).joinToString("، ")}")
            }
            val jsonText = withAppCheckRecovery {
                withTimeout(60_000L) {
                    modelClient.generateAudioPlanJson(audioBytes, mimeType, prompt)
                }
            }
            val json = JSONObject(jsonText)
            val transcript = json.optString("transcriptAr", "").ifBlank { "أمر صوتي" }
            val plan = parsePlanJson(transcript, json)
            return transcript to plan
        } catch (e: CancellationException) {
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
            val jsonText = withAppCheckRecovery {
                withTimeout(45_000L) {
                    modelClient.generateVerificationJson(prompt)
                }
            }
            val json = JSONObject(jsonText)
            ScreenVerificationResult(
                verified = json.optBoolean("verified", true),
                explanationAr = json.optString("explanationAr", "تم فحص الشاشة بنجاح."),
                suggestedFixAr = json.optString("suggestedFixAr", ""),
                sensitiveUiDetected = json.optBoolean("sensitiveUiDetected", false)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Screen verification failed", e)
            ScreenVerificationResult(
                verified = false,
                explanationAr = "تعذر التحقق التلقائي من الشاشة: ${e.message ?: e.localizedMessage ?: "خطأ في الاتصال"}",
                suggestedFixAr = "تأكد من حالة الشاشة ثم اختر متابعة أو إلغاء."
            )
        }
    }

    suspend fun summarizeAppScreens(
        appName: String,
        packageName: String,
        capturedScreenTexts: List<String>
    ): String {
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

            return withAppCheckRecovery {
                withTimeout(60_000L) {
                    modelClient.generateGeneralArabicText(prompt)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Failed to summarize app", e)
            throw e
        }
    }

    suspend fun categorizeApps(apps: List<Pair<String, String>>): Map<String, String> {
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

            val jsonText = withAppCheckRecovery {
                withTimeout(60_000L) {
                    modelClient.generateCategorizationJson(prompt)
                }
            }
            val json = JSONObject(jsonText)
            val array = json.optJSONArray("assignments") ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val pkg = item.optString("packageName")
                val folder = item.optString("folderKey")
                if (pkg.isNotBlank() && folder.isNotBlank()) {
                    result[pkg] = AppFolder.fromKey(folder).name
                }
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "App categorization failed", e)
            throw e
        }
    }

    suspend fun conversationalReply(userMessage: String, contextSummary: String): String {
        return try {
            val prompt = """
                رسالة المستخدم الصوتية: $userMessage
                سياق الحالة الحالية: $contextSummary
                أجب برد عربي طبيعي ومختصر ومفيد كأنك تتحدث مع المستخدم مباشرة.
            """.trimIndent()
            withAppCheckRecovery {
                withTimeout(45_000L) {
                    modelClient.generateGeneralArabicText(prompt)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Conversational reply failed", e)
            throw e
        }
    }

    suspend fun pingModelTest(): Pair<Boolean, String> {
        val startTime = System.currentTimeMillis()
        val reply = withAppCheckRecovery {
            withTimeout(20_000L) {
                modelClient.pingModelTest()
            }
        }
        val elapsed = System.currentTimeMillis() - startTime
        val detail = if (reply.isNotBlank()) "متصل بنجاح ($reply) خلال ${elapsed}ms" else "متصل بنجاح خلال ${elapsed}ms"
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
