/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.noamm.nojang.curlfork.internal

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.writeFully
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import org.noamm.nojang.curlfork.internal.Libcurl.READFUNC_ABORT
import org.noamm.nojang.curlfork.internal.Libcurl.READFUNC_PAUSE
import platform.posix.size_t
import kotlin.coroutines.CoroutineContext

/**
 *  The callback is getting called on each completely parser header line.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun onHeadersReceived(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    userdata: COpaquePointer
): size_t {
    val response = userdata.fromCPointer<CurlResponseBuilder>()
    val packet = response.headersBytes
    val chunkSize = (size * count).toLong()
    packet.writeFully(buffer, 0, chunkSize)

    if (isFinalHeaderLine(chunkSize, buffer) && ! response.bodyStartedReceiving.isCompleted) {
        response.bodyStartedReceiving.complete(Unit)
    }

    return chunkSize.convert()
}

/**
 * Checks if the given header represents the final header line (CR LF).
 *
 * @see <a href="https://curl.se/libcurl/c/CURLOPT_HEADERFUNCTION.html">Description.</a>
 */
@OptIn(ExperimentalForeignApi::class)
private fun isFinalHeaderLine(chunkSize: Long, buffer: CPointer<ByteVar>) =
    chunkSize == 2L && buffer[0] == 0x0D.toByte() && buffer[1] == 0x0A.toByte()

@OptIn(ExperimentalForeignApi::class)
internal fun onBodyChunkReceived(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    userdata: COpaquePointer
): size_t {
    val wrapper = userdata.fromCPointer<CurlHttpResponseBody>()
    return wrapper.onBodyChunkReceived(buffer, size, count)
}

@OptIn(ExperimentalForeignApi::class)
internal fun onBodyChunkRequested(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    dataRef: COpaquePointer,
): size_t {
    val wrapper: CurlRequestBodyData = dataRef.fromCPointer()
    val body = wrapper.body
    val requested = (size * count).toInt()

    if (body.isClosedForRead) {
        return if (body.closedCause != null) READFUNC_ABORT else 0.convert()
    }
    val readCount = try {
        body.readAvailable(1) { source: Buffer ->
            source.readAvailable(buffer, 0, requested)
        }
    }
    catch (_: Throwable) {
        return READFUNC_ABORT
    }
    if (readCount > 0) {
        return readCount.convert()
    }

    CoroutineScope(wrapper.callContext).launch {
        try {
            body.awaitContent()
        }
        catch (_: Throwable) {
            // no op, error will be handled on next read on cURL thread
        }
        finally {
            wrapper.onUnpause()
        }
    }
    return READFUNC_PAUSE
}

internal class CurlRequestBodyData(
    val body: ByteReadChannel,
    val callContext: CoroutineContext,
    val onUnpause: () -> Unit
)
