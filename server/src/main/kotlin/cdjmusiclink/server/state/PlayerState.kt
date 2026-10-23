package cdjmusiclink.server.state

import cdjmusiclink.server.model.PlayState
import cdjmusiclink.server.model.Player
import cdjmusiclink.server.model.Position
import cdjmusiclink.server.model.Slot
import cdjmusiclink.server.model.Track
import cdjmusiclink.server.model.TrackType
import cdjmusiclink.server.model.playStateOf

/** CdjStatus から取り出した純データ。変換は link 層の責務。 */
data class StatusSnapshot(
    val playing: Boolean,
    val paused: Boolean,
    val cued: Boolean,
    val looping: Boolean,
    val searching: Boolean,
    val atEnd: Boolean,
    val busy: Boolean,
    val trackLoaded: Boolean,
    val onAir: Boolean,
    val synced: Boolean,
    val master: Boolean,
    val bpmX100: Int,
    val pitchPct: Double,
    val effectiveTempo: Double?,
    val beatNumber: Int?,
    val beatWithinBar: Int?,
    val rekordboxId: Int,
    val sourcePlayer: Int,
    val slot: Slot,
    val type: TrackType,
)

enum class Change { PLAY_STATE, FLAGS, TRACK, MASTER }

/** 1 台分の状態。スレッド安全性は呼び出し側(Store)が持つ。 */
class PlayerState(val number: Int, val name: String, val address: String) {
    private var updatedAt = 0L
    private var playState = PlayState.empty
    private var playing = false
    private var paused = false
    private var cued = false
    private var looping = false
    private var onAir: Boolean? = null
    private var synced = false
    private var master = false
    private var trackBpm: Double? = null
    private var pitch = 0.0
    private var tempo: Double? = null
    private var beatNumber: Int? = null
    private var beatWithinBar: Int? = null
    private var position: Position? = null
    private var track: Track? = null
    private var trackLoaded = false

    /** 現在のスタブと一致しなかった最新のメタデータ。後から一致するスタブができたら適用する。 */
    private var pendingMetadata: Track? = null

    fun applyStatus(s: StatusSnapshot, mixerPresent: Boolean, now: Long): Set<Change> {
        val changes = mutableSetOf<Change>()

        val newPlayState = playStateOf(s.playing, s.paused, s.cued, s.searching, s.atEnd, s.busy, s.trackLoaded)
        if (newPlayState != playState) changes += Change.PLAY_STATE
        val newOnAir = if (mixerPresent) s.onAir else null
        if (newOnAir != onAir || s.synced != synced || s.master != master || s.looping != looping) {
            changes += Change.FLAGS
        }
        if (s.master != master) changes += Change.MASTER

        playState = newPlayState
        playing = s.playing
        paused = s.paused
        cued = s.cued
        looping = s.looping
        onAir = newOnAir
        synced = s.synced
        master = s.master
        trackBpm = if (s.bpmX100 == 0xffff || !s.trackLoaded) null else s.bpmX100 / 100.0
        pitch = s.pitchPct
        tempo = if (s.trackLoaded) s.effectiveTempo else null
        beatNumber = s.beatNumber
        beatWithinBar = s.beatWithinBar
        updatedAt = now
        trackLoaded = s.trackLoaded

        val current = track
        if (s.trackLoaded) {
            val changed = current == null || current.id != s.rekordboxId || current.sourcePlayer != s.sourcePlayer ||
                current.slot != s.slot || current.type != s.type
            if (changed) {
                val stub = stub(s)
                val pending = pendingMetadata
                track = if (pending != null && sameTrack(stub, pending)) {
                    pendingMetadata = null
                    merge(stub, pending)
                } else {
                    stub
                }
                changes += Change.TRACK
            }
        } else if (current != null) {
            track = null
            position = null
            pendingMetadata = null
            changes += Change.TRACK
        }
        return changes
    }

    fun applyPosition(ms: Long, definitive: Boolean, totalMs: Long?, now: Long) {
        position = Position(ms, totalMs?.let { it - ms }, now, definitive)
    }

    fun clearPosition() {
        position = null
    }

    /**
     * 現在のトラックと同一なら置き換える。一致しなければ保留し、後で一致するスタブができたときに適用する。
     * null は直近のステータスで曲がロードされていれば無視する(アンロードは [applyStatus] が扱う)。
     * MetadataFinder は曲替わりの null と新しいメタデータをステータスと前後して届けるため。変化があれば true。
     */
    fun applyMetadata(track: Track?): Boolean {
        val current = this.track
        if (track == null) {
            if (trackLoaded) return false
            pendingMetadata = null
            this.track = null
            return current != null
        }
        if (current == null || !sameTrack(current, track)) {
            pendingMetadata = track
            return false
        }
        val merged = merge(current, track)
        this.track = merged
        return merged != current
    }

    /** null の項目は変更しない。変化があれば true。 */
    fun applyAvailability(artwork: Boolean?, waveform: Boolean?, beatgrid: Boolean?): Boolean {
        val current = track ?: return false
        val updated = current.copy(
            artworkAvailable = artwork ?: current.artworkAvailable,
            waveformAvailable = waveform ?: current.waveformAvailable,
            beatgridAvailable = beatgrid ?: current.beatgridAvailable,
        )
        track = updated
        return updated != current
    }

    fun track(): Track? = track

    fun toDto(): Player = Player(
        number = number, name = name, address = address, updatedAt = updatedAt,
        playState = playState, isPlaying = playing, isPaused = paused, isCued = cued, isLooping = looping,
        isOnAir = onAir, isSynced = synced, isTempoMaster = master,
        trackBpm = trackBpm, pitch = pitch, tempo = tempo,
        beatNumber = beatNumber, beatWithinBar = beatWithinBar,
        position = position, track = track,
    )

    private fun sameTrack(a: Track, b: Track) = a.id == b.id && a.sourcePlayer == b.sourcePlayer && a.slot == b.slot

    /** メタデータで置き換えつつ、既に立っている可用性フラグは落とさない。 */
    private fun merge(current: Track, md: Track) = md.copy(
        artworkAvailable = md.artworkAvailable || current.artworkAvailable,
        waveformAvailable = md.waveformAvailable || current.waveformAvailable,
        beatgridAvailable = md.beatgridAvailable || current.beatgridAvailable,
    )

    private fun stub(s: StatusSnapshot) = Track(
        id = s.rekordboxId, sourcePlayer = s.sourcePlayer, slot = s.slot, type = s.type,
        title = null, artist = null, album = null, key = null, genre = null, label = null, remixer = null,
        originalArtist = null, comment = null, durationSec = null, startingBpm = null, rating = null,
        year = null, dateAdded = null, color = null,
        artworkAvailable = false, waveformAvailable = false, beatgridAvailable = false,
    )
}
