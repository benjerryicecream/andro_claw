package com.androclaw.agent.agent

/**
 * Deterministic scoring of a produced [CommandPlan] against a [ParseCorpus.Case].
 * Semantic, not textual: signatures normalize case and punctuation so "YouTube"
 * matches "youtube" while "open youtube" still differs from "search youtube".
 */
object CommandPlanScorer {

    data class StepScore(val expected: String, val actual: String?, val match: Boolean)

    data class Score(
        val caseId: String,
        val stepScores: List<StepScore>,
        val stepsCorrect: Int,
        val stepsTotal: Int,
        val goalCorrect: Boolean,
        val goalExpected: PlanGoal?,
        val goalActual: PlanGoal?,
        val confirmationCorrect: Boolean,
        val confirmationExpected: Boolean?,
        val confirmationActual: Boolean
    ) {
        val stepsPct: Float get() = if (stepsTotal == 0) 1f else stepsCorrect.toFloat() / stepsTotal
    }

    /** Canonical signature of one step for exact-order comparison. */
    fun stepSignature(step: PlanStep): String = when (step) {
        is PlanStep.OpenApp -> "open_app:${norm(step.target)}"
        is PlanStep.OpenUrl -> "open_url:${normUrl(step.url)}"
        is PlanStep.WebSearch -> "search:${norm(step.query)}"
        is PlanStep.ScopedSearch ->
            "search_in_app:${norm(step.app)}|${norm(step.query)}|${if (step.installGoal) "install" else "search"}"
        is PlanStep.SubTask -> "task:${norm(step.instruction)}"
    }

    private fun planGoalSignature(g: PlanGoal?): String? =
        g?.let { "${it.kind.lowercase()}:${norm(it.target)}" }

    /**
     * Score [actual] against [expected]. The expectation holds the reference for
     * steps/goal/confirmation; any expectation we could not express (e.g. a null
     * goal) is skipped and marked correct rather than penalizing unknown intent.
     */
    fun score(actual: CommandPlan, expected: ParseCorpus.Case): Score {
        val actualSteps = when (actual) {
            is CommandPlan.Single -> listOf(actual.step)
            is CommandPlan.Ordered -> actual.steps
        }
        val expectedSigs = expected.expectedSteps.map { stepSignature(it) }
        val stepScores = expectedSigs.mapIndexed { i, exp ->
            val got = actualSteps.getOrNull(i)?.let { stepSignature(it) }
            StepScore(exp, got, got != null && got == exp)
        }
        val stepsCorrect = stepScores.count { it.match }

        val goalExpected = expected.expectedGoal
        val goalActual = actual.verifierGoal
        val goalCorrect = planGoalSignature(goalExpected) == planGoalSignature(goalActual)

        val confirmationExpected = expected.expectedNeedsConfirmation
        val confirmationActual = actual.needsConfirmation
        val confirmationCorrect = confirmationActual == confirmationExpected

        return Score(
            caseId = expected.id,
            stepScores = stepScores,
            stepsCorrect = stepsCorrect,
            stepsTotal = expectedSigs.size,
            goalCorrect = goalCorrect,
            goalExpected = goalExpected,
            goalActual = goalActual,
            confirmationCorrect = confirmationCorrect,
            confirmationExpected = confirmationExpected,
            confirmationActual = confirmationActual
        )
    }

    fun formatReport(scores: List<Score>, mode: String): String = buildString {
        appendLine("CommandPlan parse corpus — mode: $mode")
        appendLine()
        appendLine(String.format("%-28s %-10s %-10s %-14s %-11s", "CASE", "STEPS", "GOAL", "CONFIRM", "VALUE"))
        appendLine("-".repeat(80))
        scores.forEach { s ->
            val steps = "${s.stepsCorrect}/${s.stepsTotal}"
            appendLine(
                String.format(
                    "%-28s %-10s %-10s %-14s %-11s",
                    s.caseId,
                    steps,
                    if (s.goalCorrect) "OK" else "MISS",
                    if (s.confirmationCorrect) "OK" else "MISS",
                    if (s.goalCorrect && s.confirmationCorrect && s.stepsPct == 1f) "PASS" else "FAIL"
                )
            )
        }
        val totalSteps = scores.sumOf { it.stepsTotal }
        val correctSteps = scores.sumOf { it.stepsCorrect }
        val correctGoals = scores.count { it.goalCorrect }
        val correctConfirms = scores.count { it.confirmationCorrect }
        val passed = scores.count {
            it.goalCorrect && it.confirmationCorrect && it.stepsPct == 1f
        }
        appendLine("-".repeat(80))
        appendLine("TOTAL       cases=${scores.size} passed=$passed")
        appendLine("STEPS       $correctSteps/$totalSteps correct (${pc(correctSteps, totalSteps)})")
        appendLine("GOALS       $correctGoals/${scores.size} correct (${pc(correctGoals, scores.size)})")
        appendLine("CONFIRM     $correctConfirms/${scores.size} correct (${pc(correctConfirms, scores.size)})")
    }

    private fun pc(a: Int, b: Int): String =
        if (b == 0) "n/a" else String.format("%.0f%%", a * 100f / b)

    private fun norm(t: String): String =
        t.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun normUrl(u: String): String =
        norm(u.removePrefix("https://").removePrefix("http://").substringBefore("#").substringAfter("q="))
}