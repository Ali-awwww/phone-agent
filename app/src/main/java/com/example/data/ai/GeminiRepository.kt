package com.example.data.ai

import android.util.Log
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.AppFolder
import com.example.model.ScreenSnapshot
import com.example.model.ScreenVerificationResult
import com.example.model.StepActionType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiRepository"

class GeminiRepository {

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

    private val plannerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = planSchema
            },
            systemInstruction = content {
                text(
                    """
                    أنت «وكيل الهاتف»، مساعد أندرويد ذكي وودود يتحدث العربية الفصحى البسيطة.
                    مهمتك تحويل طلب المستخدم إلى خطوات عملية واضحة ومتسلسلة لتنفيذها على الهاتف.
                    القواعد الصارمة (ممنوع الاختراع أو الهلوسة):
                    1. اعتمد واجهات أندرويد الرسمية أولاً (OPEN_APP، SET_ALARM، SET_REMINDER، SEARCH_IN_APP، SEND_MESSAGE، CHANGE_SETTING) ثم استخدام إمكانية الوصول (TAP_ELEMENT، INPUT_TEXT، SCROLL_SCREEN، READ_SCREEN_AND_SUMMARIZE).
                    2. لا تخترع أبداً أسماء تطبيقات غير موجودة في قائمة التطبيقات المثبتة المرسلة لك، ولا تخترع أسماء أزرار غير مرصودة في حالة الشاشة الحالية، ولا تخترع أرقام هواتف غير مذكورة في طلب المستخدم.
                    3. اجعل requiresApproval = true دائماً لأي خطوة تتضمن: إرسال رسالة (SEND_MESSAGE)، حذف بيانات، دفع مالي، تغيير إعداد مهم (CHANGE_SETTING)، أو أي إجراء خارجي حساس.
                    4. لا تتجاوز أبداً كلمة مرور أو بصمة أو شاشة دفع؛ بل ضع خطوة تطلب تدخل المستخدم وموافقته.
                    5. كل النصوص الموجهة للمستخدم يجب أن تكون بالعربية الواضحة المختصرة.
                    """.trimIndent()
                )
            }
        )
    }

    private val audioPlannerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = audioCommandSchema
            }
        )
    }

    private val verifierModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = verificationSchema
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
                text("أنت «وكيل الهاتف»، مساعد ذكي باللغة العربية. أجب باختصار ووضوح وبأسلوب عملي ودود.")
            }
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

            val response = withTimeout(60_000L) {
                plannerModel.generateContent(prompt)
            }
            val jsonText = response.text ?: throw IllegalStateException("لم يُرجع النموذج استجابة صالحة.")
            return parsePlanJson(userCommand, JSONObject(jsonText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate command plan", e)
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
            val response = withTimeout(60_000L) {
                audioPlannerModel.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text(prompt)
                    }
                )
            }
            val jsonText = response.text ?: throw IllegalStateException("لم يتم استلام رد من النموذج الصوتي.")
            val json = JSONObject(jsonText)
            val transcript = json.optString("transcriptAr", "").ifBlank { "أمر صوتي" }
            val plan = parsePlanJson(transcript, json)
            return transcript to plan
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process audio command", e)
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
            val response = withTimeout(45_000L) {
                verifierModel.generateContent(prompt)
            }
            val json = JSONObject(response.text ?: "{}")
            ScreenVerificationResult(
                verified = json.optBoolean("verified", true),
                explanationAr = json.optString("explanationAr", "تم فحص الشاشة بنجاح."),
                suggestedFixAr = json.optString("suggestedFixAr", ""),
                sensitiveUiDetected = json.optBoolean("sensitiveUiDetected", false)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Screen verification failed", e)
            ScreenVerificationResult(
                verified = false,
                explanationAr = "تعذر التحقق التلقائي من الشاشة: ${e.localizedMessage ?: "خطأ في الاتصال"}",
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

            val response = withTimeout(60_000L) {
                generalArabicModel.generateContent(prompt)
            }
            return response.text?.trim()
                ?: throw IllegalStateException("لم يُرجع النموذج ملخصاً.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to summarize app", e)
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

            val response = withTimeout(60_000L) {
                categorizerModel.generateContent(prompt)
            }
            val json = JSONObject(response.text ?: "{}")
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
            Log.e(TAG, "App categorization failed", e)
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
            val response = withTimeout(45_000L) {
                generalArabicModel.generateContent(prompt)
            }
            response.text?.trim() ?: "أنا معك، أخبرني بما تريد تنفيذه على هاتفك."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Conversational reply failed", e)
            throw e
        }
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
