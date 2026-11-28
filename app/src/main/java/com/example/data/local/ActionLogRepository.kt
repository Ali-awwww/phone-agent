package com.example.data.local

import kotlinx.coroutines.flow.Flow

class ActionLogRepository(
    private val dao: ActionLogDao
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
        return dao.insertLog(
            ActionLogEntity(
                timestamp = System.currentTimeMillis(),
                title = title,
                details = details,
                status = status.name,
                actionType = actionType,
                canUndo = canUndo,
                undoPayload = undoPayload,
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

    suspend fun clearAll() {
        dao.clearAllLogs()
    }
}
