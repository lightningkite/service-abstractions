package com.lightningkite.services.database

import kotlinx.serialization.Serializable

@Serializable
public data class ListChange<T>(
    val wholeList: List<T>? = null,
    val old: T? = null,
    val new: T? = null
)

