package com.example.security

import com.example.model.ScreenSnapshot
import java.util.Locale

/**
 * منظّف البيانات الحساسة (Sensitive Data Scrubber):
 * يُطبَّق إلزامياً على كل نص وسياق قبل خروجه من الجهاز إلى نموذج Gemini (F07)،
 * وفي سجلات الإجراءات وسجلات التصحيح (F06)، لضمان عدم تسريب أي بيانات شخصية أو مالية أو سرية.
 */
object SensitiveDataScrubber {

    private const val MASK_PASSWORD = "[كلمة-مرور-محذوفة]"
    private const val MASK_OTP = "[رمز-تحقق-محذوف]"
    private const val MASK_PHONE = "[هاتف-محذوف]"
    private const val MASK_EMAIL = "[بريد-محذوف]"
    private const val MASK_ID = "[هوية-محذوفة]"
    private const val MASK_CARD = "[بطاقة-دفع-محذوفة]"
    private const val MASK_CVV = "[CVV-محذوف]"
    private const val MASK_BANK = "[حساب-بنكي-محذوف]"
    private const val MASK_ADDRESS = "[عنوان-محذوف]"

    private val EMAIL_REGEX = Regex(
        """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""
    )

    private val IBAN_REGEX = Regex(
        """\b[A-Z]{2}\d{2}[A-Za-z0-9]{11,30}\b"""
    )

    private val SSN_REGEX = Regex(
        """\b\d{3}-\d{2}-\d{4}\b"""
    )

    private val NATIONAL_ID_WITH_KEYWORD_REGEX = Regex(
        """(?i)(?:هوية|الهوية(?:\s*الوطنية)?|إقامة|اقامة|سجل\s*مدني|national\s*id|iqama|civil\s*id|id\s*number)(?:\s*رقم)?\s*[:=؛،,\-]?\s*([12]\d{9})(?!\d)"""
    )

    private val CVV_WITH_KEYWORD_REGEX = Regex(
        """(?i)(?:cvv2?|cvc2?|security\s*code|الرمز\s*الثلاثي|رمز\s*الأمان|كود\s*الأمان|رمز\s*التحقق\s*للبطاقة)\s*[:=؛،,\-]?\s*(\d{3,4})(?!\d)"""
    )

    private val BANK_ACCOUNT_KEYWORD_REGEX = Regex(
        """(?i)(?:رقم\s*الحساب(?:\s*البنكي)?|حساب\s*بنكي|الآيبان|آيبان|bank\s*account|account\s*number)\s*[:=؛،,\-]?\s*([A-Z0-9]{8,24})(?!\d)"""
    )

    private val OTP_WITH_KEYWORD_REGEX = Regex(
        """(?i)(?:رمز\s*التحقق|كود\s*التحقق|رمز\s*التأكيد|كود\s*التأكيد|رمز\s*التفعيل|كود\s*التفعيل|كلمة\s*مرور\s*لمرة\s*واحدة|رمز\s*otp|كود\s*otp|otp|verification\s*code|verify\s*code|auth\s*code|confirmation\s*code|2fa(?:\s*code)?|one-time\s*password)(?:[^\d\n]{0,35})?(\d{4,8})(?!\d)"""
    )

    private val PASSWORD_KEYWORD_REGEX = Regex(
        """(?i)(?:كلمة\s*(?:المرور|السر)|الرقم\s*السري|رمز\s*(?:المرور|الدخول|الأمان)|الباسورد|password|passwd|passcode|secret|api_key|token|bearer)\s*(?:هو|هي|is|[:=؛،,\-])\s*([^\s,؛]+)"""
    )

    private val PHONE_WITH_KEYWORD_REGEX = Regex(
        """(?i)(?:هاتف|جوال|موبايل|تليفون|phone|mobile|tel|cell)\s*[:=؛،,\-]?\s*(\+?[\d\s\-()]{7,18})"""
    )

    private val SAUDI_GCC_PHONE_REGEX = Regex(
        """(?<!\d)(?:\+?966\s*5\d{8}|05\d{8}|01\d{7})(?!\d)"""
    )

    private val INTERNATIONAL_PHONE_REGEX = Regex(
        """(?<!\d)(?:\+\d{1,3}[\s\-]?)?(?:\(?\d{2,4}\)?[\s\-]?)?\d{3,4}[\s\-]?\d{3,4}(?!\d)"""
    )

    private val ADDRESS_KEYWORD_REGEX = Regex(
        """(?i)(?:العنوان|عنوان\s*السكن|الشارع|حي\s+|الرمز\s*البريدي|address|street|st\.|postal\s*code|zip\s*code)\s*[:=؛،,\-]\s*([^\n,؛]{4,60})"""
    )

    private val CARD_CANDIDATE_REGEX = Regex(
        """(?<!\d)(?:\d[ -]*?){13,19}(?!\d)"""
    )

    private val CARD_KEYWORD_NEARBY_REGEX = Regex(
        """(?i)(?:بطاقة|فيزا|ماستر(?:\s*كارد)?|مدى|دفع|ائتمان|card|visa|mastercard|amex|mada|credit|debit)"""
    )

    /**
     * تحويل الأرقام العربية المشرقية (٠-٩) والفارسية إلى أرقام لاتينية (0-9)
     * لتطبيق قواعد التدقيق بدقة متساوية.
     */
    fun normalizeArabicDigits(input: String): String {
        val sb = java.lang.StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append('0' + (ch - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (ch - '\u06F0'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * خوارزمية لُهن (Luhn Algorithm) المعتمدة عالمياً للتحقق من أرقام بطاقات الدفع البنكية.
     */
    fun isValidLuhn(numberString: String): Boolean {
        val digits = numberString.filter { it.isDigit() }
        if (digits.length !in 13..19) return false
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /**
     * التنقية الإلزامية الأساسية: تُطبَّق على أي نص قبل خروجه إلى Gemini أو تخزينه في السجلات.
     */
    fun scrub(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val normalized = normalizeArabicDigits(input)
        var result = normalized

        // 1. كلمات المرور والمفاتيح السرية
        result = PASSWORD_KEYWORD_REGEX.replace(result) { mr ->
            val full = mr.value
            val secret = mr.groupValues[1]
            full.replace(secret, MASK_PASSWORD)
        }

        // 2. رموز التحقق وOTP
        result = OTP_WITH_KEYWORD_REGEX.replace(result) { mr ->
            val full = mr.value
            val code = mr.groupValues[1]
            full.replace(code, MASK_OTP)
        }

        // 3. معلومات الدفع: رمز الأمان الثلاثي CVV
        result = CVV_WITH_KEYWORD_REGEX.replace(result) { mr ->
            val full = mr.value
            val cvv = mr.groupValues[1]
            full.replace(cvv, MASK_CVV)
        }

        // 4. أرقام الحسابات البنكية وIBAN
        result = IBAN_REGEX.replace(result, MASK_BANK)
        result = BANK_ACCOUNT_KEYWORD_REGEX.replace(result) { mr ->
            val full = mr.value
            val acc = mr.groupValues[1]
            full.replace(acc, MASK_BANK)
        }

        // 5. بطاقات الدفع (Luhn أو كلمات دلالية للبطاقات)
        result = CARD_CANDIDATE_REGEX.replace(result) { mr ->
            val candidate = mr.value
            val digitsOnly = candidate.filter { it.isDigit() }
            if (digitsOnly.length in 13..19 && (isValidLuhn(candidate) || CARD_KEYWORD_NEARBY_REGEX.containsMatchIn(result))) {
                MASK_CARD
            } else {
                candidate
            }
        }

        // 6. البريد الإلكتروني
        result = EMAIL_REGEX.replace(result, MASK_EMAIL)

        // 7. أرقام الهوية والإقامة وSSN
        result = SSN_REGEX.replace(result, MASK_ID)
        result = NATIONAL_ID_WITH_KEYWORD_REGEX.replace(result) { mr ->
            val full = mr.value
            val idNum = mr.groupValues[1]
            full.replace(idNum, MASK_ID)
        }

        // 8. العناوين السكنية المصحوبة بكلمات دلالية صريحة
        result = ADDRESS_KEYWORD_REGEX.replace(result) { mr ->
            val full = mr.value
            val addr = mr.groupValues[1]
            full.replace(addr, MASK_ADDRESS)
        }

        // 9. أرقام الهواتف (المصحوبة بكلمة دلالية أو بصيغة محلية/دولية صريحة)
        result = PHONE_WITH_KEYWORD_REGEX.replace(result) { mr ->
            val full = mr.value
            val phone = mr.groupValues[1]
            full.replace(phone, MASK_PHONE)
        }
        result = SAUDI_GCC_PHONE_REGEX.replace(result, MASK_PHONE)

        // التحقق من أرقام الهواتف الدولية مع تجنب التواريخ والأوقات
        result = INTERNATIONAL_PHONE_REGEX.replace(result) { mr ->
            val candidate = mr.value
            val digitsOnly = candidate.filter { it.isDigit() }
            val isTimeOrRatio = candidate.contains(':') || candidate.contains('.')
            if (digitsOnly.length in 8..15 && !isTimeOrRatio && candidate.startsWith("+")) {
                MASK_PHONE
            } else {
                candidate
            }
        }

        return result
    }

    /**
     * التحقق مما إذا كان النص يحتوي على بيانات حساسة تم رصدها.
     */
    fun containsSensitiveData(input: String?): Boolean {
        if (input.isNullOrBlank()) return false
        val scrubbed = scrub(input)
        return scrubbed != normalizeArabicDigits(input)
    }

    /**
     * تنقية نصوص السجلات وتجريدها من أحرف التحكم والأسطر الجديدة المفرطة لحماية Logcat وRoom.
     */
    fun scrubLogMessage(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val cleaned = scrub(input)
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned
    }

    /**
     * ملخص مقتضب مخصص لوضع الخصوصية (Privacy Mode):
     * لا يرسل نصوص الشاشة الكاملة إلى Gemini، بل يرسل فقط اسم الحزمة وهيكلية مقتضبة للأزرار التفاعلية.
     */
    fun scrubScreenSnapshotForPrivacyMode(snapshot: ScreenSnapshot?): String {
        if (snapshot == null) return "لا توجد نافذة نشطة"
        val pkg = snapshot.packageName.ifBlank { "تطبيق غير معروف" }
        val buttons = snapshot.clickableLabels
            .take(6)
            .map { scrub(it).take(30) }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        return buildString {
            appendLine("التطبيق الحالي: $pkg")
            appendLine("أزرار تفاعلية مقتضبة: ${buttons.ifBlank { "لا توجد أزرار ظاهرة" }}")
            if (snapshot.hasSensitiveSecurityField) {
                appendLine("تحذير أمان: شاشة تحتوي على حقول أمان أو دفع.")
            }
        }.trim()
    }

    /**
     * تقليص سياق الشاشة لوضع الخصوصية:
     * يستبدل نصوص الشاشة الكاملة بملخص مقتضب لحماية خصوصية المستخدم.
     */
    fun minimizeScreenContextForPrivacyMode(screenSummary: String?): String {
        if (screenSummary.isNullOrBlank()) return "سياق شاشة مقتضب (وضع الخصوصية مفعّل)"
        val scrubbed = scrub(screenSummary)
        val filtered = mutableListOf<String>()
        var inVisibleTextBlock = false
        for (rawLine in scrubbed.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            if (line.startsWith("النصوص الظاهرة")) {
                inVisibleTextBlock = true
                continue
            }
            if (line.startsWith("التطبيق الحالي:") ||
                line.startsWith("الأزرار") ||
                line.startsWith("عناصر تفاعلية") ||
                line.startsWith("حقول الإدخال") ||
                line.startsWith("تحذير أمان")
            ) {
                inVisibleTextBlock = false
                filtered.add(line.take(80))
            } else if (!inVisibleTextBlock) {
                filtered.add(line.take(80))
            }
        }
        return buildString {
            appendLine("وضع الخصوصية مفعّل — تم تقليص سياق الشاشة إلى الحد الأدنى:")
            if (filtered.isNotEmpty()) {
                filtered.take(4).forEach { appendLine(it) }
            } else {
                appendLine("ملخص مقتضب دون نصوص الشاشة التفصيلية.")
            }
        }.trim()
    }
}
