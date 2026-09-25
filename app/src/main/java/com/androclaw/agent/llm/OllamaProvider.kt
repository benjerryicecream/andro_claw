package com.androclaw.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ollama provider for fully local LLM inference.
 * Connects to an Ollama server (default: http://localhost:11434).
 * For Android emulator, use http://10.0.2.2:11434 to reach host machine.
 */
class OllamaProvider(
    private val model: String = "llama3.2",
    private val baseUrl: String = "http://10.0.2.2:11434"
) : LlmProvider {

    override val displayName: String = "Ollama (Local)"
    override val name: String = "ollama"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // Local models can be slow
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class OllamaRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean = false,
        val options: OllamaOptions? = null
    )

    @Serializable
    private data class OllamaMessage(val role: String, val content: String)

    @Serializable
    private data class OllamaOptions(val temperature: Float, @SerialName("num_predict") val numPredict: Int)

    @Serializable
    private data class OllamaResponse(
        val message: OllamaMessage? = null,
        @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
        @SerialName("eval_count") val evalCount: Int = 0
    )

    override suspend fun complete(
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = withContext(Dispatchers.IO) {
        try {
            val requestBody = OllamaRequest(
                model = model,
                messages = messages.map { OllamaMessage(it.role, it.content) },
                stream = false,
                options = OllamaOptions(temperature, maxTokens)
            )
            val bodyJson = json.encodeToString(requestBody)
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/chat")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext LlmResponse.Error("HTTP ${response.code}: $responseBody")
            }
            val parsed = json.decodeFromString<OllamaResponse>(responseBody)
            val text = parsed.message?.content
                ?: return@withContext LlmResponse.Error("No message content in Ollama response")
            LlmResponse.Success(
                text = text,
                inputTokens = parsed.promptEvalCount,
                outputTokens = parsed.evalCount
            )
        } catch (e: IOException) {
            LlmResponse.Error("Network error (is Ollama running?): ${e.message}", e)
        } catch (e: Exception) {
            LlmResponse.Error("Unexpected error: ${e.message}", e)
        }
    }
}
