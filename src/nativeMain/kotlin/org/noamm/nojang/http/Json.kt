package org.noamm.nojang.http

import kotlinx.serialization.json.Json

val JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}