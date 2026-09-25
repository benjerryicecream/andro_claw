package com.androclaw.agent.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class TaskStatus { RUNNING, COMPLETED, FAILED, STOPPED }

@Serializable
data class StepRecord(
    val stepIndex: Int,
    val narration: String,
    val actionJson: String,
    val timestampMs: Long = System.currentTimeMillis()
)

class StepListConverter {
    @TypeConverter
    fun fromStepList(steps: List<StepRecord>): String =
        Json.encodeToString(steps)

    @TypeConverter
    fun toStepList(json: String): List<StepRecord> =
        Json.decodeFromString(json)
}

@Entity(tableName = "tasks")
@TypeConverters(StepListConverter::class)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goal: String,
    val status: String, // TaskStatus.name
    val steps: List<StepRecord> = emptyList(),
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val savedAsRoutineId: Long? = null
)
