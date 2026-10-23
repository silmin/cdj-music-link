package cdjmusiclink.fakecdj

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress

@Serializable private data class TempoBody(val bpm: Double)
@Serializable private data class LoadBody(val trackId: Int, val slot: Int, val trackType: Int)
@Serializable private data class MasterBody(val master: Boolean)
@Serializable private data class OnAirBody(val onAir: Boolean)

/** HTTP control surface for tests: mutate the [Deck] and read its state. */
class Control(
    private val config: FakeConfig,
    private val deck: Deck,
    private val peers: Peers,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var server: HttpServer? = null

    fun start() {
        val s = HttpServer.create(InetSocketAddress(config.controlPort), 0)
        s.createContext("/") { ex -> handle(ex) }
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
    }

    private fun handle(ex: HttpExchange) {
        ex.use {
            try {
                val route = "${ex.requestMethod} ${ex.requestURI.path}"
                val body = ex.requestBody.readBytes().decodeToString()
                val now = clock()
                when (route) {
                    "GET /state" -> return reply(ex, 200, state(now))
                    "POST /play" -> deck.play(now)
                    "POST /pause" -> deck.pause(now)
                    "POST /unload" -> deck.unload()
                    "POST /tempo" -> deck.setTempo(json.decodeFromString<TempoBody>(body).bpm, now)
                    "POST /load" -> json.decodeFromString<LoadBody>(body).let { deck.load(it.trackId, it.slot, it.trackType) }
                    "POST /master" -> deck.master = json.decodeFromString<MasterBody>(body).master
                    "POST /onair" -> deck.onAir = json.decodeFromString<OnAirBody>(body).onAir
                    else -> return reply(ex, 404, """{"error":"not found"}""")
                }
                reply(ex, 200, """{"ok":true}""")
            } catch (e: Exception) {
                val msg = JsonPrimitive(e.message ?: e.javaClass.simpleName).toString()
                reply(ex, 400, """{"error":$msg}""")
            }
        }
    }

    private fun state(now: Long): String {
        val st = deck.snapshot(now)
        return buildJsonObject {
            put("number", config.number)
            put("name", config.name)
            put("playing", st.playing)
            put("master", st.master)
            put("synced", st.synced)
            put("onAir", st.onAir)
            put("bpm", st.bpm)
            put("beatNumber", st.beatNumber)
            put("beatWithinBar", st.beatWithinBar)
            put("trackId", st.trackId)
            put("trackLoaded", st.trackLoaded)
            put("slot", st.slot)
            put("trackType", st.trackType)
            put("peers", JsonArray(peers.addresses().map { it.hostAddress }.sorted().map { JsonPrimitive(it) }))
        }.toString()
    }

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
