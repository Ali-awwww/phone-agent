package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.ActionLogEntity
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.executor.CommandExecutor
import com.example.organizer.AppOrganizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ActionLogUiState(
    val logs: List<ActionLogEntity> = emptyList(),
    val selectedFilter: ActionStatus? = null,
    val feedbackMessage: String? = null
)

class ActionLogViewModel(
    private val actionLogRepository: ActionLogRepository,
    private val appOrganizer: AppOrganizer,
    private val commandExecutor: CommandExecutor
) : ViewModel() {

    private val selectedFilter = MutableStateFlow<ActionStatus?>(null)
    private val feedbackMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ActionLogUiState> = combine(
        actionLogRepository.allLogs,
        selectedFilter,
        feedbackMessage
    ) { logs, filter, feedback ->
        val filtered = if (filter == null) {
            logs
        } else {
            logs.filter { it.status == filter.name }
        }
        ActionLogUiState(
            logs = filtered,
            selectedFilter = filter,
            feedbackMessage = feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ActionLogUiState()
    )

    fun setFilter(filter: ActionStatus?) {
        selectedFilter.value = filter
    }

    fun undoAction(log: ActionLogEntity) {
        if (!log.canUndo || log.isUndone || log.undoPayload.isNullOrBlank()) return
        viewModelScope.launch {
            val success = runCatching {
                val json = JSONObject(log.undoPayload)
                when (json.optString("type")) {
                    "UNDO_MOVE_APP" -> {
                        val pkg = json.getString("packageName")
                        val prevFolder = json.getString("previousFolder")
                        appOrganizer.restoreAppFolder(pkg, prevFolder)
                        true
                    }
                    "UNDO_HIDE_APP" -> {
                        val pkg = json.getString("packageName")
                        val wasHidden = json.getBoolean("wasHidden")
                        appOrganizer.restoreAppHiddenState(pkg, wasHidden)
                        true
                    }
                    "CANCEL_REMINDER" -> {
                        val reminderId = json.getInt("reminderId")
                        commandExecutor.cancelScheduledReminder(reminderId)
                    }
                    else -> false
                }
            }.getOrDefault(false)

            if (success) {
                actionLogRepository.markUndone(log.id)
                feedbackMessage.value = "تم التراجع عن الإجراء بأمان."
            } else {
                feedbackMessage.value = "تعذر التراجع عن هذا الإجراء."
            }
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            actionLogRepository.clearAll()
            feedbackMessage.value = "تم مسح سجل الأفعال."
        }
    }

    fun clearFeedback() {
        feedbackMessage.value = null
    }

    class Factory(
        private val actionLogRepository: ActionLogRepository,
        private val appOrganizer: AppOrganizer,
        private val commandExecutor: CommandExecutor
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ActionLogViewModel(actionLogRepository, appOrganizer, commandExecutor) as T
        }
    }
}
