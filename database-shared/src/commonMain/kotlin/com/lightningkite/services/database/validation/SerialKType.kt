package com.lightningkite.services.database.validation

import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.typeParametersSerializersOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

public sealed interface SerialKType {
    public fun matches(serializer: KSerializer<*>): Boolean
    public fun matches(type: SerialKType): Boolean
    public fun qualifiedString(): String

    public data object Wildcard : SerialKType {
        override fun matches(serializer: KSerializer<*>): Boolean = true
        override fun matches(type: SerialKType): Boolean = true
        override fun toString(): String = "*"
        override fun qualifiedString(): String = "*"
    }

    public data class Specified(
        val type: Type,
        val arguments: List<SerialKType>,
        val nullable: Boolean
    ) : SerialKType {
        public sealed interface Type {
            public data class Kind(val kind: SerialKind) : Type
            public data class Exact(val serialName: String) : Type
        }

        override fun matches(serializer: KSerializer<*>): Boolean {
            if (serializer.descriptor.isNullable && !nullable) return false

            val serializer = serializer.nullElement() ?: serializer

            val typeMatches = when (type) {
                is Type.Kind -> serializer.descriptor.kind == type.kind
                is Type.Exact -> serializer.descriptor.serialName == type.serialName
            }

            if (!typeMatches) return false
            if (arguments.isEmpty() || arguments.all { it == Wildcard }) return true

            val args = serializer.typeParametersSerializersOrNull()
                ?: throw IllegalArgumentException("Cannot determine type parameters for type with serialName `${serializer.descriptor.serialName}`")

            if (args.size != arguments.size) return false

            return args.indices.all { arguments[it].matches(args[it]) }
        }

        override fun matches(type: SerialKType): Boolean {
            return when (type) {
                Wildcard -> false
                is Specified -> {
                    if (this.type != type.type) return false
                    if (arguments.size != type.arguments.size) return false

                    return arguments.indices.all { arguments[it].matches(type.arguments[it]) }
                }
            }
        }

        override fun toString(): String {
            val t = when (type) {
                is Type.Kind -> type.kind.toString()
                is Type.Exact -> type.serialName.substringAfterLast('.')
            }
            return if (arguments.isEmpty()) t
            else "$t<${arguments.joinToString()}>" + if (nullable) '?' else ""
        }

        override fun qualifiedString(): String {
            val t = when (type) {
                is Type.Kind -> type.kind.toString()
                is Type.Exact -> type.serialName
            }
            return if (arguments.isEmpty()) t
            else "$t<${arguments.joinToString { it.qualifiedString() }}>" + if (nullable) '?' else ""
        }
    }
}

private fun KType.noStarProjections(): Boolean =
    arguments.isEmpty() || arguments.all { it.type?.noStarProjections() == true }

private val kindIsSameAsType = buildMap {
    fun add(serializer: KSerializer<*>) = put(serializer.descriptor.serialName, SerialKType.Specified.Type.Kind(serializer.descriptor.kind))

    add(MapSerializer(Int.serializer(), Int.serializer()))
    add(ListSerializer(Int.serializer()))
}

@OptIn(ExperimentalSerializationApi::class)
public fun SerialKType(kType: KType, module: SerializersModule = EmptySerializersModule()): SerialKType {
    if (kType.classifier == null) return SerialKType.Wildcard

    val serializer =
        if (kType.noStarProjections()) module.serializer(kType)
        else module.serializer(
            kType.classifier as KClass<*>,
            List(kType.arguments.size) { NothingSerializer() },
            isNullable = false
        )

    val serialName = (serializer.nullElement() ?: serializer).descriptor.serialName

    val d = SerialKType.Specified(
        type = kindIsSameAsType[serialName] ?: SerialKType.Specified.Type.Exact(serialName),
        arguments = kType.arguments.map { arg ->
            arg.type?.let { SerialKType(it, module) } ?: SerialKType.Wildcard
        },
        nullable = kType.isMarkedNullable
    )

    return d
}

public inline fun <reified T> serialKTypeOf(module: SerializersModule = EmptySerializersModule()): SerialKType = SerialKType(typeOf<T>(), module)

@Suppress("FunctionName")
public fun SerialKType(serializer: KSerializer<*>): SerialKType.Specified {
    val inner = serializer.nullElement() ?: serializer
    return SerialKType.Specified(
        type = kindIsSameAsType[inner.descriptor.serialName] ?: SerialKType.Specified.Type.Exact(inner.descriptor.serialName),
        arguments = inner.typeParametersSerializersOrNull()?.map(::SerialKType) ?: emptyList(),
        nullable = serializer.descriptor.isNullable
    )
}

@OptIn(ExperimentalSerializationApi::class)
public fun SerialKType.generality(): Double =
    when (this) {
        is SerialKType.Specified -> {
            val n = arguments.size
            val argG = if (n == 0) 0.0
            else {
                val r = 0.5 / n.toDouble()
                arguments.sumOf { it.generality() * r }
            }
            argG + when (type) {
                is SerialKType.Specified.Type.Exact -> 0.0
                is SerialKType.Specified.Type.Kind -> 0.25
            }
        }
        SerialKType.Wildcard -> 1.0
    }

public fun SerialKType.specificity(): Double = 1.0 - generality()