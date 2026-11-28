package com.example.executor

data class ToolParameter(
    val name: String,
    val type: String, // "string", "int", "boolean", "enum"
    val description: String,
    val required: Boolean = false
)

data class AgentToolDefinition(
    val name: String,
    val titleAr: String,
    val descriptionAr: String,
    val parameters: List<ToolParameter>,
    val requiresApproval: Boolean = false,
    val category: String = "CORE"
)

data class ToolResolutionResult(
    val toolName: String,
    val intent: ChatRouteIntent,
    val confidence: Float,
    val errorReason: String? = null
)

object ToolRegistry {

    private val toolsMap = mutableMapOf<String, AgentToolDefinition>()

    init {
        registerDefaultTools()
    }

    private fun registerDefaultTools() {
        register(
            AgentToolDefinition(
                name = "open_app",
                titleAr = "فتح تطبيق",
                descriptionAr = "فتح أو تشغيل تطبيق مثبت على الجهاز بالاسم أو الحزمة.",
                parameters = listOf(
                    ToolParameter("appName", "string", "اسم التطبيق المراد فتحه", true)
                ),
                requiresApproval = false,
                category = "DEVICE"
            )
        )
        register(
            AgentToolDefinition(
                name = "dial_phone",
                titleAr = "فتح شاشة الاتصال",
                descriptionAr = "فتح تطبيق الهاتف برقم أو اسم المطلوب عبر واجهة الاتصال الرسمية دون إجراء مكالمة صامتة.",
                parameters = listOf(
                    ToolParameter("target", "string", "الاسم أو الرقم المطلوب الاتصال به", true)
                ),
                requiresApproval = false,
                category = "COMMUNICATION"
            )
        )
        register(
            AgentToolDefinition(
                name = "send_message",
                titleAr = "إرسال رسالة",
                descriptionAr = "إرسال رسالة نصية أو عبر تطبيقات التواصل (واتساب وغيرها).",
                parameters = listOf(
                    ToolParameter("recipient", "string", "اسم المستلم أو رقمه", true),
                    ToolParameter("messageBody", "string", "نص الرسالة المراد إرسالها", true)
                ),
                requiresApproval = true,
                category = "COMMUNICATION"
            )
        )
        register(
            AgentToolDefinition(
                name = "file_management",
                titleAr = "إدارة وفحص الملفات",
                descriptionAr = "فحص التخزين، سلة المهملات (7 أيام)، استعادة الملفات، تنظيف الملفات الكبيرة والمكررة والمؤقتة والمجلدات الفارغة مع الموافقة لكل دفعة.",
                parameters = listOf(
                    ToolParameter("queryText", "string", "نوع الفحص أو العملية المطلوبة", false)
                ),
                requiresApproval = true,
                category = "FILES"
            )
        )
        register(
            AgentToolDefinition(
                name = "play_game",
                titleAr = "اللعب الآلي والتحكم الذكي",
                descriptionAr = "تولي اللعب أو تنفيذ المهام البصرية والذكية التفاعلية خطوة بخطوة.",
                parameters = listOf(
                    ToolParameter("goalText", "string", "هدف اللعبة أو المهمة المطلوبة", true)
                ),
                requiresApproval = false,
                category = "GAME"
            )
        )
        register(
            AgentToolDefinition(
                name = "set_alarm",
                titleAr = "ضبط منبه أو تذكير",
                descriptionAr = "إنشاء منبه أو تذكير أو مهمة جديدة مع تحديد الوقت أو التأخير الزمني.",
                parameters = listOf(
                    ToolParameter("title", "string", "عنوان التذكير أو المهمة", true),
                    ToolParameter("taskType", "string", "نوع المهمة: ALARM, REMINDER, TASK", true),
                    ToolParameter("delayMinutes", "int", "التأخير بالدقائق إن وجد", false),
                    ToolParameter("hour", "int", "الساعة المحددة", false),
                    ToolParameter("minute", "int", "الدقيقة المحددة", false)
                ),
                requiresApproval = false,
                category = "PRODUCTIVITY"
            )
        )
        register(
            AgentToolDefinition(
                name = "read_screen",
                titleAr = "قراءة الشاشة الحالية",
                descriptionAr = "تحليل وفحص محتوى الشاشة الحالية والنصوص والأزرار المرئية بدقة.",
                parameters = emptyList(),
                requiresApproval = false,
                category = "SYSTEM"
            )
        )
        register(
            AgentToolDefinition(
                name = "show_apps",
                titleAr = "عرض التطبيقات والمجلدات",
                descriptionAr = "عرض التطبيقات المثبتة مصنفة حسب المجلدات (ألعاب، عمل، تواصل، تسوق، وسائط، أدوات).",
                parameters = listOf(
                    ToolParameter("folderKey", "string", "مفتاح المجلد", false),
                    ToolParameter("searchQuery", "string", "استعلام البحث عن التطبيق", false)
                ),
                requiresApproval = false,
                category = "SYSTEM"
            )
        )
        register(
            AgentToolDefinition(
                name = "show_action_history",
                titleAr = "سجل الإجراءات",
                descriptionAr = "عرض سجل الإجراءات المنفذة السابقة مع إمكانية التراجع.",
                parameters = emptyList(),
                requiresApproval = false,
                category = "SYSTEM"
            )
        )
        register(
            AgentToolDefinition(
                name = "open_settings",
                titleAr = "فتح الإعدادات والتحكم",
                descriptionAr = "فتح إعدادات الجهاز أو تغيير إعدادات الصوت والبلوتوث والواي فاي.",
                parameters = listOf(
                    ToolParameter("settingName", "string", "اسم الإعداد المراد تعديله أو فتحه", true)
                ),
                requiresApproval = true,
                category = "DEVICE"
            )
        )
        register(
            AgentToolDefinition(
                name = "morning_routine",
                titleAr = "الروتين الصباحي",
                descriptionAr = "تشغيل اختصار الروتين الصباحي بأمر واحد بعد موافقة المستخدم الأولى.",
                parameters = emptyList(),
                requiresApproval = true,
                category = "PRODUCTIVITY"
            )
        )
        register(
            AgentToolDefinition(
                name = "summarize_day",
                titleAr = "لخّص يومي",
                descriptionAr = "توليد ملخص عربي ليوم المستخدم استناداً إلى سجل الإجراءات والمهام والعادات.",
                parameters = emptyList(),
                requiresApproval = false,
                category = "PRODUCTIVITY"
            )
        )
    }

    @Synchronized
    fun register(tool: AgentToolDefinition) {
        toolsMap[tool.name] = tool
    }

    @Synchronized
    fun getTool(name: String): AgentToolDefinition? = toolsMap[name]

    @Synchronized
    fun getAllTools(): List<AgentToolDefinition> = toolsMap.values.toList()

    /**
     * توليد وصف منظم للأدوات ليتم تمريره إلى نماذج Gemini كجزء من سياق النظام والطلبات (F25).
     */
    fun getRegistryPromptDescription(): String {
        return buildString {
            appendLine("=== سجل الأدوات المتاحة (ToolRegistry) ===")
            getAllTools().forEach { tool ->
                appendLine("- أداة: ${tool.name} (${tool.titleAr})")
                appendLine("  الوصف: ${tool.descriptionAr}")
                if (tool.parameters.isNotEmpty()) {
                    appendLine("  المعاملات:")
                    tool.parameters.forEach { param ->
                        appendLine("    * ${param.name} (${param.type}, ${if (param.required) "إلزامي" else "اختياري"}): ${param.description}")
                    }
                }
                appendLine("  يتطلب موافقة حساسة: ${if (tool.requiresApproval) "نعم" else "لا"}")
            }
            appendLine("==========================================")
        }.trim()
    }

    /**
     * تحليل طلب المستخدم ومطابقته مع أدوات السجل، مع إرجاع خطأ صريح في حال كانت الأداة غير معروفة (S1 + F25).
     */
    fun resolveIntent(
        rawInput: String,
        knownAppNames: List<String> = emptyList()
    ): ToolResolutionResult {
        val raw = rawInput.trim()
        val intent = ChatIntentRouter.classifyIntentInternal(raw, knownAppNames)

        val toolName = when (intent) {
            is ChatRouteIntent.MorningRoutine -> "morning_routine"
            is ChatRouteIntent.SummarizeMyDay -> "summarize_day"
            is ChatRouteIntent.ReadScreen -> "read_screen"
            is ChatRouteIntent.ShowActionHistory -> "show_action_history"
            is ChatRouteIntent.FileManagement -> "file_management"
            is ChatRouteIntent.StartGameplay -> "play_game"
            is ChatRouteIntent.ShowApps -> "show_apps"
            is ChatRouteIntent.LocalOpenApp -> "open_app"
            is ChatRouteIntent.LocalDialPhone -> "dial_phone"
            is ChatRouteIntent.LocalToggleWifi -> "open_settings"
            is ChatRouteIntent.ListTasksAndReminders,
            is ChatRouteIntent.CancelTaskOrReminder,
            is ChatRouteIntent.CreateTaskOrReminder,
            is ChatRouteIntent.PromptReminderDetails -> "set_alarm"
            is ChatRouteIntent.ExecuteCommandPlan -> {
                val norm = ChatIntentRouter.normalizeDigitsAndArabic(raw).lowercase()
                if (norm.contains("ارسل") || norm.contains("رسالة") || norm.contains("send")) {
                    "send_message"
                } else if (norm.contains("ضبط") || norm.contains("اعدادات") || norm.contains("واي فاي") || norm.contains("بلوتوث")) {
                    "open_settings"
                } else {
                    "open_app"
                }
            }
            is ChatRouteIntent.ConversationalChat -> {
                val norm = ChatIntentRouter.normalizeDigitsAndArabic(raw).lowercase()
                if (norm.startsWith("افعل") || norm.startsWith("تشغيل أداة مجهولة")) {
                    return ToolResolutionResult(
                        toolName = "UNKNOWN",
                        intent = intent,
                        confidence = 0f,
                        errorReason = "أداة غير معروفة أو غير مدعومة في السجل: $raw"
                    )
                }
                return ToolResolutionResult(
                    toolName = "conversational_chat",
                    intent = intent,
                    confidence = 1f
                )
            }
        }

        val toolDef = getTool(toolName)
        if (toolDef == null && toolName != "conversational_chat") {
            return ToolResolutionResult(
                toolName = "UNKNOWN",
                intent = intent,
                confidence = 0f,
                errorReason = "أداة غير معروفة: $toolName"
            )
        }

        return ToolResolutionResult(
            toolName = toolName,
            intent = intent,
            confidence = 0.95f
        )
    }
}
