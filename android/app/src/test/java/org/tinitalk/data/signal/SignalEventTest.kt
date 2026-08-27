package org.tinitalk.data.signal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEventTest {
    @Test
    fun acceptsRestartRequestEvent() {
        val event = SignalEvent(
            id = "018f7d51-3f90-7e63-b657-4a83a6a90210",
            callId = "018f7d51-40a1-7bb5-a2d0-7e47f9181766",
            type = "rtc.restart.request",
            sentAt = 1787666400000,
            payload = com.google.gson.JsonObject(),
        )

        assertEquals("rtc.restart.request", SignalEvent.decode(event.encode()).type)
    }

    @Test
    fun decodesValidFixtures() {
        listOf("call_start.json", "call_resume.json", "rtc_ice.json").forEach { name ->
            val event = SignalEvent.decode(readFixture(name))
            assertTrue("$name id", event.id.isNotBlank())
            assertTrue("$name call id", event.callId.isNotBlank())
            assertTrue("$name type", event.type.isNotBlank())

            val encoded = event.encode()
            val roundTrip = SignalEvent.decode(encoded)
            assertEquals(event.type, roundTrip.type)
            assertEquals(event.callId, roundTrip.callId)
        }
    }

    @Test
    fun rejectsInvalidFixtures() {
        Files.list(fixtureRoot().resolve("invalid")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val result = runCatching { SignalEvent.decode(readPath(path)) }
                assertTrue("${path.name} should be rejected", result.isFailure)
            }
        }
    }

    @Test
    fun rejectsOversizedPayload() {
        val raw = """{"id":"018f7d51-3f90-7e63-b657-4a83a6a90210","call_id":"018f7d51-40a1-7bb5-a2d0-7e47f9181766","type":"call.start","sent_at":1787666400000,"payload":{"blob":"${"a".repeat(SignalEvent.MAX_EVENT_BYTES)}"}}"""
        val result = runCatching { SignalEvent.decode(raw) }
        assertTrue(result.isFailure)
    }

    private fun readFixture(name: String): String =
        readPath(fixtureRoot().resolve(name))

    private fun readPath(path: Path): String =
        String(Files.readAllBytes(path), Charsets.UTF_8)

    private fun fixtureRoot(): Path {
        val candidates = listOf(
            Path.of("protocol", "testdata"),
            Path.of("..", "protocol", "testdata"),
            Path.of("..", "..", "protocol", "testdata"),
        )
        return candidates.first { Files.isDirectory(it) }
    }
}
