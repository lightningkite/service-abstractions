package com.lightningkite.services.terraform

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

public interface TerraformNeed<T> {
    public val name: String
    public val serializer: KSerializer<T>
    public val default: T? get() = null
    public val instructions: String get() = "No instructions provided."
}

public fun <T> TerraformNeed(
    name: String,
    serializer: KSerializer<T>,
    default: T? = null,
    instructions: String = "No instructions provided.",
): TerraformNeed<T> =
    object : TerraformNeed<T> {
        override val name: String = name
        override val serializer: KSerializer<T> = serializer
        override val instructions: String = instructions
        override val default: T? = default
    }

public inline fun <reified T> TerraformNeed(
    name: String,
    default: T? = null,
    instructions: String = "No instructions provided.",
): TerraformNeed<T>  =
    object : TerraformNeed<T> {
        override val name: String = name
        override val serializer: KSerializer<T> = serializer()
        override val instructions: String = instructions
        override val default: T? = default
    }