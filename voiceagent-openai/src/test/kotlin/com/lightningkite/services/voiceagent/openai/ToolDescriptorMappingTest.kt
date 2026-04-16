package com.lightningkite.services.voiceagent.openai

import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.data.Description
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolDescriptorMappingTest {

    @Serializable
    data class GetWeatherArgs(
        @Description("The city and state, e.g. San Francisco, CA")
        val location: String,
        @Description("Temperature unit")
        val unit: TemperatureUnit = TemperatureUnit.celsius,
    )

    @Serializable
    enum class TemperatureUnit { celsius, fahrenheit }

    @Test
    fun `converts typed tool descriptor to OpenAI format`() {
        val descriptor = LlmToolDescriptor(
            name = "get_weather",
            description = "Get the current weather for a location",
            type = serializer<GetWeatherArgs>(),
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

        val unitProp = properties["unit"]?.jsonObject
        assertEquals("string", unitProp?.get("type")?.jsonPrimitive?.content)
        val enumValues = unitProp?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("celsius", "fahrenheit"), enumValues)

        val required = params["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(required)
        assertTrue(required.contains("location"))
    }

    @Serializable
    data class CreateUserArgs(
        @Description("User's name")
        val name: String,
        @Description("User's age")
        val age: Int,
    )

    @Test
    fun `converts nested object type`() {
        val descriptor = LlmToolDescriptor(
            name = "create_user",
            description = "Create a new user",
            type = serializer<CreateUserArgs>(),
        )

        val toolDef = descriptor.toOpenAIToolDefinition()
        val params = toolDef.parameters

        assertEquals("object", params["type"]?.jsonPrimitive?.content)
        val properties = params["properties"]?.jsonObject
        assertNotNull(properties)
        assertTrue(properties.containsKey("name"))
        assertTrue(properties.containsKey("age"))
    }

    @Serializable
    data class ProcessItemsArgs(
        @Description("List of item IDs")
        val items: List<Int>,
    )

    @Test
    fun `converts list type`() {
        val descriptor = LlmToolDescriptor(
            name = "process_items",
            description = "Process a list of items",
            type = serializer<ProcessItemsArgs>(),
        )

        val toolDef = descriptor.toOpenAIToolDefinition()
        val properties = toolDef.parameters["properties"]?.jsonObject
        val itemsProp = properties?.get("items")?.jsonObject

        assertNotNull(itemsProp)
        assertEquals("array", itemsProp["type"]?.jsonPrimitive?.content)
        assertEquals("integer", itemsProp["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Serializable
    data class SetFlagArgs(
        @Description("Whether the feature is enabled")
        val enabled: Boolean,
    )

    @Test
    fun `converts boolean type`() {
        val descriptor = LlmToolDescriptor(
            name = "set_flag",
            description = "Set a boolean flag",
            type = serializer<SetFlagArgs>(),
        )

        val toolDef = descriptor.toOpenAIToolDefinition()
        val properties = toolDef.parameters["properties"]?.jsonObject
        val enabledProp = properties?.get("enabled")?.jsonObject

        assertNotNull(enabledProp)
        assertEquals("boolean", enabledProp["type"]?.jsonPrimitive?.content)
    }

    @Serializable
    data class SetPriceArgs(
        @Description("Product price")
        val price: Double,
    )

    @Test
    fun `converts float type`() {
        val descriptor = LlmToolDescriptor(
            name = "set_price",
            description = "Set product price",
            type = serializer<SetPriceArgs>(),
        )

        val toolDef = descriptor.toOpenAIToolDefinition()
        val properties = toolDef.parameters["properties"]?.jsonObject
        val priceProp = properties?.get("price")?.jsonObject

        assertNotNull(priceProp)
        assertEquals("number", priceProp["type"]?.jsonPrimitive?.content)
    }
}
