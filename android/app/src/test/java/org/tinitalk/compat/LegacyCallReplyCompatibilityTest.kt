package org.tinitalk.compat

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tinitalk.call.CallPhase
import org.tinitalk.compat.legacy576e7cf.CallCoordinator
import org.tinitalk.compat.legacy576e7cf.SequencedSignalEvent
import org.tinitalk.compat.legacy576e7cf.SignalClient
import org.tinitalk.compat.legacy576e7cf.SignalEvent
import org.tinitalk.compat.legacy576e7cf.CallHistoryItem
import com.google.gson.Gson

class LegacyCallReplyCompatibilityTest {
    @Test
    fun preFeatureHistoryDtoReadsRowsWithOptionalReplyCode() {
        val rows = """[{"id":1,"peer_login":"bob","peer_name":"Bob","direction":"outgoing","outcome":"rejected","reached":true,"started_at":1787666400,"duration_seconds":0,"reply_code":"call_me_later"}]"""
        val decoded = Gson().fromJson(rows, Array<CallHistoryItem>::class.java)
        assertEquals(1, decoded.size)
        assertEquals("rejected", decoded.single().outcome)
        assertEquals("bob", decoded.single().peerLogin)
    }

    @Test
    fun preFeatureParserAndCoordinatorTreatEveryReplyAsOrdinaryRejection() {
        val root = listOf(Path.of("protocol/testdata"), Path.of("../protocol/testdata"), Path.of("../../protocol/testdata"))
            .first { Files.isDirectory(it) }
        val fixture = Files.readString(root.resolve("call_reject_reply.json"))
        listOf("cannot_talk", "call_me_later", "will_call_back", "future_reply").forEach { code ->
            val event = SignalEvent.decode(fixture.replace("cannot_talk", code))
            val coordinator = CallCoordinator("alice", object : SignalClient {
                override fun send(event: SignalEvent, onSettled: (() -> Unit)?) { onSettled?.invoke() }
            })
            coordinator.startCall("bob", event.callId)
            coordinator.onEvent(SequencedSignalEvent(event, 1))
            assertEquals(CallPhase.Ended, coordinator.snapshot().phase)
        }
    }
}
