package com.lightningkite.services.database


import kotlinx.serialization.Serializable

@Serializable
data class MassModification<T>(
    val condition: Condition<T>,
    val modification: Modification<T>
)
