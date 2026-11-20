package dev.codex.android

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codex.android.agent.PlanStore
import dev.codex.android.attachments.FileLibrary
import dev.codex.android.tools.WorkspaceFileTools
import dev.codex.android.data.KeyStore
import dev.codex.android.data.MemoryStore
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun chosenFilesReachTheProviderOnlyWhenSent() {
        val context = compose.activity.applicationContext
        val keys = KeyStore(context)
        val fixture = File(context.filesDir, "builds/attachment-fixtures").apply { mkdirs() }
        val library = FileLibrary(context, WorkspaceFileTools(File(context.filesDir, "workspace")))
        val initialCopies = library.list("library").map { it.key }.toSet()
        val text = File(fixture, "sample.txt").apply { writeText("The attachment contains the cedar token.") }
        val image = File(fixture, "image.png")
        val pdf = File(fixture, "page.pdf")
        val bmp = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GREEN)
        image.outputStream().use { assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bmp.recycle()
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(280, 280, 1).create())
            page.canvas.drawText("PDF fixture", 12f, 25f, Paint())
            doc.finishPage(page)
            pdf.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        val uris = listOf(text, image, pdf).map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            Intents.init()
            try {
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("onboarding_skip").performClick()
                }
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_base_url").performTextInput("http://127.0.0.1:${server.port}/v1")
                compose.onNodeWithTag("settings_api_key").performTextInput("test-key-attachments")
                compose.onNodeWithTag("settings_save").performClick()
                compose.onNodeWithText("Back").performClick()
                Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().apply {
                    clipData = ClipData.newRawUri("Test documents", uris.first()).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }))
                compose.onNodeWithTag("attach_files").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("attachment_page.pdf").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("attachment_sample.txt").assertIsDisplayed()
                compose.onNodeWithTag("image_options").performClick()
                compose.onNodeWithTag("inline_images_on").performClick()
                assertEquals(0, server.requestCount)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"Files received"},"finish_reason":"stop"}]}""",
                ))
                compose.onNodeWithTag("chat_input").performTextInput("Review all three attachments")
                compose.onNodeWithTag("chat_send").performClick()
                val request = server.takeRequest(15, TimeUnit.SECONDS) ?: error("No model request")
                assertEquals("/v1/chat/completions", request.path)
                val body = request.body.readUtf8()
                assertTrue(body.contains("The attachment contains the cedar token."))
                assertTrue(body.contains("data:image/png;base64,"))
                assertTrue(body.contains("data:image/jpeg;base64,"))
                assertTrue(body.contains("page.pdf"))
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Files received").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat_stop").fetchSemanticsNodes().isEmpty() }
                assertFalse(runBlocking { MemoryStore(context).recentMessages(20) }.any { it.content.contains("cedar token") && it.role == "user" })
                compose.onNodeWithTag("chat_new").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("keep_sample.txt").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("keep_sample.txt").performClick()
                compose.onNodeWithTag("new_chat_save_selected").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Start a new chat?").fetchSemanticsNodes().isEmpty() }
                val saved = library.list("library").filterNot { it.key in initialCopies }
                assertEquals(listOf("sample.txt"), saved.map { it.name })
                compose.waitForIdle()
                compose.onNodeWithTag("open_file_library").performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("file_library_list").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("file_library_list").performScrollToNode(hasTestTag("library_file_sample.txt"))
                compose.onNodeWithTag("library_file_sample.txt").performClick()
                compose.onNodeWithText("Delete file").performClick()
                compose.onNodeWithText("Delete", substring = false).performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Deleted sample.txt.").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(compose.onAllNodesWithTag("library_file_page.pdf").fetchSemanticsNodes().isEmpty())

            } finally {
                Intents.release()
                fixture.deleteRecursively()
                runBlocking {
                    for (entry in library.list("library").filterNot { it.key in initialCopies }) library.delete(entry)
                    keys.setImageMode("auto")
                    keys.clearApiKey()
                    keys.setBaseUrl("")
                    keys.setModel("")
                    MemoryStore(context).clearMessages()
                    PlanStore(context).clear()
                }
            }
        }
    }

    @Test fun generatedWorkspaceFileDownloadsFromChatAsExactBytes() {
        val context = compose.activity.applicationContext
        val keys = KeyStore(context)
        val name = "generated-attachment-test.html"
        val html = "<!doctype html><h1>Saved through Android Files</h1>"
        val source = File(context.filesDir, "workspace/$name")
        val destination = File(context.filesDir, "builds/export-test.html")
        destination.parentFile?.mkdirs()
        val targetUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            Intents.init()
            try {
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("onboarding_skip").performClick()
                }
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_base_url").performTextInput("http://127.0.0.1:${server.port}/v1")
                compose.onNodeWithTag("settings_api_key").performTextInput("test-key-attachments")
                compose.onNodeWithTag("settings_save").performClick()
                compose.onNodeWithText("Back").performClick()
                val arguments = org.json.JSONObject().put("path", name).put("content", html).toString()
                val call = org.json.JSONObject().put("id", "call_write").put("type", "function")
                    .put("function", org.json.JSONObject().put("name", "workspace_write").put("arguments", arguments))
                val choice = org.json.JSONObject().put("finish_reason", "tool_calls")
                    .put("message", org.json.JSONObject().put("role", "assistant").put("tool_calls", org.json.JSONArray().put(call)))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    org.json.JSONObject().put("choices", org.json.JSONArray().put(choice)).toString(),
                ))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"HTML file ready"},"finish_reason":"stop"}]}""",
                ))
                compose.onNodeWithTag("chat_input").performTextInput("Create a downloadable HTML file")
                compose.onNodeWithTag("chat_send").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Allow").fetchSemanticsNodes().isNotEmpty() }
                assertFalse(source.exists())
                compose.onNodeWithText("Allow").performClick()
                compose.waitUntil(20_000) { source.isFile && compose.onAllNodesWithTag("chat_stop").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("workspace_output_$name"))
                Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().apply {
                    data = targetUri
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }))
                compose.onNodeWithTag("download_$name").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("workspace_download_status").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(html, destination.readText())
                assertEquals(2, server.requestCount)

                compose.onNodeWithTag("chat_input").performTextInput("can you save it in downloads")
                compose.onNodeWithTag("chat_send").performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText("Tap Download on the file below", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(2, server.requestCount)

                compose.onNodeWithTag("chat_workspace_files").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("workspace_file_filter").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("workspace_file_filter").performTextInput("generated-attachment-test")
                compose.onNodeWithText("Done").performClick()
            } finally {
                Intents.release()
                source.delete()
                destination.delete()
                runBlocking {
                    keys.clearApiKey()
                    keys.setBaseUrl("")
                    keys.setModel("")
                    MemoryStore(context).clearMessages()
                    PlanStore(context).clear()
                }
            }
        }
    }

}
