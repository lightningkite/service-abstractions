package com.lightningkite.services.ai.test

import com.lightningkite.services.data.MediaType
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.ai.userMessage
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
 * A 100x100 solid-red PNG encoded as base64. A solid color so vision models reliably name it.
 *
 * Sized at 100x100 deliberately: Anthropic's vision endpoint rejects images below its minimum
 * processable size with "Could not process image" (a 4x4 image fails), so this is kept well
 * above a handful of pixels while staying tiny enough to keep tests cheap.
 *
 * Generated offline as a solid (255, 0, 0) 8-bit RGB PNG.
 */
public const val TINY_RED_PNG_BASE64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAGQAAABkCAIAAAD/gAIDAAAAkElEQVR42u3QMQ0AAAjAsPk3DRb4eJpUQZviSIEsWbJk" +
        "yUKBLFmyZMlCgSxZsmTJQoEsWbJkyUKBLFmyZMlCgSxZsmTJQoEsWbJkyUKBLFmyZMlCgSxZsmTJQoEsWbJkyUKB" +
        "LFmyZMlCgSxZsmTJQoEsWbJkyUKBLFmyZMlCgSxZsmTJQoEsWbJkyUKBLFnvFp4t6yugc3LNAAAAAElFTkSuQmCC"

/**
 * A public URL serving a 200x200 solid-blue PNG.
 *
 * Used by the URL-attachment multimodal test, which requires a URL the provider's image
 * fetcher can download. The previous Wikimedia URL 404'd (it was an SVG path, not a raster
 * file) and Wikimedia also blocks unidentified fetchers; placehold.co serves a plain
 * raster PNG built for hotlinking. Providers that only accept base64 attachments can opt out
 * via [LlmAccessTests.supportsUrlAttachments].
 */
public const val STABLE_BLUE_IMAGE_URL: String =
    "https://placehold.co/200x200/0000FF/0000FF.png"

/**
 * Convenience constructor for a single user-text message.
 */
@Deprecated("Use userMessage(text) from com.lightningkite.services.ai", ReplaceWith("userMessage(text)", "com.lightningkite.services.ai.userMessage"))
public fun userText(text: String): LlmMessage.User = userMessage(text)

/**
 * Convenience: build a system prompt content list from a single text string.
 */
@Deprecated("Use systemPrompt(text) from com.lightningkite.services.ai", ReplaceWith("systemPrompt(text)", "com.lightningkite.services.ai.systemPrompt"))
public fun systemPrompt(text: String): List<LlmPart.ContentOnly> =
    com.lightningkite.services.ai.systemPrompt(text)

/**
 * Convenience constructor for a user message carrying one attachment and a caption.
 */
@Deprecated("Use userMessage(caption, attachment) from com.lightningkite.services.ai", ReplaceWith("userMessage(caption, attachment)", "com.lightningkite.services.ai.userMessage"))
public fun userWithAttachment(caption: String, attachment: LlmAttachment): LlmMessage.User =
    userMessage(caption, attachment)

/** Shorthand for the PNG [MediaType], used by the multimodal tests. */
public val pngMediaType: MediaType get() = MediaType.Image.PNG
