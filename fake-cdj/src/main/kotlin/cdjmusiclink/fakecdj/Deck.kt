package cdjmusiclink.fakecdj

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Immutable view of a [Deck] taken under its lock. */
data class DeckState(
    val playing: Boolean,
    val master: Boolean,
    val synced: Boolean,
    val onAir: Boolean,
    val bpm: Double,
    val beatNumber: Int,
    val beatWithinBar: Int,
    val trackId: Int,
    val trackLoaded: Boolean,
    val slot: Int,
    val trackType: Int,
    val sourcePlayer: Int,
) {
    val bpmX100: Int get() = (bpm * 100).roundToInt()
    val beatIntervalMs: Int get() = (60000.0 / bpm).roundToInt()
}

/**
 * Simulated CDJ deck. Time is injected (`nowMs`) so the beat clock is deterministic. Pitch is fixed neutral, so the
 * beat interval is `60000 / bpm`. All methods are synchronized on the instance.
 */
class Deck(bpm: Double, trackId: Int, slot: Int, trackType: Int, val number: Int, sourcePlayer: Int? = null) {
    var playing = false
        private set
    var master = false
        @Synchronized set
        @Synchronized get
    var synced = false
        @Synchronized set
        @Synchronized get
    var onAir = false
        @Synchronized set
        @Synchronized get
    var bpm = bpm
        private set
    var trackId = trackId
        private set
    var slot = slot
        private set
    var trackType = trackType
        private set
    var sourcePlayer = sourcePlayer ?: number
        private set
    var trackLoaded = true
        private set
    var beatNumber = 1
        private set

    private var anchorMs = 0L
    private var anchorBeat = 1
    private var lastNowMs = 0L

    val beatWithinBar: Int @Synchronized get() = ((beatNumber - 1) % 4) + 1

    init {
        require(bpm > 0) { "bpm must be positive: $bpm" }
    }

    private val intervalMs: Double get() = 60000.0 / bpm

    @Synchronized
    fun advance(nowMs: Long) {
        lastNowMs = nowMs
        if (!playing) return
        beatNumber = anchorBeat + floor(beatsSinceAnchor(nowMs)).toInt()
    }

    private fun beatsSinceAnchor(nowMs: Long) = (nowMs - anchorMs).coerceAtLeast(0) * bpm / 60000.0

    /** Milliseconds until the next beat boundary, in `1..interval` (the full interval while paused). */
    @Synchronized
    fun msUntilNextBeat(nowMs: Long): Int {
        val max = ceil(intervalMs).toInt()
        if (!playing) return max
        val beats = beatsSinceAnchor(nowMs)
        val remaining = 1.0 - (beats - floor(beats))
        return ceil(remaining * intervalMs).toInt().coerceIn(1, max)
    }

    @Synchronized
    fun play(nowMs: Long) {
        if (playing || !trackLoaded) return
        lastNowMs = nowMs
        anchorBeat = beatNumber
        anchorMs = nowMs
        playing = true
    }

    @Synchronized
    fun pause(nowMs: Long) {
        advance(nowMs)
        playing = false
    }

    /** Changes tempo while keeping the position inside the current beat. */
    @Synchronized
    fun setTempo(newBpm: Double, nowMs: Long) {
        require(newBpm > 0) { "bpm must be positive: $newBpm" }
        advance(nowMs)
        if (playing) {
            val beats = beatsSinceAnchor(nowMs)
            val phase = beats - floor(beats)
            bpm = newBpm
            anchorBeat = beatNumber
            anchorMs = nowMs - (phase * intervalMs).toLong()
        } else {
            bpm = newBpm
        }
    }

    /** Loads a track: back to beat 1, `playing` unchanged. */
    @Synchronized
    fun load(trackId: Int, slot: Int, trackType: Int, sourcePlayer: Int? = null) {
        this.trackId = trackId
        this.slot = slot
        this.trackType = trackType
        this.sourcePlayer = sourcePlayer ?: number
        trackLoaded = true
        beatNumber = 1
        anchorBeat = 1
        anchorMs = lastNowMs
    }

    @Synchronized
    fun unload() {
        trackLoaded = false
        playing = false
    }

    @Synchronized
    fun snapshot(nowMs: Long): DeckState {
        advance(nowMs)
        return DeckState(playing, master, synced, onAir, bpm, beatNumber, beatWithinBar, trackId, trackLoaded, slot, trackType, sourcePlayer)
    }
}
