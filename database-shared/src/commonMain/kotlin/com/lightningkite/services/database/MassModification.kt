package com.lightningkite.services.database


import kotlinx.serialization.Serializable

@Serializable
public data class MassModification<T>(
    val condition: Condition<T>,
    val modification: Modification<T>
)
