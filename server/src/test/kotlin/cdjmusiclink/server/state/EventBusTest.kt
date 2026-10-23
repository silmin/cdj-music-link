package cdjmusiclink.server.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import cdjmusiclink.server.model.ApiJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusTest {
    @Test
    fun slowSubscriberDropsOldest() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<Long>()
        val job = launch {
            bus.events.collect {
                gate.await()
                received += it.id
            }
        }
        repeat(300) { bus.publish(OnlineEvent(true)) }
        gate.complete(Unit)
        advanceUntilIdle()
        job.cancel()
        // 1 件目は購読者が受け取り済みで待機中。それ以降は古い方が落ちる。
        assertEquals(1L, received.first())
        assertTrue(received[1] >= 44, "second=${received[1]}")
        assertEquals(300L, received.last())
        assertEquals(received.sorted(), received)
    }

    @Test
    fun idIsAssignedByBusAndNotSerialized() {
        val bus = EventBus()
        val e = OnlineEvent(true).withId(7)
        assertEquals(7L, e.id)
        assertEquals("""{"online":true}""", ApiJson.json.encodeToString(OnlineEvent.serializer(), e as OnlineEvent))
        assertEquals("online", e.name)
        bus.publish(e)
    }

    @Test
    fun beatEventSerializesExactShape() {
        val e = BeatEvent(1, null, 2, 128.0, 400, null, true, BeatPosition(1000), BeatWaveform(5, "#336699"), 99)
        assertEquals(
            """{"player":1,"beatNumber":null,"beatWithinBar":2,"tempo":128.0,"nextBeatInMs":400,"nextBarInMs":null,""" +
                """"isTempoMaster":true,"position":{"ms":1000},"waveform":{"height":5,"color":"#336699"},"receivedAt":99}""",
            ApiJson.json.encodeToString(BeatEvent.serializer(), e),
        )
    }
}
