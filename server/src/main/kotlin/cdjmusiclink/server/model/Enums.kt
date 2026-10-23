package cdjmusiclink.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.deepsymmetry.beatlink.CdjStatus

@Serializable
enum class PlayState {
    @SerialName("playing") playing,
    @SerialName("paused") paused,
    @SerialName("cued") cued,
    @SerialName("searching") searching,
    @SerialName("loading") loading,
    @SerialName("ended") ended,
    @SerialName("empty") empty,
}

@Serializable
enum class Slot {
    @SerialName("usb") usb,
    @SerialName("sd") sd,
    @SerialName("cd") cd,
    @SerialName("collection") collection,
    @SerialName("none") none,
}

@Serializable
enum class TrackType {
    @SerialName("rekordbox") rekordbox,
    @SerialName("unanalyzed") unanalyzed,
    @SerialName("cd") cd,
    @SerialName("none") none,
}

@Serializable
enum class DeviceKind {
    @SerialName("player") player,
    @SerialName("mixer") mixer,
    @SerialName("collection") collection,
}

fun playStateOf(
    playing: Boolean,
    paused: Boolean,
    cued: Boolean,
    searching: Boolean,
    atEnd: Boolean,
    busy: Boolean,
    loaded: Boolean,
): PlayState = when {
    !loaded -> PlayState.empty
    atEnd -> PlayState.ended
    playing -> PlayState.playing
    searching -> PlayState.searching
    cued -> PlayState.cued
    paused -> PlayState.paused
    busy -> PlayState.loading
    else -> PlayState.paused
}

fun playStateOf(s: CdjStatus): PlayState = playStateOf(
    playing = s.isPlaying,
    paused = s.isPaused,
    cued = s.isCued,
    searching = s.isSearching,
    atEnd = s.isAtEnd,
    busy = s.isBusy,
    loaded = s.isTrackLoaded,
)

fun slotOf(s: CdjStatus.TrackSourceSlot): Slot = when (s) {
    CdjStatus.TrackSourceSlot.CD_SLOT -> Slot.cd
    CdjStatus.TrackSourceSlot.SD_SLOT -> Slot.sd
    CdjStatus.TrackSourceSlot.USB_SLOT -> Slot.usb
    CdjStatus.TrackSourceSlot.COLLECTION -> Slot.collection
    else -> Slot.none
}

fun trackTypeOf(t: CdjStatus.TrackType): TrackType = when (t) {
    CdjStatus.TrackType.CD_DIGITAL_AUDIO -> TrackType.cd
    CdjStatus.TrackType.REKORDBOX -> TrackType.rekordbox
    CdjStatus.TrackType.UNANALYZED -> TrackType.unanalyzed
    else -> TrackType.none
}

/** 0x10 未満 player、0x21〜0x28 mixer、他 collection(BLT overlay.clj の device-kind と同じ)。 */
fun deviceKindOf(number: Int): DeviceKind = when {
    number < 0x10 -> DeviceKind.player
    number in 0x21..0x28 -> DeviceKind.mixer
    else -> DeviceKind.collection
}
