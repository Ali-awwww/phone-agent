package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.permissions.AgentPermissionItem
import com.example.permissions.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionsViewModel(
    private val appContext: Context
) : ViewModel() {

    private val _permissions = MutableStateFlow(PermissionHelper.getAllPermissionStatuses(appContext))
    val permissions: StateFlow<List<AgentPermissionItem>> = _permissions.asStateFlow()

    fun refresh() {
        _permissions.value = PermissionHelper.getAllPermissionStatuses(appContext)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PermissionsViewModel(context.applicationContext) as T
        }
    }
}
