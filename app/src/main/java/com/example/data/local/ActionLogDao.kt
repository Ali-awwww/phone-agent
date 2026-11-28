package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs WHERE actionType NOT LIKE 'MODEL_ROUTE_%' ORDER BY timestamp DESC")
    fun observeAllLogs(): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_logs WHERE actionType NOT LIKE 'MODEL_ROUTE_%' ORDER BY timestamp DESC LIMIT :limit")
    fun observePagedLogs(limit: Int): Flow<List<ActionLogEntity>>

    @Query("SELECT COUNT(*) FROM action_logs WHERE actionType NOT LIKE 'MODEL_ROUTE_%'")
    fun observeLogsCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ActionLogEntity): Long

    @Query("UPDATE action_logs SET status = :status, canUndo = :canUndo WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, canUndo: Boolean)

    @Query("UPDATE action_logs SET isUndone = 1, canUndo = 0, status = :status WHERE id = :id")
    suspend fun markUndone(id: Long, status: String = ActionStatus.UNDONE.name)

    @Query("SELECT * FROM action_logs WHERE actionType NOT LIKE 'MODEL_ROUTE_%' AND timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    suspend fun getLogsSince(sinceTimestamp: Long): List<ActionLogEntity>

    @Query("DELETE FROM action_logs")
    suspend fun clearAllLogs()
}
