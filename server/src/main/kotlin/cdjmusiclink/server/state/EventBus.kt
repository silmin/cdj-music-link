package cdjmusiclink.server.state

import cdjmusiclink.server.model.Device
import cdjmusiclink.server.model.Player
import cdjmusiclink.server.model.Track
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.Transient
import java.util.concurrent.atomic.AtomicLong

/** SSE イベント。`id` と `name` は JSON に含めず、SSE の `id:` / `event:` 行に使う。 */
sealed interface Event {
    val id: Long
    val name: String
    fun withId(id: Long): Event
}

@Serializable
data class BeatPosition(val ms: Long)

@Serializable
data class BeatWaveform(val height: Int, val color: String)

@Serializable
data class BeatEvent(
    val player: Int,
    val beatNumber: Int?,
    val beatWithinBar: Int?,
    val tempo: Double?,
    val nextBeatInMs: Int?,
    val nextBarInMs: Int?,
    val isTempoMaster: Boolean,
    val position: BeatPosition?,
    val waveform: BeatWaveform?,
    val receivedAt: Long,
    @Transient override val id: Long = 0,
) : Event {
    override val name: String get() = "beat"
    override fun withId(id: Long): Event = copy(id = id)
}

/** SsePlayer は Player そのもの(allOf)なので、ラッパーを付けずフラットに直列化する。 */
object PlayerEventSerializer : KSerializer<PlayerEvent> {
    override val descriptor: SerialDescriptor = Player.serializer().descriptor
    override fun serialize(encoder: Encoder, value: PlayerEvent) =
        encoder.encodeSerializableValue(Player.serializer(), value.player)
    override fun deserialize(decoder: Decoder): PlayerEvent =
        PlayerEvent(decoder.decodeSerializableValue(Player.serializer()))
}

@Serializable(with = PlayerEventSerializer::class)
data class PlayerEvent(val player: Player, override val id: Long = 0) : Event {
    override val name: String get() = "player"
    override fun withId(id: Long): Event = copy(id = id)
}

@Serializable
data class TrackEvent(val player: Int, val track: Track?, @Transient override val id: Long = 0) : Event {
    override val name: String get() = "track"
    override fun withId(id: Long): Event = copy(id = id)
}

@Serializable
data class DeviceEvent(val action: String, val device: Device, @Transient override val id: Long = 0) : Event {
    override val name: String get() = "device"
    override fun withId(id: Long): Event = copy(id = id)
}

@Serializable
data class MasterEvent(val player: Int?, val tempo: Double?, @Transient override val id: Long = 0) : Event {
    override val name: String get() = "master"
    override fun withId(id: Long): Event = copy(id = id)
}

@Serializable
data class OnlineEvent(val online: Boolean, @Transient override val id: Long = 0) : Event {
    override val name: String get() = "online"
    override fun withId(id: Long): Event = copy(id = id)
}

class EventBus {
    private val flow = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val nextId = AtomicLong(0)

    val events: SharedFlow<Event> = flow

    /** id を採番して送る。遅い購読者には古いイベントが落ちる。 */
    fun publish(e: Event) {
        flow.tryEmit(e.withId(nextId.incrementAndGet()))
    }
}
