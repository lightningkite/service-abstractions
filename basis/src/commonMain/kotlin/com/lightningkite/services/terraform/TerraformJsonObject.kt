package com.lightningkite.services.terraform

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.collections.getOrPut
import kotlin.jvm.JvmName

/**
 * DSL builder for constructing Terraform JSON configuration.
 *
 * Terraform can be configured using either HCL (HashiCorp Configuration Language)
 * or JSON. This class provides a Kotlin DSL for building Terraform JSON configurations
 * programmatically with type-safe property assignment.
 *
 * ## Key Features
 *
 * - **Hierarchical structure**: Use dot notation for nested paths
 * - **Multiple values**: Same path can have multiple values (creates JSON arrays)
 * - **Type-safe values**: Supports strings, numbers, booleans, and lists
 * - **Terraform expressions**: Use [expression] for interpolation
 *
 * ## Usage
 *
 * ```kotlin
 * val config = terraformJsonObject {
 *     "resource.aws_instance.example" {
 *         "ami" - "ami-12345678"
 *         "instance_type" - "t2.micro"
 *         "tags" {
 *             "Name" - "example-instance"
 *             "Environment" - "production"
 *         }
 *     }
 *
 *     "resource.aws_security_group.example" {
 *         "vpc_id" - expression("aws_vpc.main.id")
 *         "ingress" {
 *             "from_port" - 80
 *             "to_port" - 80
 *             "protocol" - "tcp"
 *             "cidr_blocks" - listOf("0.0.0.0/0")
 *         }
 *     }
 * }
 * ```
 *
 * Produces JSON:
 * ```json
 * {
 *   "resource": {
 *     "aws_instance": {
 *       "example": {
 *         "ami": "ami-12345678",
 *         "instance_type": "t2.micro",
 *         "tags": {
 *           "Name": "example-instance",
 *           "Environment": "production"
 *         }
 *       }
 *     },
 *     "aws_security_group": {
 *       "example": {
 *         "vpc_id": "${aws_vpc.main.id}",
 *         "ingress": {
 *           "from_port": 80,
 *           "to_port": 80,
 *           "protocol": "tcp",
 *           "cidr_blocks": ["0.0.0.0/0"]
 *         }
 *       }
 *     }
 *   }
 * }
 * ```
 */
public class TerraformJsonObject() {

    /**
     * Creates a Terraform expression string for variable interpolation.
     *
     * In Terraform JSON, expressions use `${}` syntax. This method wraps the
     * expression appropriately for JSON representation.
     *
     * Example: `expression("var.region")` → `"${var.region}"`
     *
     * @param expression The Terraform expression without `${}`
     * @return The expression wrapped for JSON
     */
    public fun expression(expression: String): String = "\${$expression}"

    public companion object {
        /**
         * Creates a Terraform expression string (static version).
         *
         * @see expression instance method for details
         */
        public fun expression(expression: String): String = "\${$expression}"
    }

    internal sealed interface Node {
        fun toJson(): JsonElement
    }

    internal class ValueNode() : Node {
        internal val values: ArrayList<JsonElement> = ArrayList()
        override fun toJson(): JsonElement = if (values.size == 1) values[0] else JsonArray(values)
        override fun toString(): String = values.toString()
    }

    internal class ObjectNode : Node {
        internal val children: HashMap<String, Node> = HashMap()
        override fun toJson(): JsonElement = JsonObject(children.mapValues { it.value.toJson() })
        override fun toString(): String = children.toString()
    }

    internal val root = ObjectNode()

    internal fun getObject(keys: List<String>): ObjectNode {
        var current = root
        for (key in keys) {
            current = (current.children.getOrPut(key) { ObjectNode() } as ObjectNode)
        }
        return current
    }

    /**
     * Assigns a JSON value to a path. Example: `"resource.aws_instance.name" - "value"`
     */
    public operator fun String.minus(value: JsonElement) {
        val keys = this.split('.')
        (getObject(keys.dropLast(1)).children.getOrPut(keys.last()) { ValueNode() } as ValueNode).values.add(value)
    }

    /** Assigns a string value to a path. */
    public operator fun String.minus(value: String?): Unit = minus(JsonPrimitive(value))
    /** Assigns a numeric value to a path. */
    public operator fun String.minus(value: Number?): Unit = minus(JsonPrimitive(value))
    /** Assigns a boolean value to a path. */
    public operator fun String.minus(value: Boolean?): Unit = minus(JsonPrimitive(value))
    /** Assigns a list of strings to a path. */
    @JvmName("minusListString") public operator fun String.minus(value: List<String?>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
    /** Assigns a list of numbers to a path. */
    @JvmName("minusListNumber") public operator fun String.minus(value: List<Number?>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
    /** Assigns a list of booleans to a path. */
    @JvmName("minusListBoolean") public operator fun String.minus(value: List<Boolean?>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
    /** Assigns a list of JSON elements to a path. */
    @JvmName("minusListJson") public operator fun String.minus(value: List<JsonElement>): Unit = minus(JsonArray(value) as JsonElement)

    /**
     * Scopes assignments within a path. Example: `"resource.aws_instance" { "name" - "value" }`
     */
    public operator fun String.invoke(builder: Subpath.() -> Unit) {
        Subpath(getObject(split('.'))).apply(builder)
    }

    override fun toString(): String = root.toString()

    /**
     * Converts the builder state to a JsonObject ready for serialization.
     */
    public fun toJsonObject(): JsonObject = JsonObject(root.children.mapValues { it.value.toJson() })

    /**
     * Merges another JsonObject into this builder.
     */
    public fun include(other: JsonObject) {
        for((key, value) in other) {
            key - value
        }
    }

    /**
     * Scoped builder for assignments within a specific path. Same operators as parent class.
     */
    public inner class Subpath internal constructor(private val root: ObjectNode) {
        internal fun getObject(keys: List<String>): ObjectNode {
            var current = root
            for (key in keys) {
                current = (current.children.getOrPut(key) { ObjectNode() } as ObjectNode)
            }
            return current
        }
        public operator fun String.minus(value: JsonElement) {
            val keys = this.split('.')
            (getObject(keys.dropLast(1)).children.getOrPut(keys.last()) { ValueNode() } as ValueNode).values.add(value)
        }

        public operator fun String.minus(value: String?): Unit = minus(JsonPrimitive(value))
        public operator fun String.minus(value: Number?): Unit = minus(JsonPrimitive(value))
        public operator fun String.minus(value: Boolean?): Unit = minus(JsonPrimitive(value))
        @JvmName("minusListString") public operator fun String.minus(value: List<String?>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
        @JvmName("minusListNumber") public operator fun String.minus(value: List<Number?>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
        @JvmName("minusListBoolean") public operator fun String.minus(value: List<Boolean?>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
        @JvmName("minusListJson") public operator fun String.minus(value: List<JsonElement>): Unit = minus(JsonArray(value) as JsonElement)
        public operator fun String.invoke(builder: Subpath.() -> Unit) {
            Subpath(getObject(split('.'))).apply(builder)
        }

        public fun include(other: JsonObject) {
            for ((key, value) in other) {
                key - value
            }
        }
    }
}

/**
 * Convenience function to create a Terraform JSON configuration.
 *
 * @param builder DSL builder block for constructing the configuration
 * @return The resulting JsonObject
 */
public inline fun terraformJsonObject(builder: TerraformJsonObject.() -> Unit): JsonObject =
    TerraformJsonObject().apply(builder).toJsonObject()
