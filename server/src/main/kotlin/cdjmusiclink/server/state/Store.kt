package cdjmusiclink.server.state

import cdjmusiclink.server.model.Device
import cdjmusiclink.server.model.DeviceKind
import cdjmusiclink.server.model.MasterInfo
import cdjmusiclink.server.model.Player
import cdjmusiclink.server.model.Track
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** デバイス・プレイヤー・マスター・オンライン状態の保持と、変化時のイベント発行。 */
class Store(private val bus: EventBus, private val clock: () -> Long) {
    private val lock = ReentrantLock()
    private val devices = linkedMapOf<Int, Device>()
    private val states = linkedMapOf<Int, PlayerState>()
    private var master = MasterInfo(null, null)
    private var online = false

    /** ロック内で状態を更新し、返ったイベントをロック外で発行する。 */
    private inline fun mutate(block: () -> List<Event>) {
        val events = lock.withLock { block() }
        events.forEach(bus::publish)
    }

    fun upsertDevice(d: Device) = mutate {
        val isNew = d.number !in devices
        devices[d.number] = d
        if (isNew) listOf(DeviceEvent("connected", d)) else emptyList()
    }

    fun removeDevice(number: Int) = mutate {
        val d = devices.remove(number) ?: return@mutate emptyList()
        states.remove(number)
        listOf(DeviceEvent("disconnected", d))
    }

    fun devices(): List<Device> = lock.withLock { devices.values.toList() }

    fun players(): List<Player> = lock.withLock { states.values.map { it.toDto() } }

    fun player(n: Int): Player? = lock.withLock { states[n]?.toDto() }

    fun status(n: Int, s: StatusSnapshot) = mutate {
        val existing = states[n]
        val state = existing ?: run {
            val dev = devices[n]
            PlayerState(n, dev?.name ?: "", dev?.address ?: "").also { states[n] = it }
        }
        val changes = state.applyStatus(s, mixerPresent(), clock())
        val out = mutableListOf<Event>()
        if (existing == null || changes.isNotEmpty()) out += PlayerEvent(state.toDto())
        if (Change.TRACK in changes) out += TrackEvent(n, state.track())
        out
    }

    fun position(n: Int, ms: Long, definitive: Boolean, totalMs: Long?) {
        lock.withLock { states[n]?.applyPosition(ms, definitive, totalMs, clock()) }
    }

    fun metadata(n: Int, track: Track?) = mutate {
        val state = states[n] ?: return@mutate emptyList()
        if (state.applyMetadata(track)) listOf(TrackEvent(n, state.track())) else emptyList()
    }

    fun availability(n: Int, artwork: Boolean?, waveform: Boolean?, beatgrid: Boolean?) = mutate {
        val state = states[n] ?: return@mutate emptyList()
        if (state.applyAvailability(artwork, waveform, beatgrid)) listOf(TrackEvent(n, state.track())) else emptyList()
    }

    fun master(player: Int?, tempo: Double?) = mutate {
        val changed = master.player != player
        master = MasterInfo(player, tempo)
        if (changed) listOf(MasterEvent(player, tempo)) else emptyList()
    }

    fun masterInfo(): MasterInfo = lock.withLock { master }

    fun online(flag: Boolean) = mutate {
        val changed = online != flag
        online = flag
        if (changed) listOf(OnlineEvent(flag)) else emptyList()
    }

    fun isOnline(): Boolean = lock.withLock { online }

    fun mixerPresent(): Boolean = lock.withLock { devices.values.any { it.kind == DeviceKind.mixer } }

    fun beat(n: Int, e: BeatEvent) = bus.publish(e)
}
