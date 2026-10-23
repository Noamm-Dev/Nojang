package controllers

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import services.MojangService

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
        val queries = runCatching { call.receive<List<String>>() }.getOrNull() ?: return call.respond(HttpStatusCode.BadRequest, "Invalid payload")
        if (queries.size > MAX_BATCH_SIZE) return call.respond(HttpStatusCode.BadRequest, "At most $MAX_BATCH_SIZE queries per request")
        call.respond(mojang.lookupAll(queries))
    }

    companion object {
        private const val MAX_BATCH_SIZE = 400
    }
}
