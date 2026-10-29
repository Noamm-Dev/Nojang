/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.noamm.nojang.curlfork.internal

import io.ktor.client.plugins.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.core.Closeable
import io.ktor.utils.io.core.build
import io.ktor.utils.io.locks.SynchronizedObject
import io.ktor.utils.io.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.readByteArray
import libcurl.*

@OptIn(ExperimentalForeignApi::class)
private class RequestHolder(
    val responseCompletable: CompletableDeferred<CurlSuccess>,
    val requestHeaders: CPointer<curl_slist>,
    val responseDataRef: StableRef<CurlResponseBuilder>,
    val requestWrapper: StableRef<CurlRequestBodyData>,
    val responseWrapper: StableRef<CurlHttpResponseBody>,
) {
    fun dispose() {
        curl_slist_free_all(requestHeaders)
        responseDataRef.dispose()
        requestWrapper.dispose()
        responseWrapper.dispose()
    }
}

@OptIn(InternalAPI::class, ExperimentalForeignApi::class)
internal class CurlMultiApiHandler: Closeable {
    private val activeHandles = mutableMapOf<EasyHandle, RequestHolder>()
    private val cancelledHandles = mutableSetOf<Pair<EasyHandle, Throwable>>()

    private val multiHandle: MultiHandle = curl_multi_init()
        ?: throw RuntimeException("Could not initialize curl multi handle")

    private val easyHandlesToUnpauseLock = SynchronizedObject()
    private val easyHandlesToUnpause = mutableListOf<EasyHandle>()

    override fun close() {
        if (activeHandles.isNotEmpty() || cancelledHandles.isNotEmpty()) handleCompleted()
        for ((handle, holder) in activeHandles) {
            cleanupEasyHandle(handle)
            holder.dispose()
        }

        activeHandles.clear()
        curl_multi_cleanup(multiHandle).verify()
    }

    fun scheduleRequest(request: CurlRequestData, deferred: CompletableDeferred<CurlSuccess>): EasyHandle {
        val easyHandle = curl_easy_init() ?: run {
            curl_slist_free_all(request.headers)
            error("Could not initialize an easy handle")
        }

        val bodyStartedReceiving = CompletableDeferred<Unit>()
        val responseBody = CurlHttpResponseBody(request.callContext) {
            unpauseEasyHandle(easyHandle)
        }
        val responseData = CurlResponseBuilder(request, bodyStartedReceiving, responseBody)
        val responseDataRef = responseData.toStableRef()
        val responseWrapper = responseBody.toStableRef()
        val requestWrapper = CurlRequestBodyData(
            body = request.content,
            callContext = request.callContext,
            onUnpause = { unpauseEasyHandle(easyHandle) },
        ).toStableRef()
        val requestHolder = RequestHolder(
            deferred,
            request.headers,
            responseDataRef,
            requestWrapper,
            responseWrapper,
        )

        bodyStartedReceiving.invokeOnCompletion {
            val result = collectSuccessResponse(easyHandle) ?: return@invokeOnCompletion
            activeHandles[easyHandle] !!.responseCompletable.complete(result)
        }

        try {
            easyHandle.setupMethod(request.method, request.contentLength)
            easyHandle.setupUploadContent(requestWrapper.asCPointer())

            easyHandle.apply {
                option(CURLOPT_URL, request.url)
                option(CURLOPT_HTTPHEADER, request.headers)
                option(CURLOPT_HEADERFUNCTION, staticCFunction(::onHeadersReceived))
                option(CURLOPT_HEADERDATA, responseDataRef.asCPointer())
                option(CURLOPT_WRITEFUNCTION, staticCFunction(::onBodyChunkReceived))
                option(CURLOPT_WRITEDATA, responseWrapper.asCPointer())
                option(CURLOPT_PRIVATE, responseDataRef.asCPointer())
                option(CURLOPT_ACCEPT_ENCODING, "")
                option(CURLOPT_IPRESOLVE, CURL_IPRESOLVE_V4)
                request.connectTimeout?.let {
                    if (it != HttpTimeoutConfig.INFINITE_TIMEOUT_MS) {
                        option(CURLOPT_CONNECTTIMEOUT_MS, request.connectTimeout)
                    }
                    else option(CURLOPT_CONNECTTIMEOUT_MS, Long.MAX_VALUE)
                }

                request.localInterface?.let { option(CURLOPT_INTERFACE, it) }
                if (request.forceHttp1) option(CURLOPT_HTTP_VERSION, CURL_HTTP_VERSION_1_1)
            }

            curl_multi_add_handle(multiHandle, easyHandle).verify()
        }
        catch (cause: Throwable) {
            try {
                try {
                    responseData.responseBody.close(cause)
                }
                finally {
                    responseData.headersBytes.close()
                }
            }
            finally {
                curl_easy_cleanup(easyHandle)
                requestHolder.dispose()
            }
            throw cause
        }

        activeHandles[easyHandle] = requestHolder
        return easyHandle
    }

    fun cancelRequest(easyHandle: EasyHandle, cause: Throwable) {
        cancelledHandles += Pair(easyHandle, cause)
    }

    fun perform(transfersRunning: IntVarOf<Int>) {
        if (activeHandles.isEmpty()) return

        // Process cancelled handles before performing to prevent them from blocking curl_multi_poll.
        if (cancelledHandles.isNotEmpty()) handleCompleted()
        if (activeHandles.isEmpty()) return

        synchronized(easyHandlesToUnpauseLock) {
            var handle = easyHandlesToUnpause.removeFirstOrNull()
            while (handle != null) {
                if (handle in activeHandles) curl_easy_pause(handle, CURLPAUSE_CONT)
                handle = easyHandlesToUnpause.removeFirstOrNull()
            }
        }
        curl_multi_perform(multiHandle, transfersRunning.ptr).verify()
        if (transfersRunning.value != 0) curl_multi_poll(multiHandle, null, 0.toUInt(), POLL_TIMEOUT_MS, null).verify()
        if (transfersRunning.value < activeHandles.size) handleCompleted()
    }

    fun hasHandlers(): Boolean = activeHandles.isNotEmpty()

    private fun EasyHandle.setupMethod(method: String, size: Long) = when (method) {
        "GET" -> option(CURLOPT_HTTPGET, 1L)
        "POST" -> {
            option(CURLOPT_POST, 1L)
            option(CURLOPT_POSTFIELDSIZE_LARGE, size)
        }

        else -> {
            if (size > 0) {
                option(CURLOPT_POST, 1L)
                option(CURLOPT_POSTFIELDSIZE_LARGE, size)
            }
            option(CURLOPT_CUSTOMREQUEST, method)
        }
    }

    private fun EasyHandle.setupUploadContent(requestPointer: COpaquePointer) {
        option(CURLOPT_READDATA, requestPointer)
        option(CURLOPT_READFUNCTION, staticCFunction(::onBodyChunkRequested))
    }

    private fun handleCompleted() {
        for ((easyHandle, cause) in cancelledHandles) removeEasyHandle(easyHandle, cause)
        cancelledHandles.clear()

        memScoped {
            do {
                val messagesLeft = alloc<IntVar>()
                val messagePtr = curl_multi_info_read(multiHandle, messagesLeft.ptr)
                val message = messagePtr?.pointed ?: continue

                val easyHandle = message.easy_handle
                    ?: error("Got a null easy handle from the message")

                try {
                    val result = processCompletedEasyHandle(message.msg, easyHandle, message.data.result)
                    val deferred = activeHandles[easyHandle] !!.responseCompletable
                    if (deferred.isCompleted) continue

                    when (result) {
                        is CurlSuccess -> deferred.complete(result)
                        is CurlFail -> deferred.completeExceptionally(result.cause)
                    }
                }
                finally {
                    activeHandles.remove(easyHandle) !!.dispose()
                }
            } while (messagesLeft.value != 0)
        }
    }

    private fun removeEasyHandle(easyHandle: EasyHandle, cause: Throwable) {
        val handler = activeHandles.remove(easyHandle) ?: return
        try {
            processCancelledEasyHandle(easyHandle, cause)
        }
        finally {
            handler.responseCompletable.completeExceptionally(cause)
            handler.dispose()
        }
    }

    private fun processCancelledEasyHandle(easyHandle: EasyHandle, cause: Throwable): CurlFail = memScoped {
        try {
            val responseDataRef = alloc<COpaquePointerVar>()
            easyHandle.apply { getInfo(CURLINFO_PRIVATE, responseDataRef.ptr) }
            val responseBuilder = responseDataRef.value !!.fromCPointer<CurlResponseBuilder>()
            try {
                return CurlFail(cause)
            }
            finally {
                responseBuilder.responseBody.close(cause)
                responseBuilder.headersBytes.close()
            }
        }
        finally {
            cleanupEasyHandle(easyHandle)
        }
    }

    private fun processCompletedEasyHandle(
        message: CURLMSG?,
        easyHandle: EasyHandle,
        result: CURLcode
    ): CurlResponseData = memScoped {
        try {
            val responseDataRef = alloc<COpaquePointerVar>()
            val httpStatusCode = alloc<LongVar>()

            easyHandle.apply {
                getInfo(CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
                getInfo(CURLINFO_PRIVATE, responseDataRef.ptr)
            }

            val responseBuilder = responseDataRef.value !!.fromCPointer<CurlResponseBuilder>()
            try {
                collectFailedResponse(
                    message = message,
                    request = responseBuilder.request,
                    result = result,
                    httpStatusCode = httpStatusCode.value,
                ) ?: collectSuccessResponse(easyHandle) !!
            }
            finally {
                responseBuilder.responseBody.close()
                responseBuilder.headersBytes.close()
            }
        }
        finally {
            cleanupEasyHandle(easyHandle)
        }
    }

    private fun collectFailedResponse(
        message: CURLMSG?,
        request: CurlRequestData,
        result: CURLcode,
        httpStatusCode: Long,
    ): CurlFail? {
        if (message != CURLMSG.CURLMSG_DONE)
            return CurlFail(IllegalStateException("Request $request failed: $message"))


        if (httpStatusCode != 0L) {
            return null
        }

        if (result == CURLE_OPERATION_TIMEDOUT) {
            return CurlFail(ConnectTimeoutException(request.url, request.connectTimeout))
        }

        val errorMessage = result.errorMessage

        if (result == CURLE_PEER_FAILED_VERIFICATION) {
            return CurlFail(IllegalStateException("TLS verification failed for request: $request. Reason: $errorMessage"))
        }

        return CurlFail(
            IllegalStateException("Connection failed for request: $request. Reason: $errorMessage")
        )
    }

    private fun collectSuccessResponse(easyHandle: EasyHandle): CurlSuccess? = memScoped {
        val responseDataRef = alloc<COpaquePointerVar>()
        val httpProtocolVersion = alloc<LongVar>()
        val httpStatusCode = alloc<LongVar>()

        easyHandle.apply {
            getInfo(CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
            getInfo(CURLINFO_HTTP_VERSION, httpProtocolVersion.ptr)
            getInfo(CURLINFO_PRIVATE, responseDataRef.ptr)
        }

        if (httpStatusCode.value == 0L) return@memScoped null
        val responseBuilder = responseDataRef.value !!.fromCPointer<CurlResponseBuilder>()
        with(responseBuilder) {
            val headers = headersBytes.build().readByteArray()

            CurlSuccess(
                httpStatusCode.value.toInt(),
                httpProtocolVersion.value,
                headers,
                responseBody
            )
        }
    }

    fun wakeup() {
        curl_multi_wakeup(multiHandle)
    }

    private fun unpauseEasyHandle(easyHandle: EasyHandle) {
        synchronized(easyHandlesToUnpauseLock) {
            easyHandlesToUnpause.add(easyHandle)
        }
        curl_multi_wakeup(multiHandle)
    }

    private fun cleanupEasyHandle(easyHandle: EasyHandle) {
        curl_multi_remove_handle(multiHandle, easyHandle).verify()
        curl_easy_cleanup(easyHandle)
    }

    private companion object {
        private const val POLL_TIMEOUT_MS = 100
    }
}