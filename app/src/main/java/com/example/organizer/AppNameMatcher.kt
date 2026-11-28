package com.example.organizer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed interface AppMatchResult {
    data class SingleMatch(
        val app: InstalledAppInfo,
        val score: Float,
        val matchReason: String
    ) : AppMatchResult

    data class MultipleMatches(
        val query: String,
        val candidates: List<InstalledAppInfo>
    ) : AppMatchResult

    data class NoMatch(
        val query: String,
        val normalizedQuery: String
    ) : AppMatchResult
}

data class ScoredAppCandidate(
    val app: InstalledAppInfo,
    val score: Float,
    val reason: String
)

/**
 * مطابقة ضبابية ومُطبّعة لأسماء التطبيقات (المرحلة 7 - b):
 * - إزالة التشكيل وتوحيد الألف والياء والتاء المربوطة، وعدم الحساسية لحالة الأحرف.
 * - دعم التحويل العربي للأسماء الأجنبية (مثل: «ايفتبول» = eFootball) عبر القاموس والتحويل الصوتي الخوارزمي.
 * - مطابقة بادئة وجزئية وتحمّل الأخطاء الإملائية الصغيرة (Levenshtein).
 * - كشف التطابقات المتعددة لسؤال المستخدم، والإبلاغ الصريح عند عدم التطابق بدل الفشل الصامت.
 */
object AppNameMatcher {

    private val FILLER_PREFIXES = setOf(
        "تطبيق", "برنامج", "لعبة", "لعبه", "منصة", "منصه", "app", "game"
    )

    // قاموس تحويل شائع بين العربية والأسماء الأجنبية
    private val BILINGUAL_ALIASES: List<Pair<List<String>, List<String>>> = listOf(
        listOf("ايفتبول", "ايفوتبول", "افوتبول", "إيفتبول", "إيفوتبول", "ايفوت بول", "بيس", "efootball", "pes") to listOf("efootball", "pes"),
        listOf("واتساب", "واتس", "وتساب", "واتس اب", "whatsapp") to listOf("whatsapp"),
        listOf("يوتيوب", "يوتوب", "youtube") to listOf("youtube"),
        listOf("تليجرام", "تيليجرام", "تلغرام", "تيلغرام", "telegram") to listOf("telegram"),
        listOf("انستقرام", "انستغرام", "انستا", "انستجرام", "instagram") to listOf("instagram"),
        listOf("فيسبوك", "فيس بوك", "فيس", "الفيسبوك", "facebook") to listOf("facebook", "katana"),
        listOf("تيكتوك", "تيك توك", "تيك", "tiktok") to listOf("tiktok", "musically"),
        listOf("سناب", "سناب شات", "سنابشات", "snapchat") to listOf("snapchat"),
        listOf("تويتر", "اكس", "twitter") to listOf("twitter", "x.android"),
        listOf("ببجي", "بابجي", "بوبجي", "pubg") to listOf("pubg", "ig"),
        listOf("فري فاير", "فريفاير", "freefire") to listOf("freefire", "dts.freefire"),
        listOf("روبلوكس", "روبلكس", "roblox") to listOf("roblox"),
        listOf("ماينكرافت", "ماين كرافت", "minecraft") to listOf("minecraft"),
        listOf("سبوتيفاي", "سبوتفاي", "spotify") to listOf("spotify"),
        listOf("نتفلكس", "نتفليكس", "netflix") to listOf("netflix"),
        listOf("كروم", "جوجل كروم", "chrome") to listOf("chrome"),
        listOf("جوجل", "غوغل", "قوقل", "google") to listOf("google", "googlequicksearchbox"),
        listOf("خرائط", "الخرائط", "مابس", "maps") to listOf("maps"),
        listOf("اعدادات", "الإعدادات", "الاعدادات", "الضبط", "ضبط", "settings") to listOf("settings"),
        listOf("ساعة", "الساعة", "منبه", "المنبه", "clock") to listOf("clock", "deskclock", "alarm"),
        listOf("كاميرا", "الكاميرا", "camera") to listOf("camera"),
        listOf("رسائل", "الرسائل", "messages") to listOf("messaging", "messages", "mms"),
        listOf("هاتف", "الهاتف", "اتصال", "المكالمات", "phone", "dialer") to listOf("dialer", "phone", "telecom"),
        listOf("متجر", "متجر بلاي", "بلاي ستور", "play") to listOf("vending", "play store", "google play"),
        listOf("جي ميل", "جيميل", "بريد", "gmail") to listOf("gmail", "gm"),
        listOf("حاسبة", "الحاسبة", "اله حاسبة", "calculator") to listOf("calculator"),
        listOf("تقويم", "التقويم", "calendar") to listOf("calendar"),
        listOf("معرض", "المعرض", "الصور", "gallery", "photos") to listOf("gallery", "photos")
    )

    fun normalize(raw: String): String {
        val asciiDigits = buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
                    in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
                    else -> append(ch)
                }
            }
        }
        return asciiDigits
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s._-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun stripLeadingFillers(normalized: String): String {
        val tokens = normalized.split(' ').filter { it.isNotBlank() }.toMutableList()
        while (tokens.size > 1 && tokens.first() in FILLER_PREFIXES) {
            tokens.removeAt(0)
        }
        return tokens.joinToString(" ").trim()
    }

    fun compactKey(normalized: String): String {
        return normalized.replace(Regex("[\\s._\\-]+"), "")
    }

    fun containsArabic(text: String): Boolean = text.any { it in '\u0600'..'\u06FF' }

    /**
     * تحويل صوتي خوارزمي للحروف العربية إلى أحرف لاتينية لتطابق أي اسم تطبيق أجنبي غير مدرج في القاموس.
     */
    fun transliterateArabicToLatin(input: String): String {
        val norm = normalize(input)
        val out = StringBuilder(norm.length * 2)
        for (i in norm.indices) {
            when (val ch = norm[i]) {
                'ا' -> out.append(if (i == 0) 'e' else 'a')
                'ب' -> out.append('b')
                'ت' -> out.append('t')
                'ث' -> out.append("th")
                'ج' -> out.append('j')
                'ح', 'ه' -> out.append('h')
                'خ' -> out.append("kh")
                'د' -> out.append('d')
                'ذ' -> out.append("dh")
                'ر' -> out.append('r')
                'ز' -> out.append('z')
                'س', 'ص' -> out.append('s')
                'ش' -> out.append("sh")
                'ض', 'ظ' -> out.append('d')
                'ط' -> out.append('t')
                'ع' -> out.append('a')
                'غ' -> out.append("gh")
                'ف' -> out.append('f')
                'ق' -> out.append('q')
                'ك' -> out.append('k')
                'ل' -> out.append('l')
                'م' -> out.append('m')
                'ن' -> out.append('n')
                'و' -> out.append('o')
                'ي' -> out.append('i')
                in 'a'..'z', in '0'..'9' -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * هيكل صوتي مختصر يزيل التكرار ويقرّب الحروف المتشابهة (p/b, v/f, c/k, z/s) والحركات غير الابتدائية.
     */
    fun phoneticSkeleton(latinOrMixed: String): String {
        val latin = if (containsArabic(latinOrMixed)) {
            transliterateArabicToLatin(latinOrMixed)
        } else {
            normalize(latinOrMixed)
        }
        val mapped = latin
            .replace("ph", "f")
            .replace("sh", "s")
            .replace("ch", "k")
            .replace('p', 'b')
            .replace('v', 'f')
            .replace('c', 'k')
            .replace('q', 'k')
            .replace('z', 's')
            .replace(Regex("[^a-z0-9]"), "")

        if (mapped.isEmpty()) return ""
        val result = StringBuilder()
        var prev = '\u0000'
        for (i in mapped.indices) {
            val ch = mapped[i]
            if (ch == prev) continue
            prev = ch
            val isVowel = ch in charArrayOf('a', 'e', 'i', 'o', 'u', 'y', 'h')
            if (i == 0 || !isVowel) {
                result.append(ch)
            }
        }
        return result.toString()
    }

    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun resolveAliasHints(normQuery: String, compactQuery: String): List<String> {
        val hints = linkedSetOf<String>()
        val noAl = if (normQuery.startsWith("ال") && normQuery.length > 3) normQuery.drop(2) else normQuery
        for ((triggers, targets) in BILINGUAL_ALIASES) {
            val matched = triggers.any { trigger ->
                val nt = normalize(trigger)
                val ct = compactKey(nt)
                normQuery == nt ||
                    noAl == nt ||
                    compactQuery == ct ||
                    (nt.length >= 4 && (normQuery.contains(nt) || compactQuery.contains(ct)))
            }
            if (matched) {
                hints.addAll(targets)
            }
        }
        return hints.toList()
    }

    fun match(
        rawQuery: String,
        installedApps: List<InstalledAppInfo>
    ): AppMatchResult {
        val stripped = stripLeadingFillers(normalize(rawQuery))
        if (stripped.isBlank() || installedApps.isEmpty()) {
            return AppMatchResult.NoMatch(rawQuery, stripped)
        }
        val noAlQuery = if (stripped.startsWith("ال") && stripped.length > 3) stripped.drop(2) else stripped
        val compactQ = compactKey(stripped)
        val compactNoAlQ = compactKey(noAlQuery)
        val queryHints = resolveAliasHints(stripped, compactQ)
        val queryTranslit = transliterateArabicToLatin(stripped)
        val querySkeleton = phoneticSkeleton(stripped)
        val querySkeletonNoLead = if (querySkeleton.length >= 4 && querySkeleton[0] in charArrayOf('a', 'e', 'i', 'o', 'u')) {
            querySkeleton.drop(1)
        } else {
            querySkeleton
        }

        val scored = installedApps.mapNotNull { app ->
            val candidate = scoreApp(
                app = app,
                normQuery = stripped,
                noAlQuery = noAlQuery,
                compactQ = compactQ,
                compactNoAlQ = compactNoAlQ,
                queryHints = queryHints,
                queryTranslit = queryTranslit,
                querySkeleton = querySkeleton,
                querySkeletonNoLead = querySkeletonNoLead
            )
            candidate?.takeIf { it.score >= 0.65f }
        }.sortedWith(
            compareByDescending<ScoredAppCandidate> { it.score }
                .thenBy { it.app.label.length }
        )

        if (scored.isEmpty()) {
            return AppMatchResult.NoMatch(rawQuery, stripped)
        }
        if (scored.size == 1) {
            val only = scored.first()
            return AppMatchResult.SingleMatch(only.app, only.score, only.reason)
        }

        val top = scored[0]
        val second = scored[1]
        if ((top.score - second.score) >= 0.15f && second.score < 0.85f) {
            return AppMatchResult.SingleMatch(top.app, top.score, top.reason)
        }

        val closeCandidates = scored
            .filter { (top.score - it.score) <= 0.15f || it.score >= 0.85f }
            .map { it.app }
            .distinctBy { it.packageName }
            .take(6)

        return if (closeCandidates.size <= 1) {
            AppMatchResult.SingleMatch(top.app, top.score, top.reason)
        } else {
            AppMatchResult.MultipleMatches(rawQuery, closeCandidates)
        }
    }

    fun findBestMatch(
        rawQuery: String,
        installedApps: List<InstalledAppInfo>
    ): InstalledAppInfo? {
        return when (val res = match(rawQuery, installedApps)) {
            is AppMatchResult.SingleMatch -> res.app
            is AppMatchResult.MultipleMatches -> res.candidates.firstOrNull()
            is AppMatchResult.NoMatch -> null
        }
    }

    /**
     * يتحقق إذا كان نص الأمر يذكر اسماً أو تحويلاً صوتياً لتطبيق مثبت (بلا حساسية لحالة الأحرف وبالتحويل العربي).
     */
    fun mentionsKnownApp(normCommand: String, knownAppNames: List<String>): Boolean {
        if (knownAppNames.isEmpty()) return false
        val normCmd = normalize(normCommand)
        val compactCmd = compactKey(normCmd)
        val cmdTokens = normCmd.split(' ').filter { it.length >= 2 }

        for (label in knownAppNames) {
            val normLabel = normalize(label)
            val compactLabel = compactKey(normLabel)
            if (normLabel.length >= 2 && (normCmd.contains(normLabel) || compactCmd.contains(compactLabel))) {
                return true
            }
            val labelSkeleton = phoneticSkeleton(normLabel)
            if (labelSkeleton.length >= 4) {
                for (tok in cmdTokens) {
                    val hints = resolveAliasHints(tok, compactKey(tok))
                    if (hints.any { hint -> compactLabel.contains(hint) || normLabel.contains(hint) }) {
                        return true
                    }
                    val tokSkeleton = phoneticSkeleton(tok)
                    val tokNoLead = tokSkeleton.dropWhile { it in charArrayOf('a', 'e', 'i', 'o', 'u') }
                    val lblNoLead = labelSkeleton.dropWhile { it in charArrayOf('a', 'e', 'i', 'o', 'u') }
                    if (tokNoLead.length >= 4 && lblNoLead == tokNoLead) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun scoreApp(
        app: InstalledAppInfo,
        normQuery: String,
        noAlQuery: String,
        compactQ: String,
        compactNoAlQ: String,
        queryHints: List<String>,
        queryTranslit: String,
        querySkeleton: String,
        querySkeletonNoLead: String
    ): ScoredAppCandidate? {
        val normLabel = normalize(app.label)
        val normPkg = app.packageName.lowercase()
        val compactLabel = compactKey(normLabel)
        val labelWords = normLabel.split(' ').filter { it.isNotBlank() }
        val pkgSegments = normPkg.split('.').filter { it.isNotBlank() }

        // 1. تطابق تام أو تام بعد إزالة الفواصل وحالة الأحرف
        if (normLabel == normQuery || normLabel == noAlQuery || normPkg == normQuery) {
            return ScoredAppCandidate(app, 1.0f, "exact")
        }
        if (compactLabel == compactQ || compactLabel == compactNoAlQ) {
            return ScoredAppCandidate(app, 0.98f, "compact_exact")
        }

        // 2. تطابق عبر قاموس التحويل العربي/الأجنبي
        for (hint in queryHints) {
            val compactHint = compactKey(hint)
            if (compactLabel == compactHint) {
                return ScoredAppCandidate(app, 0.97f, "alias_exact")
            }
            if (compactLabel.startsWith(compactHint) && compactHint.length >= 3) {
                return ScoredAppCandidate(app, 0.91f, "alias_prefix")
            }
            if (compactLabel.contains(compactHint) || pkgSegments.any { it == compactHint || it.contains(compactHint) }) {
                return ScoredAppCandidate(app, 0.88f, "alias_contains")
            }
        }

        // 3. تطابق البادئة (Prefix)
        if (compactQ.length >= 2 &&
            (compactLabel.startsWith(compactQ) || compactLabel.startsWith(compactNoAlQ) ||
                labelWords.any { it.startsWith(normQuery) || it.startsWith(noAlQuery) })
        ) {
            return ScoredAppCandidate(app, 0.90f, "prefix")
        }

        // 4. تطابق جزئي (Substring / package segment)
        if (compactQ.length >= 3 &&
            (compactLabel.contains(compactQ) || compactLabel.contains(compactNoAlQ) ||
                pkgSegments.any { it.contains(compactQ) })
        ) {
            return ScoredAppCandidate(app, 0.83f, "partial")
        }

        // 5. تطابق التحويل الصوتي الخوارزمي (Arabic -> Latin phonetic skeleton)
        val labelSkeleton = phoneticSkeleton(normLabel)
        val labelSkeletonNoLead = if (labelSkeleton.length >= 4 && labelSkeleton[0] in charArrayOf('a', 'e', 'i', 'o', 'u')) {
            labelSkeleton.drop(1)
        } else {
            labelSkeleton
        }
        if (querySkeleton.length >= 3 && labelSkeleton.isNotEmpty()) {
            if (querySkeleton == labelSkeleton ||
                (querySkeletonNoLead.length >= 4 && querySkeletonNoLead == labelSkeletonNoLead)
            ) {
                return ScoredAppCandidate(app, 0.90f, "phonetic_exact")
            }
            if (labelSkeleton.startsWith(querySkeleton) ||
                (querySkeletonNoLead.length >= 4 && labelSkeletonNoLead.startsWith(querySkeletonNoLead))
            ) {
                return ScoredAppCandidate(app, 0.84f, "phonetic_prefix")
            }
        }

        // 6. تحمّل الأخطاء الإملائية الصغيرة (Levenshtein) على الاسم المدمج أو التحويل الصوتي
        var bestTypoScore = 0f
        if (compactQ.length >= 3 && compactLabel.length >= 3) {
            val maxLen = max(compactQ.length, compactLabel.length)
            val allowedDist = when {
                maxLen <= 5 -> 1
                maxLen <= 9 -> 2
                else -> 3
            }
            val directDist = levenshteinDistance(compactQ, compactLabel)
            if (directDist <= allowedDist) {
                bestTypoScore = max(bestTypoScore, 0.86f - directDist * 0.06f)
            }
            if (abs(queryTranslit.length - compactLabel.length) <= 3 && queryTranslit.length >= 4) {
                val translitDist = levenshteinDistance(queryTranslit, compactLabel)
                if (translitDist <= allowedDist) {
                    bestTypoScore = max(bestTypoScore, 0.84f - translitDist * 0.06f)
                }
            }
            if (querySkeleton.length >= 4 && labelSkeleton.length >= 4) {
                val skelDist = levenshteinDistance(querySkeleton, labelSkeleton)
                if (skelDist <= 1) {
                    bestTypoScore = max(bestTypoScore, 0.80f - skelDist * 0.06f)
                }
            }
        }
        for (word in labelWords) {
            if (compactQ.length >= 4 && word.length >= 4) {
                val wordDist = levenshteinDistance(compactQ, word)
                val allowed = if (max(compactQ.length, word.length) <= 6) 1 else 2
                if (wordDist <= allowed) {
                    bestTypoScore = max(bestTypoScore, 0.80f - wordDist * 0.06f)
                }
            }
        }

        return if (bestTypoScore >= 0.65f) {
            ScoredAppCandidate(app, bestTypoScore, "typo_tolerant")
        } else {
            null
        }
    }
}
