package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.permissions.AgentPermissionItem
import com.example.permissions.PermissionHelper
import com.example.security.PrivacyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionsViewModel(
    private val appContext: Context,
    val privacyPreferences: PrivacyPreferences = PrivacyPreferences(appContext)
) : ViewModel() {

    private val _permissions = MutableStateFlow(PermissionHelper.getAllPermissionStatuses(appContext))
    val permissions: StateFlow<List<AgentPermissionItem>> = _permissions.asStateFlow()

    val isPrivacyModeEnabled: StateFlow<Boolean> = privacyPreferences.privacyModeEnabled

    fun setPrivacyMode(enabled: Boolean) {
        privacyPreferences.setPrivacyModeEnabled(enabled)
    }

    fun refresh() {
        _permissions.value = PermissionHelper.getAllPermissionStatuses(appContext)
    }

    class Factory(
        private val context: Context,
        private val privacyPreferences: PrivacyPreferences = PrivacyPreferences(context)
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PermissionsViewModel(context.applicationContext, privacyPreferences) as T
        }
    }
}
