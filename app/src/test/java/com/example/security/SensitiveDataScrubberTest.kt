package com.example.security

import com.example.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataScrubberTest {

    @Test
    fun `normalizeArabicDigits converts Arabic-Indic and Persian digits to Latin digits`() {
        val arabicIndic = "رمز التحقق: ٠١٢٣٤٥٦٧٨٩"
        val normalized = SensitiveDataScrubber.normalizeArabicDigits(arabicIndic)
        assertEquals("رمز التحقق: 0123456789", normalized)

        val mixed = "الهاتف: ٠٥٠١٢٣٤٥٦٧ والرقم 123"
        assertEquals("الهاتف: 0501234567 والرقم 123", SensitiveDataScrubber.normalizeArabicDigits(mixed))
    }

    @Test
    fun `isValidLuhn accurately validates valid and invalid card numbers`() {
        // بطاقة Visa صالحة وفق لُهن
        assertTrue(SensitiveDataScrubber.isValidLuhn("4111111111111111"))
        // بطاقة غير صالحة وفق لُهن (تغيير الرقم الأخير)
        assertFalse(SensitiveDataScrubber.isValidLuhn("4111111111111112"))
        // طول أقل من 13
        assertFalse(SensitiveDataScrubber.isValidLuhn("1234567890"))
    }

    @Test
    fun `scrub masks passwords, secrets and api tokens`() {
        val input1 = "كلمة المرور: MySecretPass!2026"
        val scrubbed1 = SensitiveDataScrubber.scrub(input1)
        assertTrue(scrubbed1.contains("[كلمة-مرور-محذوفة]"))
        assertFalse(scrubbed1.contains("MySecretPass!2026"))

        val input2 = "الرقم السري هو 987654"
        val scrubbed2 = SensitiveDataScrubber.scrub(input2)
        assertTrue(scrubbed2.contains("[كلمة-مرور-محذوفة]"))
        assertFalse(scrubbed2.contains("987654"))

        val input3 = "password = SecretPasswordXYZ"
        val scrubbed3 = SensitiveDataScrubber.scrub(input3)
        assertTrue(scrubbed3.contains("[كلمة-مرور-محذوفة]"))
        assertFalse(scrubbed3.contains("SecretPasswordXYZ"))
    }

    @Test
    fun `scrub masks OTP and verification codes in Arabic and English and Arabic-Indic digits`() {
        val input1 = "رمز التحقق الخاص بك هو 948210 لا تشاركه مع أحد"
        val scrubbed1 = SensitiveDataScrubber.scrub(input1)
        assertTrue(scrubbed1.contains("[رمز-تحقق-محذوف]"))
        assertFalse(scrubbed1.contains("948210"))

        val input2 = "كود التفعيل: ١٢٣٤٥٦"
        val scrubbed2 = SensitiveDataScrubber.scrub(input2)
        assertTrue(scrubbed2.contains("[رمز-تحقق-محذوف]"))
        assertFalse(scrubbed2.contains("123456"))

        val input3 = "Your OTP is 482910 for login"
        val scrubbed3 = SensitiveDataScrubber.scrub(input3)
        assertTrue(scrubbed3.contains("[رمز-تحقق-محذوف]"))
        assertFalse(scrubbed3.contains("482910"))

        val input4 = "2fa code: 883921"
        val scrubbed4 = SensitiveDataScrubber.scrub(input4)
        assertTrue(scrubbed4.contains("[رمز-تحقق-محذوف]"))
        assertFalse(scrubbed4.contains("883921"))
    }

    @Test
    fun `scrub masks local Saudi GCC and international phone numbers`() {
        val input1 = "اتصل على الرقم 0501234567 للمتابعة"
        val scrubbed1 = SensitiveDataScrubber.scrub(input1)
        assertTrue(scrubbed1.contains("[هاتف-محذوف]"))
        assertFalse(scrubbed1.contains("0501234567"))

        val input2 = "رقم الجوال: +966551234567"
        val scrubbed2 = SensitiveDataScrubber.scrub(input2)
        assertTrue(scrubbed2.contains("[هاتف-محذوف]"))
        assertFalse(scrubbed2.contains("+966551234567"))

        val input3 = "phone: +14155552671"
        val scrubbed3 = SensitiveDataScrubber.scrub(input3)
        assertTrue(scrubbed3.contains("[هاتف-محذوف]"))
        assertFalse(scrubbed3.contains("+14155552671"))
    }

    @Test
    fun `scrub masks email addresses`() {
        val input = "يرجى مراسلتنا على user.name+service@example.com أو info@wakeel.org"
        val scrubbed = SensitiveDataScrubber.scrub(input)
        assertFalse(scrubbed.contains("user.name+service@example.com"))
        assertFalse(scrubbed.contains("info@wakeel.org"))
        assertTrue(scrubbed.contains("[بريد-محذوف]"))
    }

    @Test
    fun `scrub masks National IDs Iqama and SSN`() {
        val input1 = "الهوية الوطنية: 1087654321"
        val scrubbed1 = SensitiveDataScrubber.scrub(input1)
        assertTrue(scrubbed1.contains("[هوية-محذوفة]"))
        assertFalse(scrubbed1.contains("1087654321"))

        val input2 = "إقامة رقم 2098765432"
        val scrubbed2 = SensitiveDataScrubber.scrub(input2)
        assertTrue(scrubbed2.contains("[هوية-محذوفة]"))
        assertFalse(scrubbed2.contains("2098765432"))

        val input3 = "SSN: 123-45-6789"
        val scrubbed3 = SensitiveDataScrubber.scrub(input3)
        assertTrue(scrubbed3.contains("[هوية-محذوفة]"))
        assertFalse(scrubbed3.contains("123-45-6789"))
    }

    @Test
    fun `scrub masks payment cards and CVV codes`() {
        // بطاقة Visa حقيقية التنسيق صالحة وفق Luhn
        val input1 = "تم الدفع باستخدام بطاقة رقم 4111111111111111"
        val scrubbed1 = SensitiveDataScrubber.scrub(input1)
        assertTrue(scrubbed1.contains("[بطاقة-دفع-محذوفة]"))
        assertFalse(scrubbed1.contains("4111111111111111"))

        val input2 = "cvv: 789 ورمز الأمان: 123"
        val scrubbed2 = SensitiveDataScrubber.scrub(input2)
        assertTrue(scrubbed2.contains("[CVV-محذوف]"))
        assertFalse(scrubbed2.contains("789"))
        assertFalse(scrubbed2.contains("123"))
    }

    @Test
    fun `scrub masks IBAN and bank accounts`() {
        val input1 = "الآيبان البنكي: SA0380000000608010167519 يرجى التحويل"
        val scrubbed1 = SensitiveDataScrubber.scrub(input1)
        assertTrue(scrubbed1.contains("[حساب-بنكي-محذوف]"))
        assertFalse(scrubbed1.contains("SA0380000000608010167519"))

        val input2 = "رقم الحساب البنكي: 98765432101234"
        val scrubbed2 = SensitiveDataScrubber.scrub(input2)
        assertTrue(scrubbed2.contains("[حساب-بنكي-محذوف]"))
        assertFalse(scrubbed2.contains("98765432101234"))
    }

    @Test
    fun `scrub masks physical addresses with keywords`() {
        val input = "عنوان السكن: الرياض حي النرجس شارع عثمان بن عفان"
        val scrubbed = SensitiveDataScrubber.scrub(input)
        assertTrue(scrubbed.contains("[عنوان-محذوف]"))
        assertFalse(scrubbed.contains("الرياض حي النرجس"))
    }

    @Test
    fun `scrubLogMessage strips control characters and newlines`() {
        val dirty = "سجل الإجراء:\nكلمة المرور: 123456\r\n\tبيانات إضافية"
        val cleaned = SensitiveDataScrubber.scrubLogMessage(dirty)
        assertFalse(cleaned.contains("\n"))
        assertFalse(cleaned.contains("\r"))
        assertFalse(cleaned.contains("123456"))
        assertTrue(cleaned.contains("[كلمة-مرور-محذوفة]"))
    }

    @Test
    fun `scrubScreenSnapshotForPrivacyMode generates compact summary without full text`() {
        val snapshot = ScreenSnapshot(
            packageName = "com.whatsapp",
            visibleTexts = listOf("رسائل خاصة سرية جداً", "رصيد الحساب: 5000 ريال"),
            clickableLabels = listOf("إرسال", "تسجيل خروج", "إعدادات"),
            editableHints = listOf("اكتب رسالة"),
            hasSensitiveSecurityField = false
        )
        val privacySummary = SensitiveDataScrubber.scrubScreenSnapshotForPrivacyMode(snapshot)
        assertTrue(privacySummary.contains("com.whatsapp"))
        assertTrue(privacySummary.contains("إرسال"))
        // نصوص الشاشة الكاملة لا تظهر
        assertFalse(privacySummary.contains("رسائل خاصة سرية جداً"))
        assertFalse(privacySummary.contains("رصيد الحساب"))
    }

    @Test
    fun `containsSensitiveData returns true only when sensitive info is detected`() {
        assertFalse(SensitiveDataScrubber.containsSensitiveData("مرحبا كيف حالك اليوم"))
        assertFalse(SensitiveDataScrubber.containsSensitiveData("افتح تطبيق يوتيوب وشغل مقطع قران"))
        assertTrue(SensitiveDataScrubber.containsSensitiveData("كلمة المرور: pass1234"))
        assertTrue(SensitiveDataScrubber.containsSensitiveData("رمز التحقق هو 192837"))
    }
}
