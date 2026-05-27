package com.lightningkite.services.voiceagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable tool descriptor for voice agent tools.
 *
 * @deprecated Use [com.lightningkite.services.ai.LlmToolDescriptor] with a typed Kotlin
 * serializable argument class instead. [LlmToolDescriptor] is type-safe and derives the
 * JSON schema from the class structure automatically.
 *
 * ## Migration
 *
 * Old:
 * ```kotlin
 * SerializableToolDescriptor(
 *     name = "book_appointment",
 *     description = "Book an appointment",
 *     requiredParameters = listOf(
 *         SerializableToolParameterDescriptor("date", "Date in YYYY-MM-DD", SerializableToolParameterType.String)
 *     )
 * )
 * ```
 *
 * New:
 * ```kotlin
 * @Serializable data class BookAppointmentArgs(val date: String)
 * LlmToolDescriptor(name = "book_appointment", description = "Book an appointment", type = serializer<BookAppointmentArgs>())
 * ```
 */
@Deprecated(
    "Use LlmToolDescriptor with a typed @Serializable argument class instead.",
    level = DeprecationLevel.WARNING,
)
@Serializable
public data class SerializableToolDescriptor(
    val name: String,
    val description: String,
    val requiredParameters: List<SerializableToolParameterDescriptor> = emptyList(),
    val optionalParameters: List<SerializableToolParameterDescriptor> = emptyList(),
)

/** Describes a single parameter for a [SerializableToolDescriptor]. */
@Deprecated(
    "Use LlmToolDescriptor with a typed @Serializable argument class instead.",
    level = DeprecationLevel.WARNING,
)
@Serializable
public data class SerializableToolParameterDescriptor(
    val name: String,
    val description: String,
    val type: SerializableToolParameterType,
)

/** Type system for [SerializableToolDescriptor] parameters. */
@Deprecated(
    "Use LlmToolDescriptor with a typed @Serializable argument class instead.",
    level = DeprecationLevel.WARNING,
)
@Serializable
public sealed class SerializableToolParameterType {
    @Serializable @SerialName("string") public data object String : SerializableToolParameterType()
    @Serializable @SerialName("integer") public data object Integer : SerializableToolParameterType()
    @Serializable @SerialName("float") public data object Float : SerializableToolParameterType()
    @Serializable @SerialName("boolean") public data object Boolean : SerializableToolParameterType()
    @Serializable @SerialName("null") public data object Null : SerializableToolParameterType()

    @Serializable
    @SerialName("enum")
    public data class Enum(val entries: List<kotlin.String>) : SerializableToolParameterType()

    @Serializable
    @SerialName("list")
    public data class ListType(val itemsType: SerializableToolParameterType) : SerializableToolParameterType()

    @Serializable
    @SerialName("object")
    public data class Object(
        val properties: List<SerializableToolParameterDescriptor> = emptyList(),
        val requiredProperties: List<kotlin.String> = emptyList(),
        val additionalProperties: kotlin.Boolean = false,
    ) : SerializableToolParameterType()

    @Serializable
    @SerialName("any_of")
    public data class AnyOf(val types: List<SerializableToolParameterDescriptor>) : SerializableToolParameterType()
}
