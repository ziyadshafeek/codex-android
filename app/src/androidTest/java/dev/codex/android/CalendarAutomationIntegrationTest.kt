package dev.codex.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dev.codex.android.agent.PlanStore
import dev.codex.android.data.AutomationEntity
import dev.codex.android.data.CodexDatabase
import dev.codex.android.data.KeyStore
import dev.codex.android.service.AutomationWorker
import dev.codex.android.service.CalendarSchedule
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarAutomationIntegrationTest {
    @Test fun dueSlotRunsOnceAndCreatesAReviewThread() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = CodexDatabase.getInstance(context)
        val keys = KeyStore(context)
        val manager = WorkManager.getInstance(context)
        val due = Instant.now().minusSeconds(180).truncatedTo(ChronoUnit.MINUTES)
        val id = "test-calendar"
        val title = "Calendar integration test"
        val prompt = "Return the calendar integration result"
        val calendar = CalendarSchedule(due.atZone(ZoneOffset.UTC).toLocalTime(), ZoneOffset.UTC)
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            try {
                keys.setBaseUrl("http://127.0.0.1:${server.port}/v1")
                keys.setApiKey("test-key-calendar")
                db.upsertAutomation(AutomationEntity(id, title, CodexDatabase.DEFAULT_PHONE_WORKSPACE_ID, prompt,
                    15, false, false, true, null, null, calendar, due.minusSeconds(60).toEpochMilli()))
                assertEquals(calendar, db.getAutomation(id)?.calendar)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"Calendar result"},"finish_reason":"stop"}]}""",
                ))
                repeat(2) {
                    val work = OneTimeWorkRequestBuilder<AutomationWorker>()
                        .setInputData(workDataOf(AutomationWorker.KEY_AUTOMATION_ID to id)).build()
                    manager.enqueue(work).await()
                    try {
                        val info = withTimeout(40_000) {
                            manager.getWorkInfoByIdFlow(work.id).filterNotNull().first { it.state.isFinished }
                        }
                        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
                    } finally {
                        manager.cancelWorkById(work.id).await()
                    }
                }
                assertEquals(1, server.requestCount)
                assertEquals(due.toEpochMilli(), db.getAutomation(id)?.scheduleClaimedAt)
                assertEquals("ok", db.getAutomation(id)?.lastStatus)
                val thread = db.listThreads().single { it.title == "Auto: $title" }
                assertEquals("needs_review", thread.status)
                assertTrue(db.getMessages(thread.id).any { it.content == "Calendar result" })
            } finally {
                db.deleteAutomation(id)
                db.listThreads().filter { it.title == "Auto: $title" }.forEach { db.deleteThread(it.id) }
                keys.clearApiKey()
                keys.setBaseUrl("")
                keys.setModel("")
                PlanStore(context).clear()
            }
        }
    }
}
