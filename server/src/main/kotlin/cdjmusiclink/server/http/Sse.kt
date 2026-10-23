package cdjmusiclink.server.http

import cdjmusiclink.server.model.ApiJson
import cdjmusiclink.server.model.Device
import cdjmusiclink.server.model.MasterInfo
import cdjmusiclink.server.model.Player
import cdjmusiclink.server.state.BeatEvent
import cdjmusiclink.server.state.DeviceEvent
import cdjmusiclink.server.state.Event
import cdjmusiclink.server.state.EventBus
import cdjmusiclink.server.state.MasterEvent
import cdjmusiclink.server.state.OnlineEvent
import cdjmusiclink.server.state.PlayerEvent
import cdjmusiclink.server.state.Store
import cdjmusiclink.server.state.TrackEvent
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotPayload(
    val players: List<Player>,
    val master: MasterInfo,
    val devices: List<Device>,
    val online: Boolean,
    val serverTime: Long,
)

private const val RETRY_MS = 2000L

/** 具象シリアライザで直列化する(多相にすると判別子が付いて PlayerEvent のフラット形が崩れる)。 */
private fun encode(e: Event): String = with(ApiJson.json) {
    when (e) {
        is BeatEvent -> encodeToString(BeatEvent.serializer(), e)
        is PlayerEvent -> encodeToString(PlayerEvent.serializer(), e)
        is TrackEvent -> encodeToString(TrackEvent.serializer(), e)
        is DeviceEvent -> encodeToString(DeviceEvent.serializer(), e)
        is MasterEvent -> encodeToString(MasterEvent.serializer(), e)
        is OnlineEvent -> encodeToString(OnlineEvent.serializer(), e)
    }
}

/** `install(SSE)` は Application 側(apiModule)で行う。 */
fun Route.streamRoute(store: Store, bus: EventBus, clock: () -> Long = System::currentTimeMillis) {
    sse("/stream") {
        call.response.header("X-Accel-Buffering", "no")
        // 購読を先に確立してから snapshot を送る。逆だと snapshot と購読開始の間のイベントを取りこぼす。
        bus.events.onSubscription {
            val snapshot = SnapshotPayload(
                players = store.players().filter { it.number < 0x10 },
                master = store.masterInfo(),
                devices = store.devices(),
                online = store.isOnline(),
                serverTime = clock(),
            )
            send(ServerSentEvent(data = ApiJson.json.encodeToString(SnapshotPayload.serializer(), snapshot), event = "snapshot", id = "0", retry = RETRY_MS))
        }.collect { e ->
            send(ServerSentEvent(data = encode(e), event = e.name, id = e.id.toString()))
        }
    }
}
