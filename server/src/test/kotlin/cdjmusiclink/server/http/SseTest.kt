package cdjmusiclink.server.http

import cdjmusiclink.server.model.ApiJson
import cdjmusiclink.server.model.Player
import cdjmusiclink.server.state.BeatEvent
import cdjmusiclink.server.state.BeatPosition
import cdjmusiclink.server.state.EventBus
import cdjmusiclink.server.state.PlayerEvent
import cdjmusiclink.server.state.Store
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.swagger.v3.parser.OpenAPIV3Parser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 実サーバー(CIO、空きポート)に JDK の HttpClient で繋ぎ、`text/event-stream` のワイヤ形式をそのまま読む。
 * testApplication の SSE クライアントは無限ストリームでヘッダーを返さず固まるため使わない。
 */
class SseTest {
    /** 1 フレーム分のフィールド。コメント行は除く。 */
    private class Frame(val fields: Map<String, String>) {
        val event get() = fields["event"]
        val id get() = fields["id"]
        val retry get() = fields["retry"]
        val data get() = checkNotNull(fields["data"])
    }

    private class Stream(val response: HttpResponse<java.io.InputStream>) : AutoCloseable {
        private val reader = BufferedReader(InputStreamReader(response.body(), Charsets.UTF_8))

        /** 空行で区切られた次のフレームを読む。5 秒で来なければ失敗にする。 */
        fun next(): Frame {
            val f = CompletableFuture.supplyAsync {
                val fields = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: error("stream closed")
                    if (line.isEmpty()) {
                        if (fields.isNotEmpty()) break
                        continue
                    }
                    if (line.startsWith(":")) continue
                    val i = line.indexOf(':')
                    val key = line.substring(0, i)
                    val value = line.substring(i + 1).removePrefix(" ")
                    fields[key] = if (key == "data" && key in fields) fields[key] + "\n" + value else value
                }
                Frame(fields)
            }
            return f.get(5, TimeUnit.SECONDS)
        }

        override fun close() = response.body().close()
    }

    private fun withStream(store: Store = filledStore(), bus: EventBus = EventBus(), block: (EventBus, Stream) -> Unit) {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            apiModule(store, FakeSession(), FixtureAssets(), bus, clock = { 1_700_000_000_000 })
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val res = HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/stream")).build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            ).get(5, TimeUnit.SECONDS)
            Stream(res).use { block(bus, it) }
        } finally {
            server.stop(0, 0)
        }
    }

    private fun beat() = BeatEvent(1, 5, 2, 128.0, 100, 1000, true, BeatPosition(1234), null, 1_700_000_000_000)

    @Test
    fun firstEventIsSnapshot() = withStream { _, s ->
        val f = s.next()
        assertEquals("snapshot", f.event)
        assertEquals("2000", f.retry)
        assertEquals("0", f.id)
        val snap = ApiJson.json.decodeFromString<SnapshotPayload>(f.data)
        assertEquals(listOf(1), snap.players.map { it.number })
        assertEquals(1, snap.master.player)
        assertEquals(2, snap.devices.size)
        assertEquals(1_700_000_000_000, snap.serverTime)
    }

    @Test
    fun retryHeaderIs2000() = withStream { _, s ->
        val h = s.response.headers()
        assertTrue(h.firstValue("Content-Type").get().startsWith("text/event-stream"))
        assertEquals("no", h.firstValue("X-Accel-Buffering").get())
        assertEquals("2000", s.next().retry)
    }

    @Test
    fun publishedBeatArrivesWithEventName() = withStream { bus, s ->
        s.next() // snapshot(購読確立後に送られるので、この後の publish は取りこぼさない)
        bus.publish(beat())
        val f = s.next()
        assertEquals("beat", f.event)
        assertEquals("1", f.id)
        assertEquals(beat(), ApiJson.json.decodeFromString<BeatEvent>(f.data))
        val spec = OpenAPIV3Parser().read("../openapi/openapi.yaml")
        val expected = spec.components.schemas["SseBeat"]!!.properties.keys
        assertEquals(expected, Json.parseToJsonElement(f.data).jsonObject.keys)
    }

    @Test
    fun playerEventIsFlatPlayer() = withStream { bus, s ->
        val snap = ApiJson.json.decodeFromString<SnapshotPayload>(s.next().data)
        bus.publish(PlayerEvent(snap.players.single()))
        val f = s.next()
        assertEquals("player", f.event)
        val obj: JsonObject = Json.parseToJsonElement(f.data).jsonObject
        assertTrue("number" in obj.keys && "type" !in obj.keys, obj.keys.toString())
        assertEquals(snap.players.single(), ApiJson.json.decodeFromString<Player>(f.data))
    }
}
