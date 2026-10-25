package cdjmusiclink.server.link

import cdjmusiclink.server.model.BeatDto
import cdjmusiclink.server.model.BeatGridDto
import cdjmusiclink.server.model.Color
import cdjmusiclink.server.model.Track
import cdjmusiclink.server.model.WaveformDetailDto
import cdjmusiclink.server.model.WaveformPreviewDto
import cdjmusiclink.server.model.slotOf
import cdjmusiclink.server.model.trackTypeOf
import org.deepsymmetry.beatlink.data.BeatGrid
import org.deepsymmetry.beatlink.data.DataReference
import org.deepsymmetry.beatlink.data.TrackMetadata
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle
import org.deepsymmetry.beatlink.data.WaveformPreview

private const val DETAIL_FRAMES_PER_SECOND = 150

/** Blank (empty or whitespace-only) strings mean "not known" and map to null. */
fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

/** 0 means "unknown" for duration and year in rekordbox exports. */
fun Int.zeroToNull(): Int? = takeIf { it != 0 }

fun colorHex(c: java.awt.Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

private fun WaveformStyle.dtoName(): String = when (this) {
    WaveformStyle.BLUE -> "blue"
    WaveformStyle.RGB -> "rgb"
    WaveformStyle.THREE_BAND -> "threeBand"
}

fun WaveformPreview.toDto(): WaveformPreviewDto = WaveformPreviewDto(
    style = style.dtoName(),
    segments = segmentCount,
    maxHeight = maxHeight,
    heights = List(segmentCount) { segmentHeight(it, true) },
    colors = List(segmentCount) { colorHex(segmentColor(it, true)) },
)

fun WaveformDetail.toDto(fromMs: Long? = null, toMs: Long? = null): WaveformDetailDto {
    val total = getTotalTime()
    val from = (fromMs ?: 0L).coerceIn(0L, total)
    val to = (toMs ?: total).coerceIn(from, total)
    val startFrame = (from * DETAIL_FRAMES_PER_SECOND / 1000).toInt()
    val endFrame = minOf((to * DETAIL_FRAMES_PER_SECOND / 1000).toInt(), getFrameCount())
    val frames = startFrame until maxOf(startFrame, endFrame)
    return WaveformDetailDto(
        style = style.dtoName(),
        framesPerSecond = DETAIL_FRAMES_PER_SECOND,
        fromMs = from,
        toMs = to,
        heights = frames.map { segmentHeight(it, 1) },
        colors = frames.map { colorHex(segmentColor(it, 1)) },
    )
}

fun BeatGrid.toDto(): BeatGridDto = BeatGridDto(
    (1..beatCount).map { n ->
        BeatDto(
            number = n,
            ms = getTimeWithinTrack(n),
            beatWithinBar = getBeatWithinBar(n),
            bpm = getBpm(n) / 100.0,
        )
    },
)

fun WaveformPreview.columnAt(ms: Long, totalMs: Long): Pair<Int, String> {
    val segment = if (totalMs <= 0) 0 else (ms * segmentCount / totalMs).toInt().coerceIn(0, segmentCount - 1)
    return Pair(segmentHeight(segment, true), colorHex(segmentColor(segment, true)))
}

fun TrackMetadata.toTrack(ref: DataReference, artwork: Boolean, waveform: Boolean, beatgrid: Boolean): Track {
    val itemColor = getColor()
    val awtColor = itemColor?.color
    return Track(
        id = ref.rekordboxId,
        sourcePlayer = ref.player,
        slot = slotOf(ref.slot),
        type = trackTypeOf(trackType),
        title = getTitle().blankToNull(),
        artist = getArtist()?.label.blankToNull(),
        album = getAlbum()?.label.blankToNull(),
        key = getKey()?.label.blankToNull(),
        genre = getGenre()?.label.blankToNull(),
        label = getLabel()?.label.blankToNull(),
        remixer = getRemixer()?.label.blankToNull(),
        originalArtist = getOriginalArtist()?.label.blankToNull(),
        comment = getComment().blankToNull(),
        durationSec = getDuration().zeroToNull(),
        startingBpm = getTempo().takeIf { it != 0 }?.let { it / 100.0 },
        rating = getRating(),
        year = getYear().zeroToNull(),
        dateAdded = getDateAdded().blankToNull(),
        color = if (itemColor != null && awtColor != null) Color(itemColor.colorName, colorHex(awtColor)) else null,
        artworkAvailable = artwork,
        waveformAvailable = waveform,
        beatgridAvailable = beatgrid,
    )
}
