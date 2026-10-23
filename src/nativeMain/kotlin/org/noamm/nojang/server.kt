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
import kotlinx.serialization.json.Json
import org.noamm.nojang.controllers.MojangController
import org.noamm.nojang.database.configureDatabase
import org.noamm.nojang.database.repositories.MojangRepository
import org.noamm.nojang.http.createHttpClient
import org.noamm.nojang.plugins.CallLogging
import org.noamm.nojang.services.*

fun module(app: Application) = with(app) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    install(XForwardedHeaders) { forHeaders.add(0, "CF-Connecting-IP") }
    install(ContentNegotiation) { json(json) }
    install(CallLogging)

    val database = configureDatabase()
    val http = createHttpClient(json)
    val mojangRepository = MojangRepository(database)
    val mojangApi = MojangApi(http)
    val mojangService = MojangService(mojangApi, mojangRepository)
    val mojangController = MojangController(mojangService)

    MojangCachePruner(mojangRepository).start(this)

    routing {
        get("/") { call.respondText("ok") }

        get("{query}") { mojangController.lookup(call) }
        post { mojangController.lookupAll(call) }
    }
}