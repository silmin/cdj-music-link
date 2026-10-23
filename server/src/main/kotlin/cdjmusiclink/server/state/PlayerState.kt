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

        val current = track
        if (s.trackLoaded) {
            val changed = current == null || current.id != s.rekordboxId || current.sourcePlayer != s.sourcePlayer ||
                current.slot != s.slot || current.type != s.type
            if (changed) {
                track = stub(s)
                changes += Change.TRACK
            }
        } else if (current != null) {
            track = null
            changes += Change.TRACK
        }
        return changes
    }

    fun applyPosition(ms: Long, definitive: Boolean, totalMs: Long?, now: Long) {
        position = Position(ms, totalMs?.let { it - ms }, now, definitive)
    }

    /** 現在のトラックと同一なら置き換える。null は解除。変化があれば true。 */
    fun applyMetadata(track: Track?): Boolean {
        val current = this.track
        if (track == null) {
            this.track = null
            return current != null
        }
        if (current == null || current.id != track.id || current.sourcePlayer != track.sourcePlayer ||
            current.slot != track.slot
        ) return false
        this.track = track
        return track != current
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

    private fun stub(s: StatusSnapshot) = Track(
        id = s.rekordboxId, sourcePlayer = s.sourcePlayer, slot = s.slot, type = s.type,
        title = null, artist = null, album = null, key = null, genre = null, label = null, remixer = null,
        originalArtist = null, comment = null, durationSec = null, startingBpm = null, rating = null,
        year = null, dateAdded = null, color = null,
        artworkAvailable = false, waveformAvailable = false, beatgridAvailable = false,
    )
}
