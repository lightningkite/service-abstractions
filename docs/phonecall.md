# Phone calls

`PhoneCallService` places and answers voice calls through Twilio: speak text, play audio, collect keypad input, forward, record, and stream live audio (for [voice agents](voiceagent.md)).

## Modules

| Module | Add it for |
|---|---|
| `phonecall` | `PhoneCallService`, `CallInstructions`, `test` and `console`.  Multiplatform. |
| `phonecall-twilio` | `twilio://`.  JVM. |
| `phonecall-test` | Placeholder; no shared suite yet. |

## Quick start

```kotlin
TwilioPhoneCallService  // registers twilio://
val calls = PhoneCallService.Settings("twilio://ACxxxx:token@+15551234567")("calls", context)

val callId: String = calls.startCall("+15557654321".toPhoneNumber())
calls.speak(callId, "This is a reminder about your appointment tomorrow at 3 PM.")
calls.hangup(callId)
```

`startCall` takes `OutboundCallOptions` (caller ID, timeout, recording, ...).  On a live call: `playAudioUrl`, `playAudio`, `sendDtmf`, `hold`, `resume`, `updateCall(callId, instructions)`; `getCallStatus` returns a `CallInfo`.

## Common tasks

### Answer calls

Incoming calls arrive on `onIncomingCall`, a webhook whose response tells the provider what to do:

```kotlin
// In the route Twilio calls for your number:
val incoming: IncomingCallEvent = calls.onIncomingCall.parse(queryParameters, headers, body)
val menu = CallInstructions.Gather(
    prompt = "Press 1 for sales, 2 for support.",
    numDigits = 1,
    actionUrl = "https://api.example.com/webhooks/calls/menu",
)
respond(calls.onIncomingCall.render(menu))   // TwiML for Twilio
```

`CallInstructions` include `Accept`, `Reject`, `Say`, `Play`, `Gather`, `Forward`, `Conference`, `Record`, `Pause`, `Redirect`, `Enqueue`, `StreamAudio` and `Hangup`; most take a `then` to chain the next step.  Other webhooks: `onCallStatus`, `onTranscription`, `onDtmf`.  `audioStream` is the WebSocket adapter for live audio.

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `test`, `console` | `phonecall` | `test` (the default) |
| `twilio` | `phonecall-twilio` | `twilio://<accountSid>:<authToken>@+15551234567` |

Twilio also takes an API key: `twilio://<accountSid>-<keySid>:<keySecret>@+15551234567` (a hyphen between the SIDs).  Details: [Settings URL reference: Phone calls](settings-url-reference.md#phone-calls-phonecallservicesettings).

## Testing

```kotlin
val calls = PhoneCallService.Settings("test")("calls", TestSettingContext()) as TestPhoneCallService
remindPatient(appointment)
assertEquals("This is a reminder...", calls.spokenMessages.last().text)
```

`TestPhoneCallService` records `calls`, `spokenMessages`, `playedAudioUrls`, `sentDtmf` and the instructions it rendered.

## Gotchas

- **Webhook URLs must be public HTTPS** for Twilio to reach them; use a tunnel in development.
- **Twilio's error message says `account/keySid`**, but the separator is `-`.
