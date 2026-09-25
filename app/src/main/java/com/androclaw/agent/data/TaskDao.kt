package com.androclaw.agent.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentTasks(limit: Int = 50): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM tasks WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}
