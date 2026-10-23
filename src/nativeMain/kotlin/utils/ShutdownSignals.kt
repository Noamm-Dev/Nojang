@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)

package utils

import io.ktor.server.application.ApplicationStarted
import io.ktor.server.engine.EmbeddedServer
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*

private val logger = KtorSimpleLogger("ShutdownSignals")

private val signalPipe = IntArray(2).apply { usePinned { if (pipe(it.addressOf(0)) != 0) error("pipe() failed with errno $errno") } }
private val signalByte = nativeHeap.alloc<ByteVar>()

// Only does async-signal-safe work, the actual stop happens on the thread reading the pipe
private val onSignal = staticCFunction<Int, Unit> { signal ->
    signalByte.value = signal.toByte()
    write(signalPipe[1], signalByte.ptr, 1u)
}

fun EmbeddedServer<*, *>.stopOnShutdownSignal() {
    // Replaces Ktor's own handler (installed on ApplicationStarting), which calls stop() inside the
    // signal handler and hangs when the signal interrupts the main thread blocked in start(wait = true)
    monitor.subscribe(ApplicationStarted) {
        signal(SIGTERM, onSignal)
        signal(SIGINT, onSignal)
    }

    GlobalScope.launch(Dispatchers.IO) {
        val signal = memScoped {
            val received = alloc<ByteVar>()
            read(signalPipe[0], received.ptr, 1u)
            received.value
        }

        logger.info("Received signal $signal, shutting down.")
        stop()
    }
}