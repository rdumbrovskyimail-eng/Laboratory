package com.opuside.app.feature.scratch.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScratchDao {

    @Query("SELECT * FROM scratch_records ORDER BY createdAt DESC")
    fun getAllRecords(): Flow<List<ScratchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ScratchEntity): Long

    @Delete
    suspend fun delete(record: ScratchEntity)

    @Query("SELECT COUNT(*) FROM scratch_records")
    suspend fun count(): Int
}
