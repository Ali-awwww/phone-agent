package com.example.verifier

import com.example.model.ScreenSnapshot
import com.example.security.SensitiveDataScrubber

/**
 * بديل محلي لتلخيص محتوى الشاشة عند انقطاع الشبكة أو فشل Gemini أو في وضع الخصوصية:
 * ينظّم النصوص والأزرار وحقول الإدخال بوضوح محلياً على الجهاز دون إرسال أي بيانات للخارج.
 */
object LocalScreenSummarizer {

    fun summarizeSnapshot(
        snapshot: ScreenSnapshot,
        appLabel: String = snapshot.packageName,
        privacyMode: Boolean = false
    ): String {
        if (snapshot.hasSensitiveSecurityField) {
            return "رصدت حقلاً حساساً (كلمة مرور أو دفع أو بصمة) على الشاشة الحالية؛ أمتنع عن قراءة أو تلخيص محتواها حفاظاً على أمانك."
        }

        val resolvedLabel = SensitiveDataScrubber.scrub(appLabel.ifBlank { snapshot.packageName.ifBlank { "الشاشة الحالية" } })
        val pkgNote = snapshot.packageName.takeIf { it.isNotBlank() && it != resolvedLabel }?.let { " ($it)" }.orEmpty()

        val uniqueTexts = snapshot.visibleTexts
            .map { SensitiveDataScrubber.scrub(it).trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val uniqueButtons = snapshot.clickableLabels
            .map { SensitiveDataScrubber.scrub(it).trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val uniqueInputs = snapshot.editableHints
            .map { SensitiveDataScrubber.scrub(it).trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return buildString {
            appendLine("📱 التطبيق الحالي: $resolvedLabel$pkgNote")
            if (privacyMode) {
                appendLine("🔒 وضع الخصوصية مفعّل — ملخص محلي مقتضب دون اتصال سحابي.")
            } else {
                appendLine("📝 ملخص الشاشة المحلي (دون إرسال بيانات إلى السحابة):")
            }

            if (!privacyMode && uniqueTexts.isNotEmpty()) {
                appendLine()
                appendLine("• أبرز النصوص الظاهرة (${uniqueTexts.size}):")
                uniqueTexts.take(12).forEachIndexed { idx, line ->
                    appendLine("  ${idx + 1}. $line")
                }
                if (uniqueTexts.size > 12) {
                    appendLine("  … و${uniqueTexts.size - 12} نصوص أخرى")
                }
            }

            if (uniqueButtons.isNotEmpty()) {
                appendLine()
                appendLine("• الأزرار التفاعلية المتاحة:")
                appendLine("  ${uniqueButtons.take(10).joinToString(" | ")}")
            }

            if (uniqueInputs.isNotEmpty()) {
                appendLine()
                appendLine("• حقول الإدخال:")
                appendLine("  ${uniqueInputs.take(6).joinToString(" | ")}")
            }

            if (uniqueTexts.isEmpty() && uniqueButtons.isEmpty() && uniqueInputs.isEmpty()) {
                appendLine("• لا توجد نصوص أو أزرار مقروءة على هذه الشاشة.")
            }
        }.trim()
    }

    fun summarizeFromCapturedTexts(
        appName: String,
        packageName: String,
        capturedScreenTexts: List<String>
    ): String {
        val cleanName = SensitiveDataScrubber.scrub(appName.ifBlank { packageName })
        val lines = capturedScreenTexts
            .flatMap { it.lines() }
            .map { SensitiveDataScrubber.scrub(it).trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return buildString {
            appendLine("📱 ملخص محلي لتطبيق «$cleanName» ($packageName):")
            appendLine("🔒 أُعدّ الملخص محلياً دون إرسال بيانات إلى السحابة:")
            if (lines.isEmpty()) {
                appendLine("• لم يتم التقاط نصوص قابلة للعرض.")
            } else {
                lines.take(14).forEach { line ->
                    appendLine("• $line")
                }
            }
        }.trim()
    }
}
