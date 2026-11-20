package dev.codex.android.tools

import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

/** Returns the current date and time on the device, in the device timezone. */
object GetTimeTool : Tool {
    override val name: String = "get_time"
    override val description: String = "Returns the current date and time on the device"
    override val parametersSchema: JsonObject = emptyObjectSchema()
    override val sensitive: Boolean = false

    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (VV)")

    override suspend fun execute(args: JsonObject): ToolResult {
        // e.g. "2026-11-18 21:30:00 IST (Asia/Kolkata)"
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return ToolResult(ok = true, output = now.format(formatter))
    }
}

/**
 * Returns basic device information. Never includes identifiers
 * (no IMEI, serial number, or Android ID).
 */
object DeviceInfoTool : Tool {
    override val name: String = "device_info"
    override val description: String =
        "Returns basic device information (manufacturer, model, Android version, SDK level)"
    override val parametersSchema: JsonObject = emptyObjectSchema()
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val output = buildString {
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android version: ${Build.VERSION.RELEASE}")
            append("SDK level: ${Build.VERSION.SDK_INT}")
        }
        return ToolResult(ok = true, output = output)
    }
}

/** Echoes back the given text. */
object EchoTool : Tool {
    override val name: String = "echo"
    override val description: String = "Echoes back the given text"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to echo")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("text")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: text")
        return ToolResult(ok = true, output = text)
    }
}

/**
 * Opens another app on the phone by its package name.
 * Not sensitive: launching apps is not in the gated list
 * (message / call / setting / money / delete).
 *
 * [launchIntentForPackage] is the package-resolution step, injected
 * so JVM tests can prove the tool's behaviour with a fake resolver
 * (returning null = "no launchable app") without ever launching a
 * real app. Production passes nothing and gets the default:
 * [android.content.pm.PackageManager.getLaunchIntentForPackage],
 * which on Android 11+ only sees launchable apps because the
 * manifest declares the LAUNCHER <queries> intent.
 */
class OpenAppTool(
    private val context: Context,
    private val launchIntentForPackage: (String) -> Intent? = { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg)
    },
) : Tool {
    override val name: String = "open_app"
    override val description: String = "Opens another app on the phone by its package name"
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "Package name of the app to open, e.g. com.example.app")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("package_name")) }
    }
    override val sensitive: Boolean = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "Missing required argument: package_name",
            )
        return try {
            val intent = launchIntentForPackage(packageName)
                ?: return ToolResult(
                    ok = false,
                    output = "",
                    error = "No launchable app for package $packageName",
                )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(ok = true, output = "Opened $packageName")
        } catch (e: Exception) {
            ToolResult(
                ok = false,
                output = "",
                error = e.message ?: "Failed to open $packageName",
            )
        }
    }
}
