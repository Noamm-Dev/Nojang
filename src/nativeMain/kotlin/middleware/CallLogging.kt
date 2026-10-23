package middleware

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.log
import io.ktor.server.request.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

val TIME_KEY = AttributeKey<Instant>("call_time")

@Serializable
private data class Request(val method: String, val uri: String, val status: Int, val duration: Long, val httpVersion: String, val time: LocalDateTime)

@OptIn(InternalAPI::class)
val CallLogging = createApplicationPlugin("CallLogging") {
    on(ResponseSent) { call ->
        with(call) {
            val startTime = attributes[TIME_KEY]
            val duration = Clock.System.now().minus(startTime).inWholeMilliseconds
            val timeReq = startTime.toLocalDateTime(TimeZone.currentSystemDefault())
            val status = response.status()?.value ?: - 1

            val logMsg = Json.encodeToString(
                Request(
                    method = request.httpMethod.value,
                    uri = request.uri,
                    status = status,
                    duration = duration,
                    httpVersion = request.httpVersion,
                    time = timeReq,
                )
            )

            when {
                status >= 400 -> application.log.error(logMsg)
                else -> application.log.info(logMsg)
            }
        }
    }

    on(CallSetup) { call ->
        call.attributes.put(TIME_KEY, Clock.System.now())
    }
}