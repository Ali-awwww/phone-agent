package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val details: String,
    val status: String = ActionStatus.SUCCESS.name,
    val actionType: String = "GENERAL",
    val canUndo: Boolean = false,
    val undoPayload: String? = null,
    val isUndone: Boolean = false
)
