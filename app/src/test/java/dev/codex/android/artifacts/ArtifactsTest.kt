package dev.codex.android.artifacts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM unit tests for [renderArtifactMarkdown] and [InlineCommentStore]. */
class ArtifactsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-artifacts-test").toFile()
        InlineCommentStore.configure(tempDir)
    }

    @After
    fun tearDown() {
        InlineCommentStore.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun renderTaskChecklist_rendersCheckboxesAndStatus() {
        val checklist = TaskChecklist(
            id = "chk_1",
            title = "Release Checklist",
            items = listOf(
                ChecklistItem("t1", "Run unit tests", done = true),
                ChecklistItem("t2", "Pin hashes", done = false, status = ChecklistItemStatus.IN_PROGRESS),
                ChecklistItem("t3", "Skip optional task", done = false, status = ChecklistItemStatus.SKIPPED),
            ),
            status = ArtifactStatus.IN_PROGRESS,
        )
        val md = renderArtifactMarkdown(checklist)
        assertTrue(md.contains("# Release Checklist"))
        assertTrue(md.contains("IN_PROGRESS"))
        assertTrue(md.contains("- [x] Run unit tests (`t1`)"))
        assertTrue(md.contains("- [~] Pin hashes (`t2`)"))
        assertTrue(md.contains("- [-] Skip optional task (`t3`)"))
    }

    @Test
    fun renderImplementationPlan_rendersNumberedStepsAndSummary() {
        val plan = ImplementationPlan(
            id = "pln_1",
            title = "Refactor Auth",
            summary = "Switch to per-boot bearer tokens.",
            steps = listOf(
                PlanStepItem("s1", "Generate 256-bit token", "Use SecureRandom", completed = true),
                PlanStepItem("s2", "Validate Authorization header", "Constant-time compare", completed = false),
            ),
            status = ArtifactStatus.NEEDS_REVIEW,
        )
        val md = renderArtifactMarkdown(plan)
        assertTrue(md.contains("# Refactor Auth"))
        assertTrue(md.contains("Switch to per-boot bearer tokens."))
        assertTrue(md.contains("1. [x] **Generate 256-bit token** (`s1`)"))
        assertTrue(md.contains("2. [ ] **Validate Authorization header** (`s2`)"))
    }

    @Test
    fun renderWalkthrough_rendersStepsAndEvidenceFence() {
        val wt = Walkthrough(
            id = "wt_1",
            title = "CI Verification",
            summary = "All suites green.",
            steps = listOf(
                WalkthroughStep("w1", "Unit tests", "Ran gradle test", evidence = "20 tests completed, 0 failed"),
            ),
            status = ArtifactStatus.APPROVED,
        )
        val md = renderArtifactMarkdown(wt)
        assertTrue(md.contains("## 1. Unit tests (`w1`)"))
        assertTrue(md.contains("```text\n20 tests completed, 0 failed\n```"))
    }

    @Test
    fun renderDiff_rendersCountsAndDiffBlock() {
        val diff = Diff(
            id = "dif_1",
            title = "Fix crash",
            items = listOf(
                DiffHunkItem(
                    id = "h1",
                    filePath = "src/Main.kt",
                    unifiedDiff = "-val x = null!!\n+val x = safe()",
                    addedLines = 1,
                    removedLines = 1,
                ),
            ),
            status = ArtifactStatus.APPLIED,
        )
        val md = renderArtifactMarkdown(diff)
        assertTrue(md.contains("Files changed: 1 (+1 / -1)"))
        assertTrue(md.contains("### src/Main.kt (+1 / -1) `h1`"))
        assertTrue(md.contains("```diff\n-val x = null!!\n+val x = safe()\n```"))
    }

    @Test
    fun inlineCommentStore_roundTripsToDiskAndConsumesForSteering() {
        val c1 = InlineComment("pln_1", "s1", "Use Ed25519 instead", createdAtMillis = 1_000L)
        val c2 = InlineComment("pln_1", "s2", "Add unit test", createdAtMillis = 2_000L)
        InlineCommentStore.addComment("thr_abc", c1)
        InlineCommentStore.addComment("thr_abc", c2)

        // Evict in-memory cache and verify reload from per-thread JSON file.
        InlineCommentStore.evictMemoryCache()
        val reloaded = InlineCommentStore.pendingComments("thr_abc")
        assertEquals(2, reloaded.size)
        assertEquals("Use Ed25519 instead", reloaded[0].text)
        assertEquals("Add unit test", reloaded[1].text)
        assertTrue(File(tempDir, "thr_abc.json").isFile)

        // Consume for steering resolves pending comments so they steer once.
        val steeringComments = InlineCommentStore.consumePendingForSteering("thr_abc")
        assertEquals(2, steeringComments.size)
        val formatted = InlineCommentStore.formatForSteering(steeringComments)
        assertTrue(formatted.contains("artifact=pln_1"))
        assertTrue(formatted.contains("target=s1"))
        assertTrue(formatted.contains("Use Ed25519 instead"))

        assertTrue(InlineCommentStore.pendingComments("thr_abc").isEmpty())
        assertEquals(2, InlineCommentStore.allComments("thr_abc").size)
        assertFalse(InlineCommentStore.resolveComment("thr_abc", c1.id))
    }
}
