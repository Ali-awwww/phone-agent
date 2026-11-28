package com.example.service

import com.example.model.ScreenSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

object ScreenStateBridge {
    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _latestSnapshot = MutableStateFlow<ScreenSnapshot?>(null)
    val latestSnapshot: StateFlow<ScreenSnapshot?> = _latestSnapshot.asStateFlow()

    private var serviceRef: WeakReference<PhoneAgentAccessibilityService>? = null

    @Volatile
    private var taskActive: Boolean = false

    @Volatile
    private var screenReadActive: Boolean = false

    fun attachService(service: PhoneAgentAccessibilityService) {
        serviceRef = WeakReference(service)
        _isServiceConnected.value = true
    }

    fun detachService() {
        serviceRef?.clear()
        serviceRef = null
        _isServiceConnected.value = false
    }

    fun setTaskActive(active: Boolean) {
        taskActive = active
        if (!active) {
            clearSnapshot()
        }
    }

    fun setScreenReadActive(active: Boolean) {
        screenReadActive = active
    }

    fun isOperationActive(): Boolean = taskActive || screenReadActive

    fun clearSnapshot() {
        _latestSnapshot.value = null
    }

    fun updateSnapshot(snapshot: ScreenSnapshot) {
        if (snapshot.hasSensitiveSecurityField) {
            _latestSnapshot.value = snapshot.copy(
                visibleTexts = emptyList(),
                clickableLabels = emptyList(),
                editableHints = emptyList(),
                hasSensitiveSecurityField = true
            )
        } else {
            _latestSnapshot.value = snapshot
        }
    }

    fun getService(): PhoneAgentAccessibilityService? = serviceRef?.get()
}
