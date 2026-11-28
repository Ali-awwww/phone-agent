package com.example.executor

import com.example.data.local.AgentTaskEntity

sealed interface TaskCancelResolution {
    data object NoActiveTasks : TaskCancelResolution
    data class NeedsClarification(
        val candidateTasks: List<AgentTaskEntity>,
        val reasonAr: String
    ) : TaskCancelResolution

    data class NeedsConfirmation(
        val targetTask: AgentTaskEntity
    ) : TaskCancelResolution
}

/**
 * مطابق ذكي لطلبات إلغاء التذكيرات/المهام يمنع الحذف الصامت:
 * - إذا كان الاستعلام فارغاً أو مبهماً أو يطابق أكثر من عنصر: يعرض القائمة ويطلب التحديد.
 * - حتى عند تطابق عنصر واحد بدقة: يطلب تأكيد المستخدم قبل الحذف الفعلي.
 */
object TaskCancellationResolver {
    fun resolve(
        queryText: String,
        activeTasks: List<AgentTaskEntity>
    ): TaskCancelResolution {
        if (activeTasks.isEmpty()) {
            return TaskCancelResolution.NoActiveTasks
        }

        val normQuery = ChatIntentRouter.normalizeDigitsAndArabic(queryText).lowercase().trim()
        if (normQuery.isBlank()) {
            return TaskCancelResolution.NeedsClarification(
                candidateTasks = activeTasks,
                reasonAr = "لم تحدد أي تذكير أو مهمة تريد إلغاءها. اختر العنصر المطلوب من قائمتك التالية ثم أكّد الإلغاء:"
            )
        }

        val exactMatches = activeTasks.filter {
            ChatIntentRouter.normalizeDigitsAndArabic(it.title).lowercase().trim() == normQuery
        }
        val matches = if (exactMatches.size == 1) {
            exactMatches
        } else {
            activeTasks.filter {
                ChatIntentRouter.normalizeDigitsAndArabic(it.title).lowercase().contains(normQuery)
            }
        }

        return when {
            matches.isEmpty() -> TaskCancelResolution.NeedsClarification(
                candidateTasks = activeTasks,
                reasonAr = "لست متأكداً أي تذكير تقصد إلغاءه بـ «$queryText». هذه قائمتك الحالية لتختار منه وتؤكد الإلغاء:"
            )
            matches.size > 1 -> TaskCancelResolution.NeedsClarification(
                candidateTasks = matches,
                reasonAr = "يوجد أكثر من تذكير يطابق «$queryText». حدّد أياً منها تقصد ثم أكّد الإلغاء:"
            )
            else -> TaskCancelResolution.NeedsConfirmation(matches.first())
        }
    }
}
