package com.androclaw.agent.llm

import com.androclaw.agent.data.LlmProviderType
import com.androclaw.agent.data.SecurePreferences

object LlmProviderFactory {
    fun create(prefs: SecurePreferences): LlmProvider {
        return when (LlmProviderType.valueOf(prefs.selectedProvider)) {
            LlmProviderType.OPENAI -> OpenAIProvider(
                apiKey = prefs.openAiApiKey,
                model = prefs.openAiModel,
                baseUrl = prefs.openAiBaseUrl
            )
            LlmProviderType.ANTHROPIC -> AnthropicProvider(
                apiKey = prefs.anthropicApiKey,
                model = prefs.anthropicModel
            )
            LlmProviderType.GEMINI -> GeminiProvider(
                apiKey = prefs.geminiApiKey,
                model = prefs.geminiModel
            )
            LlmProviderType.OLLAMA -> OllamaProvider(
                model = prefs.ollamaModel,
                baseUrl = prefs.ollamaBaseUrl
            )
        }
    }
}
