package org.noamm.nojang.controllers

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import org.noamm.nojang.services.MojangService

class MojangController(private val mojang: MojangService) {
    suspend fun lookup(call: ApplicationCall) {
        val query = call.parameters["query"] ?: return call.respondText("Not Found", status = HttpStatusCode.NotFound)

        val player = runCatching { mojang.lookup(query) }.getOrElse {
            call.application.log.warn("Mojang lookup failed for $query", it)
            return call.respondText("Mojang is unavailable right now, try again", status = HttpStatusCode.BadGateway)
        }

        if (player == null) return call.respondText("Not Found", status = HttpStatusCode.NotFound)
        call.respond(player)
    }

    suspend fun lookupAll(call: ApplicationCall) {
        val queries = runCatching { call.receive<List<String>>() }.getOrNull() ?: return call.respondText("Invalid payload", status = HttpStatusCode.BadRequest)
        if (queries.size > MAX_BATCH_SIZE) return call.respondText("At most $MAX_BATCH_SIZE queries per request", status = HttpStatusCode.BadRequest)

        val batch = mojang.lookupAll(queries)
        if (! batch.complete) call.response.header(INCOMPLETE_HEADER, "true")
        call.respond(batch.players)
    }

    companion object {
        private const val MAX_BATCH_SIZE = 400
        private const val INCOMPLETE_HEADER = "X-Nojang-Incomplete"
    }
}
