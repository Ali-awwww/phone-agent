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

    fun toPromptSummary(): String {
        return buildString {
            append(snapshot.toCompactPromptText())
            if (elements.isNotEmpty()) {
                appendLine("مواقع العناصر المرصودة (نسبية 0.0 إلى 1.0):")
                elements.take(25).forEach { el ->
                    val kind = if (el.isClickable) "زر" else "عنصر"
                    val x = String.format(java.util.Locale.US, "%.2f", el.centerXRatio)
                    val y = String.format(java.util.Locale.US, "%.2f", el.centerYRatio)
                    appendLine("- $kind «${el.label}» عند (x=$x, y=$y)")
                }
            }
        }.trim()
    }
}

class PhoneAgentAccessibilityService : AccessibilityService() {

    private val sensitiveKeywords = listOf(
        "كلمة المرور", "كلمة السر", "الرقم السري", "البصمة", "بصمة",
        "الدفع", "بطاقة ائتمان", "رمز التحقق", "pin", "password",
        "passcode", "fingerprint", "biometric", "payment", "checkout", "cvv"
    )

    private var lastEventCaptureMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenStateBridge.attachService(this)
        if (ScreenStateBridge.isOperationActive()) {
            captureCurrentScreen()?.let { ScreenStateBridge.updateSnapshot(it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }
        if (!ScreenStateBridge.isOperationActive()) {
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastEventCaptureMs < 2_000L) {
            return
        }
        lastEventCaptureMs = nowMs
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

    fun getActivePackageName(): String {
        return rootInActiveWindow?.packageName?.toString()
            ?: windows?.firstOrNull { it.isActive }?.root?.packageName?.toString()
            ?: ""
    }

    fun captureCurrentScreen(): ScreenSnapshot? {
        return captureDetailedScreenForGame()?.snapshot
    }

    fun captureDetailedScreenForGame(): GameScreenObservation? {
        val root = rootInActiveWindow ?: windows?.firstOrNull { it.isActive }?.root ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val visibleTexts = linkedSetOf<String>()
        val clickableLabels = linkedSetOf<String>()
        val editableHints = linkedSetOf<String>()
        val elementBounds = mutableListOf<GameElementBound>()
        var hasSensitiveField = false

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1).toFloat()
        val screenH = dm.heightPixels.coerceAtLeast(1).toFloat()
        val rect = Rect()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25) return
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
            if (sensitiveKeywords.any { combinedLower.contains(it) }) {
                hasSensitiveField = true
            }

            if (text.isNotEmpty() && !node.isPassword) {
                visibleTexts.add(text)
            } else if (desc.isNotEmpty()) {
                visibleTexts.add(desc)
            }

            if (node.isClickable) {
                val label = text.ifEmpty { desc }
                if (label.isNotEmpty()) {
                    clickableLabels.add(label)
                }
            }

            if (node.isEditable) {
                val label = hint.ifEmpty { desc.ifEmpty { text.ifEmpty { "حقل نص" } } }
                editableHints.add(label)
            }

            val labelForBound = text.ifEmpty { desc }
            if (!node.isPassword && (labelForBound.isNotEmpty() || node.isClickable) && elementBounds.size < 40) {
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val cx = (rect.exactCenterX() / screenW).coerceIn(0.02f, 0.98f)
                    val cy = (rect.exactCenterY() / screenH).coerceIn(0.02f, 0.98f)
                    val displayLabel = labelForBound.ifEmpty { "عنصر تفاعلي" }
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

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), depth + 1)
            }
        }

        traverse(root, 0)

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

    private fun normalizeArabicForMatch(input: String): String {
        return input
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .trim()
            .lowercase()
    }

    fun findAndClickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: windows?.firstOrNull { it.isActive }?.root ?: return false
        val snapshot = captureCurrentScreen()
        if (snapshot?.hasSensitiveSecurityField == true) {
            return false
        }

        val normalizedQuery = normalizeArabicForMatch(query)
        if (normalizedQuery.isEmpty()) return false

        val matches = collectNodes(root, 0) { node ->
            val t = normalizeArabicForMatch(node.text?.toString().orEmpty())
            val d = normalizeArabicForMatch(node.contentDescription?.toString().orEmpty())
            t.contains(normalizedQuery) || d.contains(normalizedQuery)
        }

        for (match in matches) {
            var target: AccessibilityNodeInfo? = match
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            if (target != null && target.isClickable && target.isEnabled) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }
        return false
    }

    suspend fun findAndClickHumanLike(query: String): Boolean {
        if (findAndClickByText(query)) return true

        val root = rootInActiveWindow ?: windows?.firstOrNull { it.isActive }?.root ?: return false
        val snapshot = captureCurrentScreen()
        if (snapshot?.hasSensitiveSecurityField == true) {
            return false
        }

        val normalizedQuery = normalizeArabicForMatch(query)
        if (normalizedQuery.isEmpty()) return false

        val matches = collectNodes(root, 0) { node ->
            val t = normalizeArabicForMatch(node.text?.toString().orEmpty())
            val d = normalizeArabicForMatch(node.contentDescription?.toString().orEmpty())
            t.contains(normalizedQuery) || d.contains(normalizedQuery)
        }

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1).toFloat()
        val screenH = dm.heightPixels.coerceAtLeast(1).toFloat()
        val rect = Rect()

        for (match in matches) {
            match.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val cx = (rect.exactCenterX() / screenW).coerceIn(0.02f, 0.98f)
                val cy = (rect.exactCenterY() / screenH).coerceIn(0.02f, 0.98f)
                if (performTapAt(cx, cy)) {
                    return true
                }
            }
        }
        return false
    }

    fun findAndInputText(hintOrLabel: String, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val snapshot = captureCurrentScreen()
        if (snapshot?.hasSensitiveSecurityField == true) {
            return false
        }

        val editableNodes = collectNodes(root, 0) { it.isEditable && it.isEnabled }
        if (editableNodes.isEmpty()) return false

        val queryLower = hintOrLabel.trim().lowercase()
        val chosen = editableNodes.firstOrNull { node ->
            val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.lowercase().orEmpty()
            } else {
                ""
            }
            val d = node.contentDescription?.toString()?.lowercase().orEmpty()
            val t = node.text?.toString()?.lowercase().orEmpty()
            queryLower.isNotEmpty() && (h.contains(queryLower) || d.contains(queryLower) || t.contains(queryLower))
        } ?: editableNodes.first()

        chosen.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        return chosen.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollables = collectNodes(root, 0) { it.isScrollable }
        for (node in scrollables) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true
            }
        }
        return false
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
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        if (node == null || depth > 25) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (predicate(node)) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            result.addAll(collectNodes(node.getChild(i), depth + 1, predicate))
        }
        return result
    }
}
