package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC")
    fun observeAllLogs(): Flow<List<ActionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ActionLogEntity): Long

    @Query("UPDATE action_logs SET status = :status, canUndo = :canUndo WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, canUndo: Boolean)

    @Query("UPDATE action_logs SET isUndone = 1, canUndo = 0, status = :status WHERE id = :id")
    suspend fun markUndone(id: Long, status: String = ActionStatus.UNDONE.name)

    @Query("DELETE FROM action_logs")
    suspend fun clearAllLogs()
}
