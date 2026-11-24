package com.example.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.ScreenSnapshot

class PhoneAgentAccessibilityService : AccessibilityService() {

    private val sensitiveKeywords = listOf(
        "كلمة المرور", "كلمة السر", "الرقم السري", "البصمة", "بصمة",
        "الدفع", "بطاقة ائتمان", "رمز التحقق", "pin", "password",
        "passcode", "fingerprint", "biometric", "payment", "checkout", "cvv"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenStateBridge.attachService(this)
        captureCurrentScreen()?.let { ScreenStateBridge.updateSnapshot(it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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

    fun captureCurrentScreen(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val visibleTexts = linkedSetOf<String>()
        val clickableLabels = linkedSetOf<String>()
        val editableHints = linkedSetOf<String>()
        var hasSensitiveField = false

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25) return
            if (node.isPassword) {
                hasSensitiveField = true
            }
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = node.hintText?.toString()?.trim().orEmpty()

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

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), depth + 1)
            }
        }

        traverse(root, 0)

        return ScreenSnapshot(
            packageName = packageName,
            visibleTexts = visibleTexts.toList(),
            clickableLabels = clickableLabels.toList(),
            editableHints = editableHints.toList(),
            hasSensitiveSecurityField = hasSensitiveField
        )
    }

    fun findAndClickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val snapshot = captureCurrentScreen()
        if (snapshot?.hasSensitiveSecurityField == true) {
            return false
        }

        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) return false

        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, 0) { node ->
            val t = node.text?.toString()?.trim()?.lowercase().orEmpty()
            val d = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            t.contains(normalizedQuery) || d.contains(normalizedQuery)
        }.let { matches.addAll(it) }

        for (match in matches) {
            var target: AccessibilityNodeInfo? = match
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            if (target != null && target.isClickable && target.isEnabled) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
            val h = node.hintText?.toString()?.lowercase().orEmpty()
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
