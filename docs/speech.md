# Speech

`TextToSpeechService` turns text into audio and `SpeechToTextService` turns audio into text: OpenAI, ElevenLabs, or offline engines (FreeTTS, Vosk).  Audio is `TypedData` (see [data.md](data.md)).

## Modules

| Module | Add it for |
|---|---|
| `speech` | Both interfaces, `test` and `console`.  Multiplatform. |
| `speech-openai` | `openai://` for TTS (`tts-1`, `tts-1-hd`) and STT (`whisper-1`).  JVM. |
| `speech-elevenlabs` | `elevenlabs://` for TTS and STT (Scribe).  JVM. |
| `speech-local` | Offline: `freetts://` (TTS) and `vosk://` (STT); `local://` means either.  JVM. |
| `speech-test` | Shared contract suites for providers. |

## Quick start

```kotlin
OpenAITextToSpeechService; OpenAISpeechToTextService  // register openai://
val tts = TextToSpeechService.Settings("openai://\${OPENAI_API_KEY}@tts-1")("tts", context)
val stt = SpeechToTextService.Settings("openai://\${OPENAI_API_KEY}@whisper-1")("stt", context)

val audio: TypedData = tts.synthesize("Your table is ready.", TtsVoiceConfig(voiceId = "alloy"))
val result: TranscriptionResult = stt.transcribe(recording, TranscriptionOptions(language = "en"))
println(result.text)
```

## Common tasks

- **Stream long text**: `tts.synthesizeStream(text)` emits audio chunks as they're generated.
- **Pick a voice**: `tts.listVoices(language = "en-US")` returns `VoiceInfo`s; pass `voiceId` in `TtsVoiceConfig`.  `stability`, `similarityBoost` and `style` there are ElevenLabs controls.
- **Format and speed**: `TtsSynthesisOptions(outputFormat = AudioFormat.MP3_44100_128, speed = 1.1f, model = ...)`.
- **Transcribe a URL**: `stt.transcribeUrl("https://.../voicemail.mp3")`.
- **Word timings and speakers**: `TranscriptionOptions(wordTimestamps = true, speakerDiarization = true)`; the result has `words` and `speakers` where the provider supports them.

## Settings URLs

| Scheme | Service | Module | Example |
|---|---|---|---|
| `test`, `console` | both | `speech` | `test` |
| `openai` | both | `speech-openai` | `openai://${OPENAI_API_KEY}@tts-1` |
| `elevenlabs` | both | `speech-elevenlabs` | `elevenlabs://${ELEVENLABS_API_KEY}@eleven_multilingual_v2` |
| `freetts`, `local` | TTS | `speech-local` | `freetts://` |
| `vosk`, `local` | STT | `speech-local` | `vosk://?modelName=vosk-model-small-en-us-0.15` |

The form is `<scheme>://<apiKey>@<model>`; without a key in the URL, `OPENAI_API_KEY` / `ELEVENLABS_API_KEY` is used.  Details: [Settings URL reference: Speech-to-Text](settings-url-reference.md#speech-to-text) and [Text-to-Speech](settings-url-reference.md#text-to-speech).  Reference the implementation class (`OpenAITextToSpeechService`, `ElevenLabsSpeechToTextService`, `VoskSpeechToTextService`, ...) before parsing settings.

## Testing

`test` is the default for both.  The fakes return canned results and record every call:

```kotlin
val tts = TextToSpeechService.Settings("test")("tts", TestSettingContext()) as TestTextToSpeechService
val stt = SpeechToTextService.Settings("test")("stt", TestSettingContext()) as TestSpeechToTextService
stt.mockTranscriptionText = "I'd like to book a table"
handleVoicemail(recording)
assertEquals("Thanks, we'll call you back.", tts.synthesisHistory.last().text)
```

Provider authors: subclass `TextToSpeechServiceTests` / `SpeechToTextServiceTests` in `speech-test`.

## Gotchas

- **`openai://whisper-1`** (model but no `@`) ignores the model and uses the default.  Write `openai://<key>@whisper-1`, or `openai://?model=whisper-1` to use the environment's key.
- **Vosk downloads its model** (tens of MB) into `./local/vosk-models` on first use.
- **FreeTTS** sounds robotic; it's for offline development.
