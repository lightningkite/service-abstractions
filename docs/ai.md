# AI (LLMs)

`LlmAccess` talks to a large language model provider: Anthropic, OpenAI or any OpenAI-compatible server, AWS Bedrock, Ollama, or a model running on the device.  One provider-neutral prompt type (`LlmPrompt`) covers system prompts, multi-turn messages, images, tools, reasoning and prompt caching.  The model is chosen per call (`LlmModelId`), not in the URL.

## Modules

| Module | Add it for |
|---|---|
| `ai` | `LlmAccess`, `LlmPrompt`, `inference`, sanitizing.  Multiplatform.  No built-in scheme. |
| `ai-anthropic` | `anthropic://`.  JVM, Android, JS, iOS, macOS. |
| `ai-openai` | `openai://`: OpenAI, and OpenAI-compatible servers (LM Studio, vLLM, Groq, OpenRouter).  JVM, Android, JS, iOS, macOS. |
| `ai-bedrock` | `bedrock://`: AWS Bedrock with static or profile credentials.  Multiplatform. |
| `ai-bedrock-aws-sdk` | `bedrock-sdk://`: Bedrock with the AWS SDK's refreshing credential chain.  JVM. |
| `ai-ollama` | `ollama://`, optionally starting the server and pulling the model.  JVM. |
| `ai-embedded` | `embedded://`: on-device inference (LiteRT-LM, Transformers.js in browsers).  See [its README](../ai-embedded/README.md) for platform setup. |
| `ai-test` | Shared contract suites (`TextGenerationTests`, `ToolCallingTests`, `StreamingTests`, ...) for providers. |

## Quick start

```kotlin
AnthropicLlmSettings.ensureRegistered()  // registers anthropic://
val llm = LlmAccess.Settings("anthropic://?apiKey=\${ANTHROPIC_API_KEY}")("llm", context)

val result: LlmResult = llm.inference(
    LlmModelId("claude-haiku-4-5"),
    LlmPrompt(
        systemPrompt = systemPrompt("Answer in one sentence."),
        messages = listOf(userMessage("Why is the sky blue?")),
    ),
)
println(result.message.plainText())
println("${result.usage.inputTokens} in, ${result.usage.outputTokens} out")
```

`inference` collects the stream into one `LlmResult` and retries rate limits, server errors and transport failures (4 attempts, backing off 1 s, 2 s, 4 s or the provider's `retryAfter`).  Other failures throw an `LlmException` subclass (`Auth`, `InvalidModel`, `InvalidRequest`, ...).

Keep the model in settings next to the access, so deployments can change it: `val model: LlmModelId = LlmModelId("claude-haiku-4-5")`.

## Common tasks

### Stream

```kotlin
llm.stream(model, LlmPrompt(messages = listOf(userMessage("Tell me a story.")))).collect { event ->
    when (event) {
        is LlmStreamEvent.TextDelta -> print(event.text)
        is LlmStreamEvent.Finished -> println("\n[${event.stopReason}]")
        else -> {}   // ReasoningDelta, ToolCallEmitted, AttachmentEmitted
    }
}
```

`stream` doesn't retry.

### Call tools

```kotlin
@Serializable
data class WeatherQuery(val city: String)

val weather = LlmToolDescriptor("get_weather", "Current weather for a city", WeatherQuery.serializer())
var prompt = LlmPrompt(messages = listOf(userMessage("Do I need an umbrella in Provo?")), tools = listOf(weather))

while (true) {
    val result = llm.inference(model, prompt)
    val calls = result.message.toolCalls()
    if (calls.isEmpty()) { println(result.message.plainText()); break }
    prompt += result.message
    for (call in calls) {
        val query = Json.decodeFromString(WeatherQuery.serializer(), call.inputJson)
        prompt += toolResult(call.id, lookUpWeather(query.city))
    }
}
```

The tool's input schema is generated from its serializer, including `@Description` annotations.  `LlmToolChoice` is `Auto` (default), `None`, `Required` or `Specific(name)`.

### Images and options

```kotlin
userMessage("What's in this picture?", LlmAttachment.Base64(MediaType.Image.PNG, base64Png))
userMessage("And this one?", LlmAttachment.Url(MediaType.Image.JPEG, "https://example.com/cat.jpg"))

LlmPrompt(messages = messages, maxTokens = 500, reasoningEffort = LlmReasoningEffort.Low)
    .withToolChoice(LlmToolChoice.Required)
    .withStopSequences(listOf("END"))
```

Providers that can't take an attachment replace it with a note saying so.  `message.withCacheBoundary()` marks where the provider may cache the prompt prefix, on providers that support it.

### List models

`llm.getModels()` returns `LlmModelInfo`s with context sizes and prices; `info.usdCost(result.usage)` estimates a call's cost.

### Keep secrets out of prompts

```kotlin
val safe: LlmAccess = llm.sanitized(CommonPatterns.SSN, CommonPatterns.CREDIT_CARD, explicitValues = setOf(apiKey))
```

Matches are replaced with placeholders before the request and restored in the response.

## Settings URLs

| Scheme | Module | Register with | Example |
|---|---|---|---|
| `anthropic` | `ai-anthropic` | `AnthropicLlmSettings.ensureRegistered()` | `anthropic://?apiKey=${ANTHROPIC_API_KEY}` |
| `openai` | `ai-openai` | `OpenAiLlmSettings.ensureRegistered()` | `openai://?apiKey=lm-studio&baseUrl=http://localhost:1234/v1` |
| `bedrock` | `ai-bedrock` | `BedrockLlmSettings.ensureRegistered()` | `bedrock://my-profile@?region=us-west-2` |
| `bedrock-sdk` | `ai-bedrock-aws-sdk` | `BedrockSdkLlm.ensureRegistered()` | `bedrock-sdk://any?region=us-east-1&profile=dev` |
| `ollama` | `ai-ollama` | `OllamaSchemeRegistrar.ensureRegistered()` | `ollama://llama3.2?autoStart=true&autoPull=true` |
| `embedded` | `ai-embedded` | `EmbeddedLlmSettings.ensureRegistered()` | `embedded://gemma-3-1b?path=/data/models/Gemma3-1B-IT.litertlm` |

Parameters and quirks: [Settings URL reference: LLM access](settings-url-reference.md#llm-access).

- API keys can come from the URL, from `${VAR}` references in it, or (Anthropic, OpenAI) from `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`.  Browsers have no environment, so there the key must be in the URL.
- `anthropic://?directBrowserAccess=true` lets a web page call Anthropic directly; the key is then visible to anyone using the page.
- `bedrock://` resolves credentials once, at startup; use `bedrock-sdk://` where credentials rotate (instance roles, SSO).
- There is no fake scheme for tests.
- Terraform helpers for keys and IAM: `anthropic`, `openai`, `awsBedrock`, `ollama` on `TerraformNeed<LlmAccess.Settings>`.

## Testing

There's no `test` LLM.  Unit-test your own logic against a hand-written `LlmAccess` that returns canned `LlmStreamEvent`s, or run the real thing locally with `ollama://` or LM Studio through `openai://`.

Provider authors: subclass the suites in `ai-test` (override `service` and `cheapModel`).  They call a live model, so they live in an `integration` package and only run with `-Pexpensive`.

## Gotchas

- **"No handler anthropic"**: `ensureRegistered()` wasn't called before parsing settings.
- **`openai://` URL-decodes values**: write `+` as `%2B`.  An unset `${VAR}` throws there; in other schemes it stays literal.
- **`ollama://` with `autoStart`/`autoPull` blocks** at startup while the server starts or the model downloads.
- **`bedrock://downloadUrlAttachments=true`** makes the server fetch attachment URLs: only for trusted prompts.
