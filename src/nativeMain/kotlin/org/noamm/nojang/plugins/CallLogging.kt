package org.noamm.nojang.plugins

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.util.AttributeKey
import io.ktor.util.logging.KtorSimpleLogger
import kotlin.time.*

private val logger = KtorSimpleLogger("CallLogging")
private val START_KEY = AttributeKey<TimeMark>("call_start")

val CallLogging = createApplicationPlugin("CallLogging") {
    on(CallSetup) { call ->
        call.attributes.put(START_KEY, TimeSource.Monotonic.markNow())
    }

    on(ResponseSent) { call ->
        with(call) {
            val duration = attributes[START_KEY].elapsedNow().inWholeMilliseconds
            val status = response.status()?.value ?: - 1

            val line = "${Clock.System.now()} \"${request.httpMethod.value} ${request.uri} $status ${duration}ms"

            when {
                status !in 0 ..< 500 -> logger.error(line)
                status >= 400 -> logger.warn(line)
                else -> logger.info(line)
            }
        }
    }
}