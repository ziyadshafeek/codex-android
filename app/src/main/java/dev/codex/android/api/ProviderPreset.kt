package dev.codex.android.api

internal enum class ProviderPreset(val label: String, val baseUrl: String?, val model: String?) {
    COARENA("Coarena", "https://coarena.ai/api/v1", "gpt-6-luna"),
    NVIDIA_NIM("NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3-ultra-550b-a55b"),
    AI_STUDIO("AI Studio", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-4-hydrogen"),
    CUSTOM("Custom", null, null);

    companion object {
        fun forUrl(url: String): ProviderPreset = entries.firstOrNull {
            it.baseUrl != null && endpointKey(it.baseUrl) == endpointKey(url)
        } ?: CUSTOM
    }
}

/** Match equivalent settings without reusing a key for another endpoint. */
internal fun endpointKey(url: String): String = url.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
