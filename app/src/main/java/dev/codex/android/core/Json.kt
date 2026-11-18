package dev.codex.android.core

import kotlinx.serialization.json.Json

val CodexJson: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }
