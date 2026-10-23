package cdjmusiclink.server.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class DtoJsonTest {
    private val player = Player(
        number = 2, name = "CDJ-2000nexus", address = "192.168.1.12", updatedAt = 1000L,
        playState = PlayState.playing, isPlaying = true, isPaused = false, isCued = false,
        isLooping = false, isOnAir = null, isSynced = false, isTempoMaster = true,
        trackBpm = null, pitch = 1.25, tempo = null, beatNumber = null, beatWithinBar = null,
        position = null, track = null,
    )

    @Test
    fun playerSerializesNullsExplicitly() {
        val obj = ApiJson.json.encodeToJsonElement(Player.serializer(), player).jsonObject
        assertEquals(JsonNull, obj["isOnAir"])
        assertEquals(JsonNull, obj["track"])
        assertEquals(JsonNull, obj["position"])
        assertEquals("playing", obj["playState"]?.jsonPrimitive?.content)
        assertEquals(19, obj.size)
    }

    @Test
    fun trackEnumsUseLowerCaseNames() {
        val track = Track(
            id = 7, sourcePlayer = 1, slot = Slot.usb, type = TrackType.rekordbox,
            title = null, artist = null, album = null, key = null, genre = null, label = null,
            remixer = null, originalArtist = null, comment = null, durationSec = null,
            startingBpm = null, rating = null, year = null, dateAdded = null, color = Color("Pink", "#ff5aa8"),
            artworkAvailable = false, waveformAvailable = false, beatgridAvailable = false,
        )
        val obj: JsonObject = ApiJson.json.encodeToJsonElement(Track.serializer(), track).jsonObject
        assertEquals(JsonPrimitive("usb"), obj["slot"])
        assertEquals(JsonPrimitive("rekordbox"), obj["type"])
        assertEquals(JsonNull, obj["title"])
        assertEquals(22, obj.size)
    }

    @Test
    fun deviceKindSerializesLowerCase() {
        val s = ApiJson.json.encodeToString(Device.serializer(), Device(33, "DJM", "10.0.0.1", DeviceKind.mixer))
        assertEquals("""{"number":33,"name":"DJM","address":"10.0.0.1","kind":"mixer"}""", s)
    }
}
