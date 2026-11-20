package dev.codex.android.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.codex.android.api.endpointKey

/**
 * On-device store for the user's own API credentials (BYOK — bring your own key).
 *
 * The key is entered by the user at runtime and stored on-device only.
 * Nothing in this file contains a real key, a default key, or a placeholder
 * that looks like a key. Key material is never logged.
 *
 * Storage is [EncryptedSharedPreferences] (AES256_GCM master key,
 * AES256_SIV key encryption, AES256_GCM value encryption). If encrypted
 * storage cannot be created (e.g. a broken keystore on some devices), this
 * falls back to plain [SharedPreferences] only for non-secret settings.
 * API keys are unavailable when encrypted storage fails.
 */
class KeyStore(context: Context) {

    companion object {
        /**
         * No default endpoint is baked in. The user enters their own
         * OpenAI-compatible API base URL in Settings (BYOK).
         * An empty base URL means "not configured yet".
         */
        const val DEFAULT_BASE_URL = ""
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private const val PREFS_FILE = "codex_keys"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_GITHUB_REPO = "github_repo"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val TAG = "KeyStore"

        // Process-wide cache (diagnosis item 8): the encrypted store is
        // created once per process, on whichever thread first needs it —
        // never in a constructor on the main thread. KeyStore's
        // constructor does no I/O; prefs resolve lazily through here.
        @Volatile
        private var cachedPrefs: SharedPreferences? = null
        private val prefsLock = Any()
        @Volatile private var encryptedProviderPrefsAvailable = false
        private var githubPrefs: SharedPreferences? = null

        // GitHub write credentials never fall back to plaintext storage.
        private fun githubPrefsFor(context: Context): SharedPreferences = synchronized(prefsLock) {
            githubPrefs ?: createEncryptedPrefs(context.applicationContext, "codex_github_keys")
                .also { githubPrefs = it }
        }

        /** Returns the process-wide prefs instance, creating it once. */
        fun prefsFor(context: Context): SharedPreferences {
            return cachedPrefs ?: synchronized(prefsLock) {
                cachedPrefs ?: createPrefs(context.applicationContext)
                    .also { cachedPrefs = it }
            }
        }

        /**
         * Creates the store now, on the calling thread — call from a
         * background thread (ChatViewModel init warms it on IO) so the
         * first UI access finds it already cached.
         */
        fun warmUp(context: Context) {
            prefsFor(context)
        }

        private fun createEncryptedPrefs(context: Context, name: String): SharedPreferences =
            EncryptedSharedPreferences.create(
                context, name,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

        private fun createPrefs(context: Context): SharedPreferences = try {
            createEncryptedPrefs(context, PREFS_FILE).also { encryptedProviderPrefsAvailable = true }
        } catch (e: Exception) {
            // Log only which store is in use — never the key or exception detail.
            Log.w(TAG, "encrypted store unavailable, using fallback store")
            context.getSharedPreferences(
                "${PREFS_FILE}_fallback",
                Context.MODE_PRIVATE,
            ).also { it.edit().remove(KEY_API_KEY).apply() }
        }
    }

    private val appContext: Context = context.applicationContext

    /**
     * Resolved lazily from the process-wide cache on first access, never
     * in the constructor — so constructing a KeyStore on the main thread
     * (ChatViewModel's field initialiser) performs no disk/keystore I/O.
     */
    private val prefs: SharedPreferences get() = prefsFor(appContext)

    /** The stored API key, or null when none has been set. */
    fun getApiKey(): String? {
        val store = prefs
        return if (encryptedProviderPrefsAvailable) store.getString(KEY_API_KEY, null) else null
    }

    /**
     * Stores the API key, trimmed. Blank keys are rejected (nothing is stored).
     * The key value is never logged.
     */
    fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return
        val store = prefs
        check(encryptedProviderPrefsAvailable) { "Encrypted API key storage is unavailable." }
        store.edit().putString(KEY_API_KEY, trimmed).apply()
    }

    /** Removes the stored API key. */
    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    /** The stored base URL, or "" when none is stored or it is blank. */
    fun getBaseUrl(): String =
        prefs.getString(KEY_BASE_URL, null)?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    /** Stores the API base URL, trimmed (item 7 — setApiKey already trims). */
    fun setBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url.trim()).apply()
    }

    /** The stored model name, or [DEFAULT_MODEL] when none is stored or it is blank. */
    fun getModel(): String =
        prefs.getString(KEY_MODEL, null)?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /** Stores the model name, trimmed (item 7). */
    fun setModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model.trim()).apply()
    }

    /** Save a provider change atomically; never carry an old key to a different endpoint. */
    internal fun saveProvider(baseUrl: String, model: String, newKey: String) {
        val store = prefs
        val key = newKey.trim()
        if (key.isNotEmpty()) check(encryptedProviderPrefsAvailable) { "Encrypted API key storage is unavailable." }
        val edit = store.edit().putString(KEY_BASE_URL, baseUrl.trim()).putString(KEY_MODEL, model.trim())
        if (key.isNotEmpty()) edit.putString(KEY_API_KEY, key)
        else if (endpointKey(getBaseUrl()) != endpointKey(baseUrl)) edit.remove(KEY_API_KEY)
        edit.apply()
    }

    internal fun apiKeyFor(baseUrl: String): String? =
        getApiKey()?.takeIf { endpointKey(getBaseUrl()) == endpointKey(baseUrl) }

    internal fun githubRepository(): String? = prefs.getString(KEY_GITHUB_REPO, null)

    internal fun githubToken(): String? = githubPrefsFor(appContext).getString(KEY_GITHUB_TOKEN, null)

    internal fun saveGitHub(repository: String, token: String) {
        if (token.isNotBlank()) githubPrefsFor(appContext).edit().putString(KEY_GITHUB_TOKEN, token.trim()).apply()
        prefs.edit().putString(KEY_GITHUB_REPO, repository.trim()).apply()
    }

    internal fun clearGitHub() {
        githubPrefsFor(appContext).edit().remove(KEY_GITHUB_TOKEN).apply()
        prefs.edit().remove(KEY_GITHUB_REPO).apply()
    }

    private fun imageModeKey(base: String, model: String) = "inline_images:${endpointKey(base)}:$model"
    internal fun imageMode(base: String = getBaseUrl(), model: String = getModel()): String =
        prefs.getString(imageModeKey(base, model), "auto") ?: "auto"
    internal fun setImageMode(value: String) { prefs.edit().putString(imageModeKey(getBaseUrl(), getModel()), value).apply() }

    internal fun contextTokens(): Int = prefs.getInt("context_budget_tokens", dev.codex.android.context.DEFAULT_CONTEXT_TOKENS)
    internal fun setContextTokens(tokens: Int) { prefs.edit().putInt("context_budget_tokens", tokens).apply() }
    internal fun autoCompact(): Boolean = prefs.getBoolean("auto_compact_chat", true)
    internal fun setAutoCompact(enabled: Boolean) { prefs.edit().putBoolean("auto_compact_chat", enabled).apply() }

    internal fun soulMarkdown(): String = prefs.getString("agent_soul_markdown", "").orEmpty()

    /**
     * The user's name, or null when never set. Non-secret — used only
     * to address the user in the system prompt. No stored profile
     * existed before this; this single field is the whole profile.
     */
    internal fun userName(): String? =
        prefs.getString("user_name", null)?.trim()?.takeIf { it.isNotBlank() }

    /** Stores the user's name, trimmed and capped. Blank clears it. */
    internal fun setUserName(name: String) {
        val trimmed = name.trim()
        prefs.edit().apply {
            if (trimmed.isBlank()) remove("user_name") else putString("user_name", trimmed.take(100))
        }.apply()
    }

    internal fun saveSoulMarkdown(text: String): Boolean = prefs.edit().apply {
        if (text.isBlank()) remove("agent_soul_markdown") else putString("agent_soul_markdown", text)
    }.commit()

    internal fun isMemoryRecallEnabled(): Boolean = prefs.getBoolean("memory_recall_enabled", true)

    internal fun setMemoryRecallEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("memory_recall_enabled", enabled).apply()
    }

    /** True when a non-blank API key is stored. */
    fun hasKey(): Boolean = getApiKey()?.isNotBlank() == true
}
