package cdjmusiclink.server.http

import cdjmusiclink.server.model.ErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) =
    respond(status, ErrorDto(message))
