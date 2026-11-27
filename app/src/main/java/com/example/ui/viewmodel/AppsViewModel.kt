package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.model.AppFolder
import com.example.organizer.AppOrganizer
import com.example.organizer.InstalledAppInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AppsViewModel"

const val TRY_SUMMARIZE_TIMEOUT_MS = 180_000L

enum class SummarizeBlockedReason {
    ACCESSIBILITY,
    OVERLAY
}

data class AppsUiState(
    val allApps: List<InstalledAppInfo> = emptyList(),
    val selectedFolder: AppFolder? = null,
    val searchQuery: String = "",
    val showHiddenApps: Boolean = false,
    val isLoading: Boolean = true,
    val isAutoCategorizing: Boolean = false,
    val summarizingAppName: String? = null,
    val summarizingStep: String? = null,
    val summarizingElapsedSeconds: Int = 0,
    val summarizeBlockedReason: SummarizeBlockedReason? = null,
    val appSummaryResult: Pair<String, String>? = null,
    val statusMessage: String? = null
) {
    val filteredApps: List<InstalledAppInfo>
        get() = allApps.filter { app ->
            val matchesHidden = showHiddenApps || !app.isHidden
            val matchesFolder = selectedFolder == null || app.folder == selectedFolder
            val q = searchQuery.trim().lowercase()
            val matchesSearch = q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q)
            matchesHidden && matchesFolder && matchesSearch
        }

    val folderCounts: Map<AppFolder, Int>
        get() = AppFolder.entries.associateWith { folder ->
            allApps.count { it.folder == folder && (showHiddenApps || !it.isHidden) }
        }
}

class AppsViewModel(
    private val appOrganizer: AppOrganizer
) : ViewModel() {

    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()

    init {
        observeApps()
    }

    private fun observeApps() {
        viewModelScope.launch {
            try {
                appOrganizer.installedAppsFlow.collectLatest { apps ->
                    _state.update { it.copy(allApps = apps, isLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load installed apps", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "تعذر تحميل قائمة التطبيقات: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            runCatching { appOrganizer.refreshInstalledPackagesCache() }
        }
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun selectFolder(folder: AppFolder?) {
        _state.update { it.copy(selectedFolder = folder) }
    }

    fun toggleShowHidden() {
        _state.update { it.copy(showHiddenApps = !it.showHiddenApps) }
    }

    fun moveAppToFolder(app: InstalledAppInfo, folder: AppFolder) {
        viewModelScope.launch {
            appOrganizer.moveAppToFolder(app, folder)
            _state.update { it.copy(statusMessage = "تم نقل «${app.label}» إلى ${folder.titleAr}") }
        }
    }

    fun toggleHideApp(app: InstalledAppInfo) {
        viewModelScope.launch {
            val nextHidden = !app.isHidden
            appOrganizer.toggleHideApp(app, nextHidden)
            _state.update {
                it.copy(
                    statusMessage = if (nextHidden) "تم إخفاء «${app.label}»" else "تمت إعادة إظهار «${app.label}»"
                )
            }
        }
    }

    fun autoCategorizeWithGemini() {
        val apps = _state.value.allApps
        if (apps.isEmpty() || _state.value.isAutoCategorizing) return
        viewModelScope.launch {
            _state.update { it.copy(isAutoCategorizing = true, statusMessage = null) }
            try {
                appOrganizer.autoCategorizeWithGemini(apps)
                _state.update {
                    it.copy(
                        isAutoCategorizing = false,
                        statusMessage = "تم تصنيف التطبيقات بنجاح باستخدام Gemini."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini categorization failed", e)
                _state.update {
                    it.copy(
                        isAutoCategorizing = false,
                        statusMessage = "تعذر التصنيف التلقائي: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun openApp(context: Context, app: InstalledAppInfo) {
        val intent = AppOrganizer.createLaunchIntent(context, app.packageName)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
                .onFailure { err ->
                    _state.update { it.copy(statusMessage = "تعذر فتح «${app.label}»: ${err.localizedMessage}") }
                }
        } else {
            _state.update { it.copy(statusMessage = "لا يمكن فتح «${app.label}» مباشرة.") }
        }
    }

    private var summarizeJob: Job? = null
    private var summaryTimerJob: Job? = null

    fun tryAndSummarizeApp(app: InstalledAppInfo) {
        summarizeJob?.cancel()
        summaryTimerJob?.cancel()
        _state.update {
            it.copy(
                summarizingAppName = app.label,
                summarizingStep = "جارٍ فتح التطبيق…",
                summarizingElapsedSeconds = 0,
                statusMessage = null
            )
        }
        summaryTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _state.update { it.copy(summarizingElapsedSeconds = it.summarizingElapsedSeconds + 1) }
            }
        }
        summarizeJob = viewModelScope.launch {
            try {
                val summary = kotlinx.coroutines.withTimeout(TRY_SUMMARIZE_TIMEOUT_MS) {
                    appOrganizer.tryAndSummarizeApp(app) { step ->
                        _state.update { it.copy(summarizingStep = step) }
                    }
                }
                summaryTimerJob?.cancel()
                _state.update {
                    it.copy(
                        summarizingAppName = null,
                        summarizingStep = null,
                        summarizingElapsedSeconds = 0,
                        appSummaryResult = app.label to summary
                    )
                }
            } catch (e: TimeoutCancellationException) {
                summaryTimerJob?.cancel()
                _state.update {
                    it.copy(
                        summarizingAppName = null,
                        summarizingStep = null,
                        summarizingElapsedSeconds = 0,
                        statusMessage = "انتهت مهلة التلخيص (3 دقائق). تحقق من الاتصال وإمكانية الوصول ثم أعد المحاولة."
                    )
                }
            } catch (e: CancellationException) {
                summaryTimerJob?.cancel()
                throw e
            } catch (e: Exception) {
                summaryTimerJob?.cancel()
                Log.e(TAG, "Try and summarize failed", e)
                _state.update {
                    it.copy(
                        summarizingAppName = null,
                        summarizingStep = null,
                        summarizingElapsedSeconds = 0,
                        statusMessage = "تعذر تلخيص التطبيق: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun cancelSummarizing() {
        summarizeJob?.cancel()
        summaryTimerJob?.cancel()
        summarizeJob = null
        summaryTimerJob = null
        _state.update {
            it.copy(
                summarizingAppName = null,
                summarizingStep = null,
                summarizingElapsedSeconds = 0,
                statusMessage = "تم إلغاء عملية التلخيص."
            )
        }
    }

    fun setSummarizeBlockedReason(reason: SummarizeBlockedReason?) {
        _state.update { it.copy(summarizeBlockedReason = reason) }
    }

    fun dismissSummaryDialog() {
        _state.update { it.copy(appSummaryResult = null) }
    }

    fun clearStatusMessage() {
        _state.update { it.copy(statusMessage = null) }
    }

    class Factory(private val appOrganizer: AppOrganizer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AppsViewModel(appOrganizer) as T
        }
    }
}
