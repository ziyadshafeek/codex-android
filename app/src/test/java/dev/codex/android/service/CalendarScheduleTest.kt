package dev.codex.android.service

import java.time.DayOfWeek
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CalendarScheduleTest {
    private fun at(value: String) = Instant.parse(value)

    @Test fun dailySchedulesUseTheSavedZoneAndRejectDuplicateOrOldSlots() {
        val schedule = requireNotNull(CalendarSchedule.parse("09:30", "Asia/Kolkata"))
        val due = at("2026-11-20T04:00:00Z")
        val started = at("2026-11-20T03:00:00Z")
        assertEquals(due, schedule.nextAfter(started))
        assertEquals(due, schedule.dueAt(at("2026-11-20T04:15:00Z"), started, null))
        assertNull(schedule.dueAt(at("2026-11-20T04:15:00Z"), started, due))
        assertNull(schedule.dueAt(at("2026-11-20T04:15:00Z"), due, null))
        assertNull(schedule.dueAt(at("2026-11-20T10:01:00Z"), started, null))
    }

    @Test fun weeklySchedulesHandleDaylightSavingGapAndOverlap() {
        val gap = requireNotNull(CalendarSchedule.parse("02:30", "America/New_York", DayOfWeek.SUNDAY))
        assertEquals(at("2026-03-08T07:30:00Z"), gap.nextAfter(at("2026-03-07T09:00:00Z")))
        val overlap = requireNotNull(CalendarSchedule.parse("01:30", "America/New_York", DayOfWeek.SUNDAY))
        val firstOccurrence = at("2026-11-01T05:30:00Z")
        assertEquals(firstOccurrence, overlap.nextAfter(at("2026-11-01T00:00:00Z")))
        assertNull(overlap.dueAt(at("2026-11-01T06:30:00Z"), at("2026-11-01T00:00:00Z"), firstOccurrence))
        assertEquals(at("2026-11-08T06:30:00Z"), overlap.nextAfter(firstOccurrence))
        assertNull(CalendarSchedule.parse("24:00", "Asia/Kolkata"))
        assertNull(CalendarSchedule.parse("08:00", "Nowhere/Invalid"))
    }
}
