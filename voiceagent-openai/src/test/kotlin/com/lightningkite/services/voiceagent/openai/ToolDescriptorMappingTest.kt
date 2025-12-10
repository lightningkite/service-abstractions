package com.lightningkite.services.voiceagent.openai

import com.lightningkite.services.voiceagent.SerializableToolDescriptor
import com.lightningkite.services.voiceagent.SerializableToolParameterDescriptor
import com.lightningkite.services.voiceagent.SerializableToolParameterType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolDescriptorMappingTest {

    @Test
    fun `converts simple tool descriptor to OpenAI format`() {
        val descriptor = SerializableToolDescriptor(
            name = "get_weather",
            description = "Get the current weather for a location",
            requiredParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "location",
                    description = "The city and state, e.g. San Francisco, CA",
                    type = SerializableToolParameterType.String
                )
            ),
            optionalParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "unit",
                    description = "Temperature unit",
                    type = SerializableToolParameterType.Enum(listOf("celsius", "fahrenheit"))
                )
            )
        )

        val toolDef = descriptor.toOpenAIToolDefinition()

        assertEquals("function", toolDef.type)
        assertEquals("get_weather", toolDef.name)
        assertEquals("Get the current weather for a location", toolDef.description)

        val params = toolDef.parameters
        assertEquals("object", params["type"]?.jsonPrimitive?.content)

        val properties = params["properties"]?.jsonObject
        assertNotNull(properties)
        assertTrue(properties.containsKey("location"))
        assertTrue(properties.containsKey("unit"))

        val locationProp = properties["location"]?.jsonObject
        assertEquals("string", locationProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("The city and state, e.g. San Francisco, CA", locationProp?.get("description")?.jsonPrimitive?.content)

        val unitProp = properties["unit"]?.jsonObject
        assertEquals("string", unitProp?.get("type")?.jsonPrimitive?.content)
        val enumValues = unitProp?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("celsius", "fahrenheit"), enumValues)

        val required = params["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("location"), required)
    }

    @Test
    fun `converts nested object type`() {
        val descriptor = SerializableToolDescriptor(
            name = "create_user",
            description = "Create a new user",
            requiredParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "user",
                    description = "User information",
                    type = SerializableToolParameterType.Object(
                        properties = listOf(
                            SerializableToolParameterDescriptor(
                                name = "name",
                                description = "User's name",
                                type = SerializableToolParameterType.String
                            ),
                            SerializableToolParameterDescriptor(
                                name = "age",
                                description = "User's age",
                                type = SerializableToolParameterType.Integer
                            )
                        ),
                        requiredProperties = listOf("name")
                    )
                )
            )
        )

        val toolDef = descriptor.toOpenAIToolDefinition()
        val userProp = toolDef.parameters["properties"]?.jsonObject?.get("user")?.jsonObject

        assertNotNull(userProp)
        assertEquals("object", userProp["type"]?.jsonPrimitive?.content)

        val nestedProps = userProp["properties"]?.jsonObject
        assertTrue(nestedProps?.containsKey("name") == true)
        assertTrue(nestedProps?.containsKey("age") == true)

        val nestedRequired = userProp["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("name"), nestedRequired)
    }

    @Test
    fun `converts list type`() {
        val descriptor = SerializableToolDescriptor(
            name = "process_items",
            description = "Process a list of items",
            requiredParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "items",
                    description = "List of item IDs",
                    type = SerializableToolParameterType.ListType(SerializableToolParameterType.Integer)
                )
            )
        )

        val toolDef = descriptor.toOpenAIToolDefinition()
        val itemsProp = toolDef.parameters["properties"]?.jsonObject?.get("items")?.jsonObject

        assertNotNull(itemsProp)
        assertEquals("array", itemsProp["type"]?.jsonPrimitive?.content)
        assertEquals("integer", itemsProp["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `converts boolean type`() {
        val descriptor = SerializableToolDescriptor(
            name = "set_flag",
            description = "Set a boolean flag",
            requiredParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "enabled",
                    description = "Whether the feature is enabled",
                    type = SerializableToolParameterType.Boolean
                )
            )
        )

        val toolDef = descriptor.toOpenAIToolDefinition()
        val enabledProp = toolDef.parameters["properties"]?.jsonObject?.get("enabled")?.jsonObject

        assertNotNull(enabledProp)
        assertEquals("boolean", enabledProp["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `converts float type`() {
        val descriptor = SerializableToolDescriptor(
            name = "set_price",
            description = "Set product price",
            requiredParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "price",
                    description = "Product price",
                    type = SerializableToolParameterType.Float
                )
            )
        )

        val toolDef = descriptor.toOpenAIToolDefinition()
        val priceProp = toolDef.parameters["properties"]?.jsonObject?.get("price")?.jsonObject

        assertNotNull(priceProp)
        assertEquals("number", priceProp["type"]?.jsonPrimitive?.content)
    }
}
