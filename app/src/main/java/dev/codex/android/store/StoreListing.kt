package dev.codex.android.store

import kotlinx.serialization.Serializable

/**
 * One app listed in the mini app store.
 *
 * [approved] is the moderation flag — false until a human approves the
 * listing. [status] tracks the build state of the app (see
 * [AppBuildStatus]). The store lists apps only; it takes no payment.
 */
@Serializable
data class StoreListing(
    val id: String,
    val appId: String,
    val name: String,
    val description: String,
    val status: AppBuildStatus,
    val approved: Boolean,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
