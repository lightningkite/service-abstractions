package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.nullElement
import kotlinx.serialization.KSerializer

public class NotNullOptic<A, B : Any>(public val wraps: Optic<A, B?>) : Optic<A, B> {
    override val serializer: KSerializer<B>
        @Suppress("UNCHECKED_CAST")
        get() = wraps.serializer.nullElement()!! as KSerializer<B>

    override val properties: List<SerializableProperty<*, *>> get() = wraps.properties

    override fun get(source: A): B? = wraps.get(source)

    override fun getOrThrow(source: A): B =
        wraps.getOrThrow(source) ?: throw IllegalArgumentException("NotNullOptic expected a non-null value for $wraps, but was null")

    override fun set(source: A, value: B): A = wraps.set(source, value)

    override fun mapCondition(condition: Condition<B>): Condition<A> =
        wraps.mapCondition(Condition.IfNotNull(condition))

    override fun mapModification(modification: Modification<B>): Modification<A> =
        wraps.mapModification(Modification.IfNotNull(modification))

    override fun toString(): String = "$wraps?"
}

public val <A, B : Any> Optic<A, B?>.notNull: NotNullOptic<A, B> get() = NotNullOptic(this)