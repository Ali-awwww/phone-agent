package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.ScreenSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class GameElementBound(
    val label: String,
    val centerXRatio: Float,
    val centerYRatio: Float,
    val isClickable: Boolean
)

data class GameScreenObservation(
    val snapshot: ScreenSnapshot,
    val elements: List<GameElementBound>
) {
    fun isNearEmptyTree(): Boolean {
        val meaningfulElements = elements.count { it.label.isNotBlank() && it.label != "عنصر تفاعلي" }
        return snapshot.visibleTexts.isEmpty() &&
            snapshot.clickableLabels.isEmpty() &&
            meaningfulElements == 0
    }

    fun toPromptSummary(privacyMode: Boolean = false): String {
        return buildString {
            if (privacyMode) {
                appendLine(snapshot.toMinimalPrivacyPromptText())
                val clickableElements = elements.filter { it.isClickable && it.label.isNotBlank() && it.label != "عنصر تفاعلي" }.take(8)
                if (clickableElements.isNotEmpty()) {
                    appendLine("مواقع أزرار التحكم (نسبية):")
                    clickableElements.forEach { el ->
                        val x = String.format(java.util.Locale.US, "%.2f", el.centerXRatio)
                        val y = String.format(java.util.Locale.US, "%.2f", el.centerYRatio)
                        val scrubbed = com.example.security.SensitiveDataScrubber.scrub(el.label)
                        appendLine("- زر «$scrubbed» عند (x=$x, y=$y)")
                    }
                }
            } else {
                append(snapshot.toCompactPromptText())
                if (elements.isNotEmpty()) {
                    appendLine("مواقع العناصر المرصودة (نسبية 0.0 إلى 1.0):")
                    elements.take(25).forEach { el ->
                        val kind = if (el.isClickable) "زر" else "عنصر"
                        val x = String.format(java.util.Locale.US, "%.2f", el.centerXRatio)
                        val y = String.format(java.util.Locale.US, "%.2f", el.centerYRatio)
                        val scrubbed = com.example.security.SensitiveDataScrubber.scrub(el.label)
                        appendLine("- $kind «$scrubbed» عند (x=$x, y=$y)")
                    }
                }
            }
        }.trim()
    }
}

class PhoneAgentAccessibilityService : AccessibilityService() {

    private val sensitiveKeywords = listOf(
        "كلمة المرور", "كلمة السر", "الرقم السري", "البصمة", "بصمة",
        "الدفع", "بطاقة ائتمان", "رمز التحقق", "pin", "password",
        "passcode", "fingerprint", "biometric", "payment", "checkout", "cvv", "cvc",
        "iban", "credit card", "debit card", "رقم البطاقة", "كود التحقق", "رمز التأكيد",
        "رقم الهوية", "رقم الحساب", "حساب بنكي", "الرمز السري", "رمز الأمان", "otp"
    )

    private var lastWindowEventCaptureMs: Long = 0L
    private var lastClickEventCaptureMs: Long = 0L
    private var lastContentEventCaptureMs: Long = 0L
    private var lastCapturedPackageName: String = ""

    companion object {
        const val WINDOW_STATE_THROTTLE_MS = 450L
        const val VIEW_CLICKED_THROTTLE_MS = 500L
        const val CONTENT_CHANGED_THROTTLE_MS = 1_200L

        val SENSITIVE_KEYWORDS = listOf(
            "كلمة المرور", "كلمة السر", "الرقم السري", "البصمة", "بصمة",
            "الدفع", "بطاقة ائتمان", "رمز التحقق", "pin", "password",
            "passcode", "fingerprint", "biometric", "payment", "checkout", "cvv", "cvc",
            "iban", "credit card", "debit card", "رقم البطاقة", "كود التحقق", "رمز التأكيد",
            "رقم الهوية", "رقم الحساب", "حساب بنكي", "الرمز السري", "رمز الأمان", "otp"
        )

        internal fun normalizeArabicForMatch(input: String): String {
            return input
                .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ى', 'ي')
                .trim()
                .lowercase()
        }

        internal fun isInteractiveNodeEligible(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            return node.isVisibleToUser && node.isEnabled
        }

        /**
         * F11: أولوية المطابقة الدقيقة على البادئة ثم السلسلة الجزئية لمنع النقر الخاطئ على أول سلسلة جزئية.
         */
        internal fun matchPriorityScore(
            normalizedQuery: String,
            text: String,
            contentDesc: String
        ): Int {
            if (normalizedQuery.isEmpty()) return 0
            val t = normalizeArabicForMatch(text)
            val d = normalizeArabicForMatch(contentDesc)
            return when {
                t == normalizedQuery || d == normalizedQuery -> 3
                (t.startsWith(normalizedQuery) || d.startsWith(normalizedQuery)) -> 2
                (t.contains(normalizedQuery) || d.contains(normalizedQuery)) -> 1
                else -> 0
            }
        }

        internal fun matchPriorityScore(node: AccessibilityNodeInfo, query: String): Int {
            return matchPriorityScore(
                normalizedQuery = normalizeArabicForMatch(query),
                text = node.text?.toString().orEmpty(),
                contentDesc = node.contentDescription?.toString().orEmpty()
            )
        }

        internal fun rankMatchingNodes(
            candidates: List<AccessibilityNodeInfo>,
            query: String
        ): List<AccessibilityNodeInfo> {
            val normalizedQuery = normalizeArabicForMatch(query)
            return candidates
                .mapNotNull { node ->
                    if (!node.isVisibleToUser || !node.isEnabled) return@mapNotNull null
                    val score = matchPriorityScore(
                        normalizedQuery = normalizedQuery,
                        text = node.text?.toString().orEmpty(),
                        contentDesc = node.contentDescription?.toString().orEmpty()
                    )
                    if (score > 0) node to score else null
                }
                .sortedByDescending { it.second }
                .map { it.first }
        }

        internal fun shouldProcessAccessibilityEvent(
            eventType: Int,
            eventPackage: String?,
            lastEventPackage: String?,
            nowMs: Long,
            lastCaptureMs: Long,
            lastPackageChangeMs: Long = lastCaptureMs,
            isOperationActive: Boolean = true
        ): Boolean {
            if (!isOperationActive) return false
            val pkg = eventPackage.orEmpty()
            val packageChanged = pkg.isNotBlank() && pkg != lastEventPackage
            return when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    packageChanged || (nowMs - lastCaptureMs >= WINDOW_STATE_THROTTLE_MS)
                AccessibilityEvent.TYPE_VIEW_CLICKED ->
                    nowMs - lastCaptureMs >= VIEW_CLICKED_THROTTLE_MS
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                    packageChanged || (nowMs - lastCaptureMs >= CONTENT_CHANGED_THROTTLE_MS)
                else -> false
            }
        }

        internal fun extractScreenObservationFromRoot(
            root: AccessibilityNodeInfo,
            screenW: Float = 1080f,
            screenH: Float = 2400f,
            recycleRoot: Boolean = true
        ): GameScreenObservation {
            val packageName = root.packageName?.toString().orEmpty()
            val visibleTexts = linkedSetOf<String>()
            val clickableLabels = linkedSetOf<String>()
            val editableHints = linkedSetOf<String>()
            val elementBounds = mutableListOf<GameElementBound>()
            var hasSensitiveField = false
            val rect = Rect()

            fun traverse(node: AccessibilityNodeInfo?, depth: Int, isRootNode: Boolean) {
                if (node == null || depth > 14 || elementBounds.size >= 80) return
                try {
                    if (node.isPassword) {
                        hasSensitiveField = true
                    }
                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString()?.trim().orEmpty()
                    } else {
                        ""
                    }

                    val combinedLower = "$text $desc $hint".lowercase()
                    if (SENSITIVE_KEYWORDS.any { combinedLower.contains(it) }) {
                        hasSensitiveField = true
                    }

                    val isInteractableVisible = node.isVisibleToUser && node.isEnabled
                    if (isInteractableVisible) {
                        if (!node.isPassword) {
                            if (text.isNotEmpty()) {
                                visibleTexts.add(com.example.security.SensitiveDataScrubber.scrub(text))
                            } else if (desc.isNotEmpty()) {
                                visibleTexts.add(com.example.security.SensitiveDataScrubber.scrub(desc))
                            }
                        }

                        if (node.isClickable && !node.isPassword) {
                            val label = text.ifEmpty { desc }
                            if (label.isNotEmpty()) {
                                clickableLabels.add(com.example.security.SensitiveDataScrubber.scrub(label))
                            }
                        }

                        if (node.isEditable) {
                            if (node.isPassword) {
                                editableHints.add("حقل كلمة مرور محمي")
                            } else {
                                val label = hint.ifEmpty { desc.ifEmpty { text.ifEmpty { "حقل نص" } } }
                                editableHints.add(com.example.security.SensitiveDataScrubber.scrub(label))
                            }
                        }

                        val labelForBound = text.ifEmpty { desc }
                        if (!node.isPassword && (labelForBound.isNotEmpty() || node.isClickable) && elementBounds.size < 80) {
                            node.getBoundsInScreen(rect)
                            if (rect.width() > 0 && rect.height() > 0) {
                                val cx = (rect.exactCenterX() / screenW).coerceIn(0.02f, 0.98f)
                                val cy = (rect.exactCenterY() / screenH).coerceIn(0.02f, 0.98f)
                                val displayLabel = com.example.security.SensitiveDataScrubber.scrub(labelForBound.ifEmpty { "عنصر تفاعلي" })
                                elementBounds.add(
                                    GameElementBound(
                                        label = displayLabel,
                                        centerXRatio = cx,
                                        centerYRatio = cy,
                                        isClickable = node.isClickable
                                    )
                                )
                            }
                        }
                    }

                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            traverse(child, depth + 1, isRootNode = false)
                        }
                    }
                } finally {
                    if (!isRootNode) {
                        runCatching { node.recycle() }
                    }
                }
            }

            try {
                traverse(root, 0, isRootNode = true)
            } finally {
                if (recycleRoot) {
                    runCatching { root.recycle() }
                }
            }

            val snapshot = ScreenSnapshot(
                packageName = packageName,
                visibleTexts = visibleTexts.toList(),
                clickableLabels = clickableLabels.toList(),
                editableHints = editableHints.toList(),
                hasSensitiveSecurityField = hasSensitiveField
            )
            return GameScreenObservation(
                snapshot = snapshot,
                elements = elementBounds
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenStateBridge.attachService(this)
        if (ScreenStateBridge.isOperationActive()) {
            captureCurrentScreen()?.let { ScreenStateBridge.updateSnapshot(it) }
        }
    }

    internal fun shouldProcessAccessibilityEvent(
        eventType: Int,
        packageName: String?,
        nowMs: Long,
        isOperationActive: Boolean
    ): Boolean {
        if (!isOperationActive) return false
        val pkg = packageName.orEmpty()
        val packageChanged = pkg.isNotBlank() && pkg != lastCapturedPackageName

        val allowed = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                packageChanged || (nowMs - lastWindowEventCaptureMs >= WINDOW_STATE_THROTTLE_MS)
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                nowMs - lastClickEventCaptureMs >= VIEW_CLICKED_THROTTLE_MS
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                packageChanged || (nowMs - lastContentEventCaptureMs >= CONTENT_CHANGED_THROTTLE_MS)
            else -> false
        }
        if (!allowed) return false

        if (pkg.isNotBlank()) {
            lastCapturedPackageName = pkg
        }
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> lastWindowEventCaptureMs = nowMs
            AccessibilityEvent.TYPE_VIEW_CLICKED -> lastClickEventCaptureMs = nowMs
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> lastContentEventCaptureMs = nowMs
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val pkgName = event.packageName?.toString()
        val nowMs = SystemClock.elapsedRealtime()
        if (!shouldProcessAccessibilityEvent(eventType, pkgName, nowMs, ScreenStateBridge.isOperationActive())) {
            return
        }
        val snapshot = captureCurrentScreen() ?: return
        ScreenStateBridge.updateSnapshot(snapshot)
    }

    override fun onInterrupt() {
        // تم إيقاف الخدمة مؤقتاً من النظام
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenStateBridge.detachService()
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node != null) {
            runCatching { node.recycle() }
        }
    }

    fun getActivePackageName(): String {
        val root = rootInActiveWindow ?: windows?.firstOrNull { it.isActive }?.root
        return try {
            root?.packageName?.toString().orEmpty()
        } finally {
            safeRecycle(root)
        }
    }

    fun captureCurrentScreen(): ScreenSnapshot? {
        return captureDetailedScreenForGame()?.snapshot
    }

    fun captureDetailedScreenForGame(): GameScreenObservation? {
        val root = rootInActiveWindow ?: windows?.firstOrNull { it.isActive }?.root ?: return null
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1).toFloat()
        val screenH = dm.heightPixels.coerceAtLeast(1).toFloat()
        return extractScreenObservationFromRoot(root, screenW, screenH, recycleRoot = true)
    }

    internal fun extractScreenObservationFromRoot(
        root: AccessibilityNodeInfo,
        screenW: Float = 1080f,
        screenH: Float = 2400f,
        recycleRoot: Boolean = true
    ): GameScreenObservation {
        return Companion.extractScreenObservationFromRoot(root, screenW, screenH, recycleRoot)
    }

    internal fun rankMatchingNodes(
        normalizedQuery: String,
        candidates: List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        return Companion.rankMatchingNodes(candidates, normalizedQuery)
    }

    fun findAndClickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: windows?.firstOrNull { it.isActive }?.root ?: return false
        val acquiredNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val dm = resources.displayMetrics
            val obs = extractScreenObservationFromRoot(
                root = root,
                screenW = dm.widthPixels.coerceAtLeast(1).toFloat(),
                screenH = dm.heightPixels.coerceAtLeast(1).toFloat(),
                recycleRoot = false
            )
            if (obs.snapshot.hasSensitiveSecurityField) {
                return false
            }

            val normalizedQuery = normalizeArabicForMatch(query)
            if (normalizedQuery.isEmpty()) return false

            val candidates = collectNodes(root, 0, acquiredNodes) { node ->
                node.isVisibleToUser && node.isEnabled && matchPriorityScore(
                    normalizedQuery = normalizedQuery,
                    text = node.text?.toString().orEmpty(),
                    contentDesc = node.contentDescription?.toString().orEmpty()
                ) > 0
            }
            val matches = rankMatchingNodes(normalizedQuery, candidates)

            for (match in matches) {
                var target: AccessibilityNodeInfo? = match
                while (target != null && !target.isClickable) {
                    val parent = target.parent
                    if (parent != null && parent !== root) {
                        acquiredNodes.add(parent)
                    }
                    target = parent
                }
                if (target != null && target.isVisibleToUser && target.isClickable && target.isEnabled) {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
            }
            return false
        } finally {
            acquiredNodes.distinct().forEach { safeRecycle(it) }
            safeRecycle(root)
        }
    }

    suspend fun findAndClickHumanLike(query: String): Boolean {
        if (findAndClickByText(query)) return true

        val root = rootInActiveWindow ?: windows?.firstOrNull { it.isActive }?.root ?: return false
        val acquiredNodes = mutableListOf<AccessibilityNodeInfo>()
        var tapCoords: Pair<Float, Float>? = null
        try {
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels.coerceAtLeast(1).toFloat()
            val screenH = dm.heightPixels.coerceAtLeast(1).toFloat()
            val obs = extractScreenObservationFromRoot(
                root = root,
                screenW = screenW,
                screenH = screenH,
                recycleRoot = false
            )
            if (obs.snapshot.hasSensitiveSecurityField) {
                return false
            }

            val normalizedQuery = normalizeArabicForMatch(query)
            if (normalizedQuery.isEmpty()) return false

            val candidates = collectNodes(root, 0, acquiredNodes) { node ->
                node.isVisibleToUser && node.isEnabled && matchPriorityScore(
                    normalizedQuery = normalizedQuery,
                    text = node.text?.toString().orEmpty(),
                    contentDesc = node.contentDescription?.toString().orEmpty()
                ) > 0
            }
            val matches = rankMatchingNodes(normalizedQuery, candidates)
            val rect = Rect()

            for (match in matches) {
                match.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val cx = (rect.exactCenterX() / screenW).coerceIn(0.02f, 0.98f)
                    val cy = (rect.exactCenterY() / screenH).coerceIn(0.02f, 0.98f)
                    tapCoords = cx to cy
                    break
                }
            }
        } finally {
            acquiredNodes.distinct().forEach { safeRecycle(it) }
            safeRecycle(root)
        }

        val coords = tapCoords ?: return false
        return performTapAt(coords.first, coords.second)
    }

    fun findAndInputText(hintOrLabel: String, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val acquiredNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val dm = resources.displayMetrics
            val obs = extractScreenObservationFromRoot(
                root = root,
                screenW = dm.widthPixels.coerceAtLeast(1).toFloat(),
                screenH = dm.heightPixels.coerceAtLeast(1).toFloat(),
                recycleRoot = false
            )
            if (obs.snapshot.hasSensitiveSecurityField) {
                return false
            }

            val editableNodes = collectNodes(root, 0, acquiredNodes) {
                it.isVisibleToUser && it.isEditable && it.isEnabled
            }
            if (editableNodes.isEmpty()) return false

            val queryLower = normalizeArabicForMatch(hintOrLabel)
            val ranked = if (queryLower.isNotEmpty()) {
                editableNodes.sortedByDescending { node ->
                    val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString().orEmpty()
                    } else ""
                    val d = node.contentDescription?.toString().orEmpty()
                    val t = node.text?.toString().orEmpty()
                    maxOf(
                        matchPriorityScore(queryLower, h, d),
                        matchPriorityScore(queryLower, t, d)
                    )
                }
            } else {
                editableNodes
            }
            val chosen = ranked.first()

            chosen.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return chosen.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            acquiredNodes.distinct().forEach { safeRecycle(it) }
            safeRecycle(root)
        }
    }

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val acquiredNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val scrollables = collectNodes(root, 0, acquiredNodes) {
                it.isVisibleToUser && it.isEnabled && it.isScrollable
            }
            for (node in scrollables) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    return true
                }
            }
            return false
        } finally {
            acquiredNodes.distinct().forEach { safeRecycle(it) }
            safeRecycle(root)
        }
    }

    suspend fun performTapAt(xRatio: Float, yRatio: Float): Boolean {
        val snapshot = captureCurrentScreen()
        if (snapshot?.hasSensitiveSecurityField == true) {
            return false
        }
        val dm = resources.displayMetrics
        val x = (dm.widthPixels * xRatio.coerceIn(0.02f, 0.98f))
        val y = (dm.heightPixels * yRatio.coerceIn(0.02f, 0.98f))
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspend(gesture)
    }

    suspend fun performSwipe(
        startXRatio: Float,
        startYRatio: Float,
        endXRatio: Float,
        endYRatio: Float,
        durationMs: Long = 360L
    ): Boolean {
        val snapshot = captureCurrentScreen()
        if (snapshot?.hasSensitiveSecurityField == true) {
            return false
        }
        val dm = resources.displayMetrics
        val startX = dm.widthPixels * startXRatio.coerceIn(0.02f, 0.98f)
        val startY = dm.heightPixels * startYRatio.coerceIn(0.02f, 0.98f)
        val endX = dm.widthPixels * endXRatio.coerceIn(0.02f, 0.98f)
        val endY = dm.heightPixels * endYRatio.coerceIn(0.02f, 0.98f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(80L, 2000L)
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspend(gesture)
    }

    suspend fun performDrag(
        startXRatio: Float,
        startYRatio: Float,
        endXRatio: Float,
        endYRatio: Float,
        durationMs: Long = 650L
    ): Boolean {
        return performSwipe(startXRatio, startYRatio, endXRatio, endYRatio, durationMs)
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    fun navigateBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        acquiredChildNodes: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        if (node == null || depth > 25) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (predicate(node)) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                acquiredChildNodes.add(child)
                result.addAll(collectNodes(child, depth + 1, acquiredChildNodes, predicate))
            }
        }
        return result
    }
}
