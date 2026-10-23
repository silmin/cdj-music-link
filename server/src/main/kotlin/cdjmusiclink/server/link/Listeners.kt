package cdjmusiclink.server.link

import cdjmusiclink.server.model.Device
import cdjmusiclink.server.model.deviceKindOf
import cdjmusiclink.server.model.slotOf
import cdjmusiclink.server.model.trackTypeOf
import cdjmusiclink.server.state.BeatEvent
import cdjmusiclink.server.state.BeatPosition
import cdjmusiclink.server.state.BeatWaveform
import cdjmusiclink.server.state.Change
import cdjmusiclink.server.state.StatusSnapshot
import cdjmusiclink.server.state.Store
import kotlinx.coroutines.CoroutineScope
import org.deepsymmetry.beatlink.Beat
import org.deepsymmetry.beatlink.BeatFinder
import org.deepsymmetry.beatlink.BeatListener
import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.DeviceAnnouncement
import org.deepsymmetry.beatlink.DeviceAnnouncementListener
import org.deepsymmetry.beatlink.DeviceFinder
import org.deepsymmetry.beatlink.DeviceUpdate
import org.deepsymmetry.beatlink.DeviceUpdateListener
import org.deepsymmetry.beatlink.MasterListener
import org.deepsymmetry.beatlink.Util
import org.deepsymmetry.beatlink.VirtualCdj
import org.deepsymmetry.beatlink.data.AlbumArtListener
import org.deepsymmetry.beatlink.data.AlbumArtUpdate
import org.deepsymmetry.beatlink.data.ArtFinder
import org.deepsymmetry.beatlink.data.BeatGridFinder
import org.deepsymmetry.beatlink.data.BeatGridListener
import org.deepsymmetry.beatlink.data.BeatGridUpdate
import org.deepsymmetry.beatlink.data.MetadataFinder
import org.deepsymmetry.beatlink.data.TimeFinder
import org.deepsymmetry.beatlink.data.TrackMetadata
import org.deepsymmetry.beatlink.data.TrackMetadataListener
import org.deepsymmetry.beatlink.data.TrackMetadataUpdate
import org.deepsymmetry.beatlink.data.TrackPositionListener
import org.deepsymmetry.beatlink.data.TrackPositionUpdate
import org.deepsymmetry.beatlink.data.WaveformDetailUpdate
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformListener
import org.deepsymmetry.beatlink.data.WaveformPreviewUpdate
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** beat-link のコールバックを Store の呼び出しに変換する。beat-link の型はこのパッケージの外に出さない。 */
object Listeners {
    private val log = LoggerFactory.getLogger(Listeners::class.java)
    private const val PLAYER_LIMIT = 0x10
    private const val MAX_INT_MS = 0x7fffffffL

    /** 登録済みリスナーのハンドル。 */
    class Attached internal constructor(
        private val announcement: DeviceAnnouncementListener,
        private val update: DeviceUpdateListener,
        private val beat: BeatListener,
        private val master: MasterListener,
        private val metadata: TrackMetadataListener,
        private val art: AlbumArtListener,
        private val waveform: WaveformListener,
        private val beatGrid: BeatGridListener,
        private val positions: ConcurrentHashMap<Int, TrackPositionListener>,
    ) {
        fun detach() {
            DeviceFinder.getInstance().removeDeviceAnnouncementListener(announcement)
            VirtualCdj.getInstance().removeUpdateListener(update)
            VirtualCdj.getInstance().removeMasterListener(master)
            BeatFinder.getInstance().removeBeatListener(beat)
            MetadataFinder.getInstance().removeTrackMetadataListener(metadata)
            ArtFinder.getInstance().removeAlbumArtListener(art)
            WaveformFinder.getInstance().removeWaveformListener(waveform)
            BeatGridFinder.getInstance().removeBeatGridListener(beatGrid)
            positions.values.forEach { TimeFinder.getInstance().removeTrackPositionListener(it) }
            positions.clear()
            synchronized(Listeners) { if (current === this) current = null }
        }
    }

    private var current: Attached? = null

    fun snapshotOf(s: CdjStatus): StatusSnapshot = StatusSnapshot(
        playing = s.isPlaying,
        paused = s.isPaused,
        cued = s.isCued,
        looping = s.isLooping,
        searching = s.isSearching,
        atEnd = s.isAtEnd,
        busy = s.isBusy,
        trackLoaded = s.isTrackLoaded,
        onAir = s.isOnAir,
        synced = s.isSynced,
        master = s.isTempoMaster,
        bpmX100 = s.bpm,
        pitchPct = Util.pitchToPercentage(s.pitch.toLong()),
        effectiveTempo = s.effectiveTempo,
        beatNumber = s.beatNumber.takeIf { it > 0 },
        beatWithinBar = if (s.isBeatWithinBarMeaningful) s.beatWithinBar else null,
        rekordboxId = s.rekordboxId,
        sourcePlayer = s.trackSourcePlayer,
        slot = slotOf(s.trackSourceSlot),
        type = trackTypeOf(s.trackType),
    )

    fun beatEventOf(
        b: Beat,
        positionMs: Long?,
        waveform: Pair<Int, String>?,
        now: Long,
        isTempoMaster: Boolean,
        beatNumber: Int? = null,
    ): BeatEvent = BeatEvent(
        player = b.deviceNumber,
        beatNumber = beatNumber,
        beatWithinBar = if (b.isBeatWithinBarMeaningful) b.beatWithinBar else null,
        tempo = b.effectiveTempo,
        nextBeatInMs = b.nextBeat.takeIf { it in 1L..MAX_INT_MS }?.toInt(),
        nextBarInMs = b.nextBar.takeIf { it in 1L..MAX_INT_MS }?.toInt(),
        isTempoMaster = isTempoMaster,
        position = positionMs?.let { BeatPosition(it) },
        waveform = waveform?.let { BeatWaveform(it.first, it.second) },
        receivedAt = now,
    )

    private fun totalMs(player: Int): Long? =
        WaveformFinder.getInstance().getLatestDetailFor(player)?.totalTime
            ?: MetadataFinder.getInstance().getLatestMetadataFor(player)?.duration?.takeIf { it > 0 }?.times(1000L)

    private fun trackOf(player: Int, md: TrackMetadata) = md.toTrack(
        md.trackReference,
        artwork = ArtFinder.getInstance().getLatestArtFor(player) != null,
        waveform = WaveformFinder.getInstance().getLatestPreviewFor(player) != null,
        beatgrid = BeatGridFinder.getInstance().getLatestBeatGridFor(player) != null,
    )

    /**
     * 曲替わりでスタブを作った直後に、MetadataFinder が既に持っているメタデータを引いて当てる。
     * メタデータがステータスより先に届いた場合、beat-link は再配送しないため。
     */
    private fun pullMetadata(store: Store, s: CdjStatus) {
        val finder = MetadataFinder.getInstance()
        if (!finder.isRunning) return
        val n = s.deviceNumber
        val md = finder.getLatestMetadataFor(n) ?: return
        val ref = md.trackReference
        if (ref.rekordboxId == s.rekordboxId && ref.player == s.trackSourcePlayer && ref.slot == s.trackSourceSlot) {
            store.metadata(n, trackOf(n, md))
        }
    }

    /**
     * 全リスナーを登録する。冪等で、登録済みなら既存のハンドルを返す。
     * [onAllDevicesLost] は deviceLost 後にデバイスが 1 台も残っていないとき呼ばれる。
     */
    @Synchronized
    fun attach(
        store: Store,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        onAllDevicesLost: () -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
    ): Attached {
        current?.let { return it }

        val positions = ConcurrentHashMap<Int, TrackPositionListener>()
        val masterRef = java.util.concurrent.atomic.AtomicReference<Int?>(null)

        fun guarded(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                log.warn("listener failed: {}", what, e)
            }
        }

        val announcement = object : DeviceAnnouncementListener {
            override fun deviceFound(a: DeviceAnnouncement) = guarded("deviceFound") {
                val n = a.deviceNumber
                store.upsertDevice(Device(n, a.deviceName, a.address.hostAddress, deviceKindOf(n)))
                if (n < PLAYER_LIMIT) {
                    positions.computeIfAbsent(n) {
                        // 位置不明時（登録直後を含む）は null が来る。非 null 型にすると Kotlin の引数チェックで
                        // addTrackPositionListener ごと例外になり、TimeFinder 側に登録だけが残る。
                        TrackPositionListener { u: TrackPositionUpdate? ->
                            guarded("movementChanged") {
                                if (u != null) store.position(n, u.milliseconds, u.definitive, totalMs(n)) else store.clearPosition(n)
                            }
                        }.also { TimeFinder.getInstance().addTrackPositionListener(n, it) }
                    }
                }
            }

            override fun deviceLost(a: DeviceAnnouncement) = guarded("deviceLost") {
                val n = a.deviceNumber
                positions.remove(n)?.let { TimeFinder.getInstance().removeTrackPositionListener(it) }
                store.removeDevice(n)
                if (DeviceFinder.getInstance().currentDevices.isEmpty()) onAllDevicesLost()
            }
        }

        val update = DeviceUpdateListener { u: DeviceUpdate ->
            if (u is CdjStatus) {
                guarded("status") {
                    val changes = store.status(u.deviceNumber, snapshotOf(u))
                    if (Change.TRACK in changes && u.isTrackLoaded) pullMetadata(store, u)
                }
            }
        }

        val beat = BeatListener { b: Beat ->
            guarded("newBeat") {
                val n = b.deviceNumber
                val time = TimeFinder.getInstance()
                val positionMs = if (time.isRunning) time.getTimeFor(n).takeIf { it >= 0 } else null
                val waveform = positionMs?.let { ms ->
                    val total = totalMs(n)
                    val preview = WaveformFinder.getInstance().getLatestPreviewFor(n)
                    if (preview != null && total != null) preview.columnAt(ms, total) else null
                }
                val beatNumber = if (time.isRunning) time.getLatestPositionFor(n)?.beatNumber?.takeIf { it > 0 } else null
                val master = VirtualCdj.getInstance().isRunning && b.isTempoMaster
                store.beat(n, beatEventOf(b, positionMs, waveform, clock(), master, beatNumber))
            }
        }

        val master = object : MasterListener {
            override fun masterChanged(u: DeviceUpdate?) = guarded("masterChanged") {
                masterRef.set(u?.deviceNumber)
                store.master(masterRef.get(), (u as? CdjStatus)?.effectiveTempo)
            }

            override fun tempoChanged(tempo: Double) = guarded("tempoChanged") { store.master(masterRef.get(), tempo) }
            override fun newBeat(b: Beat) = Unit
        }

        val metadata = TrackMetadataListener { u: TrackMetadataUpdate ->
            guarded("metadataChanged") {
                val p = u.player
                store.metadata(p, u.metadata?.let { trackOf(p, it) })
            }
        }

        val art = AlbumArtListener { u: AlbumArtUpdate ->
            guarded("albumArtChanged") { store.availability(u.player, u.art != null, null, null) }
        }

        val waveform = object : WaveformListener {
            override fun previewChanged(u: WaveformPreviewUpdate) = guarded("previewChanged") {
                store.availability(u.player, null, u.preview != null, null)
            }

            override fun detailChanged(u: WaveformDetailUpdate) = Unit
        }

        val beatGrid = BeatGridListener { u: BeatGridUpdate ->
            guarded("beatGridChanged") { store.availability(u.player, null, null, u.beatGrid != null) }
        }

        DeviceFinder.getInstance().addDeviceAnnouncementListener(announcement)
        VirtualCdj.getInstance().addUpdateListener(update)
        VirtualCdj.getInstance().addMasterListener(master)
        BeatFinder.getInstance().addBeatListener(beat)
        MetadataFinder.getInstance().addTrackMetadataListener(metadata)
        ArtFinder.getInstance().addAlbumArtListener(art)
        WaveformFinder.getInstance().addWaveformListener(waveform)
        BeatGridFinder.getInstance().addBeatGridListener(beatGrid)

        return Attached(announcement, update, beat, master, metadata, art, waveform, beatGrid, positions)
            .also { current = it }
    }
}
