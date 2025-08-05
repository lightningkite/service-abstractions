package com.lightningkite.services

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
public data class HealthStatus(
    val level: Level,
    val checkedAt: Instant = Clock.System.now(),
    val additionalMessage: String? = null
) {
    @Serializable
    public enum class Level {
        OK,
        WARNING,
        URGENT,
        ERROR,
    }
}
