package dev.codex.android.store

import kotlinx.serialization.Serializable

/**
 * One app listed in the mini app store.
 *
 * [pricePaise] is the price in paise (e.g. 9900 = ₹99; 0 = free).
 * Prices are integer paise, never float. [approved] is the moderation
 * flag — false until a human approves the listing. [status] tracks the
 * build state of the app (see [AppBuildStatus]).
 */
@Serializable
data class StoreListing(
    val id: String,
    val appId: String,
    val name: String,
    val description: String,
    val pricePaise: Long,
    val currency: String = "INR",
    val status: AppBuildStatus,
    val approved: Boolean,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
