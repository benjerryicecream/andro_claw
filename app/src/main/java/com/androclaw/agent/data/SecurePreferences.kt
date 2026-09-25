package com.androclaw.agent.data

import com.androclaw.agent.BuildConfig

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Secure, encrypted preferences storage.
 * API keys stored via EncryptedSharedPreferences.
 * Non-sensitive settings stored in plaintext DataStore.
 */
class SecurePreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "androclaw_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // --- Provider selection ---
    var selectedProvider: String
        get() = securePrefs.getString(KEY_PROVIDER, LlmProviderType.OPENAI.name) ?: LlmProviderType.OPENAI.name
        set(value) = securePrefs.edit().putString(KEY_PROVIDER, value).apply()

    // --- API Keys ---
    var openAiApiKey: String
        get() = securePrefs.getString(KEY_OPENAI_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_OPENAI_KEY, value).apply()

    var anthropicApiKey: String
        get() = securePrefs.getString(KEY_ANTHROPIC_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_ANTHROPIC_KEY, value).apply()

    var geminiApiKey: String
        get() = securePrefs.getString(KEY_GEMINI_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_GEMINI_KEY, value).apply()

    // --- Model names ---
    var openAiModel: String
        get() = securePrefs.getString(KEY_OPENAI_MODEL, "gpt-4o") ?: "gpt-4o"
        set(value) = securePrefs.edit().putString(KEY_OPENAI_MODEL, value).apply()

    var anthropicModel: String
        get() = securePrefs.getString(KEY_ANTHROPIC_MODEL, "claude-3-5-sonnet-20241022") ?: "claude-3-5-sonnet-20241022"
        set(value) = securePrefs.edit().putString(KEY_ANTHROPIC_MODEL, value).apply()

    var geminiModel: String
        get() = securePrefs.getString(KEY_GEMINI_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash"
        set(value) = securePrefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    var ollamaModel: String
        get() = securePrefs.getString(KEY_OLLAMA_MODEL, "llama3.2") ?: "llama3.2"
        set(value) = securePrefs.edit().putString(KEY_OLLAMA_MODEL, value).apply()

    var openAiBaseUrl: String
        get() = securePrefs.getString(KEY_OPENAI_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = securePrefs.edit().putString(KEY_OPENAI_BASE_URL, value).apply()

    var ollamaBaseUrl: String
        get() = securePrefs.getString(KEY_OLLAMA_BASE_URL, "http://localhost:11434") ?: "http://localhost:11434"
        set(value) = securePrefs.edit().putString(KEY_OLLAMA_BASE_URL, value).apply()

    // --- Non-sensitive settings ---
    var maxSteps: Int
        get() = securePrefs.getInt(KEY_MAX_STEPS, 15)
        set(value) = securePrefs.edit().putInt(KEY_MAX_STEPS, value).apply()

    var debugMode: Boolean
        get() = BuildConfig.DEBUG && securePrefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = securePrefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    var onboardingComplete: Boolean
        get() = securePrefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = securePrefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    // --- Confirmation policies ---
    var confirmMessages: Boolean
        get() = securePrefs.getBoolean(KEY_CONFIRM_MESSAGES, true)
        set(value) = securePrefs.edit().putBoolean(KEY_CONFIRM_MESSAGES, value).apply()

    var confirmCalls: Boolean
        get() = securePrefs.getBoolean(KEY_CONFIRM_CALLS, true)
        set(value) = securePrefs.edit().putBoolean(KEY_CONFIRM_CALLS, value).apply()

    var confirmPayments: Boolean
        get() = securePrefs.getBoolean(KEY_CONFIRM_PAYMENTS, true)
        set(value) = securePrefs.edit().putBoolean(KEY_CONFIRM_PAYMENTS, value).apply()

    var confirmDeletions: Boolean
        get() = securePrefs.getBoolean(KEY_CONFIRM_DELETIONS, true)
        set(value) = securePrefs.edit().putBoolean(KEY_CONFIRM_DELETIONS, value).apply()

    var confirmSystemSettings: Boolean
        get() = securePrefs.getBoolean(KEY_CONFIRM_SYSTEM, true)
        set(value) = securePrefs.edit().putBoolean(KEY_CONFIRM_SYSTEM, value).apply()

    var confirmPermissions: Boolean
        get() = securePrefs.getBoolean(KEY_CONFIRM_PERMISSIONS, true)
        set(value) = securePrefs.edit().putBoolean(KEY_CONFIRM_PERMISSIONS, value).apply()

    var unrestrictedMode: Boolean
        get() = securePrefs.getBoolean(KEY_UNRESTRICTED_MODE, true)
        set(value) = securePrefs.edit().putBoolean(KEY_UNRESTRICTED_MODE, value).apply()

    // --- App filter ---
    var allowlistPackages: Set<String>
        get() = securePrefs.getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()
        set(value) = securePrefs.edit().putStringSet(KEY_ALLOWLIST, value).apply()

    var blocklistPackages: Set<String>
        get() = securePrefs.getStringSet(KEY_BLOCKLIST, DEFAULT_BLOCKLIST) ?: DEFAULT_BLOCKLIST
        set(value) = securePrefs.edit().putStringSet(KEY_BLOCKLIST, value).apply()

    var useAllowlist: Boolean
        get() = securePrefs.getBoolean(KEY_USE_ALLOWLIST, false)
        set(value) = securePrefs.edit().putBoolean(KEY_USE_ALLOWLIST, value).apply()

    var showFloatingOverlay: Boolean
        get() = securePrefs.getBoolean(KEY_SHOW_FLOATING_OVERLAY, false)
        set(value) = securePrefs.edit().putBoolean(KEY_SHOW_FLOATING_OVERLAY, value).apply()

    // --- System 1 decision backend ---
    var decisionBackend: String
        get() = securePrefs.getString(KEY_DECISION_BACKEND, DECISION_BACKEND_OFF) ?: DECISION_BACKEND_OFF
        set(value) = securePrefs.edit().putString(KEY_DECISION_BACKEND, value).apply()

    var layaServerUrl: String
        get() = securePrefs.getString(KEY_LAYA_SERVER_URL, DEFAULT_LAYA_SERVER_URL) ?: DEFAULT_LAYA_SERVER_URL
        set(value) = securePrefs.edit().putString(KEY_LAYA_SERVER_URL, value).apply()

    // --- Chat watch mode ---
    var isWatchingChat: Boolean
        get() = securePrefs.getBoolean(KEY_WATCHING_CHAT, false)
        set(value) = securePrefs.edit().putBoolean(KEY_WATCHING_CHAT, value).apply()

    var watchPollSeconds: Int
        get() = securePrefs.getInt(KEY_WATCH_POLL_SECONDS, DEFAULT_WATCH_POLL_SECONDS)
        set(value) = securePrefs.edit().putInt(KEY_WATCH_POLL_SECONDS, value.coerceIn(MIN_WATCH_POLL_SECONDS, MAX_WATCH_POLL_SECONDS)).apply()

    var watchReplyCount: Int
        get() = securePrefs.getInt(KEY_WATCH_REPLY_COUNT, 0)
        set(value) = securePrefs.edit().putInt(KEY_WATCH_REPLY_COUNT, value.coerceAtLeast(0)).apply()

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_OPENAI_KEY = "openai_key"
        private const val KEY_ANTHROPIC_KEY = "anthropic_key"
        private const val KEY_GEMINI_KEY = "gemini_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_ANTHROPIC_MODEL = "anthropic_model"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_OLLAMA_MODEL = "ollama_model"
        private const val KEY_OPENAI_BASE_URL = "openai_base_url"
        private const val KEY_OLLAMA_BASE_URL = "ollama_base_url"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_CONFIRM_MESSAGES = "confirm_messages"
        private const val KEY_CONFIRM_CALLS = "confirm_calls"
        private const val KEY_CONFIRM_PAYMENTS = "confirm_payments"
        private const val KEY_CONFIRM_DELETIONS = "confirm_deletions"
        private const val KEY_CONFIRM_SYSTEM = "confirm_system"
        private const val KEY_CONFIRM_PERMISSIONS = "confirm_permissions"
        private const val KEY_UNRESTRICTED_MODE = "unrestricted_mode"
        private const val KEY_ALLOWLIST = "allowlist"
        private const val KEY_BLOCKLIST = "blocklist"
        private const val KEY_USE_ALLOWLIST = "use_allowlist"
        private const val KEY_SHOW_FLOATING_OVERLAY = "show_floating_overlay"
        private const val KEY_DECISION_BACKEND = "decision_backend"
        private const val KEY_LAYA_SERVER_URL = "laya_server_url"
        private const val KEY_WATCHING_CHAT = "watching_chat"
        private const val KEY_WATCH_POLL_SECONDS = "watch_poll_seconds"
        private const val KEY_WATCH_REPLY_COUNT = "watch_reply_count"

        const val DECISION_BACKEND_OFF = "off"
        const val DECISION_BACKEND_LAYA = "laya"
        const val DEFAULT_LAYA_SERVER_URL = "http://127.0.0.1:7710"

        const val DEFAULT_WATCH_POLL_SECONDS = 20
        const val MIN_WATCH_POLL_SECONDS = 5
        const val MAX_WATCH_POLL_SECONDS = 120

        val DEFAULT_BLOCKLIST = setOf(
            "com.androclaw.agent",
            "com.google.android.inputmethod.latin",
            "com.android.vending",
            "com.google.android.gms",
            "com.chase.sig.android",
            "com.infonow.bofa",
            "com.wellsfargo.mobile",
            "com.citi.citimobile",
            "com.capitalone.enterprise1",
            "com.americanexpress.android.acctsvcs.us",
            "com.venmo",
            "com.paypal.android.p2pmobile",
            "com.squareup.cash",
            "com.google.android.apps.walletnfcrel",
            "com.agilebits.onepassword",
            "com.lastpass.lpandroid",
            "com.bitwarden.passwordmanager",
            "com.dashlane",
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.duosecurity.duomobile"
        )

        @Volatile
        private var INSTANCE: SecurePreferences? = null

        fun getInstance(context: Context): SecurePreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurePreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

enum class LlmProviderType {
    OPENAI, ANTHROPIC, GEMINI, OLLAMA
}
