package com.lightningkite.services.database

import kotlinx.serialization.Serializable

@Serializable
public enum class Aggregate {
    Sum,
    Average,
    StandardDeviationSample,
    StandardDeviationPopulation
}
