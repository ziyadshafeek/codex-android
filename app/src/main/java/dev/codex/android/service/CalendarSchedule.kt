package dev.codex.android.service

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** A daily time, or the same fixed time on a particular weekday, in a named time zone. */
data class CalendarSchedule(val time: LocalTime, val zone: ZoneId, val weekday: DayOfWeek? = null) {
    fun nextAfter(instant: Instant): Instant {
        val start = instant.atZone(zone).toLocalDate()
        return (0L..7L).asSequence().map(start::plusDays)
            .filter { weekday == null || it.dayOfWeek == weekday }
            .map { it.atTime(time).atZone(zone).toInstant() }
            .first { it > instant }
    }

    /** Only the latest slot; no backlog if Android was asleep or offline for a long time. */
    fun dueAt(now: Instant, activatedAt: Instant, lastClaimed: Instant?): Instant? {
        val start = now.atZone(zone).toLocalDate()
        return (0L..7L).asSequence().map(start::minusDays)
            .filter { weekday == null || it.dayOfWeek == weekday }
            .map { it.atTime(time).atZone(zone).toInstant() }
            .firstOrNull { it <= now }
            ?.takeIf { it > activatedAt && (lastClaimed == null || it > lastClaimed) && Duration.between(it, now) <= GRACE }
    }

    companion object {
        private val GRACE = Duration.ofHours(6)
        fun parse(time: String, zone: String, weekday: DayOfWeek? = null): CalendarSchedule? {
            if (!Regex("[0-9]{2}:[0-9]{2}").matches(time.trim())) return null
            return runCatching { CalendarSchedule(LocalTime.parse(time.trim()), ZoneId.of(zone.trim()), weekday) }.getOrNull()
        }
    }
}
