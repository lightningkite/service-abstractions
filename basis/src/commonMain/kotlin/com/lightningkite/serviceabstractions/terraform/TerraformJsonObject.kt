package com.lightningkite.serviceabstractions.terraform

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.collections.getOrPut
import kotlin.jvm.JvmName

public class TerraformJsonObject() {

    public fun tfExpression(expression: String): String = "\${$expression}"

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
    public operator fun String.minus(value: JsonElement) {
        val keys = this.split('.')
        (getObject(keys.dropLast(1)).children.getOrPut(keys.last()) { ValueNode() } as ValueNode).values.add(value)
    }

    public operator fun String.minus(value: String): Unit = minus(JsonPrimitive(value))
    public operator fun String.minus(value: Number): Unit = minus(JsonPrimitive(value))
    public operator fun String.minus(value: Boolean): Unit = minus(JsonPrimitive(value))
    @JvmName("minusListString") public operator fun String.minus(value: List<String>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
    @JvmName("minusListNumber") public operator fun String.minus(value: List<Number>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
    @JvmName("minusListBoolean") public operator fun String.minus(value: List<Boolean>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
    @JvmName("minusListJson") public operator fun String.minus(value: List<JsonElement>): Unit = minus(JsonArray(value) as JsonElement)
    public operator fun String.invoke(builder: Subpath.() -> Unit) {
        Subpath(getObject(split('.'))).apply(builder)
    }

    override fun toString(): String = root.toString()
    public fun toJsonObject(): JsonObject = JsonObject(root.children.mapValues { it.value.toJson() })

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

        public operator fun String.minus(value: String): Unit = minus(JsonPrimitive(value))
        public operator fun String.minus(value: Number): Unit = minus(JsonPrimitive(value))
        public operator fun String.minus(value: Boolean): Unit = minus(JsonPrimitive(value))
        @JvmName("minusListString") public operator fun String.minus(value: List<String>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
        @JvmName("minusListNumber") public operator fun String.minus(value: List<Number>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
        @JvmName("minusListBoolean") public operator fun String.minus(value: List<Boolean>): Unit = minus(JsonArray(value.map(::JsonPrimitive)) as JsonElement)
        @JvmName("minusListJson") public operator fun String.minus(value: List<JsonElement>): Unit = minus(JsonArray(value) as JsonElement)
        public operator fun String.invoke(builder: Subpath.() -> Unit) {
            Subpath(getObject(split('.'))).apply(builder)
        }
    }
}

public inline fun terraformJsonObject(builder: TerraformJsonObject.() -> Unit): JsonObject =
    TerraformJsonObject().apply(builder).toJsonObject()