# Service Abstractions Demo

Small, self-contained `fun main()` files showing each Service Abstractions subsystem actually
working, driven through the settings-URL mechanism using local/fake implementations - no
credentials required (except `ai`/`embedding`, which have no fake provider).

Run any of them with `./gradlew :demo:<task>`:

| Task | Subsystem | Shows |
|---|---|---|
| `runCombinedDemo` | all of the above | One `AppSettings` data class, one JSON config, one `TestSettingContext` - the real-world wiring pattern for a whole app |
| `runDatabaseDemo` | database | Insert/query/update/delete via `ram://`, using a `@GenerateDataClassPaths` model for type-safe conditions/modifications |
| `runCacheDemo` | cache | `get`/`set`/`setIfNotExists`/`remove` and TTL expiry via `ram://` |
| `runEmailDemo` | email | Send via `test://`, then read back what was captured |
| `runSmsDemo` | sms | Send via `test://`, then read back what was captured |
| `runFilesDemo` | files | Write/read/`signUrl`/delete via `file://` in a temp directory (cleans up after itself) |
| `runNotificationsDemo` | notifications | Send via `test://`, then read back what was captured |
| `runPubsubDemo` | pubsub | Publish/subscribe round trip via `local://` |
| `runPhonecallDemo` | phonecall | Start a call, speak, hang up, via `test://` |
| `runSpeechDemo` | speech | Text-to-speech output fed into speech-to-text, via `test://`, showing the two services compose |
| `runSubscriptionPaymentsDemo` | subscription-payments | Create a customer, then a checkout session, via `test://` |
| `runVoiceagentDemo` | voiceagent | Open a session, send a message, collect the response events, via `test://` |
| `runAiDemo` | ai | Calls a real provider from `AI_URL` (no fake exists); prints setup instructions and exits cleanly if unset |
| `runEmbeddingDemo` | embedding | Calls a real provider from `EMBEDDING_URL` (no fake exists); prints setup instructions and exits cleanly if unset |
| `runHumanDemo` | human-services | Human-in-the-loop email/SMS dashboard at `http://localhost:8800` |

## The settings-URL mechanism

Every service has a `Settings` value class wrapping a URL string. Calling it with a name and a
`SettingContext` builds the service:

```kotlin
val context = TestSettingContext()
val cache: Cache = Cache.Settings("ram://")("my-cache", context)
```

`Settings` is usually a property of a larger `@Serializable` settings data class decoded from
JSON (or environment variables, or wherever your app loads config from) - see
[`combinedDemo.kt`](src/main/kotlin/com/lightningkite/services/demo/combinedDemo.kt) for the
full pattern. The URL's scheme (the part before `://`) picks the implementation; everything
after configures it. `TestSettingContext` is a zero-dependency `SettingContext` for exactly this
kind of local running/testing - reuse one instance across every service you construct in a
given process.

## Fake/local schemes by subsystem

| Subsystem | Scheme(s) | Notes |
|---|---|---|
| database | `ram`, `ram-preload`, `delay` | Also `json-files://<path>` from `:database-jsonfile` |
| cache | `ram`, `ram-unsafe` | |
| email | `test`, `console` | `test://` captures sent messages for readback; `console://` just prints |
| sms | `test`, `console` | Same distinction as email |
| notifications | `test`, `console` | Same distinction as email |
| files | `file://` | The *only* local scheme - no `test`/`ram` equivalent. Supports `?serveUrl=` (required) and `?signedUrlDuration=` |
| pubsub | `local`, `debug` | No `test` scheme - `debug` additionally logs every publish |
| phonecall | `test`, `console` | |
| speech (tts + stt) | `test`, `console` | |
| subscription-payments | `test`, `console` | Default scheme is `console`, not `test` |
| voiceagent | `test`, `console` | |
| telemetry | `noop`, `logging` (JVM) | |
| ai | *(none)* | Only real providers: `anthropic`, `openai`, `bedrock`, `ollama`, `embedded` |
| embedding | *(none)* | Only real providers: `openai`, `ollama`, `bedrock` |

Scheme matching works with or without the `://` suffix (`"ram"` and `"ram://"` are equivalent).
