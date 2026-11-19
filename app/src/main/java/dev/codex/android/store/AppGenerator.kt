package dev.codex.android.store

import java.util.UUID

/**
 * Generates small apps from [AppTemplate]s.
 *
 * This is a GENERATOR, not a builder: it writes a small app's source from
 * templates. It does NOT run Gradle or build an APK on the phone — turning
 * the generated files into an APK is a documented future step via a build
 * service / CI hook. No network, no API key, no secrets, no payment code.
 */
object AppGenerator {

    /** Returns the three built-in templates: counter, notes, timer. */
    fun templates(): List<AppTemplate> = listOf(counterTemplate, notesTemplate, timerTemplate)

    /**
     * Generates an app from the template with [templateId].
     *
     * The package name is derived from [name]: lowercased, ASCII letters and
     * digits only, under `com.codex.apps` (e.g. "My Counter" →
     * `com.codex.apps.mycounter`); when nothing usable remains it falls back
     * to `com.codex.apps.app`. The placeholders `{{APP_NAME}}`,
     * `{{PACKAGE}}` and `{{DESCRIPTION}}` are substituted in every template
     * file's contents, and `{{PACKAGE}}` in a file path becomes the
     * package's directory form (e.g. `com/codex/apps/mycounter`).
     *
     * @throws IllegalArgumentException for a blank [name] or [description],
     *   or for an unknown [templateId].
     */
    fun generate(name: String, description: String, templateId: String): GeneratedApp {
        require(name.isNotBlank()) { "App name must not be blank" }
        require(description.isNotBlank()) { "App description must not be blank" }
        val template = templates().find { it.id == templateId }
            ?: throw IllegalArgumentException("Unknown template id: $templateId")

        val packageName = derivePackageName(name)
        val packagePath = packageName.replace('.', '/')
        val files = template.files.entries.associate { (path, source) ->
            val resolvedPath = path.replace("{{PACKAGE}}", packagePath)
            val resolvedSource = source
                .replace("{{APP_NAME}}", name.trim())
                .replace("{{PACKAGE}}", packageName)
                .replace("{{DESCRIPTION}}", description.trim())
            resolvedPath to resolvedSource
        }

        return GeneratedApp(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            description = description.trim(),
            templateId = template.id,
            packageName = packageName,
            status = AppBuildStatus.GENERATED,
            files = files,
        )
    }

    /**
     * Records a request to build [app] into an APK: returns the app with
     * status [AppBuildStatus.BUILD_REQUESTED].
     *
     * This only records the request. The actual APK build via a build
     * service / CI hook is a documented future step and is NOT performed
     * here — there is no Gradle invocation, no upload, and no network call.
     * Pure function, no I/O.
     */
    fun requestBuild(app: GeneratedApp): GeneratedApp =
        app.copy(status = AppBuildStatus.BUILD_REQUESTED)

    /**
     * Derives `com.codex.apps.<slug>` from an app name: lowercase, ASCII
     * letters and digits only. Falls back to `com.codex.apps.app` when
     * nothing usable remains; a slug starting with a digit is prefixed
     * with `app` so the package segment stays a valid identifier.
     */
    private fun derivePackageName(name: String): String {
        val slug = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        if (slug.isEmpty()) return "com.codex.apps.app"
        val safeSlug = if (slug.first().isDigit()) "app$slug" else slug
        return "com.codex.apps.$safeSlug"
    }
}
