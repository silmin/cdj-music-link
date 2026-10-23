package cdjmusiclink.server.http

import cdjmusiclink.server.state.EventBus
import cdjmusiclink.server.state.Store
import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContractTest {
    private val validator = OpenApiInteractionValidator.createFor("openapi.yaml").build()

    private val paths = listOf(
        "/v1/health", "/v1/devices", "/v1/players", "/v1/players/1", "/v1/players/1/track",
        "/v1/players/1/waveform/preview", "/v1/players/1/waveform/detail", "/v1/players/1/waveform/detail?fromMs=1000&toMs=2000",
        "/v1/players/1/beatgrid", "/v1/master", "/v1/now-playing",
        "/v1/players/9", "/v1/players/9/track", "/v1/players/1/waveform/detail?fromMs=abc",
    )

    private suspend fun check(path: String, res: HttpResponse): List<String> {
        val resp = SimpleResponse.Builder(res.status.value)
            .withContentType(res.headers[HttpHeaders.ContentType])
            .withBody(res.bodyAsText())
            .build()
        return validator.validateResponse(path.substringBefore('?'), Request.Method.GET, resp).messages.map { "$path: ${it.message}" }
    }

    @Test
    fun allGetResponsesMatchSpec() = testApplication {
        application { apiModule(filledStore(), FakeSession(), FixtureAssets(), EventBus()) }
        val errors = paths.flatMap { check(it, client.get(it)) }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun nowPlayingNullMatchesSpec() = testApplication {
        application { apiModule(Store(EventBus()) { 1 }, FakeSession(online = false, deviceNumber = null), FixtureAssets(), EventBus()) }
        val res = client.get("/v1/now-playing")
        assertTrue(res.bodyAsText() == "null")
        val errors = check("/v1/now-playing", res) + check("/v1/health", client.get("/v1/health"))
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }
}
