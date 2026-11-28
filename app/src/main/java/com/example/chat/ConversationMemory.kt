package com.example.chat

import java.util.Locale

data class ChatTurn(
    val role: String, // "user" or "model"
    val text: String
)

object ConversationMemory {

    fun formatHistory(
        turns: List<ChatTurn>,
        maxTurns: Int = 15,
        maxCharsPerMessage: Int = 500,
        maxTotalChars: Int = 4000
    ): String {
        if (turns.isEmpty() || maxTurns <= 0 || maxTotalChars <= 0) return ""
        val recent = turns.takeLast(maxTurns * 2)
        val keptLinesReversed = mutableListOf<String>()
        var totalChars = 0

        // F18: الاقتطاع دائماً من الأقدم — نبدأ من أحدث رسالة ونحتفظ بالأحدث أولاً
        for (turn in recent.asReversed()) {
            val truncatedText = if (turn.text.length > maxCharsPerMessage) {
                turn.text.take(maxCharsPerMessage) + "..."
            } else {
                turn.text
            }
            val prefix = if (turn.role == "user") "المستخدم: " else "المساعد: "
            val line = "$prefix$truncatedText"
            val addedLen = line.length + if (keptLinesReversed.isEmpty()) 0 else 1
            if (totalChars + addedLen > maxTotalChars) {
                if (keptLinesReversed.isEmpty() && maxTotalChars > prefix.length + 3) {
                    val allowedBody = (maxTotalChars - prefix.length - 3).coerceAtLeast(1)
                    keptLinesReversed.add("$prefix${truncatedText.take(allowedBody)}...")
                }
                break
            }
            keptLinesReversed.add(line)
            totalChars += addedLen
        }
        return keptLinesReversed.asReversed().joinToString("\n").trim()
    }

    fun formatHistoryWithSummaries(
        summaries: List<String>,
        turns: List<ChatTurn>,
        maxTurns: Int = 15,
        maxCharsPerMessage: Int = 500,
        maxTotalChars: Int = 4500
    ): String {
        val summaryBlock = if (summaries.isNotEmpty()) {
            val joinedSummaries = summaries
                .filter { it.isNotBlank() }
                .take(3)
                .joinToString("\n- ", prefix = "ملخص الحوار السابق المحفوظ:\n- ")
            joinedSummaries.take(1200)
        } else {
            ""
        }
        val remainingBudget = (maxTotalChars - summaryBlock.length - 2).coerceAtLeast(600)
        val recentBlock = formatHistory(
            turns = turns,
            maxTurns = maxTurns,
            maxCharsPerMessage = maxCharsPerMessage,
            maxTotalChars = remainingBudget
        )
        return listOf(summaryBlock, recentBlock)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .trim()
    }

    fun buildDeterministicTurnSummary(turns: List<ChatTurn>, maxPoints: Int = 4): String {
        if (turns.isEmpty()) return ""
        val userTopics = turns
            .filter { it.role == "user" && it.text.isNotBlank() }
            .map { it.text.replace('\n', ' ').trim().take(70) }
            .distinct()
            .take(maxPoints)
        val agentHighlights = turns
            .filter { it.role == "model" && it.text.isNotBlank() }
            .map { it.text.replace('\n', ' ').trim().take(70) }
            .distinct()
            .take(2)
        return buildString {
            append("ملخص ${turns.size} رسائل سابقة: ")
            if (userTopics.isNotEmpty()) {
                append("موضوعات المستخدم (${userTopics.joinToString("؛ ")})")
            }
            if (agentHighlights.isNotEmpty()) {
                if (userTopics.isNotEmpty()) append(" — ")
                append("أبرز الردود (${agentHighlights.joinToString("؛ ")})")
            }
        }.trim()
    }

    fun isInstalledAppQuestion(text: String): Boolean {
        val norm = text.lowercase(Locale("ar"))
        val keywords = listOf(
            "هو لدي الان",
            "هي لدي الان",
            "هل هي مثبتة",
            "هل هو مثبت",
            "هل لدي",
            "هل عندي",
            "هل موجود",
            "موجودة لدي",
            "موجود لدي",
            "مثبتة عندي",
            "مثبت عندي"
        )
        return keywords.any { norm.contains(it) }
    }

    fun findAppInHistory(historyText: String, installedLabels: List<String>): String? {
        val normHistory = historyText.lowercase(Locale("ar"))
        for (label in installedLabels) {
            val cleanLabel = label.lowercase(Locale("ar")).trim()
            if (cleanLabel.length >= 2 && normHistory.contains(cleanLabel)) {
                return label
            }
        }
        val aliases = mapOf(
            "ببجي" to "PUBG MOBILE",
            "pubg" to "PUBG MOBILE",
            "واتساب" to "WhatsApp",
            "تليجرام" to "Telegram",
            "فيسبوك" to "Facebook",
            "انستغرام" to "Instagram",
            "تيك توك" to "TikTok"
        )
        for ((alias, canonical) in aliases) {
            if (normHistory.contains(alias)) {
                val matched = installedLabels.firstOrNull { it.equals(canonical, ignoreCase = true) }
                if (matched != null) return matched
            }
        }
        return null
    }
}
