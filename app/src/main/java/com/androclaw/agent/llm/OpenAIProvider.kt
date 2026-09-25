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

class OpenAIProvider(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val baseUrl: String = "https://api.openai.com/v1"
) : LlmProvider {

    override val displayName: String = "OpenAI-compatible"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<MessageDto>,
        val temperature: Float,
        @SerialName("max_tokens") val maxTokens: Int
    )

    @Serializable
    private data class MessageDto(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null
    )

    @Serializable
    private data class Choice(val message: MessageDto, @SerialName("finish_reason") val finishReason: String? = null)

    @Serializable
    private data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0
    )

    override suspend fun complete(
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = withContext(Dispatchers.IO) {
        try {
            val requestBody = ChatRequest(
                model = model,
                messages = messages.map { MessageDto(it.role, it.content) },
                temperature = temperature,
                maxTokens = maxTokens
            )
            val bodyJson = json.encodeToString(requestBody)
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext LlmResponse.Error("HTTP ${response.code}: $responseBody")
            }
            val parsed = json.decodeFromString<ChatResponse>(responseBody)
            val text = parsed.choices.firstOrNull()?.message?.content
                ?: return@withContext LlmResponse.Error("No choices in response")
            LlmResponse.Success(
                text = text,
                inputTokens = parsed.usage?.promptTokens ?: 0,
                outputTokens = parsed.usage?.completionTokens ?: 0
            )
        } catch (e: IOException) {
            LlmResponse.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            LlmResponse.Error("Unexpected error: ${e.message}", e)
        }
    }
}
