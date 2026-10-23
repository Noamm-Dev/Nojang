@file:OptIn(ExperimentalForeignApi::class)

package org.noamm.nojang

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.noamm.nojang.utils.stopOnShutdownSignal
import platform.posix.getenv

fun main() {
    embeddedServer(
        factory = CIO,
        port = getenv("PORT")?.toKString()?.toInt() ?: 8080,
        host = getenv("HOST")?.toKString() ?: "0.0.0.0",
        module = ::module
    ).apply { stopOnShutdownSignal() }.start(wait = true)
}