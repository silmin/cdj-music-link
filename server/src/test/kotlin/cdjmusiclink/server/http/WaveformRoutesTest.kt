package cdjmusiclink.server.http

import cdjmusiclink.server.model.ApiJson
import cdjmusiclink.server.model.BeatGridDto
import cdjmusiclink.server.model.ErrorDto
import cdjmusiclink.server.model.WaveformDetailDto
import cdjmusiclink.server.model.WaveformPreviewDto
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaveformRoutesTest {
    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { apiModule(filledStore(), FakeSession(), FixtureAssets()) }
        block()
    }

    @Test
    fun previewMatchesFixture() = app {
        val dto = ApiJson.json.decodeFromString<WaveformPreviewDto>(client.get("/v1/players/1/waveform/preview").bodyAsText())
        assertEquals(1200, dto.segments)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/players/2/waveform/preview").status)
    }

    @Test
    fun detailRejectsBadRange() = app {
        for (q in listOf("fromMs=5000&toMs=1000", "fromMs=abc", "toMs=x", "fromMs=-1")) {
            val res = client.get("/v1/players/1/waveform/detail?$q")
            assertEquals(HttpStatusCode.BadRequest, res.status, q)
            assertTrue(ApiJson.json.decodeFromString<ErrorDto>(res.bodyAsText()).error.isNotEmpty(), q)
        }
    }

    @Test
    fun detailClipsToRange() = app {
        val dto = ApiJson.json.decodeFromString<WaveformDetailDto>(client.get("/v1/players/1/waveform/detail?fromMs=1000&toMs=2000").bodyAsText())
        assertEquals(1000L, dto.fromMs)
        assertEquals(2000L, dto.toMs)
        assertEquals(HttpStatusCode.OK, client.get("/v1/players/1/waveform/detail").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/players/2/waveform/detail").status)
    }

    @Test
    fun beatGridServed() = app {
        val dto = ApiJson.json.decodeFromString<BeatGridDto>(client.get("/v1/players/1/beatgrid").bodyAsText())
        assertTrue(dto.beats.isNotEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/players/2/beatgrid").status)
    }
}
