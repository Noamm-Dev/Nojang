/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("NOTHING_TO_INLINE")
@file:OptIn(ExperimentalForeignApi::class)

package org.noamm.nojang.curlfork.internal

import kotlinx.cinterop.*

internal inline fun <T: Any> T.toStableRef(): StableRef<T> = StableRef.create(this)
internal inline fun <reified T: Any> COpaquePointer.fromCPointer(): T = asStableRef<T>().get()
