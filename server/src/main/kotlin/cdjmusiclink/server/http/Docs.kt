package cdjmusiclink.server.http

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.response.respondBytes

private const val SWAGGER_UI_VERSION = "5.34.0"
private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

private val DOCS_HTML = """
<!doctype html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <title>cdj-music-link API</title>
  <link rel="stylesheet" href="/docs/webjars/swagger-ui.css">
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="/docs/webjars/swagger-ui-bundle.js"></script>
  <script>
    window.ui = SwaggerUIBundle({ url: "/openapi.yaml", dom_id: "#swagger-ui" });
  </script>
</body>
</html>
""".trimIndent()

fun Route.docsRoutes() {
    get("/docs") { call.respondText(DOCS_HTML, ContentType.Text.Html) }
    get("/openapi.yaml") {
        val spec = object {}.javaClass.getResourceAsStream("/openapi.yaml")?.use { it.readBytes() }
        if (spec == null) {
            call.respondError(HttpStatusCode.NotFound, "openapi.yaml not found")
        } else {
            call.respondText(String(spec, Charsets.UTF_8), ContentType.parse("application/yaml"))
        }
    }
    // staticResources は basePackage の '.' を '/' に置換し 5.34.0 を解決できないため、自前で返す。
    get("/docs/webjars/{file}") {
        val file = call.parameters["file"].orEmpty()
        val bytes = file.takeIf { it.matches(SAFE_NAME) }?.let {
            object {}.javaClass.getResourceAsStream("/META-INF/resources/webjars/swagger-ui/$SWAGGER_UI_VERSION/$it")?.use { s -> s.readBytes() }
        }
        if (bytes == null) {
            call.respondError(HttpStatusCode.NotFound, "$file not found")
        } else {
            call.respondBytes(bytes, ContentType.defaultForFilePath(file))
        }
    }
}
