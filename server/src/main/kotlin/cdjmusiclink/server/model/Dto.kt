package cdjmusiclink.server.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ApiJson {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
}

@Serializable
data class Health(
    val online: Boolean,
    val deviceNumber: Int?,
    val deviceCount: Int,
    val serverTime: Long,
    val version: String,
)

@Serializable
data class Device(val number: Int, val name: String, val address: String, val kind: DeviceKind)

@Serializable
data class Color(val name: String, val hex: String)

@Serializable
data class Position(val ms: Long, val remainingMs: Long?, val updatedAt: Long, val definitive: Boolean)

@Serializable
data class Track(
    val id: Int,
    val sourcePlayer: Int,
    val slot: Slot,
    val type: TrackType,
    val title: String?,
    val artist: String?,
    val album: String?,
    val key: String?,
    val genre: String?,
    val label: String?,
    val remixer: String?,
    val originalArtist: String?,
    val comment: String?,
    val durationSec: Int?,
    val startingBpm: Double?,
    val rating: Int?,
    val year: Int?,
    val dateAdded: String?,
    val color: Color?,
    val artworkAvailable: Boolean,
    val waveformAvailable: Boolean,
    val beatgridAvailable: Boolean,
)

@Serializable
data class Player(
    val number: Int,
    val name: String,
    val address: String,
    val updatedAt: Long,
    val playState: PlayState,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val isCued: Boolean,
    val isLooping: Boolean,
    val isOnAir: Boolean?,
    val isSynced: Boolean,
    val isTempoMaster: Boolean,
    val trackBpm: Double?,
    val pitch: Double,
    val tempo: Double?,
    val beatNumber: Int?,
    val beatWithinBar: Int?,
    val position: Position?,
    val track: Track?,
)

@Serializable
data class MasterInfo(val player: Int?, val tempo: Double?)

@Serializable
data class WaveformPreviewDto(
    val style: String,
    val segments: Int,
    val maxHeight: Int,
    val heights: List<Int>,
    val colors: List<String>,
)

@Serializable
data class WaveformDetailDto(
    val style: String,
    val framesPerSecond: Int,
    val fromMs: Long,
    val toMs: Long,
    val heights: List<Int>,
    val colors: List<String>,
)

@Serializable
data class BeatDto(val number: Int, val ms: Long, val beatWithinBar: Int, val bpm: Double)

@Serializable
data class BeatGridDto(val beats: List<BeatDto>)

@Serializable
data class ErrorDto(val error: String)
