package cdjmusiclink.server.state

import cdjmusiclink.server.model.Device
import cdjmusiclink.server.model.DeviceKind
import cdjmusiclink.server.model.MasterInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {
    private val cdj = Device(1, "CDJ-3000", "10.0.0.1", DeviceKind.player)
    private val djm = Device(33, "DJM-V10", "10.0.0.9", DeviceKind.mixer)

    private fun kotlinx.coroutines.test.TestScope.collect(bus: EventBus): MutableList<Event> {
        val out = mutableListOf<Event>()
        backgroundScope.launch { bus.events.collect { out += it } }
        return out
    }

    @Test
    fun statusChangeEmitsPlayerEventOnlyOnChange() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(); val store = Store(bus) { 1000 }
        val events = collect(bus)
        store.upsertDevice(cdj)
        events.clear()
        store.status(1, snap())
        store.status(1, snap())
        assertEquals(1, events.count { it is PlayerEvent })
        assertEquals(1, events.count { it is TrackEvent })
        val pe = events.filterIsInstance<PlayerEvent>().single()
        assertEquals("CDJ-3000", pe.player.name)
        assertEquals(1000L, pe.player.updatedAt)
    }

    @Test
    fun metadataEmitsTrackEvent() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(); val store = Store(bus) { 1000 }
        val events = collect(bus)
        store.upsertDevice(cdj)
        store.status(1, snap(id = 5))
        events.clear()
        val track = store.player(1)!!.track!!.copy(title = "T")
        store.metadata(1, track)
        store.metadata(1, null) // 曲がロードされている間の null は無視
        assertEquals(listOf<Any>(TrackEvent(1, track).copy(id = events[0].id)), events)
        assertEquals("T", store.player(1)!!.track!!.title)
    }

    @Test
    fun metadataBeforeStubIsDeliveredWithTrackEvent() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(); val store = Store(bus) { 1000 }
        val events = collect(bus)
        store.upsertDevice(cdj)
        store.status(1, snap(id = 5))
        val next = store.player(1)!!.track!!.copy(id = 9, title = "Next")
        events.clear()
        store.metadata(1, null)
        store.metadata(1, next)
        assertTrue(events.none { it is TrackEvent })
        val changes = store.status(1, snap(id = 9))
        assertTrue(Change.TRACK in changes)
        val te = events.filterIsInstance<TrackEvent>().single()
        assertEquals("Next", te.track!!.title)
    }

    @Test
    fun clearPositionRemovesPosition() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(); val store = Store(bus) { 1000 }
        store.upsertDevice(cdj)
        store.status(1, snap())
        store.position(1, 500, true, null)
        assertEquals(500L, store.player(1)!!.position!!.ms)
        store.clearPosition(1)
        assertNull(store.player(1)!!.position)
        store.clearPosition(7) // unknown player: ignored
    }

    @Test
    fun removeDeviceEmitsDisconnected() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(); val store = Store(bus) { 1000 }
        val events = collect(bus)
        store.upsertDevice(cdj)
        store.upsertDevice(cdj)
        store.status(1, snap())
        events.clear()
        store.removeDevice(1)
        val e = events.single() as DeviceEvent
        assertEquals("disconnected", e.action)
        assertEquals(cdj, e.device)
        assertNull(store.player(1))
        assertTrue(store.devices().isEmpty())
        store.removeDevice(1)
        assertEquals(1, events.size)
    }

    @Test
    fun upsertEmitsConnectedOnlyForNewNumber() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(); val store = Store(bus) { 1000 }
        val events = collect(bus)
        store.upsertDevice(cdj); store.upsertDevice(cdj)
        assertEquals(listOf("connected"), events.map { (it as DeviceEvent).action })
    }

    @Test
    fun masterOnlineAndMixer() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus(); val store = Store(bus) { 1000 }
        val events = collect(bus)
        assertFalse(store.mixerPresent())
        store.upsertDevice(djm)
        assertTrue(store.mixerPresent())
        events.clear()
        store.master(1, 128.0); store.master(1, 129.0)
        assertEquals(1, events.count { it is MasterEvent })
        assertEquals(MasterInfo(1, 129.0), store.masterInfo())
        store.online(true); store.online(true); store.online(false)
        assertEquals(listOf(true, false), events.filterIsInstance<OnlineEvent>().map { it.online })
        store.position(7, 1, true, null) // unknown player: ignored
        assertNull(store.player(7))
    }
}
