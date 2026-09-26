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

class GeminiProvider(
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash"
) : LlmProvider {

    override val displayName: String = "Google Gemini"
    override val name: String = "gemini"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Gemini native structured output: pins responseMimeType=application/json so
     * the model is constrained to emit exactly one JSON object (no fences, no prose).
     */
    override suspend fun completeJson(
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = runStructured(messages, temperature, maxTokens, responseMimeType = "application/json")

    private suspend fun runStructured(
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int,
        responseMimeType: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val systemMsg = messages.firstOrNull { it.role == "system" }
            val conversationMsgs = messages.filter { it.role != "system" }

            val contents = conversationMsgs.map { msg ->
                GeminiContent(
                    role = if (msg.role == "assistant") "model" else "user",
                    parts = listOf(GeminiPart(msg.content))
                )
            }

            val systemInstruction = systemMsg?.let {
                GeminiContent(role = "user", parts = listOf(GeminiPart(it.content)))
            }

            val requestBody = GeminiRequest(
                contents = contents,
                generationConfig = GenerationConfig(temperature, maxTokens, responseMimeType),
                systemInstruction = systemInstruction
            )

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val bodyJson = json.encodeToString(requestBody)
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext LlmResponse.Error("HTTP ${response.code}: $responseBody")
            }
            val parsed = json.decodeFromString<GeminiResponse>(responseBody)
            val text = parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: return@withContext LlmResponse.Error("No content in Gemini response")
            LlmResponse.Success(
                text = text,
                inputTokens = parsed.usageMetadata?.promptTokenCount ?: 0,
                outputTokens = parsed.usageMetadata?.candidatesTokenCount ?: 0
            )
        } catch (e: IOException) {
            LlmResponse.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            LlmResponse.Error("Unexpected error: ${e.message}", e)
        }
    }

    override suspend fun complete(
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = runStructured(messages, temperature, maxTokens, responseMimeType = null)

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        @SerialName("generationConfig") val generationConfig: GenerationConfig,
        @SerialName("systemInstruction") val systemInstruction: GeminiContent? = null
    )

    @Serializable
    private data class GeminiContent(
        val role: String = "user",
        val parts: List<GeminiPart>
    )

    @Serializable
    private data class GeminiPart(val text: String)

    @Serializable
    private data class GenerationConfig(
        val temperature: Float,
        @SerialName("maxOutputTokens") val maxOutputTokens: Int,
        @SerialName("responseMimeType") val responseMimeType: String? = null
    )

    @Serializable
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate> = emptyList(),
        @SerialName("usageMetadata") val usageMetadata: GeminiUsage? = null
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null,
        @SerialName("finishReason") val finishReason: String? = null
    )

    @Serializable
    private data class GeminiUsage(
        @SerialName("promptTokenCount") val promptTokenCount: Int = 0,
        @SerialName("candidatesTokenCount") val candidatesTokenCount: Int = 0
    )
}
