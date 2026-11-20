package dev.codex.android.tools

import android.content.Context
import dev.codex.android.scheduler.ScheduledTask
import dev.codex.android.scheduler.TaskScheduler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Schedules a one-shot task: at the given time the alarm fires,
 * [dev.codex.android.scheduler.TaskReceiver] notifies the user and
 * starts the agent service with the task's prompt.
 *
 * Scheduling starts future agent work, so it requires user approval.
 */
class ScheduleTaskTool(private val context: Context) : Tool {
    override val name: String = "schedule_task"
    override val description: String =
        "Schedules a one-shot task for a later time. At that time the agent is started " +
            "with the given prompt. Time is either \"HH:mm\" (today, or tomorrow if that " +
            "time has already passed) or an ISO date-time \"yyyy-MM-ddTHH:mm\"."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
                put("description", "Short title for the scheduled task, shown in the notification")
            }
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "What the agent should do when the task fires")
            }
            putJsonObject("time") {
                put("type", "string")
                put("description", "When to run it: \"HH:mm\" (e.g. \"09:30\") or \"yyyy-MM-ddTHH:mm\" (e.g. \"2026-11-20T09:30\")")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("title"))
            add(JsonPrimitive("prompt"))
            add(JsonPrimitive("time"))
        }
    }
    override val sensitive: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: title")
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: prompt")
        val timeRaw = args["time"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(ok = false, output = "", error = "Missing required argument: time")
        if (title.isEmpty()) {
            return ToolResult(ok = false, output = "", error = "title must not be empty")
        }
        if (prompt.isEmpty()) {
            return ToolResult(ok = false, output = "", error = "prompt must not be empty")
        }

        val triggerAtMillis = parseTime(timeRaw)
            ?: return ToolResult(
                ok = false,
                output = "",
                error = "Could not parse time \"$timeRaw\" — use \"HH:mm\" (e.g. \"09:30\") " +
                    "or \"yyyy-MM-ddTHH:mm\" (e.g. \"2026-11-20T09:30\")",
            )

        return try {
            val task = ScheduledTask(
                id = UUID.randomUUID().toString(),
                title = title,
                prompt = prompt,
                triggerAtMillis = triggerAtMillis,
            )
            TaskScheduler.schedule(context, task)
            val human = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(triggerAtMillis),
                ZoneId.systemDefault(),
            ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            ToolResult(ok = true, output = "Scheduled '$title' for $human")
        } catch (e: Exception) {
            ToolResult(
                ok = false,
                output = "",
                error = e.message ?: "Failed to schedule \"$title\"",
            )
        }
    }

    /**
     * Parses [timeRaw] into wall-clock epoch millis, or null when it
     * matches neither accepted format. "HH:mm" in the past today
     * rolls over to tomorrow.
     */
    internal fun parseTime(timeRaw: String): Long? {
        val zone = ZoneId.systemDefault()
        // ISO date-time first: "yyyy-MM-ddTHH:mm" (seconds also accepted).
        for (formatter in ISO_FORMATTERS) {
            try {
                val dateTime = LocalDateTime.parse(timeRaw, formatter)
                return dateTime.atZone(zone).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                // Try the next format.
            }
        }
        // Bare time: "HH:mm" today, or tomorrow if already past.
        try {
            val time = LocalTime.parse(timeRaw, DateTimeFormatter.ofPattern("HH:mm"))
            var dateTime = LocalDateTime.of(LocalDate.now(zone), time)
            if (dateTime.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
                dateTime = dateTime.plusDays(1)
            }
            return dateTime.atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            return null
        }
    }

    companion object {
        private val ISO_FORMATTERS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        )
    }
}
