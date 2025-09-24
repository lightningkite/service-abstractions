package com.lightningkite.services.terraform

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

public interface TerraformNeed<T> {
    public val name: String
    public val serializer: KSerializer<T>
    public val default: T? get() = null
    public val instructions: String get() = "No instructions provided."

    public companion object {
        public inline operator fun <reified T> invoke(name: String): TerraformNeed<T> = object : TerraformNeed<T> {
            override val name: String = name
            override val serializer: KSerializer<T> = serializer()
        }
    }
}