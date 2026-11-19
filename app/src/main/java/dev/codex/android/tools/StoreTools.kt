package dev.codex.android.tools

import android.content.Context
import dev.codex.android.store.AppBuildStatus
import dev.codex.android.store.AppGenerator
import dev.codex.android.store.StoreCatalog
import dev.codex.android.store.StoreListing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** JSON Schema for a tool that takes no arguments: {"type":"object","properties":{}}. */
private fun emptyObjectSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {}
}

/** The template ids [AppGenerator] accepts. */
private val STORE_TEMPLATES: Set<String> = setOf("counter", "notes", "timer")

/**
 * Generates an app from a template, requests its APK build on the build
 * service, and lists it in the store as pending moderation approval.
 *
 * Not sensitive: generating an app and listing it unapproved is not in
 * the gated list (message / call / setting / money / delete). No payment
 * is taken. Never throws.
 */
class GenerateAppTool(private val context: Context) : Tool {
    override val name: String = "generate_app"
    override val description: String =
        "Generates an app from a template (counter, notes, or timer), requests its APK " +
            "build on the build service, and lists it in the store as pending approval"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Name of the app to generate")
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", "Short description of what the app does")
            }
            putJsonObject("template") {
                put("type", "string")
                put("description", "Template to build from: one of \"counter\", \"notes\", \"timer\"")
                putJsonArray("enum") {
                    add(JsonPrimitive("counter"))
                    add(JsonPrimitive("notes"))
                    add(JsonPrimitive("timer"))
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("name"))
            add(JsonPrimitive("description"))
            add(JsonPrimitive("template"))
        }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: name")
        val description = args["description"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "Missing required argument: description",
            )
        val template = args["template"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "Missing required argument: template",
            )
        if (name.isEmpty()) {
            return ToolResult(ok = false, output = "", error = "name must not be empty")
        }
        if (description.isEmpty()) {
            return ToolResult(ok = false, output = "", error = "description must not be empty")
        }
        if (template !in STORE_TEMPLATES) {
            return ToolResult(
                ok = false,
                output = "",
                error = "Unknown template \"$template\" — use one of: counter, notes, timer",
            )
        }

        return try {
            val generated = AppGenerator.generate(name, description, template)
            AppGenerator.requestBuild(generated)
            val listing = StoreListing(
                id = generated.id,
                appId = generated.id,
                name = generated.name,
                description = generated.description,
                status = AppBuildStatus.BUILD_REQUESTED,
                approved = false,
            )
            StoreCatalog(context).save(listing)
            ToolResult(
                ok = true,
                output = "Generated '$name' from the $template template. Build requested — " +
                    "the APK build runs on the build service (not on this phone). " +
                    "Listed in the store as pending approval.",
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(ok = false, output = "", error = e.message ?: "Invalid app details")
        } catch (e: Exception) {
            ToolResult(
                ok = false,
                output = "",
                error = e.message ?: "Failed to generate \"$name\"",
            )
        }
    }
}

/**
 * Lists the approved apps in the mini app store.
 * Not sensitive: read-only. Never throws.
 */
class ListStoreTool(private val context: Context) : Tool {
    override val name: String = "list_store"
    override val description: String = "Lists the approved apps in the store"
    override val parametersSchema: JsonObject = emptyObjectSchema()
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        return try {
            val listings = StoreCatalog(context).approved()
            if (listings.isEmpty()) {
                return ToolResult(ok = true, output = "The store is empty.")
            }
            val output = listings.mapIndexed { index, listing ->
                "${index + 1}. ${listing.name} — ${listing.description}"
            }.joinToString("\n")
            ToolResult(ok = true, output = output)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = e.message ?: "Failed to list the store")
        }
    }
}
