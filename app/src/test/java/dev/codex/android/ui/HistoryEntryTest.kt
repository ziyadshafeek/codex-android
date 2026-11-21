package dev.codex.android.ui

import dev.codex.android.data.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the History fix (rows open their chat; delete per item;
 * clear all).
 *
 * These target the pure, Android-free seams in ChatViewModel:
 * [addHistoryEntry] (the record path), [firstGoalOfThread] (the load
 * path — a thread's stored messages back into a row), [mergeHistoryEntries]
 * (the seed merge) and [removeHistoryEntry] (the delete list op). The
 * ViewModel itself needs a real Application + SQLite database, so the
 * actual SQLite round-trip (row survives a restart; delete/clear-all
 * remove the stored rows) is covered by the instrumented tests / the
 * phone check instead — see the file header note in ChatViewModel
 * (deleteHistoryEntry / clearHistory). The row-tap chain itself
 * (HistoryScreen row -> onOpenThread -> MainActivity ->
 * [ChatViewModel.openThread]) is Compose wiring, verified by code
 * inspection; what these tests prove about it is the contract it relies
 * on — every seeded entry carries exactly the thread id its row must
 * open.
 */
class HistoryEntryTest {

    private fun userMessage(threadId: String, content: String, createdAt: Long) =
        TimelineMessage(threadId = threadId, role = "user", kind = "text", content = content, createdAt = createdAt)

    private fun systemMessage(threadId: String, content: String, createdAt: Long) =
        TimelineMessage(threadId = threadId, role = "system", kind = "text", content = content, createdAt = createdAt)

    // --- (a) record path: threadId is stored on the entry ---

    @Test
    fun addHistoryEntry_storesThreadId() {
        val entry = HistoryEntry("fix the login bug", 1_000L, "thread-1")
        val list = addHistoryEntry(emptyList(), entry)
        assertEquals(listOf(entry), list)
        assertEquals("thread-1", list.single().threadId)
    }

    @Test
    fun addHistoryEntry_repeatMovesToTopAndKeepsNewThreadId() {
        val old = HistoryEntry("same goal", 1_000L, "thread-1")
        val current = listOf(old, HistoryEntry("other goal", 2_000L, "thread-2"))
        val repeated = HistoryEntry("same goal", 3_000L, "thread-3")
        val list = addHistoryEntry(current, repeated)
        assertEquals(listOf("same goal", "other goal"), list.map { it.text })
        // The repeat is the NEW run: its thread id wins.
        assertEquals("thread-3", list.first().threadId)
    }

    @Test
    fun addHistoryEntry_capsAtFifty() {
        val current = (1..60).map { HistoryEntry("goal $it", it.toLong(), "thread-$it") }
        val list = addHistoryEntry(current, HistoryEntry("newest", 61L, "thread-new"))
        assertEquals(50, list.size)
        assertEquals("newest", list.first().text)
    }

    // --- (a) load path: a thread's stored messages round-trip to a row ---

    @Test
    fun firstGoalOfThread_carriesThreadId() {
        val entry = firstGoalOfThread(
            "thread-9",
            listOf(userMessage("thread-9", "build the report", 5_000L)),
        )
        assertEquals(HistoryEntry("build the report", 5_000L, "thread-9"), entry)
    }

    @Test
    fun firstGoalOfThread_skipsLeadingSystemNote() {
        // A /fork thread starts with a system note before the first user goal.
        val entry = firstGoalOfThread(
            "thread-9",
            listOf(
                systemMessage("thread-9", "Forked from thread-1", 4_000L),
                userMessage("thread-9", "continue the fix", 5_000L),
            ),
        )
        assertEquals(HistoryEntry("continue the fix", 5_000L, "thread-9"), entry)
    }

    @Test
    fun firstGoalOfThread_nullWithoutUserTextMessage() {
        assertNull(
            firstGoalOfThread("thread-9", listOf(systemMessage("thread-9", "note", 4_000L))),
        )
        assertNull(firstGoalOfThread("thread-9", emptyList()))
    }

    @Test
    fun seededEntries_threadIdIsTheThreadToOpen() {
        // The contract the row-tap chain relies on: the entry a row is
        // built from carries exactly the id openThread must receive.
        val entries = listOf("thread-a", "thread-b").map { threadId ->
            firstGoalOfThread(threadId, listOf(userMessage(threadId, "goal in $threadId", 1L)))!!
        }
        assertEquals(listOf("thread-a", "thread-b"), entries.map { it.threadId })
    }

    // --- seed merge: threaded rows win, newest first, capped ---

    @Test
    fun mergeHistoryEntries_threadedWinsOverLegacyForSameText() {
        val threaded = listOf(HistoryEntry("same goal", 2_000L, "thread-1"))
        val legacy = listOf(HistoryEntry("same goal", 1_000L, ""))
        val merged = mergeHistoryEntries(threaded, legacy)
        assertEquals(1, merged.size)
        assertEquals("thread-1", merged.single().threadId)
    }

    @Test
    fun mergeHistoryEntries_legacyOnlyRowsStayButCannotOpen() {
        val merged = mergeHistoryEntries(
            emptyList(),
            listOf(HistoryEntry("old goal", 1_000L, "")),
        )
        assertEquals(1, merged.size)
        assertTrue(merged.single().threadId.isBlank())
    }

    @Test
    fun mergeHistoryEntries_newestFirstCappedAtFifty() {
        val threaded = (1..60).map { HistoryEntry("goal $it", it.toLong(), "thread-$it") }
        val merged = mergeHistoryEntries(threaded, emptyList())
        assertEquals(50, merged.size)
        assertEquals("goal 60", merged.first().text)
        assertEquals("goal 11", merged.last().text)
    }

    @Test
    fun mergeHistoryEntries_emptySourcesStayEmpty() {
        // The state clear-all must leave behind: a re-seed from empty
        // sources produces an empty list.
        assertTrue(mergeHistoryEntries(emptyList(), emptyList()).isEmpty())
    }

    // --- (b) delete per item: only that entry goes ---

    @Test
    fun removeHistoryEntry_removesOnlyThatEntry() {
        val target = HistoryEntry("delete me", 1_000L, "thread-1")
        val sameTextOtherThread = HistoryEntry("delete me", 2_000L, "thread-2")
        val other = HistoryEntry("keep me", 3_000L, "thread-3")
        val list = listOf(target, sameTextOtherThread, other)
        val remaining = removeHistoryEntry(list, target)
        assertEquals(listOf(sameTextOtherThread, other), remaining)
    }

    @Test
    fun removeHistoryEntry_noMatchLeavesListUnchanged() {
        val list = listOf(HistoryEntry("keep me", 1_000L, "thread-1"))
        val missing = HistoryEntry("not there", 9_999L, "thread-9")
        assertEquals(list, removeHistoryEntry(list, missing))
    }

    @Test
    fun removeHistoryEntry_sameTextSameThreadDifferentTimeIsDifferentRow() {
        val first = HistoryEntry("same goal", 1_000L, "thread-1")
        val second = HistoryEntry("same goal", 2_000L, "thread-1")
        val remaining = removeHistoryEntry(listOf(first, second), first)
        assertEquals(listOf(second), remaining)
    }

    // --- delete persistence: the receipt copy of a goal ---

    @Test
    fun goalReceiptLikePattern_matchesAttachedReceipt() {
        assertEquals("fix the bug\n\nAttached: %", goalReceiptLikePattern("fix the bug"))
    }

    @Test
    fun goalReceiptLikePattern_escapesLikeWildcards() {
        assertEquals("100\\% sure\\_thing\n\nAttached: %", goalReceiptLikePattern("100% sure_thing"))
    }
}
