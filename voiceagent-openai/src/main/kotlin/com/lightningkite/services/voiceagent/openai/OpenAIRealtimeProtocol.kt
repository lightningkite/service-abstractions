package com.lightningkite.services.voiceagent.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI Realtime API client events (sent from client to server).
 */
@Serializable
internal sealed class ClientEvent {
    abstract val eventId: String?

    @Serializable
    @SerialName("session.update")
    data class SessionUpdate(
        @SerialName("event_id") override val eventId: String? = null,
        val session: SessionConfig,
    ) : ClientEvent()

    @Serializable
    @SerialName("input_audio_buffer.append")
    data class InputAudioBufferAppend(
        @SerialName("event_id") override val eventId: String? = null,
        val audio: String, // Base64 encoded
    ) : ClientEvent()

    @Serializable
    @SerialName("input_audio_buffer.commit")
    data class InputAudioBufferCommit(
        @SerialName("event_id") override val eventId: String? = null,
    ) : ClientEvent()

    @Serializable
    @SerialName("input_audio_buffer.clear")
    data class InputAudioBufferClear(
        @SerialName("event_id") override val eventId: String? = null,
    ) : ClientEvent()

    @Serializable
    @SerialName("conversation.item.create")
    data class ConversationItemCreate(
        @SerialName("event_id") override val eventId: String? = null,
        @SerialName("previous_item_id") val previousItemId: String? = null,
        val item: ConversationItem,
    ) : ClientEvent()

    @Serializable
    @SerialName("response.create")
    data class ResponseCreate(
        @SerialName("event_id") override val eventId: String? = null,
        val response: ResponseConfig? = null,
    ) : ClientEvent()

    @Serializable
    @SerialName("response.cancel")
    data class ResponseCancel(
        @SerialName("event_id") override val eventId: String? = null,
    ) : ClientEvent()
}

/**
 * OpenAI Realtime API server events (sent from server to client).
 */
@Serializable
internal sealed class ServerEvent {
    abstract val eventId: String

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("event_id") override val eventId: String,
        val error: ErrorInfo,
    ) : ServerEvent()

    @Serializable
    @SerialName("session.created")
    data class SessionCreated(
        @SerialName("event_id") override val eventId: String,
        val session: SessionInfo,
    ) : ServerEvent()

    @Serializable
    @SerialName("session.updated")
    data class SessionUpdated(
        @SerialName("event_id") override val eventId: String,
        val session: SessionInfo,
    ) : ServerEvent()

    @Serializable
    @SerialName("input_audio_buffer.committed")
    data class InputAudioBufferCommitted(
        @SerialName("event_id") override val eventId: String,
        @SerialName("previous_item_id") val previousItemId: String?,
        @SerialName("item_id") val itemId: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("input_audio_buffer.cleared")
    data class InputAudioBufferCleared(
        @SerialName("event_id") override val eventId: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("input_audio_buffer.speech_started")
    data class InputAudioBufferSpeechStarted(
        @SerialName("event_id") override val eventId: String,
        @SerialName("audio_start_ms") val audioStartMs: Long,
        @SerialName("item_id") val itemId: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("input_audio_buffer.speech_stopped")
    data class InputAudioBufferSpeechStopped(
        @SerialName("event_id") override val eventId: String,
        @SerialName("audio_end_ms") val audioEndMs: Long,
        @SerialName("item_id") val itemId: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("conversation.item.created")
    data class ConversationItemCreated(
        @SerialName("event_id") override val eventId: String,
        @SerialName("previous_item_id") val previousItemId: String?,
        val item: ConversationItem,
    ) : ServerEvent()

    @Serializable
    @SerialName("conversation.item.input_audio_transcription.completed")
    data class InputAudioTranscriptionCompleted(
        @SerialName("event_id") override val eventId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("content_index") val contentIndex: Int,
        val transcript: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("conversation.item.input_audio_transcription.failed")
    data class InputAudioTranscriptionFailed(
        @SerialName("event_id") override val eventId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("content_index") val contentIndex: Int,
        val error: ErrorInfo,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.created")
    data class ResponseCreated(
        @SerialName("event_id") override val eventId: String,
        val response: ResponseInfo,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.done")
    data class ResponseDone(
        @SerialName("event_id") override val eventId: String,
        val response: ResponseInfo,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.output_item.added")
    data class ResponseOutputItemAdded(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("output_index") val outputIndex: Int,
        val item: ConversationItem,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.output_item.done")
    data class ResponseOutputItemDone(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("output_index") val outputIndex: Int,
        val item: ConversationItem,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.content_part.added")
    data class ResponseContentPartAdded(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("content_index") val contentIndex: Int,
        val part: ContentPart,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.content_part.done")
    data class ResponseContentPartDone(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("content_index") val contentIndex: Int,
        val part: ContentPart,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.audio.delta")
    data class ResponseAudioDelta(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("content_index") val contentIndex: Int,
        val delta: String, // Base64 encoded audio
    ) : ServerEvent()

    @Serializable
    @SerialName("response.audio.done")
    data class ResponseAudioDone(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("content_index") val contentIndex: Int,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.audio_transcript.delta")
    data class ResponseAudioTranscriptDelta(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("content_index") val contentIndex: Int,
        val delta: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.audio_transcript.done")
    data class ResponseAudioTranscriptDone(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("content_index") val contentIndex: Int,
        val transcript: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.text.delta")
    data class ResponseTextDelta(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("content_index") val contentIndex: Int,
        val delta: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.text.done")
    data class ResponseTextDone(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("content_index") val contentIndex: Int,
        val text: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.function_call_arguments.delta")
    data class ResponseFunctionCallArgumentsDelta(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("call_id") val callId: String,
        val delta: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("response.function_call_arguments.done")
    data class ResponseFunctionCallArgumentsDone(
        @SerialName("event_id") override val eventId: String,
        @SerialName("response_id") val responseId: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("output_index") val outputIndex: Int,
        @SerialName("call_id") val callId: String,
        val name: String,
        val arguments: String,
    ) : ServerEvent()

    @Serializable
    @SerialName("rate_limits.updated")
    data class RateLimitsUpdated(
        @SerialName("event_id") override val eventId: String,
        @SerialName("rate_limits") val rateLimits: List<RateLimitInfoOpenAI>,
    ) : ServerEvent()
}

// Supporting types

@Serializable
internal data class SessionConfig(
    val modalities: List<String>? = null,
    val instructions: String? = null,
    val voice: String? = null,
    @SerialName("input_audio_format") val inputAudioFormat: String? = null,
    @SerialName("output_audio_format") val outputAudioFormat: String? = null,
    @SerialName("input_audio_transcription") val inputAudioTranscription: TranscriptionConfigOpenAI? = null,
    @SerialName("turn_detection") val turnDetection: TurnDetectionConfigOpenAI? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_response_output_tokens") val maxResponseOutputTokens: JsonElement? = null,
)

@Serializable
internal data class SessionInfo(
    val id: String,
    val model: String? = null,
    val modalities: List<String>? = null,
    val instructions: String? = null,
    val voice: String? = null,
    @SerialName("input_audio_format") val inputAudioFormat: String? = null,
    @SerialName("output_audio_format") val outputAudioFormat: String? = null,
    @SerialName("input_audio_transcription") val inputAudioTranscription: TranscriptionConfigOpenAI? = null,
    @SerialName("turn_detection") val turnDetection: TurnDetectionConfigOpenAI? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_response_output_tokens") val maxResponseOutputTokens: JsonElement? = null,
)

@Serializable
internal data class TranscriptionConfigOpenAI(
    val model: String? = null,
    val language: String? = null,
    val prompt: String? = null,
)

@Serializable
internal data class TurnDetectionConfigOpenAI(
    val type: String,
    val threshold: Double? = null,
    @SerialName("prefix_padding_ms") val prefixPaddingMs: Int? = null,
    @SerialName("silence_duration_ms") val silenceDurationMs: Int? = null,
    @SerialName("create_response") val createResponse: Boolean? = null,
    @SerialName("interrupt_response") val interruptResponse: Boolean? = null,
    val eagerness: String? = null,
)

@Serializable
internal data class ToolDefinition(
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class ResponseConfig(
    val modalities: List<String>? = null,
    val instructions: String? = null,
    val voice: String? = null,
    @SerialName("output_audio_format") val outputAudioFormat: String? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: JsonElement? = null,
)

@Serializable
internal data class ResponseInfo(
    val id: String,
    val status: String,
    @SerialName("status_details") val statusDetails: JsonElement? = null,
    val output: List<ConversationItem>? = null,
    val usage: UsageInfoOpenAI? = null,
)

@Serializable
internal data class UsageInfoOpenAI(
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("input_token_details") val inputTokenDetails: TokenDetailsOpenAI? = null,
    @SerialName("output_token_details") val outputTokenDetails: TokenDetailsOpenAI? = null,
)

@Serializable
internal data class TokenDetailsOpenAI(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
    @SerialName("text_tokens") val textTokens: Int? = null,
    @SerialName("audio_tokens") val audioTokens: Int? = null,
)

@Serializable
internal data class ConversationItem(
    val id: String? = null,
    val type: String, // "message", "function_call", "function_call_output"
    val status: String? = null,
    val role: String? = null,
    val content: List<ContentPart>? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val output: String? = null,
)

@Serializable
internal data class ContentPart(
    val type: String, // "input_text", "input_audio", "text", "audio"
    val text: String? = null,
    val audio: String? = null,
    val transcript: String? = null,
)

@Serializable
internal data class ErrorInfo(
    val type: String,
    val code: String? = null,
    val message: String,
    val param: String? = null,
    @SerialName("event_id") val eventId: String? = null,
)

@Serializable
internal data class RateLimitInfoOpenAI(
    val name: String,
    val limit: Int,
    val remaining: Int,
    @SerialName("reset_seconds") val resetSeconds: Double,
)
