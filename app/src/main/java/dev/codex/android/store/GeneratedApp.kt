package dev.codex.android.store

import kotlinx.serialization.Serializable

/** Lifecycle of a generated app, from first draft to a built APK. */
@Serializable
enum class AppBuildStatus {
    DRAFT,
    GENERATED,
    BUILD_REQUESTED,
    BUILT,
    FAILED,
}

/**
 * A small app generated from a template by [AppGenerator].
 *
 * [files] maps a relative path (e.g.
 * `"app/src/main/java/com/codex/apps/mycounter/MainActivity.kt"`) to that
 * file's full source text. This is a GENERATOR output only: nothing here
 * runs Gradle or builds an APK on the phone. Turning these files into an
 * APK is a future step via a build service / CI hook (see
 * [AppGenerator.requestBuild]).
 */
@Serializable
data class GeneratedApp(
    val id: String,
    val name: String,
    val description: String,
    val templateId: String,
    val packageName: String,
    val status: AppBuildStatus,
    val files: Map<String, String>,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
