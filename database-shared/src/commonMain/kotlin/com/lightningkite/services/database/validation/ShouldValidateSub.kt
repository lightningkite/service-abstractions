package com.lightningkite.services.database.validation

import kotlinx.serialization.KSerializer

public interface ShouldValidateSub<A> : KSerializer<A> {
    public data class SerializerAndValue<T>(val serializer: KSerializer<T>, val value: T)

    public fun validate(
        value: A,
        annotations: List<Annotation>,
        defer: (value: SerializerAndValue<*>, annotations: List<Annotation>) -> Unit,
    )
}