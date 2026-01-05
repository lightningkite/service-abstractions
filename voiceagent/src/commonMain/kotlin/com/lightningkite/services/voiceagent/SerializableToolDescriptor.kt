package com.lightningkite.services.voiceagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable tool descriptor compatible with Koog's ToolDescriptor format.
 *
 * This is a multiplatform-compatible representation of a tool that can be
 * called by the voice agent. It mirrors Koog's ToolDescriptor structure
 * to allow easy conversion.
 *
 * ## Creating from Koog Tools
 *
 * ```kotlin
 * // In JVM code with Koog dependency:
 * val koogTool: Tool<*, *> = MyTool
 * val descriptor = SerializableToolDescriptor.fromKoog(koogTool.descriptor)
 * ```
 *
 * ## Manual Creation
 *
 * ```kotlin
 * val descriptor = SerializableToolDescriptor(
 *     name = "book_appointment",
 *     description = "Book an appointment for the customer",
 *     requiredParameters = listOf(
 *         SerializableToolParameterDescriptor(
 *             name = "date",
 *             description = "Date in YYYY-MM-DD format",
 *             type = SerializableToolParameterType.String
 *         ),
 *         SerializableToolParameterDescriptor(
 *             name = "time",
 *             description = "Time in HH:MM format",
 *             type = SerializableToolParameterType.String
 *         ),
 *     ),
 * )
 * ```
 *
 * @property name The tool's identifier (used in function calls)
 * @property description What the tool does (shown to the LLM)
 * @property requiredParameters Parameters that must be provided
 * @property optionalParameters Parameters that may be omitted
 */
@Serializable
public data class SerializableToolDescriptor(
    val name: String,
    val description: String,
    val requiredParameters: List<SerializableToolParameterDescriptor> = emptyList(),
    val optionalParameters: List<SerializableToolParameterDescriptor> = emptyList(),
)

/**
 * Describes a single parameter for a tool.
 *
 * @property name Parameter name in snake_case
 * @property description Human-readable description
 * @property type The parameter's data type
 */
@Serializable
public data class SerializableToolParameterDescriptor(
    val name: String,
    val description: String,
    val type: SerializableToolParameterType,
)

/**
 * Type system for tool parameters.
 *
 * Mirrors Koog's ToolParameterType for compatibility.
 */
@Serializable
public sealed class SerializableToolParameterType {
    @Serializable
    @SerialName("string")
    public data object String : SerializableToolParameterType()

    @Serializable
    @SerialName("integer")
    public data object Integer : SerializableToolParameterType()

    @Serializable
    @SerialName("float")
    public data object Float : SerializableToolParameterType()

    @Serializable
    @SerialName("boolean")
    public data object Boolean : SerializableToolParameterType()

    @Serializable
    @SerialName("null")
    public data object Null : SerializableToolParameterType()

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
    public data class AnyOf(val types: kotlin.collections.List<SerializableToolParameterDescriptor>) : SerializableToolParameterType()
}
