package com.lightningkite.services.phonecall.twilio

/**
 * Type-safe builder for TwiML (Twilio Markup Language) documents.
 *
 * Example:
 * ```kotlin
 * val twiml = TwimlBuilder.build {
 *     say("Hello world!", voice = "Polly.Matthew")
 *     gather(numDigits = 1, action = "/handle-key") {
 *         say("Press 1 for sales, 2 for support")
 *     }
 *     hangup()
 * }
 * ```
 */
public class TwimlBuilder private constructor() {
    private val content = StringBuilder()
    private var indentLevel = 1

    private fun indent(): String = "  ".repeat(indentLevel)

    /**
     * Adds a raw text node (will be XML-escaped).
     */
    public fun text(value: String) {
        content.append(indent()).append(escapeXml(value)).append("\n")
    }

    /**
     * Adds a generic XML element with attributes and optional children.
     */
    public fun element(
        name: String,
        vararg attributes: Pair<String, Any?>,
        children: (TwimlBuilder.() -> Unit)? = null
    ) {
        val attrs = attributes
            .filter { it.second != null }
            .joinToString("") { """ ${it.first}="${escapeXml(it.second.toString())}"""" }

        if (children != null) {
            content.append(indent()).append("<$name$attrs>\n")
            indentLevel++
            children()
            indentLevel--
            content.append(indent()).append("</$name>\n")
        } else {
            content.append(indent()).append("<$name$attrs/>\n")
        }
    }

    /**
     * Adds an element with text content.
     */
    public fun element(
        name: String,
        textContent: String,
        vararg attributes: Pair<String, Any?>
    ) {
        val attrs = attributes
            .filter { it.second != null }
            .joinToString("") { """ ${it.first}="${escapeXml(it.second.toString())}"""" }

        content.append(indent()).append("<$name$attrs>${escapeXml(textContent)}</$name>\n")
    }

    // ==================== TwiML Verbs ====================

    /**
     * `<Say>` - Text-to-speech.
     *
     * @param text Text to speak
     * @param voice Voice to use (e.g., "Polly.Matthew", "Polly.Joanna")
     * @param language Language code (e.g., "en-US")
     * @param loop Number of times to repeat (0 = infinite)
     */
    public fun say(
        text: String,
        voice: String? = null,
        language: String? = null,
        loop: Int? = null
    ) {
        element("Say", text, "voice" to voice, "language" to language, "loop" to loop)
    }

    /**
     * `<Play>` - Play audio from URL.
     *
     * @param url URL of audio file
     * @param loop Number of times to repeat (0 = infinite)
     * @param digits DTMF digits to play instead of audio
     */
    public fun play(
        url: String? = null,
        loop: Int? = null,
        digits: String? = null
    ) {
        if (url != null) {
            element("Play", url, "loop" to loop)
        } else if (digits != null) {
            element("Play", "", "digits" to digits)
        } else {
            element("Play", "loop" to loop)
        }
    }

    /**
     * `<Gather>` - Collect DTMF or speech input.
     *
     * @param action URL to submit input to
     * @param method HTTP method (GET or POST)
     * @param numDigits Number of digits to collect
     * @param timeout Seconds to wait for input
     * @param finishOnKey Key that ends input (default: #)
     * @param input Input type: "dtmf", "speech", or "dtmf speech"
     * @param speechTimeout Seconds of silence before speech ends ("auto" or number)
     * @param children Nested TwiML to play while gathering
     */
    public fun gather(
        action: String? = null,
        method: String? = null,
        numDigits: Int? = null,
        timeout: Int? = null,
        finishOnKey: String? = null,
        input: String? = null,
        speechTimeout: String? = null,
        children: (TwimlBuilder.() -> Unit)? = null
    ) {
        element(
            "Gather",
            "action" to action,
            "method" to method,
            "numDigits" to numDigits,
            "timeout" to timeout,
            "finishOnKey" to finishOnKey,
            "input" to input,
            "speechTimeout" to speechTimeout,
            children = children
        )
    }

    /**
     * `<Dial>` - Connect to another party.
     *
     * @param number Phone number to dial (simple case)
     * @param action URL for dial status callback
     * @param method HTTP method for callback
     * @param timeout Seconds to wait for answer
     * @param callerId Caller ID to display
     * @param record Recording mode: "do-not-record", "record-from-answer", etc.
     * @param children Nested elements (Number, Sip, Client, etc.)
     */
    public fun dial(
        number: String? = null,
        action: String? = null,
        method: String? = null,
        timeout: Int? = null,
        callerId: String? = null,
        record: String? = null,
        children: (TwimlBuilder.() -> Unit)? = null
    ) {
        if (number != null && children == null) {
            element("Dial", number, "action" to action, "method" to method, "timeout" to timeout, "callerId" to callerId, "record" to record)
        } else {
            element(
                "Dial",
                "action" to action,
                "method" to method,
                "timeout" to timeout,
                "callerId" to callerId,
                "record" to record,
                children = children
            )
        }
    }

    /**
     * `<Number>` - Phone number inside Dial.
     */
    public fun number(
        phoneNumber: String,
        sendDigits: String? = null,
        statusCallback: String? = null,
        statusCallbackEvent: String? = null,
        statusCallbackMethod: String? = null
    ) {
        element(
            "Number", phoneNumber,
            "sendDigits" to sendDigits,
            "statusCallback" to statusCallback,
            "statusCallbackEvent" to statusCallbackEvent,
            "statusCallbackMethod" to statusCallbackMethod
        )
    }

    /**
     * `<Sip>` - SIP URI inside Dial.
     */
    public fun sip(
        uri: String,
        username: String? = null,
        password: String? = null
    ) {
        element("Sip", uri, "username" to username, "password" to password)
    }

    /**
     * `<Client>` - Twilio Client inside Dial.
     */
    public fun client(identity: String) {
        element("Client", identity)
    }

    /**
     * `<Queue>` - Queue inside Dial.
     */
    public fun queue(
        name: String,
        url: String? = null,
        method: String? = null
    ) {
        element("Queue", name, "url" to url, "method" to method)
    }

    /**
     * `<Record>` - Record audio.
     *
     * @param action URL for recording callback
     * @param method HTTP method for callback
     * @param timeout Seconds of silence before ending
     * @param finishOnKey Key that ends recording
     * @param maxLength Maximum recording length in seconds
     * @param playBeep Play beep before recording
     * @param transcribe Enable transcription
     * @param transcribeCallback URL for transcription callback
     */
    public fun record(
        action: String? = null,
        method: String? = null,
        timeout: Int? = null,
        finishOnKey: String? = null,
        maxLength: Int? = null,
        playBeep: Boolean? = null,
        transcribe: Boolean? = null,
        transcribeCallback: String? = null
    ) {
        element(
            "Record",
            "action" to action,
            "method" to method,
            "timeout" to timeout,
            "finishOnKey" to finishOnKey,
            "maxLength" to maxLength,
            "playBeep" to playBeep,
            "transcribe" to transcribe,
            "transcribeCallback" to transcribeCallback
        )
    }

    /**
     * `<Pause>` - Wait silently.
     *
     * @param length Seconds to pause (default: 1)
     */
    public fun pause(length: Int? = null) {
        element("Pause", "length" to length)
    }

    /**
     * `<Hangup>` - End the call.
     */
    public fun hangup() {
        element("Hangup")
    }

    /**
     * `<Reject>` - Reject an incoming call.
     *
     * @param reason Rejection reason: "rejected" or "busy"
     */
    public fun reject(reason: String? = null) {
        element("Reject", "reason" to reason)
    }

    /**
     * `<Redirect>` - Redirect to another TwiML URL.
     *
     * @param url URL to fetch TwiML from
     * @param method HTTP method (GET or POST)
     */
    public fun redirect(url: String, method: String? = null) {
        element("Redirect", url, "method" to method)
    }

    /**
     * `<Enqueue>` - Add caller to a queue.
     *
     * @param name Queue name
     * @param action URL for enqueue callback
     * @param method HTTP method for callback
     * @param waitUrl URL for wait music/messages
     * @param waitUrlMethod HTTP method for wait URL
     */
    public fun enqueue(
        name: String,
        action: String? = null,
        method: String? = null,
        waitUrl: String? = null,
        waitUrlMethod: String? = null
    ) {
        element(
            "Enqueue", name,
            "action" to action,
            "method" to method,
            "waitUrl" to waitUrl,
            "waitUrlMethod" to waitUrlMethod
        )
    }

    /**
     * `<Connect>` - Connect to a Stream or other endpoint.
     */
    public fun connect(children: TwimlBuilder.() -> Unit) {
        element("Connect", children = children)
    }

    /**
     * `<Stream>` - Bidirectional audio stream (inside Connect).
     *
     * @param url WebSocket URL for the stream
     * @param name Stream name
     * @param track Which track to stream: "inbound_track", "outbound_track", "both_tracks"
     * @param parameters Optional custom parameters to pass to the stream
     */
    public fun stream(
        url: String,
        name: String? = null,
        track: String? = null,
        parameters: Map<String, String>? = null
    ) {
        if (parameters.isNullOrEmpty()) {
            element("Stream", "url" to url, "name" to name, "track" to track)
        } else {
            element("Stream", "url" to url, "name" to name, "track" to track) {
                parameters.forEach { (key, value) ->
                    parameter(key, value)
                }
            }
        }
    }

    /**
     * `<Parameter>` - Custom parameter (inside Stream).
     *
     * @param name Parameter name
     * @param value Parameter value
     */
    public fun parameter(name: String, value: String) {
        element("Parameter", "name" to name, "value" to value)
    }

    /**
     * `<Start>` - Start a background operation.
     */
    public fun start(children: TwimlBuilder.() -> Unit) {
        element("Start", children = children)
    }

    /**
     * `<Stop>` - Stop a background operation.
     */
    public fun stop(children: TwimlBuilder.() -> Unit) {
        element("Stop", children = children)
    }

    public companion object {
        /**
         * Builds a complete TwiML document.
         *
         * Example:
         * ```kotlin
         * val twiml = TwimlBuilder.build {
         *     say("Welcome!")
         *     gather(numDigits = 1, action = "/menu") {
         *         say("Press 1 for sales")
         *     }
         *     say("Goodbye!")
         *     hangup()
         * }
         * ```
         */
        public fun build(block: TwimlBuilder.() -> Unit): String {
            val builder = TwimlBuilder()
            builder.block()
            return buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("<Response>")
                append(builder.content)
                appendLine("</Response>")
            }
        }

        /**
         * Escapes special XML characters.
         */
        public fun escapeXml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }
    }
}
