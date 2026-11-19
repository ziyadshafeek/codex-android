package dev.codex.android.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Phase 5 — first-run onboarding state.
 *
 * A tiny plain [SharedPreferences] wrapper recording whether the user has
 * finished (or skipped) the first-run onboarding flow. No key material,
 * no personal data — just one boolean. The constructor never throws:
 * if the preferences file cannot be opened, the wrapper degrades to
 * "not complete" reads and no-op writes so the app still works.
 */
class OnboardingPrefs(context: Context) {

    companion object {
        private const val PREFS_FILE = "codex_onboarding"
        private const val KEY_COMPLETE = "onboarding_complete"
    }

    private val prefs: SharedPreferences? = try {
        val appContext = context.applicationContext ?: context
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        null
    }

    /** True once the user has finished or skipped onboarding. Never throws. */
    fun isComplete(): Boolean {
        return try {
            prefs?.getBoolean(KEY_COMPLETE, false) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Marks onboarding as done (finished or skipped). Never throws. */
    fun setComplete() {
        try {
            prefs?.edit()?.putBoolean(KEY_COMPLETE, true)?.apply()
        } catch (e: Exception) {
            // No-op: onboarding simply shows again next launch.
        }
    }
}
