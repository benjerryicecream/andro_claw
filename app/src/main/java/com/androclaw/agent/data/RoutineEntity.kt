package com.androclaw.agent.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RoutineStep(
    val actionType: String,
    val actionJson: String,
    val narration: String
)

class RoutineStepListConverter {
    @TypeConverter
    fun fromStepList(steps: List<RoutineStep>): String = Json.encodeToString(steps)

    @TypeConverter
    fun toStepList(json: String): List<RoutineStep> = Json.decodeFromString(json)
}

@Entity(tableName = "routines")
@TypeConverters(RoutineStepListConverter::class)
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val triggerPhrase: String,
    val steps: List<RoutineStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val runCount: Int = 0
)
