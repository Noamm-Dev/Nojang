import controllers.MojangController
import database.configureDatabase
import database.repositories.MojangRepository
import http.JSON
import http.createHttpClients
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import middleware.CallLogging
import services.MojangCachePruner
import services.MojangService
import utils.stopOnShutdownSignal

fun main() {
    embeddedServer(CIO, port = 8080) {
        val http = createHttpClients()
        val database = configureDatabase()
        val mojangRepository = MojangRepository(database)
        val mojang = MojangService(http, mojangRepository)
        val mojangController = MojangController(mojang)

        MojangCachePruner(mojangRepository).also { it.start(this) }

        install(CallLogging)
        install(XForwardedHeaders) { forHeaders.add(0, "CF-Connecting-IP") }
        install(ContentNegotiation) { json(JSON) }

        routing {
            get("/") { call.respondText("Hello, world!") }

            get("{query}") { mojangController.lookup(call) }
            post { mojangController.lookupAll(call) }
        }
    }.apply { stopOnShutdownSignal() }.start(wait = true)
}
