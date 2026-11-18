# Voice agents

`VoiceAgentService` opens real-time speech-to-speech sessions with a model (OpenAI Realtime): stream microphone audio in, get spoken replies, transcripts and tool calls back as events.  `voiceagent-phonecall` connects a session to a [phone call](phonecall.md).

## Modules

| Module | Add it for |
|---|---|
| `voiceagent` | `VoiceAgentService`, `VoiceAgentSession`, events, `test` and `console`.  Multiplatform. |
| `voiceagent-openai` | `openai-realtime://`.  JVM. |
| `voiceagent-phonecall` | `handlePhoneVoiceSession` / `handleDirectVoiceSession`: bridge phone or browser audio to a session, with format conversion and a jitter buffer.  JVM. |
| `voiceagent-test` | Shared contract suites for providers. |

## Quick start

```kotlin
OpenAIVoiceAgentService  // registers openai-realtime://
val agents = VoiceAgentService.Settings("openai-realtime://gpt-4o-realtime-preview?apiKey=\${OPENAI_API_KEY}")("agent", context)

val session = agents.createSession(VoiceAgentSessionConfig(instructions = "You are a friendly receptionist."))
session.awaitConnection()
launch {
    session.events.collect { event ->
        when (event) {
            is VoiceAgentEvent.AudioDelta -> speaker.play(event.delta)         // base64 audio
            is VoiceAgentEvent.ToolCallDone -> session.sendToolResult(event.callId, runTool(event.toolName, event.arguments))
            else -> {}
        }
    }
}
microphone.collect { chunk -> session.sendAudio(chunk) }   // PCM16, 24 kHz by default
```

## Common tasks

- **Configure**: `VoiceAgentSessionConfig` sets `instructions`, `voice`, input/output `AudioFormat`, `turnDetection` (server voice-activity detection by default), `inputTranscription`, `tools` (the same `LlmToolDescriptor`s as [ai.md](ai.md#call-tools)), `temperature`.  Change it mid-session with `updateSession`.
- **Text in**: `session.addMessage(VoiceAgentSession.MessageRole.User, "Are you open today?")`, then `session.createResponse()`.
- **Barge-in**: `cancelResponse()` stops the reply; `clearInputBuffer()` drops unprocessed audio.  With turn detection off, call `commitAudio()` to end the user's turn.
- **Phone calls**: answer with `createVoiceAgentStreamInstructions(webSocketUrl)` so the provider streams the call's audio to your WebSocket, then run `handlePhoneVoiceSession(...)` there.

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `test`, `console` | `voiceagent` | `test` (the default) |
| `openai-realtime` | `voiceagent-openai` | `openai-realtime://gpt-4o-realtime-preview?apiKey=${OPENAI_API_KEY}` |

The model is in the URL (default `gpt-4o-realtime-preview`); the key defaults to `OPENAI_API_KEY`.  Details: [Settings URL reference: Voice agent](settings-url-reference.md#voice-agent).

## Testing

`test` sessions emit a canned conversation: `./gradlew :demo:runVoiceagentDemo` prints the events.  Provider authors: subclass the suites in `voiceagent-test`.

## Gotchas

- **Events can drop**: the OpenAI session buffers 64 events and drops the oldest when you fall behind.  Keep the collector fast; hand slow work (tool calls) to another coroutine.
- **Audio formats must match** what the session was configured with; phone audio (8 kHz μ-law) needs converting, which `voiceagent-phonecall` does.
