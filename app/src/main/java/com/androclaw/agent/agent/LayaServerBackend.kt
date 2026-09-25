package com.androclaw.agent.agent

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP [DecisionClient] speaking the Laya "System 1" contract:
 * POST { "model": "laya", "state": ..., "questions": { id: { type, instructions, criteria } } }
 * ->  { id: { choice, confidence, probabilities } | { noul } | { score, confidence } }
 *
 * The local Laya server does not exist yet; this backend is only required to be
 * correct against the contract. Any failure (unreachable host, non-2xx, timeout,
 * malformed JSON) returns null so the caller fails closed to the LLM path.
 *
 * All I/O runs on Dispatchers.IO. Never throws out of [evaluate].
 */
class LayaServerBackend(
    baseUrl: String = "http://127.0.0.1:7710",
    private val timeoutMs: Long = 10_000L
) : DecisionClient {

    private val endpoint: String =
        baseUrl.trim().trimEnd('/').let {
            if (it.endsWith("/v1/systemone")) it else "$it/v1/systemone"
        }

    override suspend fun evaluate(
        state: String,
        questions: Map<String, DecisionQuestion>
    ): Map<String, DecisionAnswer>? = withContext(Dispatchers.IO) {
        val connection = try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn
        } catch (e: Exception) {
            Log.w(TAG, "Decision backend unreachable at $endpoint: ${e.message}")
            return@withContext null
        }

        try {
            val payload = JSONObject()
                .put("model", "laya")
                .put("state", state)
                .put("questions", buildQuestionsJson(questions))

            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Decision backend HTTP $code at $endpoint")
                return@withContext null
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                .use { it.readText() }

            parseAnswers(JSONObject(body), questions)
        } catch (e: Exception) {
            Log.w(TAG, "Decision backend failure at $endpoint: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun buildQuestionsJson(questions: Map<String, DecisionQuestion>): JSONObject {
        val out = JSONObject()
        for ((id, q) in questions) {
            out.put(
                id, JSONObject()
                    .put("type", q.type)
                    .put("instructions", q.instructions)
                    .put("criteria", JSONObject(q.options))
            )
        }
        return out
    }

    /** Parse one answer per question id. Any malformed answer fails the whole call. */
    private fun parseAnswers(
        body: JSONObject,
        questions: Map<String, DecisionQuestion>
    ): Map<String, DecisionAnswer>? {
        val answers = mutableMapOf<String, DecisionAnswer>()
        for ((id, q) in questions) {
            val raw = body.optJSONObject(id) ?: return null
            val parsed: DecisionAnswer = when (q.type) {
                "choice" -> {
                    val choice = raw.optString("choice", "").trim()
                    if (choice.isEmpty()) return null
                    val probs = raw.optJSONObject("probabilities") ?: JSONObject()
                    DecisionAnswer.Choice(
                        choice = choice,
                        confidence = raw.optDouble("confidence", 0.0),
                        probabilities = buildStringDoubleMap(probs)
                    )
                }
                "noul" -> {
                    if (!raw.has("noul")) return null
                    DecisionAnswer.Noul(raw.optDouble("noul", 0.0))
                }
                "score" -> {
                    if (!raw.has("score")) return null
                    DecisionAnswer.Score(
                        score = raw.optDouble("score", 0.0),
                        confidence = raw.optDouble("confidence", 0.0)
                    )
                }
                else -> return null
            }
            answers[id] = parsed
        }
        return answers
    }

    private fun buildStringDoubleMap(obj: JSONObject): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        obj.keys().forEach { key -> map[key] = obj.optDouble(key, 0.0) }
        return map
    }

    companion object {
        private const val TAG = "LayaServerBackend"
    }
}