package dev.codex.android.api

import android.content.res.AssetManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Metadata for a single model from the `models.dev` catalog.
 *
 * BYOK strict: this enriches model capabilities (context window, pricing,
 * tool support) only — it never supplies an API endpoint, provider, or key.
 */
data class ModelInfo(
    val id: String,
    val providerId: String,
    val displayName: String,
    val contextWindow: Int,
    val inputPricePerMillionUsd: Double?,
    val outputPricePerMillionUsd: Double?,
    val supportsTools: Boolean,
    val inputModalities: Set<String> = emptySet(),
)

/**
 * Fetches and caches the open `models.dev` catalog (`https://models.dev/api.json`)
 * with disk caching (ETag + 24 h TTL) and an offline bundled snapshot fallback
 * (`assets/models-snapshot.json`).
 *
 * All disk, asset, and network I/O runs inside [refresh] on [Dispatchers.IO];
 * [lookup], [all], and [providers] read an immutable in-memory snapshot and
 * never block the Main thread.
 */
class ModelsCatalog internal constructor(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val snapshotLoader: () -> String?,
    private val catalogUrl: String = DEFAULT_CATALOG_URL,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    constructor(
        client: OkHttpClient,
        cacheDir: File,
        assets: AssetManager,
    ) : this(
        client = client,
        cacheDir = cacheDir,
        snapshotLoader = {
            assets.open(SNAPSHOT_ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        },
    )

    companion object {
        const val DEFAULT_CATALOG_URL: String = "https://models.dev/api.json"
        const val SNAPSHOT_ASSET_NAME: String = "models-snapshot.json"
        val DEFAULT_TTL_MS: Long = TimeUnit.HOURS.toMillis(24)

        private const val CACHE_BODY_FILE = "models_catalog_cache.json"
        private const val CACHE_META_FILE = "models_catalog_meta.json"

        /** First-party providers preferred when bare model IDs collide with aggregators. */
        private val PREFERRED_PROVIDERS: Set<String> = setOf(
            "openai",
            "anthropic",
            "google",
            "deepseek",
            "xai",
            "mistral",
            "meta",
            "alibaba",
            "qwen",
        )
    }

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Volatile
    private var snapshot: CatalogIndex = CatalogIndex.EMPTY

    private val bodyCacheFile: File get() = File(cacheDir, CACHE_BODY_FILE)
    private val metaCacheFile: File get() = File(cacheDir, CACHE_META_FILE)

    /**
     * Refreshes the catalog on [Dispatchers.IO].
     *
     * - If a valid disk cache exists within the 24 h TTL, loads it without a
     *   network request and returns `false`.
     * - Otherwise fetches [catalogUrl] with `If-None-Match` when an ETag is
     *   cached:
     *   - HTTP 200 with valid catalog data: updates disk cache + memory and
     *     returns `true`.
     *   - HTTP 304 Not Modified: refreshes cache timestamp + memory from disk
     *     and returns `false`.
     *   - Fetch failure, non-2xx/304 status, or malformed payload: falls back
     *     to the bundled offline snapshot (`models-snapshot.json`) on
     *     [Dispatchers.IO] and returns `false`.
     */
    internal suspend fun loadOffline() = withContext(Dispatchers.IO) {
        (readCachedModels() ?: snapshotLoader()?.let(::parseCatalog))?.let { snapshot = CatalogIndex.from(it) }
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val now = clockMs()
        val meta = readMeta()

        // 1. Valid non-expired disk cache hit.
        if (meta != null && (now - meta.fetchedAtMs) in 0 until ttlMs) {
            val cached = readCachedModels()
            if (cached != null) {
                snapshot = CatalogIndex.from(cached)
                return@withContext false
            }
        }

        // 2. Fetch fresh catalog (conditional GET when we have an ETag).
        val requestBuilder = Request.Builder()
            .url(catalogUrl)
            .get()
            .header("Accept", "application/json")
        meta?.etag?.takeIf { it.isNotBlank() }?.let { etag ->
            requestBuilder.header("If-None-Match", etag)
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                when (response.code) {
                    304 -> {
                        val cached = readCachedModels()
                        if (cached != null) {
                            val updatedEtag = response.header("ETag") ?: meta?.etag
                            writeMeta(CatalogCacheMeta(etag = updatedEtag, fetchedAtMs = now))
                            snapshot = CatalogIndex.from(cached)
                            return@withContext false
                        }
                    }
                    in 200..299 -> {
                        val body = response.body?.string().orEmpty()
                        val parsed = parseCatalog(body)
                        if (parsed != null && parsed.isNotEmpty()) {
                            writeCache(
                                rawJson = body,
                                meta = CatalogCacheMeta(
                                    etag = response.header("ETag"),
                                    fetchedAtMs = now,
                                ),
                            )
                            snapshot = CatalogIndex.from(parsed)
                            return@withContext true
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // Fall through to bundled offline snapshot.
        } catch (_: Exception) {
            // Fall through to bundled offline snapshot.
        }

        // 3. Fetch failed or cache expired without valid network refresh:
        // fall back to bundled offline snapshot on Dispatchers.IO.
        loadOfflineSnapshot()
        false
    }

    /**
     * Looks up a model by bare id (e.g. `"gpt-4o"`) or provider-qualified id
     * (e.g. `"openai/gpt-4o"`). Pure in-memory lookup; never blocks.
     */
    fun lookup(modelId: String): ModelInfo? = snapshot.lookup(modelId)

    /** Returns all loaded models, sorted by provider id then model id. */
    fun all(): List<ModelInfo> = snapshot.all

    /** Returns all distinct provider ids in the loaded catalog, sorted. */
    fun providers(): List<String> = snapshot.providers

    private fun loadOfflineSnapshot() {
        val raw = runCatching { snapshotLoader() }.getOrNull() ?: return
        val parsed = parseCatalog(raw) ?: return
        if (parsed.isNotEmpty()) {
            snapshot = CatalogIndex.from(parsed)
        }
    }

    private fun readCachedModels(): List<ModelInfo>? {
        val file = bodyCacheFile
        if (!file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val parsed = parseCatalog(raw) ?: return null
        return parsed.takeIf { it.isNotEmpty() }
    }

    private fun readMeta(): CatalogCacheMeta? {
        val file = metaCacheFile
        if (!file.isFile) return null
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            json.decodeFromString(CatalogCacheMeta.serializer(), raw)
        }.getOrNull()
    }

    private fun writeCache(rawJson: String, meta: CatalogCacheMeta) {
        runCatching {
            cacheDir.mkdirs()
            val tmpBody = File(cacheDir, "$CACHE_BODY_FILE.tmp")
            tmpBody.writeText(rawJson, Charsets.UTF_8)
            if (!tmpBody.renameTo(bodyCacheFile)) {
                bodyCacheFile.writeText(rawJson, Charsets.UTF_8)
                tmpBody.delete()
            }
            writeMeta(meta)
        }
    }

    private fun writeMeta(meta: CatalogCacheMeta) {
        runCatching {
            cacheDir.mkdirs()
            val encoded = json.encodeToString(CatalogCacheMeta.serializer(), meta)
            metaCacheFile.writeText(encoded, Charsets.UTF_8)
        }
    }

    /**
     * Parses a `models.dev/api.json` payload into [ModelInfo] entries.
     * Tolerates unknown fields at root, provider, model, cost, and limit levels.
     * Returns null when [raw] is not a valid JSON object.
     */
    internal fun parseCatalog(raw: String): List<ModelInfo>? {
        if (raw.isBlank()) return null
        val root = runCatching {
            json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull() ?: return null

        val out = mutableListOf<ModelInfo>()
        for ((providerKey, providerEl) in root) {
            if (providerEl !is JsonObject) continue
            val providerDto = runCatching {
                json.decodeFromJsonElement(ProviderDto.serializer(), providerEl)
            }.getOrNull() ?: continue
            if (providerDto.models.isEmpty()) continue

            val providerId = providerDto.id?.takeIf { it.isNotBlank() } ?: providerKey
            for ((modelKey, modelEl) in providerDto.models) {
                if (modelEl !is JsonObject) continue
                val modelDto = runCatching {
                    json.decodeFromJsonElement(ModelDto.serializer(), modelEl)
                }.getOrNull() ?: continue
                val id = modelDto.id?.takeIf { it.isNotBlank() } ?: modelKey
                val displayName = modelDto.name?.takeIf { it.isNotBlank() } ?: id
                val contextWindow = (modelDto.limit?.context ?: 0L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt()
                out += ModelInfo(
                    id = id,
                    providerId = providerId,
                    displayName = displayName,
                    contextWindow = contextWindow,
                    inputPricePerMillionUsd = modelDto.cost?.input,
                    outputPricePerMillionUsd = modelDto.cost?.output,
                    supportsTools = modelDto.toolCall,
                    inputModalities = modelDto.modalities?.input.orEmpty().toSet(),
                )
            }
        }
        return out.sortedWith(compareBy<ModelInfo>({ it.providerId }, { it.id }))
    }

    @Serializable
    private data class CatalogCacheMeta(
        val etag: String? = null,
        val fetchedAtMs: Long = 0L,
    )

    @Serializable
    private data class ProviderDto(
        val id: String? = null,
        val name: String? = null,
        val models: Map<String, JsonElement> = emptyMap(),
    )

    @Serializable
    private data class ModelDto(
        val id: String? = null,
        val name: String? = null,
        @SerialName("tool_call")
        val toolCall: Boolean = false,
        val cost: CostDto? = null,
        val limit: LimitDto? = null,
        val modalities: ModalitiesDto? = null,
    )

    @Serializable
    private data class ModalitiesDto(val input: List<String> = emptyList())

    @Serializable
    private data class CostDto(
        val input: Double? = null,
        val output: Double? = null,
    )

    @Serializable
    private data class LimitDto(
        val context: Long? = null,
    )

    private class CatalogIndex(
        val all: List<ModelInfo>,
        val providers: List<String>,
        private val byQualified: Map<String, ModelInfo>,
        private val byBareId: Map<String, ModelInfo>,
        private val byLowerQualified: Map<String, ModelInfo>,
        private val byLowerBareId: Map<String, ModelInfo>,
    ) {
        fun lookup(rawModelId: String): ModelInfo? {
            val query = rawModelId.trim()
            if (query.isEmpty()) return null
            val lower = query.lowercase()
            return byQualified[query]
                ?: byBareId[query]
                ?: byLowerQualified[lower]
                ?: byLowerBareId[lower]
        }

        companion object {
            val EMPTY = CatalogIndex(
                all = emptyList(),
                providers = emptyList(),
                byQualified = emptyMap(),
                byBareId = emptyMap(),
                byLowerQualified = emptyMap(),
                byLowerBareId = emptyMap(),
            )

            fun from(models: List<ModelInfo>): CatalogIndex {
                val sorted = models.sortedWith(compareBy<ModelInfo>({ it.providerId }, { it.id }))
                val providers = sorted.map { it.providerId }.distinct().sorted()
                val byQualified = LinkedHashMap<String, ModelInfo>(sorted.size)
                val byLowerQualified = LinkedHashMap<String, ModelInfo>(sorted.size)
                val byBareId = LinkedHashMap<String, ModelInfo>(sorted.size)
                val byLowerBareId = LinkedHashMap<String, ModelInfo>(sorted.size)

                // Populate preferred first-party providers before aggregators
                // so bare lookups (e.g. "gpt-4o") resolve to canonical metadata.
                val lookupPriority = sorted.sortedWith(
                    compareBy<ModelInfo>(
                        { if (it.providerId in PREFERRED_PROVIDERS) 0 else 1 },
                        { it.providerId },
                        { it.id },
                    ),
                )
                for (model in lookupPriority) {
                    val qualified = "${model.providerId}/${model.id}"
                    byQualified.putIfAbsent(qualified, model)
                    byLowerQualified.putIfAbsent(qualified.lowercase(), model)
                    byBareId.putIfAbsent(model.id, model)
                    byLowerBareId.putIfAbsent(model.id.lowercase(), model)
                }
                return CatalogIndex(
                    all = sorted,
                    providers = providers,
                    byQualified = byQualified,
                    byBareId = byBareId,
                    byLowerQualified = byLowerQualified,
                    byLowerBareId = byLowerBareId,
                )
            }
        }
    }
}
