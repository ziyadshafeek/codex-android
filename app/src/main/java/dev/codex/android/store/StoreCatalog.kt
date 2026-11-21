package dev.codex.android.store

import android.content.Context
import dev.codex.android.core.CodexJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Persists the store catalogue ([StoreListing]s) as JSON in the app's
 * private files dir. Same pattern as [dev.codex.android.agent.PlanStore]:
 * never throws — a corrupt or missing file simply yields an empty list,
 * and a failed save is swallowed (the in-memory state still works).
 *
 * The store lists apps only. It takes no payment and holds no payment
 * provider, SDK, or charging code.
 */
class StoreCatalog(context: Context) {

    /**
     * Serialises save/approve/remove: each is a read-modify-write on
     * one JSON file, so two concurrent writers can silently lose one
     * update. Reads (all/approved/pending) stay lock-free — a torn
     * read just decodes to empty and retries on the next call.
     */
    private val mutex = Mutex()

    private val file: File = File(context.filesDir, "codex_store.json")

    /** Loads all listings, or an empty list when none exist / on error. Never throws. */
    private suspend fun loadAll(): List<StoreListing> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext emptyList()
            val raw = file.readText()
            if (raw.isBlank()) return@withContext emptyList()
            CodexJson.decodeFromString<List<StoreListing>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Writes the full listing list. Never throws. */
    private suspend fun saveAll(listings: List<StoreListing>) = withContext(Dispatchers.IO) {
        try {
            val raw = CodexJson.encodeToString(listings)
            file.writeText(raw)
        } catch (e: Exception) {
            // Swallowed by design — persistence must never break a run.
        }
    }

    /** All listings, in insertion order. Empty list on error/missing file. Never throws. */
    suspend fun all(): List<StoreListing> = loadAll()

    /** Approved listings only (approved == true), newest first. Never throws. */
    suspend fun approved(): List<StoreListing> =
        loadAll().filter { it.approved }.sortedByDescending { it.createdAtMillis }

    /** Listings awaiting moderation (approved == false), newest first. Never throws. */
    suspend fun pending(): List<StoreListing> =
        loadAll().filter { !it.approved }.sortedByDescending { it.createdAtMillis }

    /** Inserts or replaces [listing] by id. Never throws. */
    suspend fun save(listing: StoreListing) {
        mutex.withLock {
            val updated = loadAll().filter { it.id != listing.id } + listing
            saveAll(updated)
        }
    }

    /** Marks the listing with [id] as approved. No-op when missing. Never throws. */
    suspend fun approve(id: String) {
        mutex.withLock {
            val listings = loadAll()
            if (listings.none { it.id == id }) return@withLock
            saveAll(listings.map { if (it.id == id) it.copy(approved = true) else it })
        }
    }

    /** Deletes the listing with [id]. No-op when missing. Never throws. */
    suspend fun remove(id: String) {
        mutex.withLock {
            val listings = loadAll()
            if (listings.none { it.id == id }) return@withLock
            saveAll(listings.filter { it.id != id })
        }
    }

    /**
     * Deletes a store listing AND its generated source folder
     * (`workspace/generated/<listing.appId>`, written by
     * [AppGenerator.saveSource]). Returns true only when the listing is
     * really gone from the catalogue AND the source folder is really gone.
     * The templates themselves are never touched — only the generated
     * app's own folder. Never throws; a failure reports false.
     */
    suspend fun deleteListingAndSource(listing: StoreListing, workspaceDir: File): Boolean {
        mutex.withLock {
            val listings = loadAll()
            if (listings.any { it.id == listing.id }) {
                saveAll(listings.filter { it.id != listing.id })
            }
        }
        val sourceGone = deleteGeneratedAppSource(workspaceDir, listing.appId)
        val listingGone = loadAll().none { it.id == listing.id }
        return sourceGone && listingGone
    }
}

/**
 * Deletes the generated source folder `workspace/generated/<appId>` and
 * returns true only when it is really gone (already-gone counts as
 * success). Pure file op, JVM-testable. Rejects blank / path-traversal
 * [appId]s outright so a crafted id can never escape the `generated`
 * folder.
 */
internal fun deleteGeneratedAppSource(workspaceDir: File, appId: String): Boolean {
    if (appId.isBlank() || appId == "." || appId == ".." ||
        '/' in appId || '\\' in appId || '\u0000' in appId
    ) return false
    return runCatching {
        val generatedRoot = File(workspaceDir, "generated").canonicalFile
        val target = File(generatedRoot, appId).canonicalFile
        if (target != generatedRoot && !target.path.startsWith(generatedRoot.path + File.separator)) {
            return false
        }
        if (!target.exists()) return true
        target.deleteRecursively()
        !target.exists()
    }.getOrDefault(false)
}
