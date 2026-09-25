package com.androclaw.agent.agent

import com.androclaw.agent.data.RoutineDao
import com.androclaw.agent.data.RoutineEntity
import com.androclaw.agent.data.RoutineStep
import com.androclaw.agent.data.StepRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Manages saved routines — completed task step sequences that can be replayed.
 */
class RoutineManager(private val routineDao: RoutineDao) {

    fun getAllRoutines(): Flow<List<RoutineEntity>> = routineDao.getAllRoutines()

    suspend fun saveAsRoutine(
        name: String,
        description: String,
        triggerPhrase: String,
        steps: List<StepRecord>
    ): Long {
        val routineSteps = steps.map { step ->
            RoutineStep(
                actionType = extractActionType(step.actionJson),
                actionJson = step.actionJson,
                narration = step.narration
            )
        }
        val routine = RoutineEntity(
            name = name,
            description = description,
            triggerPhrase = triggerPhrase,
            steps = routineSteps
        )
        return routineDao.insert(routine)
    }

    suspend fun deleteRoutine(id: Long) = routineDao.deleteById(id)

    suspend fun recordRun(id: Long) = routineDao.recordRun(id, System.currentTimeMillis())

    suspend fun findByTriggerPhrase(phrase: String): RoutineEntity? =
        routineDao.findByTriggerPhrase(phrase)

    private fun extractActionType(actionJson: String): String {
        return try {
            val el = kotlinx.serialization.json.Json.parseToJsonElement(actionJson)
            el.jsonObject["action"]?.let {
                kotlinx.serialization.json.Json.decodeFromJsonElement<String>(it)
            } ?: "unknown"
        } catch (e: Exception) { "unknown" }
    }
}
