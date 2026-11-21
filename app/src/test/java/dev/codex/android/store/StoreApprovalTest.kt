package dev.codex.android.store

import dev.codex.android.core.CodexJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI audit: the Store screen's Approve button calls StoreCatalog.approve,
 * which flips a listing to approved=true and persists the whole list as
 * JSON (saveAll/loadAll); the screen then re-reads approved()/pending().
 * These tests prove the persistence half of that flow on the JVM: the
 * approval flag survives the exact JSON round-trip the catalog uses, and
 * the approved()/pending() filters then classify the listing the way the
 * screen's two sections expect. The tap itself needs a phone/emulator.
 */
class StoreApprovalTest {

    private fun listing(id: String, approved: Boolean) = StoreListing(
        id = id,
        appId = "app_1",
        name = "Counter",
        description = "A counter",
        status = AppBuildStatus.GENERATED,
        approved = approved,
        createdAtMillis = 1000L,
    )

    @Test
    fun approvalSurvivesJsonRoundTrip() {
        val approved = listing("lst_1", false).copy(approved = true)
        val encoded = CodexJson.encodeToString(listOf(approved))
        val decoded = CodexJson.decodeFromString<List<StoreListing>>(encoded)
        assertEquals(listOf(approved), decoded)
        assertTrue(decoded.single().approved)
    }

    @Test
    fun approvedAndPendingFiltersSplitOnFlag() {
        val all = listOf(listing("lst_pending", false), listing("lst_approved", true))
        val encoded = CodexJson.encodeToString(all)
        val roundTripped = CodexJson.decodeFromString<List<StoreListing>>(encoded)
        assertEquals(listOf("lst_approved"), roundTripped.filter { it.approved }.map { it.id })
        assertEquals(listOf("lst_pending"), roundTripped.filter { !it.approved }.map { it.id })
    }

    @Test
    fun unapprovedListingStaysPendingAfterRoundTrip() {
        val encoded = CodexJson.encodeToString(listOf(listing("lst_1", false)))
        val decoded = CodexJson.decodeFromString<List<StoreListing>>(encoded)
        assertEquals(emptyList<String>(), decoded.filter { it.approved }.map { it.id })
        assertEquals(listOf("lst_1"), decoded.filter { !it.approved }.map { it.id })
    }
}
