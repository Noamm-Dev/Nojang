package org.noamm.nojang.models

import kotlinx.serialization.Serializable

@Serializable
data class MojangData(val name: String, val id: String)