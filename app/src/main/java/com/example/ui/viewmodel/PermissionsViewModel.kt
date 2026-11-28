package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.HabitLogEntity
import com.example.data.local.PhoneAgentDatabase
import com.example.data.local.UserMemoryEntity
import com.example.data.local.UserMemoryRepository
import com.example.permissions.AgentPermissionItem
import com.example.permissions.PermissionHelper
import com.example.security.MemoryAndHabitPreferences
import com.example.security.PrivacyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PermissionsViewModel(
    private val appContext: Context,
    val privacyPreferences: PrivacyPreferences = PrivacyPreferences(appContext),
    private val userMemoryRepository: UserMemoryRepository = PhoneAgentDatabase.getInstance(appContext).let { db ->
        UserMemoryRepository(
            memoryDao = db.userMemoryDao(),
            habitDao = db.habitLogDao(),
            summaryDao = db.conversationSummaryDao(),
            privacyPreferences = privacyPreferences
        )
    },
    private val memoryAndHabitPreferences: MemoryAndHabitPreferences = MemoryAndHabitPreferences(appContext)
) : ViewModel() {

    private val _permissions = MutableStateFlow(PermissionHelper.getAllPermissionStatuses(appContext))
    val permissions: StateFlow<List<AgentPermissionItem>> = _permissions.asStateFlow()

    val isPrivacyModeEnabled: StateFlow<Boolean> = privacyPreferences.privacyModeEnabled

    val memories: StateFlow<List<UserMemoryEntity>> = userMemoryRepository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val habits: StateFlow<List<HabitLogEntity>> = userMemoryRepository.allHabits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val predictiveLearningEnabled: StateFlow<Boolean> = memoryAndHabitPreferences.predictiveLearningEnabled
    val anonymizedHabitGeminiConsent: StateFlow<Boolean> = memoryAndHabitPreferences.anonymizedHabitGeminiConsent
    val morningRoutineApproved: StateFlow<Boolean> = memoryAndHabitPreferences.morningRoutineApproved

    fun setPrivacyMode(enabled: Boolean) {
        privacyPreferences.setPrivacyModeEnabled(enabled)
    }

    fun forgetMemoryFact(id: Long) {
        viewModelScope.launch {
            userMemoryRepository.forgetFact(id)
        }
    }

    fun clearAllUserMemory() {
        viewModelScope.launch {
            userMemoryRepository.clearMemory()
            memoryAndHabitPreferences.setTurnsSinceLastSummary(0)
        }
    }

    fun clearAllHabits() {
        viewModelScope.launch {
            userMemoryRepository.clearHabits()
            memoryAndHabitPreferences.clearDismissedSuggestionKeys()
        }
    }

    fun setPredictiveLearningEnabled(enabled: Boolean) {
        memoryAndHabitPreferences.setPredictiveLearningEnabled(enabled)
    }

    fun setAnonymizedHabitGeminiConsent(consented: Boolean) {
        memoryAndHabitPreferences.setAnonymizedHabitGeminiConsent(consented)
    }

    fun resetMorningRoutineApproval() {
        memoryAndHabitPreferences.setMorningRoutineApproved(false)
    }

    fun refresh() {
        _permissions.value = PermissionHelper.getAllPermissionStatuses(appContext)
    }

    class Factory(
        private val context: Context,
        private val privacyPreferences: PrivacyPreferences = PrivacyPreferences(context),
        private val userMemoryRepository: UserMemoryRepository? = null,
        private val memoryAndHabitPreferences: MemoryAndHabitPreferences = MemoryAndHabitPreferences(context)
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appCtx = context.applicationContext
            val repo = userMemoryRepository ?: PhoneAgentDatabase.getInstance(appCtx).let { db ->
                UserMemoryRepository(
                    memoryDao = db.userMemoryDao(),
                    habitDao = db.habitLogDao(),
                    summaryDao = db.conversationSummaryDao(),
                    privacyPreferences = privacyPreferences
                )
            }
            return PermissionsViewModel(
                appContext = appCtx,
                privacyPreferences = privacyPreferences,
                userMemoryRepository = repo,
                memoryAndHabitPreferences = memoryAndHabitPreferences
            ) as T
        }
    }
}
