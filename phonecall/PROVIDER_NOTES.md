# Phone Call Provider Implementation Notes

## Plivo Voice API

### Authentication
- Base URL: `https://api.plivo.com/v1/Account/{auth_id}/Call/`
- Uses Basic Auth with Auth ID and Auth Token

### Making Outbound Calls
POST to `/Call/` with form parameters:
- `from` (required) - Caller ID in E.164 format
- `to` (required) - Destination number(s), up to 1000 separated by `<`
- `answer_url` (required) - URL that returns Plivo XML when call is answered
- `answer_method` - HTTP verb for answer_url (default: POST)
- `ring_url` - URL notified when call starts ringing
- `hangup_url` - URL notified when call ends
- `fallback_url` - Invoked if answer_url fails
- `caller_name` - Sets caller name (up to 50 characters)
- `send_digits` - DTMF digits to send after connection; use `w` (0.5s) or `W` (1s) for delays
- `send_on_preanswer` - If true, sends digits during pre-answer
- `time_limit` - Maximum call duration in seconds (default: 14400)
- `hangup_on_ring` - Maximum duration from ringing to hangup

### Machine Detection
- `machine_detection` - Values: `true` or `hangup`
- `machine_detection_time` - Detection duration in ms (default: 5000; range: 2000-10000)
- `machine_detection_url` - Callback URL for async detection results
- `machine_detection_maximum_speech_length` - Max speech duration in ms (default: 5000)
- `machine_detection_initial_silence` - Max silence after answer in ms (default: 4500)
- `machine_detection_maximum_words` - Max sentence count (default: 3)
- `machine_detection_initial_greeting` - Max greeting length in ms (default: 1500)

### Call Status Values
- `ringing` - Call is ringing
- `in-progress` - Call is active
- `completed` - Call ended successfully
- `busy` - Line was busy
- `failed` - Call failed
- `no-answer` - Not answered
- `cancel` - Cancelled

### Plivo XML Elements

#### `<Speak>` (TTS)
```xml
<Speak voice="WOMAN" language="en-US" loop="1">Hello world</Speak>
```
- `voice` - `WOMAN` or `MAN` (default: WOMAN)
- `language` - Language code (default: en-US)
- `loop` - Number of times to repeat (0 = infinite)
- Supports SSML for advanced speech control

Supported languages: en-US, en-GB, en-AU, fr-FR, fr-CA, es-ES, es-US, de-DE, it-IT, nl-NL, pt-PT, pt-BR, da-DK, sv-SE, pl-PL, ru-RU

#### `<Play>` (Audio)
```xml
<Play loop="1">https://example.com/audio.mp3</Play>
```

#### `<GetDigits>` (DTMF Input)
```xml
<GetDigits action="https://example.com/dtmf" method="POST" timeout="5" numDigits="1" finishOnKey="#">
  <Speak>Press 1 for sales</Speak>
</GetDigits>
```

#### `<GetInput>` (Speech + DTMF)
```xml
<GetInput action="https://example.com/input" inputType="speech dtmf">
  <Speak>Say yes or press 1</Speak>
</GetInput>
```

#### `<Dial>` (Forward)
```xml
<Dial timeout="30" callerId="+15551234567">
  <Number>+15559876543</Number>
</Dial>
```

#### `<Record>`
```xml
<Record action="https://example.com/recording" maxLength="60" finishOnKey="#" transcriptionUrl="https://example.com/transcribe"/>
```

#### Other Elements
- `<Hangup/>` - End call
- `<Wait length="5"/>` - Pause
- `<Redirect>https://example.com/next</Redirect>` - Fetch new XML
- `<DTMF>1234</DTMF>` - Send DTMF tones
- `<Conference>` - Multi-party calls
- `<PreAnswer>` - Pre-answer handling
- `<AudioStream>` - Real-time audio streaming

### URL Format for Settings
```
plivo://{auth_id}:{auth_token}@{from_phone_number}
```

---

## Vonage Voice API

### Authentication
- Base URL: `https://api.nexmo.com/v1/calls`
- Uses JWT authentication with Application ID and Private Key
- Alternative: API Key + Secret for some endpoints

### Making Outbound Calls
POST to `/v1/calls` with JSON body:
```json
{
  "to": [{"type": "phone", "number": "15559876543"}],
  "from": {"type": "phone", "number": "15551234567"},
  "ncco": [...],
  "answer_url": ["https://example.com/answer"],
  "event_url": ["https://example.com/events"],
  "machine_detection": "continue"
}
```

Key parameters:
- `to` - Array of endpoint objects (phone, sip, websocket, app)
- `from` - Caller endpoint object
- `ncco` - Inline NCCO actions (alternative to answer_url)
- `answer_url` - URL returning NCCO JSON
- `event_url` - URL for status webhooks
- `machine_detection` - `continue` or `hangup`
- `length_timer` - Max call duration
- `ringing_timer` - Max ring time

### Call Status Values (Webhooks)
- `started` - Call initiated
- `ringing` - Phone is ringing
- `answered` - Call was answered
- `completed` / `complete` - Call ended normally
- `busy` - Line busy
- `rejected` - Call declined
- `failed` - Technical failure
- `timeout` - No answer
- `cancelled` - Cancelled before answer
- `unanswered` - Not answered

### NCCO (Nexmo Call Control Objects)

NCCO is a JSON array of actions executed in order (FIFO).

#### `talk` (TTS)
```json
{
  "action": "talk",
  "text": "Hello world",
  "language": "en-US",
  "style": 0,
  "premium": false,
  "loop": 1,
  "level": 0,
  "bargeIn": false
}
```
- `language` - BCP-47 code (23 languages supported)
- `style` - Voice style variant (0-5 depending on language)
- `premium` - Use premium voices
- `level` - Volume adjustment in dB (-1 to 1)
- `bargeIn` - Allow DTMF to interrupt

#### `stream` (Audio)
```json
{
  "action": "stream",
  "streamUrl": ["https://example.com/audio.mp3"],
  "loop": 1,
  "level": 0,
  "bargeIn": false
}
```

#### `input` (DTMF + Speech)
```json
{
  "action": "input",
  "type": ["dtmf", "speech"],
  "dtmf": {
    "maxDigits": 1,
    "timeOut": 5,
    "submitOnHash": true
  },
  "speech": {
    "language": "en-US",
    "endOnSilence": 1,
    "maxDuration": 60
  },
  "eventUrl": ["https://example.com/input"]
}
```

#### `connect` (Forward/Dial)
```json
{
  "action": "connect",
  "endpoint": [{"type": "phone", "number": "15559876543"}],
  "from": "15551234567",
  "timeout": 30,
  "eventUrl": ["https://example.com/connect-events"]
}
```
Endpoint types: phone, app, websocket, sip, vbc

#### `record`
```json
{
  "action": "record",
  "eventUrl": ["https://example.com/recording"],
  "endOnSilence": 3,
  "endOnKey": "#",
  "timeOut": 60,
  "beepStart": true,
  "transcription": {
    "language": "en-US",
    "eventUrl": ["https://example.com/transcription"]
  }
}
```

#### Other Actions
- `conversation` - Conference/multi-party
- `notify` - Send webhook without waiting

### Unique Vonage Features
- **Conversation API** - Groups related calls into conversations
- **WebSocket endpoint** - Connect calls to WebSocket for real-time audio
- **SIP endpoint** - Direct SIP connectivity
- **VBC endpoint** - Vonage Business Communications integration
- **Premium TTS** - Higher quality voices at additional cost

### URL Format for Settings
```
vonage://{application_id}:{private_key_base64}@{from_phone_number}
```
Or with API credentials:
```
vonage://{api_key}:{api_secret}@{from_phone_number}?app_id={application_id}
```

---

## Provider Comparison Matrix

| Feature | Twilio | Plivo | Vonage |
|---------|--------|-------|--------|
| Call Control Format | TwiML (XML) | Plivo XML | NCCO (JSON) |
| Auth Method | Account SID + Auth Token / API Key | Auth ID + Auth Token | JWT / API Key |
| TTS Element | `<Say>` | `<Speak>` | `talk` action |
| Audio Play | `<Play>` | `<Play>` | `stream` action |
| Gather DTMF | `<Gather>` | `<GetDigits>` | `input` action (type: dtmf) |
| Gather Speech | `<Gather input="speech">` | `<GetInput>` | `input` action (type: speech) |
| Forward/Dial | `<Dial>` | `<Dial>` | `connect` action |
| Record | `<Record>` | `<Record>` | `record` action |
| Hangup | `<Hangup/>` | `<Hangup/>` | (end of NCCO) |
| Pause | `<Pause>` | `<Wait>` | (use `talk` with silence or `stream` silent audio) |
| Redirect | `<Redirect>` | `<Redirect>` | (fetch new NCCO) |
| Queue | `<Enqueue>` | `<Conference>` | `conversation` action |
| SSML Support | Yes | Yes | No (use style parameter) |
| Caller Name (CNAM) | Limited | `caller_name` param | Via `from` object |
| WebSocket Audio | Media Streams | AudioStream | WebSocket endpoint |
| Multi-party | `<Conference>` | `<Conference>`, `<MultiPartyCall>` | `conversation` action |

## Status Mapping

| Abstract Status | Twilio | Plivo | Vonage |
|-----------------|--------|-------|--------|
| STARTED | - | - | started |
| QUEUED | queued | - | - |
| RINGING | ringing | ringing | ringing |
| IN_PROGRESS | in-progress | in-progress | answered |
| COMPLETED | completed | completed | completed |
| BUSY | busy | busy | busy |
| NO_ANSWER | no-answer | no-answer | timeout, unanswered |
| REJECTED | - | - | rejected |
| CANCELED | canceled | cancel | cancelled |
| FAILED | failed | failed | failed |

## Machine Detection Mapping

| Abstract Mode | Twilio | Plivo | Vonage |
|---------------|--------|-------|--------|
| DISABLED | (omit param) | (omit param) | (omit param) |
| ENABLED | `MachineDetection=Enable` | `machine_detection=true` | `machine_detection=continue` |
| DETECT_MESSAGE_END | `MachineDetection=DetectMessageEnd` | (use timing params) | (not directly supported) |

## Implementation Priority

1. **Plivo** - Most similar to Twilio, straightforward port
2. **Vonage** - Different format (JSON vs XML) but well-documented
3. **Bandwidth** - Similar to Twilio (BXML)
4. **Telnyx** - Similar to Twilio (TeXML)
