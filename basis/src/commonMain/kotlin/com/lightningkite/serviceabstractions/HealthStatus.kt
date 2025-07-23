package com.lightningkite.serviceabstractions

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

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
