package dev.codex.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * JVM unit tests for [ModelsCatalog]: remote fetch + lookup, disk TTL cache
 * hit, ETag 304 validation, offline-snapshot fallback, and tolerance for
 * unknown extra fields (`ignoreUnknownKeys`).
 */
class ModelsCatalogTest {

    private lateinit var cacheDir: File

    private val cannedApiJson = """
        {
          "openai": {
            "id": "openai",
            "name": "OpenAI",
            "models": {
              "gpt-4o": {
                "id": "gpt-4o",
                "name": "GPT-4o",
                "tool_call": true,
                "cost": { "input": 2.5, "output": 10.0 },
                "limit": { "context": 128000, "output": 16384 }
              }
            }
          },
          "anthropic": {
            "id": "anthropic",
            "name": "Anthropic",
            "models": {
              "claude-sonnet-4-5": {
                "id": "claude-sonnet-4-5",
                "name": "Claude Sonnet 4.5",
                "tool_call": true,
                "cost": { "input": 3.0, "output": 15.0 },
                "limit": { "context": 200000, "output": 64000 }
              }
            }
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("models-catalog-test-").toFile()
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun readBundledSnapshot(): String {
        val candidates = listOf(
            File("src/main/assets/models-snapshot.json"),
            File("app/src/main/assets/models-snapshot.json"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: cannedApiJson
    }

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    private fun response(
        request: Request,
        code: Int,
        body: String = "",
        etag: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Status $code")
            .header("Content-Type", "application/json")
            .body(body.toResponseBody("application/json".toMediaType()))
        if (etag != null) builder.header("ETag", etag)
        return builder.build()
    }

    @Test
    fun refresh_fetchesCatalogAndSupportsLookupAllAndProviders() = runBlocking {
        var calls = 0
        val client = fakeClient { req ->
            calls++
            response(req, 200, cannedApiJson, etag = "\"v1\"")
        }
        val catalog = ModelsCatalog(
            client = client,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
        )

        val fetched = catalog.refresh()
        assertTrue(fetched)
        assertEquals(1, calls)

        val gpt = catalog.lookup("gpt-4o")
        assertNotNull(gpt)
        assertEquals("gpt-4o", gpt!!.id)
        assertEquals("openai", gpt.providerId)
        assertEquals("GPT-4o", gpt.displayName)
        assertEquals(128000, gpt.contextWindow)
        assertEquals(2.5, gpt.inputPricePerMillionUsd!!, 1e-9)
        assertEquals(10.0, gpt.outputPricePerMillionUsd!!, 1e-9)
        assertTrue(gpt.supportsTools)

        // Provider-qualified lookup also works.
        assertEquals(gpt, catalog.lookup("openai/gpt-4o"))
        assertNotNull(catalog.lookup("claude-sonnet-4-5"))
        assertNull(catalog.lookup("nonexistent-model"))
        assertEquals(2, catalog.all().size)
        assertEquals(listOf("anthropic", "openai"), catalog.providers())
    }

    @Test
    fun refresh_usesDiskCacheWithinTtlAndEtagAfterExpiry() = runBlocking {
        var nowMs = 1_000_000L
        var calls = 0
        val seenIfNoneMatch = mutableListOf<String?>()
        val client = fakeClient { req ->
            calls++
            seenIfNoneMatch += req.header("If-None-Match")
            if (req.header("If-None-Match") == "\"etag-42\"") {
                response(req, 304, "")
            } else {
                response(req, 200, cannedApiJson, etag = "\"etag-42\"")
            }
        }

        val first = ModelsCatalog(
            client = client,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
            clockMs = { nowMs },
        )
        assertTrue(first.refresh())
        assertEquals(1, calls)

        // Fresh instance sharing cacheDir within 24h TTL hits disk cache with 0 HTTP calls.
        nowMs += TimeUnit.HOURS.toMillis(6)
        val cachedInstance = ModelsCatalog(
            client = client,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
            clockMs = { nowMs },
        )
        assertFalse(cachedInstance.refresh())
        assertEquals(1, calls)
        assertNotNull(cachedInstance.lookup("gpt-4o"))

        // Advance past 24h TTL: sends If-None-Match, gets 304, reuses cache.
        nowMs += TimeUnit.HOURS.toMillis(24)
        assertFalse(cachedInstance.refresh())
        assertEquals(2, calls)
        assertEquals(listOf(null, "\"etag-42\""), seenIfNoneMatch)
        assertNotNull(cachedInstance.lookup("claude-sonnet-4-5"))
    }

    @Test
    fun refresh_fallsBackToOfflineSnapshotOnNetworkFailure() = runBlocking {
        val failingClient = fakeClient { throw IOException("No network") }
        val catalog = ModelsCatalog(
            client = failingClient,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
        )

        val fetched = catalog.refresh()
        assertFalse(fetched)

        val gpt = catalog.lookup("gpt-4o")
        assertNotNull("Expected offline snapshot to populate gpt-4o", gpt)
        assertEquals(128000, gpt!!.contextWindow)
        assertNotNull(catalog.lookup("claude-sonnet-4-5"))
        assertTrue(catalog.providers().isNotEmpty())
    }

    @Test
    fun refresh_unknownExtraFieldsDoNotCrashParsing() = runBlocking {
        val jsonWithUnknownFields = """
            {
              "unexpected_top_level_scalar": "ignored",
              "unexpected_top_level_array": [1, 2, 3],
              "openai": {
                "id": "openai",
                "name": "OpenAI",
                "extra_provider_field": { "nested": true },
                "models": {
                  "gpt-5": {
                    "id": "gpt-5",
                    "name": "GPT-5",
                    "tool_call": true,
                    "reasoning": true,
                    "modalities": { "input": ["text", "image"], "output": ["text"] },
                    "future_unknown_flag": "v99",
                    "cost": { "input": 1.25, "output": 10.0, "cache_read": 0.125, "extra_cost": 99 },
                    "limit": { "context": 400000, "output": 128000, "extra_limit": 42 }
                  }
                }
              }
            }
        """.trimIndent()
        val client = fakeClient { req -> response(req, 200, jsonWithUnknownFields) }
        val catalog = ModelsCatalog(
            client = client,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
        )

        assertTrue(catalog.refresh())
        val model = catalog.lookup("gpt-5")
        assertNotNull(model)
        assertEquals(400000, model!!.contextWindow)
        assertEquals(1.25, model.inputPricePerMillionUsd!!, 1e-9)
        assertEquals(10.0, model.outputPricePerMillionUsd!!, 1e-9)
        assertTrue(model.supportsTools)
    }

    @Test
    fun refresh_malformedSnapshotJson_returnsEmptyCatalog() = runBlocking {
        val failingClient = fakeClient { throw IOException("No network") }
        val catalog = ModelsCatalog(
            client = failingClient,
            cacheDir = cacheDir,
            snapshotLoader = { "{not valid json" },
        )

        assertFalse(catalog.refresh())
        assertNull(catalog.lookup("gpt-4o"))
        assertTrue(catalog.all().isEmpty())
        assertTrue(catalog.providers().isEmpty())
    }

    @Test
    fun refresh_missingSnapshotAsset_returnsEmptyCatalog() = runBlocking {
        val failingClient = fakeClient { throw IOException("No network") }
        val catalog = ModelsCatalog(
            client = failingClient,
            cacheDir = cacheDir,
            snapshotLoader = { null },
        )

        assertFalse(catalog.refresh())
        assertNull(catalog.lookup("gpt-4o"))
        assertTrue(catalog.all().isEmpty())
    }

    @Test
    fun refresh_serverError500_fallsBackToSnapshot() = runBlocking {
        val client = fakeClient { req -> response(req, 500, "") }
        val catalog = ModelsCatalog(
            client = client,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
        )

        assertFalse(catalog.refresh())
        assertNotNull(catalog.lookup("gpt-4o"))
    }

    @Test
    fun refresh_empty200Body_fallsBackToSnapshot() = runBlocking {
        val client = fakeClient { req -> response(req, 200, "") }
        val catalog = ModelsCatalog(
            client = client,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
        )

        assertFalse(catalog.refresh())
        assertNotNull(catalog.lookup("gpt-4o"))
        assertNotNull(catalog.lookup("claude-sonnet-4-5"))
    }

    @Test
    fun refresh_expiredCache_serverReturnsNewBody_replacesCache() = runBlocking {
        var nowMs = 1_000_000L
        val newBody = """
            {
              "openai": {
                "id": "openai",
                "models": {
                  "gpt-5": {
                    "id": "gpt-5",
                    "name": "GPT-5",
                    "tool_call": true,
                    "cost": { "input": 1.25, "output": 10.0 },
                    "limit": { "context": 400000 }
                  }
                }
              }
            }
        """.trimIndent()
        val client = fakeClient { req ->
            if (req.header("If-None-Match") == "\"v1\"") {
                response(req, 200, newBody, etag = "\"v2\"")
            } else {
                response(req, 200, cannedApiJson, etag = "\"v1\"")
            }
        }
        val catalog = ModelsCatalog(
            client = client,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
            clockMs = { nowMs },
        )

        assertTrue(catalog.refresh())
        assertNotNull(catalog.lookup("gpt-4o"))

        // Past the 24 h TTL the stale ETag does not 304 — the new body wins.
        nowMs += TimeUnit.HOURS.toMillis(25)
        assertTrue(catalog.refresh())
        assertNotNull(catalog.lookup("gpt-5"))
        assertNull(catalog.lookup("gpt-4o"))
        assertEquals(listOf("openai"), catalog.providers())
    }

    @Test
    fun lookup_caseInsensitiveAndPrefersFirstPartyOnBareCollision() = runBlocking {
        val collidingJson = """
            {
              "openrouter": {
                "id": "openrouter",
                "models": {
                  "gpt-x": {
                    "id": "gpt-x",
                    "name": "GPT-X via OpenRouter",
                    "tool_call": false,
                    "limit": { "context": 64000 }
                  }
                }
              },
              "openai": {
                "id": "openai",
                "models": {
                  "gpt-x": {
                    "id": "gpt-x",
                    "name": "GPT-X",
                    "tool_call": true,
                    "limit": { "context": 128000 }
                  }
                }
              }
            }
        """.trimIndent()
        val client = fakeClient { req -> response(req, 200, collidingJson) }
        val catalog = ModelsCatalog(
            client = client,
            cacheDir = cacheDir,
            snapshotLoader = { readBundledSnapshot() },
        )

        assertTrue(catalog.refresh())
        // Bare id resolves to the first-party provider, not the aggregator.
        val bare = catalog.lookup("gpt-x")
        assertNotNull(bare)
        assertEquals("openai", bare!!.providerId)
        assertEquals(128000, bare.contextWindow)
        // Case-insensitive bare lookup finds the same entry.
        assertEquals(bare, catalog.lookup("GPT-X"))
        assertEquals(bare, catalog.lookup("OpenAI/GPT-X"))
        // Qualified lookup still reaches the aggregator's copy.
        val routed = catalog.lookup("openrouter/gpt-x")
        assertNotNull(routed)
        assertEquals("openrouter", routed!!.providerId)
        assertEquals(64000, routed.contextWindow)
    }
}
