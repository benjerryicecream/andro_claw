package com.androclaw.agent.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: RoutineEntity): Long

    @Update
    suspend fun update(routine: RoutineEntity)

    @Query("SELECT * FROM routines ORDER BY createdAt DESC")
    fun getAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun getById(id: Long): RoutineEntity?

    @Query("SELECT * FROM routines WHERE triggerPhrase = :phrase LIMIT 1")
    suspend fun findByTriggerPhrase(phrase: String): RoutineEntity?

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE routines SET lastRunAt = :timestamp, runCount = runCount + 1 WHERE id = :id")
    suspend fun recordRun(id: Long, timestamp: Long)
}
