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

class AnthropicProvider(
    private val apiKey: String,
    private val model: String = "claude-3-5-sonnet-20241022"
) : LlmProvider {

    override val displayName: String = "Anthropic"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        val messages: List<AnthropicMessage>,
        @SerialName("max_tokens") val maxTokens: Int,
        val temperature: Float,
        val system: String? = null
    )

    @Serializable
    private data class AnthropicMessage(val role: String, val content: String)

    @Serializable
    private data class AnthropicResponse(
        val content: List<ContentBlock> = emptyList(),
        val usage: AnthropicUsage? = null
    )

    @Serializable
    private data class ContentBlock(val type: String, val text: String = "")

    @Serializable
    private data class AnthropicUsage(
        @SerialName("input_tokens") val inputTokens: Int = 0,
        @SerialName("output_tokens") val outputTokens: Int = 0
    )

    override suspend fun complete(
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = withContext(Dispatchers.IO) {
        try {
            // Separate system message from conversation
            val systemMsg = messages.firstOrNull { it.role == "system" }?.content
            val conversationMsgs = messages.filter { it.role != "system" }
                .map { AnthropicMessage(it.role, it.content) }

            val requestBody = AnthropicRequest(
                model = model,
                messages = conversationMsgs,
                maxTokens = maxTokens,
                temperature = temperature,
                system = systemMsg
            )
            val bodyJson = json.encodeToString(requestBody)
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext LlmResponse.Error("HTTP ${response.code}: $responseBody")
            }
            val parsed = json.decodeFromString<AnthropicResponse>(responseBody)
            val text = parsed.content.firstOrNull { it.type == "text" }?.text
                ?: return@withContext LlmResponse.Error("No text content in response")
            LlmResponse.Success(
                text = text,
                inputTokens = parsed.usage?.inputTokens ?: 0,
                outputTokens = parsed.usage?.outputTokens ?: 0
            )
        } catch (e: IOException) {
            LlmResponse.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            LlmResponse.Error("Unexpected error: ${e.message}", e)
        }
    }
}
