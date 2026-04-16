package com.lightningkite.services.ai.test

import com.lightningkite.MediaType
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
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
public val weatherTool: LlmToolDescriptor<WeatherArgs> = LlmToolDescriptor(
    name = "get_weather",
    description = "Fetch the current weather for a city.",
    type = serializer<WeatherArgs>(),
)

public val currentTimeTool: LlmToolDescriptor<CurrentTimeArgs> = LlmToolDescriptor(
    name = "get_current_time",
    description = "Fetch the current time in the given IANA time zone.",
    type = serializer<CurrentTimeArgs>(),
)

public val nestedWeatherTool: LlmToolDescriptor<NestedWeatherRequest> = LlmToolDescriptor(
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
public fun userText(text: String): LlmMessage.User =
    LlmMessage.User(listOf(LlmPart.Text(text)))

/**
 * Convenience: build a [LlmPrompt] with a system instruction as the first argument.
 */
public fun systemPrompt(text: String): List<LlmPart.ContentOnly> =
    listOf(LlmPart.Text(text))

/**
 * Convenience constructor for a user message carrying one attachment and a caption.
 */
public fun userWithAttachment(caption: String, attachment: LlmAttachment): LlmMessage.User =
    LlmMessage.User(listOf(LlmPart.Text(caption), LlmPart.Attachment(attachment)))

/** Shorthand for the PNG [MediaType], used by the multimodal tests. */
public val pngMediaType: MediaType get() = MediaType.Image.PNG
