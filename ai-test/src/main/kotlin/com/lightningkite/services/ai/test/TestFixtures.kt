package com.lightningkite.services.ai.test

import com.lightningkite.MediaType
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmContent
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmMessageSource
import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.data.Description
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Serializable argument types used by the shared tool-calling test suite. Defined once here so
 * tests in every `LlmAccess*Tests` class reference the same tool surface.
 */
@Serializable
public data class WeatherArgs(
    @Description("City name, e.g. 'Tokyo' or 'Paris'.") public val city: String,
    public val units: TemperatureUnits = TemperatureUnits.Celsius,
)

@Serializable
public enum class TemperatureUnits { Celsius, Fahrenheit }

@Serializable
public data class CurrentTimeArgs(
    @Description("IANA time zone name, e.g. 'America/Denver' or 'UTC'.")
    public val timeZone: String = "UTC",
)

/**
 * Nested-object tool args: a top-level request containing a [WeatherArgs] and free-form notes.
 * Mirrors the shape used by the JSON-schema round-trip tests in `:ai`.
 */
@Serializable
public data class NestedWeatherRequest(
    public val weather: WeatherArgs,
    public val notes: List<String> = emptyList(),
)

/**
 * The "weather" tool the suite uses throughout. Stable name so provider-specific
 * subclasses can log / filter against it.
 */
public val weatherTool: LlmToolDescriptor = LlmToolDescriptor(
    name = "get_weather",
    description = "Fetch the current weather for a city.",
    type = serializer<WeatherArgs>(),
)

public val currentTimeTool: LlmToolDescriptor = LlmToolDescriptor(
    name = "get_current_time",
    description = "Fetch the current time in the given IANA time zone.",
    type = serializer<CurrentTimeArgs>(),
)

public val nestedWeatherTool: LlmToolDescriptor = LlmToolDescriptor(
    name = "get_weather_nested",
    description = "Fetch weather with additional metadata.",
    type = serializer<NestedWeatherRequest>(),
)

/**
 * A 4x4 solid-red PNG encoded as base64. Small enough to keep tests cheap, large enough for
 * multimodal models to reliably identify the color.
 *
 * Generated offline with Python PIL: `Image.new("RGB", (4, 4), (255, 0, 0))`.
 */
public const val TINY_RED_PNG_BASE64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAF0lEQVQIW2P8z8DwnwEJMGGKjAqxIgQAYX4CHv1Q+X" +
        "IAAAAASUVORK5CYII="

/**
 * A stable public URL pointing at a solid-blue image hosted on Wikimedia Commons.
 *
 * Used by the URL-attachment multimodal test. Providers that only accept base64 attachments
 * can opt out via [LlmAccessTests.supportsUrlAttachments].
 */
public const val STABLE_BLUE_IMAGE_URL: String =
    "https://upload.wikimedia.org/wikipedia/commons/3/38/Solid_blue.svg.png"

/**
 * Convenience constructor for a single user-text message.
 */
public fun userText(text: String): LlmMessage =
    LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text(text)))

/**
 * Convenience constructor for a system-instruction message.
 */
public fun systemText(text: String): LlmMessage =
    LlmMessage(LlmMessageSource.System, listOf(LlmContent.Text(text)))

/**
 * Convenience constructor for a user message carrying one attachment and a caption.
 */
public fun userWithAttachment(caption: String, attachment: LlmAttachment): LlmMessage =
    LlmMessage(
        LlmMessageSource.User,
        listOf(LlmContent.Text(caption), LlmContent.Attachment(attachment)),
    )

/**
 * Concatenate every [LlmContent.Text] block in the message into one string. Useful for
 * tests that want the raw assistant text without caring how the provider chunks it.
 */
public fun LlmMessage.plainText(): String =
    content.filterIsInstance<LlmContent.Text>().joinToString("") { it.text }

/**
 * Return the first [LlmContent.ToolCall] in the message, or null.
 */
public fun LlmMessage.firstToolCall(): LlmContent.ToolCall? =
    content.filterIsInstance<LlmContent.ToolCall>().firstOrNull()

/**
 * Return all [LlmContent.ToolCall] blocks in the message.
 */
public fun LlmMessage.toolCalls(): List<LlmContent.ToolCall> =
    content.filterIsInstance<LlmContent.ToolCall>()

/** Shorthand for the PNG [MediaType], used by the multimodal tests. */
public val pngMediaType: MediaType get() = MediaType.Image.PNG
