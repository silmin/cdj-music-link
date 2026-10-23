package cdjmusiclink.server.http

import cdjmusiclink.server.model.ApiJson
import cdjmusiclink.server.model.ErrorDto
import cdjmusiclink.server.model.Health
import cdjmusiclink.server.model.Player
import cdjmusiclink.server.state.EventBus
import cdjmusiclink.server.state.Store
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutesTest {
    private fun app(store: Store = filledStore(), session: SessionInfo = FakeSession(), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { apiModule(store, session, FixtureAssets(), EventBus()) }
            block()
        }

    @Test
    fun healthReportsOnline() = app {
        val res = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, res.status)
        val h = ApiJson.json.decodeFromString<Health>(res.bodyAsText())
        assertTrue(h.online)
        assertEquals(5, h.deviceNumber)
        assertEquals(2, h.deviceCount)
        assertEquals("9.9.9", h.version)
        assertTrue(h.serverTime > 0)
    }

    @Test
    fun playersListsOnlyPlayers() = app {
        val players = ApiJson.json.decodeFromString(ListSerializer(Player.serializer()), client.get("/v1/players").bodyAsText())
        assertEquals(listOf(1), players.map { it.number })
    }

    @Test
    fun unknownPlayerIs404WithErrorJson() = app {
        for (path in listOf("/v1/players/9", "/v1/players/0", "/v1/players/16", "/v1/players/abc", "/v1/players/9/track")) {
            val res = client.get(path)
            assertEquals(HttpStatusCode.NotFound, res.status, path)
            val e = ApiJson.json.decodeFromString<ErrorDto>(res.bodyAsText())
            assertTrue(e.error.isNotEmpty(), path)
        }
    }

    @Test
    fun trackIs404WhenNoTrack() {
        val store = Store(EventBus()) { 1 }
        store.status(1, cdjmusiclink.server.state.snap(trackLoaded = false))
        app(store) {
            assertEquals(HttpStatusCode.OK, client.get("/v1/players/1").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/players/1/track").status)
        }
    }

    @Test
    fun artworkContentTypeFromBytes() = app {
        val res: HttpResponse = client.get("/v1/players/1/artwork")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("image/jpeg", res.headers[HttpHeaders.ContentType])
        assertEquals("no-store", res.headers[HttpHeaders.CacheControl])
        assertTrue(res.readRawBytes().contentEquals(fixture("art.jpg")))
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/players/2/artwork").status)
    }

    @Test
    fun corsAllowsAnyOrigin() = app {
        val res = client.get("/v1/health") { header(HttpHeaders.Origin, "http://x") }
        assertEquals("*", res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun masterAndNowPlaying() = app {
        assertTrue(client.get("/v1/master").bodyAsText().contains("\"player\":1"))
        val np = ApiJson.json.decodeFromString<Player>(client.get("/v1/now-playing").bodyAsText())
        assertEquals(1, np.number)
    }

    @Test
    fun docsAndSpecAreServed() = app {
        val docs = client.get("/docs")
        assertEquals(HttpStatusCode.OK, docs.status)
        assertTrue(docs.bodyAsText().contains("/openapi.yaml"))
        val spec = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.headers[HttpHeaders.ContentType]!!.startsWith("application/yaml"))
        assertTrue(spec.bodyAsText().startsWith("openapi:"))
        assertEquals(HttpStatusCode.OK, client.get("/docs/webjars/swagger-ui.css").status)
        assertFalse(client.get("/nope").status.isSuccess())
    }
}
