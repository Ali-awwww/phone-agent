package com.example.data.local

import com.example.security.PrivacyPreferences
import com.example.security.SensitiveDataScrubber
import kotlinx.coroutines.flow.Flow

class ActionLogRepository(
    private val dao: ActionLogDao,
    private val privacyPreferences: PrivacyPreferences? = null
) {
    val allLogs: Flow<List<ActionLogEntity>> = dao.observeAllLogs()

    fun observePagedLogs(limit: Int = DEFAULT_PAGE_SIZE): Flow<List<ActionLogEntity>> =
        dao.observePagedLogs(limit.coerceAtLeast(1))

    fun observeLogsCount(): Flow<Int> = dao.observeLogsCount()

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }

    suspend fun logAction(
        title: String,
        details: String,
        status: ActionStatus,
        actionType: String,
        canUndo: Boolean = false,
        undoPayload: String? = null
    ): Long {
        val isPrivacyOn = privacyPreferences?.isPrivacyModeEnabled() == true
        val scrubbedTitle = SensitiveDataScrubber.scrub(title)
        val finalDetails = if (isPrivacyOn) {
            "وضع الخصوصية مفعّل — أُخفيت التفاصيل لحماية خصوصيتك."
        } else {
            SensitiveDataScrubber.scrub(details)
        }
        val scrubbedUndo = undoPayload?.let { SensitiveDataScrubber.scrub(it) }

        return dao.insertLog(
            ActionLogEntity(
                timestamp = System.currentTimeMillis(),
                title = scrubbedTitle,
                details = finalDetails,
                status = status.name,
                actionType = actionType,
                canUndo = canUndo,
                undoPayload = scrubbedUndo,
                isUndone = false
            )
        )
    }

    suspend fun updateStatus(id: Long, status: ActionStatus, canUndo: Boolean = false) {
        dao.updateStatus(id, status.name, canUndo)
    }

    suspend fun markUndone(id: Long) {
        dao.markUndone(id, ActionStatus.UNDONE.name)
    }

    suspend fun getLogsSince(sinceTimestamp: Long): List<ActionLogEntity> {
        return dao.getLogsSince(sinceTimestamp)
    }

    suspend fun clearAll() {
        dao.clearAllLogs()
    }
}
