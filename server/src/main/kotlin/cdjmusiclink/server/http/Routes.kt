package cdjmusiclink.server.http

import cdjmusiclink.server.link.toDto
import cdjmusiclink.server.model.ApiJson
import cdjmusiclink.server.model.Health
import cdjmusiclink.server.model.Player
import cdjmusiclink.server.state.NowPlaying
import cdjmusiclink.server.state.Store
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private val PLAYER_RANGE = 1..15

fun Application.apiModule(store: Store, session: SessionInfo, assets: AssetSource) {
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Options)
    }
    install(ContentNegotiation) { json(ApiJson.json) }

    routing {
        route("/v1") {
            systemRoutes(store, session)
            playerRoutes(store, assets)
        }
        docsRoutes()
    }
}

private fun Route.systemRoutes(store: Store, session: SessionInfo) {
    get("/health") {
        call.respond(
            Health(
                online = session.online,
                deviceNumber = session.deviceNumber,
                deviceCount = store.devices().size,
                serverTime = System.currentTimeMillis(),
                version = session.version,
            ),
        )
    }
    get("/devices") { call.respond(store.devices()) }
    get("/master") { call.respond(store.masterInfo()) }
    get("/now-playing") {
        val p: Player? = NowPlaying.pick(store.players(), store.mixerPresent())
        if (p == null) call.respondText("null", ContentType.Application.Json) else call.respond(p)
    }
}

private suspend fun RoutingContext.playerNumber(): Int? {
    val n = call.parameters["n"]?.toIntOrNull()?.takeIf { it in PLAYER_RANGE }
    if (n == null) call.respondError(HttpStatusCode.NotFound, "player ${call.parameters["n"]} not found")
    return n
}

private fun sniffImage(bytes: ByteArray): ContentType = when {
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ContentType.Image.JPEG
    bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> ContentType.Image.PNG
    else -> ContentType.Application.OctetStream
}

private fun Route.playerRoutes(store: Store, assets: AssetSource) {
    get("/players") { call.respond(store.players().filter { it.number < 0x10 }) }

    route("/players/{n}") {
        get {
            val n = playerNumber() ?: return@get
            val player = store.player(n)
            if (player == null) call.respondError(HttpStatusCode.NotFound, "player $n not found") else call.respond(player)
        }
        get("/track") {
            val n = playerNumber() ?: return@get
            val track = store.player(n)?.track
            if (track == null) call.respondError(HttpStatusCode.NotFound, "track of player $n not found") else call.respond(track)
        }
        get("/artwork") {
            val n = playerNumber() ?: return@get
            val bytes = assets.artwork(n)
            if (bytes == null) {
                call.respondError(HttpStatusCode.NotFound, "artwork of player $n not found")
            } else {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(bytes, sniffImage(bytes))
            }
        }
        get("/waveform/preview") {
            val n = playerNumber() ?: return@get
            val dto = assets.preview(n)?.toDto()
            if (dto == null) call.respondError(HttpStatusCode.NotFound, "waveform preview of player $n not found") else call.respond(dto)
        }
        get("/waveform/detail") {
            val n = playerNumber() ?: return@get
            val from = call.parseMs("fromMs") ?: return@get
            val to = call.parseMs("toMs") ?: return@get
            if (from.value != null && to.value != null && from.value > to.value) {
                return@get call.respondError(HttpStatusCode.BadRequest, "fromMs must be <= toMs")
            }
            val dto = assets.detail(n)?.toDto(from.value, to.value)
            if (dto == null) call.respondError(HttpStatusCode.NotFound, "waveform detail of player $n not found") else call.respond(dto)
        }
        get("/beatgrid") {
            val n = playerNumber() ?: return@get
            val dto = assets.beatGrid(n)?.toDto()
            if (dto == null) call.respondError(HttpStatusCode.NotFound, "beatgrid of player $n not found") else call.respond(dto)
        }
    }
}

private class Ms(val value: Long?)

/** 未指定は `Ms(null)`。不正値は 400 を返して null。 */
private suspend fun ApplicationCall.parseMs(name: String): Ms? {
    val raw = request.queryParameters[name] ?: return Ms(null)
    val v = raw.toLongOrNull()?.takeIf { it >= 0 }
    if (v == null) {
        respondError(HttpStatusCode.BadRequest, "$name must be a non-negative integer")
        return null
    }
    return Ms(v)
}
