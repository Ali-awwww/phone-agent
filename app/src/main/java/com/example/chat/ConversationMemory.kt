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
        if (turns.isEmpty()) return ""
        val recent = turns.takeLast(maxTurns * 2)
        val sb = StringBuilder()
        var totalChars = 0

        for (turn in recent) {
            val truncatedText = if (turn.text.length > maxCharsPerMessage) {
                turn.text.take(maxCharsPerMessage) + "..."
            } else {
                turn.text
            }
            val prefix = if (turn.role == "user") "المستخدم: " else "المساعد: "
            val line = "$prefix$truncatedText\n"
            if (totalChars + line.length > maxTotalChars) break
            sb.append(line)
            totalChars += line.length
        }
        return sb.toString().trim()
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
