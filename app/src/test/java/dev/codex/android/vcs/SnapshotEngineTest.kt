package dev.codex.android.vcs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [SnapshotEngine]: works on non-Git folders, stores snapshots
 * strictly outside the workspace, surgical undo/redo, and preserves timestamps of
 * both restored and untouched files.
 */
class SnapshotEngineTest {

    private lateinit var tempRoot: File
    private lateinit var workspaceDir: File
    private lateinit var snapshotsBaseDir: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("codex-snapshot-test").toFile()
        workspaceDir = File(tempRoot, "my-non-git-workspace").apply { mkdirs() }
        snapshotsBaseDir = File(tempRoot, "filesDir/snapshots").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun nonGitFolder_snapshotUndoRedo_restoresContentAndPreservesTimestamps() = runBlocking {
        // Confirm no .git directory exists.
        assertFalse(File(workspaceDir, ".git").exists())

        val untouched = File(workspaceDir, "untouched.txt").apply {
            writeText("never changed")
            setLastModified(1_700_000_000_000L)
        }
        val edited = File(workspaceDir, "app.kt").apply {
            writeText("fun main() = println(1)")
            setLastModified(1_700_000_100_000L)
        }
        val originalUntouchedMtime = untouched.lastModified()
        val originalEditedMtime = edited.lastModified()

        val engine = SnapshotEngine(workspaceDir, snapshotsBaseDir)
        // Explicit init (adapted contract): baseline must be captured here,
        // before the turn's edits below — NOT lazily at first snapshot().
        engine.initialize()
        // Store must live OUTSIDE the workspace.
        assertFalse(engine.storeDir.canonicalPath.startsWith(workspaceDir.canonicalPath))

        // Turn 1: edit app.kt and create new.kt
        edited.writeText("fun main() = println(2)")
        edited.setLastModified(1_700_000_200_000L)
        val afterEditMtime = edited.lastModified()
        val added = File(workspaceDir, "new.kt").apply { writeText("val x = 42") }

        val snap = engine.snapshot("Turn 1 edit")
        assertEquals(listOf("app.kt", "new.kt"), snap.meta.changedPaths)
        assertEquals(1, engine.history().size)

        // Undo Turn 1
        assertTrue(engine.undo())
        assertEquals("fun main() = println(1)", edited.readText())
        assertEquals(originalEditedMtime, edited.lastModified())
        assertFalse("newly added file must be removed on undo", added.exists())
        // Untouched file was NEVER clobbered.
        assertEquals(originalUntouchedMtime, untouched.lastModified())

        // Redo Turn 1
        assertTrue(engine.redo())
        assertEquals("fun main() = println(2)", edited.readText())
        assertEquals(afterEditMtime, edited.lastModified())
        assertTrue(added.isFile)
        assertEquals("val x = 42", added.readText())
        assertEquals(originalUntouchedMtime, untouched.lastModified())

        // Extra redo beyond head returns false.
        assertFalse(engine.redo())
    }
}
