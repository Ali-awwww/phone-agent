package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_organizations")
data class AppOrganizationEntity(
    @PrimaryKey val packageName: String,
    val folderKey: String,
    val isHidden: Boolean = false,
    val lastSummary: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
