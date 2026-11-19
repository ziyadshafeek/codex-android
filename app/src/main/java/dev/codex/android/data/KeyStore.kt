package dev.codex.android.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
 * falls back to plain [SharedPreferences] so the app still works; the
 * constructor never throws.
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
        private const val TAG = "KeyStore"

        // Process-wide cache (diagnosis item 8): the encrypted store is
        // created once per process, on whichever thread first needs it —
        // never in a constructor on the main thread. KeyStore's
        // constructor does no I/O; prefs resolve lazily through here.
        @Volatile
        private var cachedPrefs: SharedPreferences? = null
        private val prefsLock = Any()

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

        private fun createPrefs(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Log only which store is in use — never the key or exception detail.
            Log.w(TAG, "encrypted store unavailable, using fallback store")
            context.getSharedPreferences(
                "${PREFS_FILE}_fallback",
                Context.MODE_PRIVATE,
            )
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
    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    /**
     * Stores the API key, trimmed. Blank keys are rejected (nothing is stored).
     * The key value is never logged.
     */
    fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return
        prefs.edit().putString(KEY_API_KEY, trimmed).apply()
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

    /** True when a non-blank API key is stored. */
    fun hasKey(): Boolean = getApiKey()?.isNotBlank() == true
}
