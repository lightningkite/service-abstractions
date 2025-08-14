package com.lightningkite.services.database.mongodb.bson

internal data class Configuration(
    val encodeDefaults: Boolean = true,
    val classDiscriminator: String = "___type",
    val nonEncodeNull: Boolean = false
)