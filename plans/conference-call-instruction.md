# Plan: Add Conference CallInstruction

## Overview

Add a `Conference` instruction to `CallInstructions` to support conference/bridge calls. This is needed for lightning-server's transfer-to-human feature where an AI agent can bridge a caller with a human support agent.

## Use Case

Transfer flow:
1. Caller is talking to AI agent
2. AI initiates transfer to human
3. Caller is moved into a conference room
4. Human agent is dialed and added to conference
5. AI introduces both parties, then leaves
6. Caller and human continue privately

---

## Implementation Steps

### Step 1: Add Conference CallInstruction

**File**: `phonecall/src/commonMain/kotlin/com/lightningkite/services/phonecall/CallInstructions.kt`

Add new sealed class member:

```kotlin
/**
 * Join or create a conference call (multi-party).
 *
 * This enables multiple participants to be connected in a single call.
 * Useful for call transfers where an agent can introduce parties before leaving.
 *
 * @property name Unique name for the conference room
 * @property startOnEnter Whether to start the conference when this participant joins
 * @property endOnExit Whether to end the conference when this participant leaves
 * @property muted Whether this participant joins muted
 * @property beep Whether to play a beep when participants join/leave
 * @property waitUrl URL for hold music while waiting for other participants
 * @property statusCallbackUrl URL for conference status webhooks
 * @property statusCallbackEvents Events to send to callback (e.g., "join", "leave")
 * @property then Instructions to execute after leaving the conference
 */
@Serializable
data class Conference(
    val name: String,
    val startOnEnter: Boolean = true,
    val endOnExit: Boolean = false,
    val muted: Boolean = false,
    val beep: Boolean = true,
    val waitUrl: String? = null,
    val statusCallbackUrl: String? = null,
    val statusCallbackEvents: List<String> = listOf("join", "leave"),
    val then: CallInstructions? = null
) : CallInstructions()
```

### Step 2: Add TwiML rendering for Conference

**File**: `phonecall-twilio/src/main/kotlin/com/lightningkite/services/phonecall/twilio/TwilioPhoneCallService.kt`

In the `renderInstruction()` function, add handling for `Conference`:

```kotlin
is CallInstructions.Conference -> {
    append("  <Dial")
    inst.statusCallbackUrl?.let { append(""" action="$it"""") }
    appendLine(">")
    append("    <Conference")
    append(""" startConferenceOnEnter="${inst.startOnEnter}"""")
    append(""" endConferenceOnExit="${inst.endOnExit}"""")
    if (inst.muted) append(""" muted="true"""")
    if (!inst.beep) append(""" beep="false"""")
    inst.waitUrl?.let { append(""" waitUrl="$it"""") }
    if (inst.statusCallbackUrl != null && inst.statusCallbackEvents.isNotEmpty()) {
        append(""" statusCallback="${inst.statusCallbackUrl}"""")
        append(""" statusCallbackEvent="${inst.statusCallbackEvents.joinToString(" ")}"""")
    }
    appendLine(">")
    appendLine("      ${inst.name}")
    appendLine("    </Conference>")
    appendLine("  </Dial>")
    inst.then?.let { renderInstruction(it) }
}
```

This generates TwiML like:
```xml
<Dial action="/status-callback">
  <Conference startConferenceOnEnter="true" endConferenceOnExit="false"
              waitUrl="https://example.com/hold-music.mp3"
              statusCallback="/conference-status" statusCallbackEvent="join leave">
    my-conference-room
  </Conference>
</Dial>
```

---

## Key Files to Modify

| File | Change |
|------|--------|
| `phonecall/src/commonMain/.../CallInstructions.kt` | Add `Conference` data class |
| `phonecall-twilio/src/main/.../TwilioPhoneCallService.kt` | Add TwiML rendering |

---

## Testing

- Verify TwiML output matches Twilio's expected format
- Test with actual Twilio conference to ensure participants can join/leave correctly
