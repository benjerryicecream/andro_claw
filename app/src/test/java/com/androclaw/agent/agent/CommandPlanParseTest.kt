package com.androclaw.agent.agent

import com.androclaw.agent.llm.GeminiProvider
import com.androclaw.agent.llm.LlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Parsing-quality gate for the structured planner.
 *
 * Runs the full [ParseCorpus] through [CommandPlanner.parseCommand] and scores
 * step correctness, goal correctness, and confirmation flags via
 * [CommandPlanScorer] — quality is measured, not eyeballed. The report prints to
 * stdout and build/reports/parse_corpus.txt.
 *
 * Provider selection:
 *   - GEMINI_API_KEY env var, else local.properties key "geminiApiKey".
 *   - With a key: the live Gemini structured-plan path is exercised (this is the
 *     real measurement, and every corpus parse is logged to laya_training.jsonl).
 *   - Without a key: the deterministic fallback is exercised; the fast-path and
 *     no-crash assertions still gate so the test is a real offline regression gate.
 */
class CommandPlanParseTest {

    @Test
    fun corpusScoring() {
        val provider = resolveProvider()
        val mode = if (provider != null) "gemini-structured" else "deterministic-fallback"
        val repoRoot = findRepoRoot()
        val logger = TrainingLogger(repoRoot)

        val scores = runBlocking {
            ParseCorpus.cases.map { c ->
                val taskId = "parse-corpus-${c.id}"
                val result = CommandPlanner.parseCommand(
                    c.command,
                    provider,
                    logger,
                    provider?.name ?: "deterministic",
                    taskId
                )
                CommandPlanScorer.score(result.plan, c)
            }
        }

        println(CommandPlanScorer.formatReport(scores, mode))
        File(repoRoot, "build/reports").mkdirs()
        File(repoRoot, "build/reports/parse_corpus.txt")
            .writeText(CommandPlanScorer.formatReport(scores, mode))

        // Regression floors that must hold regardless of mode:
        // 1. Every case produced a scored plan (never a crash / never empty steps).
        scores.forEach { s ->
            assertEquals("${s.caseId}: expected some steps", true, s.stepsTotal > 0)
        }
        // 2. The exact "^open <single app>$" fast path is the regex's whole job:
        //    it must be perfectly parsed (steps + goal) every time, LLM or not.
        ParseCorpus.cases.filter { it.viaFastPath }.forEach { c ->
            val s = scores.first { it.caseId == c.id }
            assertEquals("${c.id}: fast-path steps", s.stepsTotal, s.stepsCorrect)
            assertEquals("${c.id}: fast-path goal", true, s.goalCorrect)
        }
        // 3. Deterministic mode must never throw nor misparse into an empty plan.
        //    (Incorrect-but-plausible step sets are acceptable offline; the live
        //    LLM mode below is the accuracy measurement.)
        if (provider == null) {
            assertEquals("all plans scored", ParseCorpus.cases.size, scores.size)
            val noSteps = scores.count { it.stepsCorrect == 0 }
            // regression: "open chrom…" style compound must produce at least its open step.
            scores.filter { it.caseId.contains("browser-open") || it.caseId.contains("regress") }
                .forEach { s -> assertEquals("${s.caseId}: at least an open step", true, s.stepsCorrect >= 1) }
            println("OFFLINE MODE: no provider key — step/goal accuracy is NOT measured; pass GEMINI_API_KEY or local.properties (geminiApiKey) for the real gate.")
            if (noSteps > 0) println("WARN: $noSteps case(s) parsed to zero matching steps in fallback mode")
        } else {
            // Live mode: every case must at least produce the RIGHT NUMBER of steps
            // (no merging/dropping) — a cheap hard gate on top of the printed scores.
            scores.forEach { s ->
                val expected = ParseCorpus.cases.first { it.id == s.caseId }.expectedSteps.size
                assertEquals("${s.caseId}: step count preserved", expected, s.stepsTotal)
                if (s.goalCorrect && s.stepsPct == 1f && s.confirmationCorrect) Unit else {
                    println("MISS ${s.caseId} — measure, don't approve. See report for details.")
                }
            }
        }
    }

    private fun resolveProvider(): LlmProvider? {
        val key = (System.getenv("GEMINI_API_KEY") ?: "").trim()
            .ifBlank { projectProperties["geminiApiKey"].orEmpty() }
            .ifBlank { projectProperties["gemini.apiKey"].orEmpty() }
            .trim()
        if (key.isEmpty()) {
            println("No GEMINI_API_KEY / local.properties geminiApiKey found — running deterministic fallback only.")
            return null
        }
        println("Using Gemini provider for structured parsing (env/local.properties key).")
        return GeminiProvider(key)
    }

    private val projectProperties: Map<String, String> by lazy {
        val props = Properties()
        propertiesFiles().forEach { f ->
            runCatching { f.inputStream()?.use { props.load(it) } }
        }
        props.entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
    }

    private fun propertiesFiles(): List<File> = listOf(
        File(System.getProperty("user.dir"), "local.properties"),
        File(System.getProperty("user.dir"), "../local.properties")
    ).filter { it.isFile }

    private fun findRepoRoot(): File {
        val start = File(System.getProperty("user.dir")).canonicalFile
        var d: File? = start
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile || File(d, "laya_training.jsonl").isFile) return d
            d = d.parentFile
        }
        return start
    }
}