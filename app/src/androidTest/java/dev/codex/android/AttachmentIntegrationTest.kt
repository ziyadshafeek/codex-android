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
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codex.android.agent.PlanStore
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
        val text = File(fixture, "sample.txt").apply { writeText("The attachment contains the cedar token.") }
        val image = File(fixture, "image.png")
        val pdf = File(fixture, "page.pdf")
        val bmp = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GREEN)
        image.outputStream().use { assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bmp.recycle()
        PdfDocument().use { doc ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(280, 280, 1).create())
            page.canvas.drawText("PDF fixture", 12f, 25f, Paint())
            doc.finishPage(page)
            pdf.outputStream().use(doc::writeTo)
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
            } finally {
                Intents.release()
                fixture.deleteRecursively()
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
