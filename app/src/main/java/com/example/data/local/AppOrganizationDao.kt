package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppOrganizationDao {
    @Query("SELECT * FROM app_organizations")
    fun observeAllOrganizations(): Flow<List<AppOrganizationEntity>>

    @Query("SELECT * FROM app_organizations WHERE packageName = :packageName LIMIT 1")
    suspend fun getOrganization(packageName: String): AppOrganizationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrganization(entity: AppOrganizationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AppOrganizationEntity>)

    @Query("UPDATE app_organizations SET folderKey = :folderKey, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun updateFolder(packageName: String, folderKey: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_organizations SET isHidden = :isHidden, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun updateHidden(packageName: String, isHidden: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_organizations SET lastSummary = :summary, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun updateSummary(packageName: String, summary: String, updatedAt: Long = System.currentTimeMillis())
}
