@file:OptIn(ExperimentalForeignApi::class)

package org.noamm.nojang

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.cinterop.ExperimentalForeignApi
import org.noamm.nojang.controllers.MojangController
import org.noamm.nojang.database.configureDatabase
import org.noamm.nojang.database.repositories.MojangRepository
import org.noamm.nojang.http.JSON
import org.noamm.nojang.http.createHttpClient
import org.noamm.nojang.plugins.CallLogging
import org.noamm.nojang.services.*

fun module(app: Application) = with(app) {
    val http = createHttpClient()
    val database = configureDatabase()
    val mojangRepository = MojangRepository(database)
    val mojang = MojangService(MojangApi(http), mojangRepository)
    val mojangController = MojangController(mojang)

    MojangCachePruner(mojangRepository).start(this)

    install(CallLogging)
    install(XForwardedHeaders) { forHeaders.add(0, "CF-Connecting-IP") }
    install(ContentNegotiation) { json(JSON) }

    routing {
        get("/") { call.respondText("ok") }

        get("{query}") { mojangController.lookup(call) }
        post { mojangController.lookupAll(call) }
    }
}