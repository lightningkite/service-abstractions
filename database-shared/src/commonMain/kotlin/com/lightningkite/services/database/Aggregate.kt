package com.lightningkite.services.database

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

@Serializable
public enum class Aggregate {
    Sum,
    Average,
    StandardDeviationSample,
    StandardDeviationPopulation
}
