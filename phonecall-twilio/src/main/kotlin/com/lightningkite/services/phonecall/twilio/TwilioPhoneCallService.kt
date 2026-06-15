package com.lightningkite.services.phonecall.twilio

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.http.SettingContextElement
import com.lightningkite.services.telemetry.telemetryTrace
import com.lightningkite.services.phonecall.*
import com.lightningkite.services.webhooksubservice.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("TwilioPhoneCallService")

/**
 * Twilio Voice implementation for making and receiving phone calls.
 *
 * Provides voice communication with:
 * - **Outbound calls**: Initiate calls via Twilio REST API
 * - **Inbound calls**: Receive calls via webhooks, respond with TwiML
 * - **TTS**: Text-to-speech using Twilio's voices (Amazon Polly, Google)
 * - **Transcription**: Real-time speech-to-text
 * - **Audio streaming**: Bidirectional audio via Media Streams
 *
 * ## Supported URL Schemes
 *
 * - `twilio://accountSid:authToken@fromPhoneNumber` - Complete Twilio configuration
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production Twilio account
 * PhoneCallService.Settings("twilio://ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:your_auth_token@+15551234567")
 *
 * // Using helper function
 * PhoneCallService.Settings.Companion.twilio(
 *     account = "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
 *     key = "your_auth_token",
 *     from = "+15551234567"
 * )
 * ```
 *
 * ## Webhook Setup
 *
 * Webhooks are automatically configured when [configureWebhook] is called on the
 * respective webhook subservices. The service will update your Twilio phone number's
 * Voice URL and Status Callback via the API.
 *
 * ## Important Gotchas
 *
 * - **Phone number format**: Must use E.164 format (e.g., +15551234567)
 * - **From number must be verified**: Twilio requires you to own/verify the from number
 * - **Cost per minute**: Twilio charges per minute for voice calls
 * - **TwiML responses**: Webhook handlers must return valid TwiML XML
 * - **Webhook timeout**: Twilio expects responses within 15 seconds
 * - **Media Streams**: Require WebSocket support for bidirectional audio
 * - **Signature validation**: Webhook signature validation is ALWAYS enforced.
 *   You MUST call [configureWebhook] on webhook subservices before processing webhooks.
 *   For unit tests, use [computeSignature] to generate valid signatures.
 *
 * ## Twilio Pricing (approximate)
 *
 * - **Outbound calls (US)**: ~$0.014/min
 * - **Inbound calls (US)**: ~$0.0085/min
 * - **TTS (Amazon Polly)**: ~$0.0008/100 chars
 * - **Transcription**: ~$0.02/min
 *
 * @property name Service name for logging/metrics
 * @property context Service context
 * @property account Twilio Account SID (starts with AC)
 * @property authUser Authentication username - either Account SID (for auth token) or API Key SID (for API key)
 * @property authSecret Authentication secret - either Auth Token or API Key Secret
 * @property defaultFrom Default sender phone number in E.164 format
 */
public class TwilioPhoneCallService(
    override val name: String,
    override val context: SettingContext,
    private val account: String,
    private val authUser: String,
    private val authSecret: String,
    private val defaultFrom: String,
) : PhoneCallService {

    /**
     * Secondary constructor for backwards compatibility when using Account SID + Auth Token.
     */
    public constructor(
        name: String,
        context: SettingContext,
        account: String,
        authToken: String,
        defaultFrom: String,
    ) : this(name, context, account, account, authToken, defaultFrom)

    private val baseUrl = "https://api.twilio.com/2010-04-01/Accounts/$account"

    private val client = com.lightningkite.services.http.client.config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(username = authUser, password = authSecret)
                }
                realm = "Twilio API"
                sendWithoutRequest { true }  // Send auth proactively to avoid 401 challenge
            }
        }
    }

    // Cached HMAC key spec — SecretKeySpec is thread-safe for reading
    private val hmacKeySpec by lazy { SecretKeySpec(authSecret.toByteArray(Charsets.UTF_8), "HmacSHA1") }

    // Mac is NOT thread-safe, so use a ThreadLocal
    private val threadLocalMac = ThreadLocal.withInitial<Mac> {
        Mac.getInstance("HmacSHA1").also { it.init(hmacKeySpec) }
    }

    // Cache phone number SID lookups to avoid repeated API calls per webhook configure
    private val phoneNumberSidCache = ConcurrentHashMap<String, String>()

    // Stored webhook URLs - configured via configureWebhook()
    private var incomingCallWebhookUrl: String? = null
    private var statusCallbackUrl: String? = null
    private var transcriptionCallbackUrl: String? = null

    // ==================== Signature Validation ====================

    /**
     * Validates the Twilio webhook signature.
     *
     * @param url The full URL of your webhook endpoint (including https://)
     * @param params The parsed form parameters from the request body
     * @param signature The value of the X-Twilio-Signature header
     * @return true if the signature is valid, false otherwise
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun validateSignature(url: String, params: Map<String, String>, signature: String): Boolean {
        // Build the data string: URL + sorted params concatenated
        val data = buildString {
            append(url)
            params.keys.sorted().forEach { key ->
                append(key)
                append(params[key] ?: "")
            }
        }

        // Compute HMAC-SHA1 using thread-local Mac with cached key spec
        val mac = threadLocalMac.get()
        mac.reset()
        val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))

        // Decode and compare in constant time — string equality short-circuits and leaks
        // timing information about the expected HMAC.
        val provided = try {
            Base64.decode(signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return java.security.MessageDigest.isEqual(provided, rawHmac)
    }

    /**
     * Validates the Twilio webhook signature from headers and body.
     *
     * Signature validation is ALWAYS enforced - there is no way to disable it.
     * This ensures that webhook requests actually come from Twilio and not an attacker.
     *
     * @throws SecurityException if:
     * - The webhook URL has not been configured (call [configureWebhook] first)
     * - The X-Twilio-Signature header is missing
     * - The signature is invalid
     */
    private fun validateWebhookSignature(
        headers: Map<String, List<String>>,
        params: Map<String, String>,
        webhookUrl: String?,
    ) {
        // Webhook URL MUST be configured before processing webhooks
        if (webhookUrl == null) {
            throw SecurityException(
                "Webhook URL not configured. You must call configureWebhook() before processing webhooks. " +
                        "This is required to validate that webhook requests actually come from Twilio. " +
                        "For unit tests, call configureWebhook() with a test URL and use computeSignature() to generate valid signatures."
            )
        }

        val signature = headers["X-Twilio-Signature"]?.firstOrNull()
            ?: headers["x-twilio-signature"]?.firstOrNull()
            ?: throw SecurityException("Missing X-Twilio-Signature header")

        if (!validateSignature(webhookUrl, params, signature)) {
            throw SecurityException("Invalid Twilio webhook signature")
        }
    }

    /**
     * Computes a Twilio webhook signature for the given URL and parameters.
     *
     * This is useful for unit tests that need to simulate Twilio webhook requests
     * with valid signatures. In production, Twilio computes this signature automatically.
     *
     * ## Usage in Tests
     *
     * ```kotlin
     * // Set up the webhook URLs for testing (without calling Twilio API)
     * service.setWebhookUrlsForTesting(
     *     incomingCallUrl = "https://test.example.com/incoming",
     *     statusCallbackUrl = "https://test.example.com/status"
     * )
     *
     * // Build your test parameters
     * val params = mapOf(
     *     "CallSid" to "CA123",
     *     "From" to "+15551234567",
     *     "To" to "+15559876543"
     * )
     *
     * // Compute a valid signature
     * val signature = service.computeSignature("https://test.example.com/incoming", params)
     *
     * // Call parse with the valid signature
     * val event = service.onIncomingCall.parse(
     *     queryParameters = emptyList(),
     *     headers = mapOf("X-Twilio-Signature" to listOf(signature)),
     *     body = TypedData.text(params.toFormUrlEncoded(), MediaType.Application.FormUrlEncoded)
     * )
     * ```
     *
     * @param url The full webhook URL (must match what was passed to setWebhookUrlsForTesting)
     * @param params The form parameters that will be sent in the request body
     * @return The computed X-Twilio-Signature value
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun computeSignature(url: String, params: Map<String, String>): String {
        val data = buildString {
            append(url)
            params.keys.sorted().forEach { key ->
                append(key)
                append(params[key] ?: "")
            }
        }

        // Use thread-local Mac with cached key spec
        val mac = threadLocalMac.get()
        mac.reset()
        val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encode(rawHmac)
    }

    /**
     * Sets webhook URLs for testing purposes WITHOUT calling the Twilio API.
     *
     * In production, use [configureWebhook] on each webhook subservice instead,
     * which both sets the URL AND configures it in Twilio's system.
     *
     * This method is intended for unit tests that need to test webhook parsing
     * with signature validation, but don't want to make real API calls to Twilio.
     *
     * **Important**: This does NOT bypass signature validation. You must still
     * provide valid signatures using [computeSignature] when calling webhook parse methods.
     *
     * @param incomingCallUrl URL for incoming call webhooks (null to leave unchanged)
     * @param statusCallbackUrl URL for call status webhooks (null to leave unchanged)
     * @param transcriptionCallbackUrl URL for transcription webhooks (null to leave unchanged)
     * @param dtmfCallbackUrl URL for DTMF/gather webhooks (null to leave unchanged)
     */
    public fun setWebhookUrlsForTesting(
        incomingCallUrl: String? = null,
        statusCallbackUrl: String? = null,
        transcriptionCallbackUrl: String? = null,
        dtmfCallbackUrl: String? = null,
    ) {
        incomingCallUrl?.let { this.incomingCallWebhookUrl = it }
        statusCallbackUrl?.let { this.statusCallbackUrl = it }
        transcriptionCallbackUrl?.let { this.transcriptionCallbackUrl = it }
        dtmfCallbackUrl?.let { this.dtmfCallbackUrl = it }
    }

    override suspend fun startCall(to: PhoneNumber, options: OutboundCallOptions): String {
        val from = options.from?.raw ?: defaultFrom

        return telemetryTrace("start", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("phonecall.operation"), "start")
            put(TelemetryKey.OfString("phonecall.to"), context.telemetrySanitization.redactPhoneNumber(to.raw))
            put(TelemetryKey.OfString("phonecall.from"), context.telemetrySanitization.redactPhoneNumber(from))
            put(TelemetryKey.OfString("phonecall.provider"), "twilio")
            put(TelemetryKey.OfBoolean("phonecall.recording_enabled"), options.recordingEnabled)
            put(TelemetryKey.OfString("phonecall.machine_detection"), options.machineDetection.name)
        }) { span ->
                val response = withContext(SettingContextElement(context)) {
                    client.submitForm(
                        url = "$baseUrl/Calls.json",
                        formParameters = Parameters.build {
                            append("From", from)
                            append("To", to.raw)
                            append("Timeout", options.timeout.inWholeSeconds.toString())

                            // If we have an initial message, use TwiML
                            val initialMessage = options.initialMessage
                            if (initialMessage != null) {
                                append("Twiml", buildTwimlInternal {
                                    say(initialMessage)
                                    // Keep call alive after initial message
                                    appendLine("""<Pause length="3600"/>""")
                                })
                            } else {
                                // Default: just connect the call and keep it alive
                                append("Twiml", "<Response><Pause length=\"3600\"/></Response>")
                            }


                            if (options.recordingEnabled) {
                                append("Record", "true")
                            }

                            // Add status callback URL if configured
                            statusCallbackUrl?.let {
                                append("StatusCallback", it)
                                append("StatusCallbackEvent", "initiated ringing answered completed")
                            }

                            // Set caller name if provided
                            options.callerName?.let { append("CallerName", it) }

                            // Send DTMF digits after connection
                            options.sendDigitsOnConnect?.let { append("SendDigits", it) }

                            // Enable answering machine detection if requested
                            // This is especially useful for trial accounts where user must press a key
                            when (options.machineDetection) {
                                MachineDetectionMode.ENABLED -> {
                                    append("MachineDetection", "Enable")
                                }

                                MachineDetectionMode.DETECT_MESSAGE_END -> {
                                    append("MachineDetection", "DetectMessageEnd")
                                }

                                MachineDetectionMode.DISABLED -> { /* Don't add parameter */
                                }
                            }
                        }
                    )
                }

                if (!response.status.isSuccess()) {
                    val errorMessage = response.bodyAsText()
                    throw PhoneCallException("Failed to start call: $errorMessage")
                }

                val responseBody = response.bodyAsText()
                // Parse the SID from JSON response
                val sidMatch = Regex(""""sid"\s*:\s*"([^"]+)"""").find(responseBody)
                val callId = sidMatch?.groupValues?.get(1)
                    ?: throw PhoneCallException("Could not parse call SID from response: $responseBody")

                span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("phonecall.call_id"), callId) })

                // Wait for call to be answered using exponential backoff polling.
                // TODO: Replace this polling loop with a webhook-driven status flow via StatusCallback
                //       for lower latency and reduced API load. See onCallStatus webhook for the event handler.
                val maxWaitTime = options.timeout + 30.seconds  // Extra time for trial account message
                var pollInterval = 1000.milliseconds  // Start at 1s, cap at 5s
                val maxPollInterval = 5000.milliseconds
                val maxPollCount = 30
                var elapsed = 0.milliseconds
                var pollCount = 0

                logger.debug { "[$name] Waiting for call $callId to be answered (max wait: $maxWaitTime)" }

                while (elapsed < maxWaitTime && pollCount < maxPollCount) {
                    delay(pollInterval)
                    elapsed += pollInterval
                    pollCount++
                    // Exponential backoff capped at maxPollInterval
                    pollInterval = minOf(pollInterval * 2, maxPollInterval)

                    val status = getCallStatus(callId)
                    logger.debug { "[$name] Call $callId status: ${status?.status}, answeredBy: ${status?.answeredBy}, elapsed: $elapsed" }

                    when (status?.status) {
                        CallStatus.IN_PROGRESS -> {
                            // If machine detection is enabled, wait for human detection
                            if (options.machineDetection != MachineDetectionMode.DISABLED) {
                                when (status.answeredBy) {
                                    AnsweredBy.HUMAN, AnsweredBy.UNKNOWN -> {
                                        // UNKNOWN means AMD finished but couldn't determine - treat as human
                                        // This commonly happens with trial accounts after user presses key
                                        // Apply postAnswerDelay for trial accounts or other scenarios needing extra wait
                                        logger.info { "[$name] Call $callId answered (answeredBy: ${status.answeredBy})" }
                                        status.answeredBy?.let { span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("phonecall.answered_by"), it.name) }) }
                                        if (options.postAnswerDelay > Duration.ZERO) {
                                            logger.debug { "[$name] Applying postAnswerDelay of ${options.postAnswerDelay}" }
                                            delay(options.postAnswerDelay)
                                        }
                                        return@telemetryTrace callId
                                    }

                                    AnsweredBy.MACHINE_START, AnsweredBy.MACHINE_END_BEEP,
                                    AnsweredBy.MACHINE_END_SILENCE, AnsweredBy.MACHINE_END_OTHER,
                                        -> {
                                        // Still return the call ID, let caller decide what to do
                                        logger.info { "[$name] Call $callId answered by machine (answeredBy: ${status.answeredBy})" }
                                        status.answeredBy?.let { span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("phonecall.answered_by"), it.name) }) }
                                        if (options.postAnswerDelay > Duration.ZERO) {
                                            logger.debug { "[$name] Applying postAnswerDelay of ${options.postAnswerDelay}" }
                                            delay(options.postAnswerDelay)
                                        }
                                        return@telemetryTrace callId
                                    }

                                    AnsweredBy.FAX -> {
                                        logger.warn { "[$name] Call $callId answered by fax machine" }
                                        span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("phonecall.answered_by"), "FAX") })
                                        throw PhoneCallException("Fax machine detected")
                                    }

                                    null -> {
                                        // Still waiting for AMD to complete
                                        logger.trace { "[$name] Call $callId IN_PROGRESS but AMD not yet complete" }
                                    }
                                }
                            } else {
                                // No machine detection - apply postAnswerDelay if set
                                logger.info { "[$name] Call $callId connected (no AMD)" }
                                if (options.postAnswerDelay > Duration.ZERO) {
                                    logger.debug { "[$name] Applying postAnswerDelay of ${options.postAnswerDelay}" }
                                    delay(options.postAnswerDelay)
                                }
                                return@telemetryTrace callId
                            }
                        }

                        CallStatus.COMPLETED, CallStatus.BUSY, CallStatus.NO_ANSWER,
                        CallStatus.REJECTED, CallStatus.CANCELED, CallStatus.FAILED,
                            -> {
                            logger.warn { "[$name] Call $callId ended before connecting: ${status.status}" }
                            span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("phonecall.status"), status.status.name) })
                            throw PhoneCallException("Call ended before connecting: ${status.status}")
                        }

                        else -> {
                            // Keep waiting (QUEUED, RINGING)
                            logger.trace { "[$name] Call $callId still waiting: ${status?.status}" }
                        }
                    }
                }

                logger.error { "[$name] Call $callId timed out waiting for answer after $elapsed" }
                throw PhoneCallException("Call timed out waiting for answer")
        }
    }

    override suspend fun speak(callId: String, text: String, voice: TtsVoice): Unit = telemetryTrace("speak", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "speak")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfLong("phonecall.text_length"), text.length.toLong())
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
        put(TelemetryKey.OfString("phonecall.voice"), voice.name ?: voice.gender.name)
    }) {
        logger.debug { "[$name] speak() called for call $callId, text length: ${text.length} chars" }
        val twiml = buildTwimlInternal {
            say(text, voice)
            // Keep call alive after speaking - use a long pause
            appendLine("""<Pause length="3600"/>""")
        }
        logger.trace { "[$name] TwiML for speak: $twiml" }

        withContext(SettingContextElement(context)) {
            updateCallWithTwiml(callId, twiml)
        }
        logger.debug { "[$name] TwiML sent for call $callId" }

        // Estimate TTS duration: ~150 words per minute, average 5 chars per word
        // So roughly 750 chars per minute, or ~80ms per character.
        // TODO: Replace this heuristic delay with a Gather/status callback completion event
        //       so that speak() returns precisely when TTS finishes rather than over/under-waiting.
        val estimatedDuration = (text.length * 80).milliseconds
        logger.debug { "[$name] Waiting $estimatedDuration for TTS to complete" }
        delay(estimatedDuration)
        logger.debug { "[$name] speak() completed for call $callId" }
    }

    override suspend fun playAudioUrl(callId: String, url: String, loop: Int): Unit = telemetryTrace("play_audio", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "play_audio")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
        put(TelemetryKey.OfString("phonecall.audio_url"), url)
        put(TelemetryKey.OfLong("phonecall.loop"), loop.toLong())
    }) {
        val twiml = buildTwimlInternal {
            appendLine("""  <Play loop="$loop">${escapeXml(url)}</Play>""")
            // Keep call alive after playing
            appendLine("""  <Pause length="3600"/>""")
        }

        withContext(SettingContextElement(context)) {
            updateCallWithTwiml(callId, twiml)
        }
    }

    override suspend fun playAudio(callId: String, audio: TypedData) {
        // For Twilio, we need to provide a URL to the audio
        // This is a limitation - in a real implementation, you'd need to host the audio
        throw PhoneCallException("playAudio requires hosting audio at a URL. Use playAudioUrl() or speak() instead.")
    }

    override suspend fun sendDtmf(callId: String, digits: String): Unit = telemetryTrace("send_dtmf", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "send_dtmf")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
    }) {
        val twiml = buildTwimlInternal {
            appendLine("""<Play digits="$digits"/>""")
        }

        withContext(SettingContextElement(context)) {
            updateCallWithTwiml(callId, twiml)
        }
    }

    override suspend fun hold(callId: String): Unit = telemetryTrace("hold", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "hold")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
    }) {
        val twiml = buildTwimlInternal {
            appendLine("""<Play loop="0">http://com.twilio.sounds.music.s3.amazonaws.com/hold-music.mp3</Play>""")
        }

        withContext(SettingContextElement(context)) {
            updateCallWithTwiml(callId, twiml)
        }
    }

    override suspend fun resume(callId: String): Unit = telemetryTrace("resume", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "resume")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
    }) {
        // Resume by providing new instructions
        val twiml = buildTwimlInternal {
            appendLine("""<Pause length="3600"/>""")
        }

        withContext(SettingContextElement(context)) {
            updateCallWithTwiml(callId, twiml)
        }
    }

    override suspend fun hangup(callId: String): Unit = telemetryTrace("hangup", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "hangup")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
    }) {
        logger.info { "[$name] Hanging up call $callId" }
        val response = withContext(SettingContextElement(context)) {
            client.submitForm(
                url = "$baseUrl/Calls/$callId.json",
                formParameters = Parameters.build {
                    append("Status", "completed")
                }
            )
        }

        if (!response.status.isSuccess()) {
            val errorMessage = response.bodyAsText()
            logger.error { "[$name] Failed to hangup call $callId: $errorMessage" }
            throw PhoneCallException("Failed to hangup call: $errorMessage")
        }
        logger.debug { "[$name] Call $callId hangup successful" }
    }

    override suspend fun getCallStatus(callId: String): CallInfo? = telemetryTrace("get_status", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "get_status")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
    }) { span ->
        val response = withContext(SettingContextElement(context)) {
            client.get("$baseUrl/Calls/$callId.json")
        }

        if (response.status == HttpStatusCode.NotFound) {
            span.enrich(TelemetryAttributes { put(TelemetryKey.OfBoolean("phonecall.found"), false) })
            return@telemetryTrace null
        }

        if (!response.status.isSuccess()) {
            val errorMessage = response.bodyAsText()
            throw PhoneCallException("Failed to get call status: $errorMessage")
        }

        val body = response.bodyAsText()
        val callInfo = parseTwilioCallResponse(body)

        span.enrich(TelemetryAttributes {
            put(TelemetryKey.OfBoolean("phonecall.found"), true)
            callInfo?.status?.let { put(TelemetryKey.OfString("phonecall.status"), it.name) }
        })

        callInfo
    }

    override suspend fun updateCall(callId: String, instructions: CallInstructions): Unit = telemetryTrace("update", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "update")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
    }) {
        withContext(SettingContextElement(context)) {
            updateCallWithTwiml(callId, renderInstructions(instructions))
        }
    }

    /**
     * Updates an active call with raw TwiML instructions.
     *
     * @param callId The call to update
     * @param instructions Raw TwiML XML string
     */
    public suspend fun updateCallRaw(callId: String, instructions: String): Unit = telemetryTrace("update_raw", attributes = TelemetryAttributes {
        put(TelemetryKey.OfString("phonecall.operation"), "update_raw")
        put(TelemetryKey.OfString("phonecall.call_id"), callId)
        put(TelemetryKey.OfString("phonecall.provider"), "twilio")
    }) {
        logger.debug { "[$name] Updating call $callId with TwiML" }
        val response = withContext(SettingContextElement(context)) {
            client.submitForm(
                url = "$baseUrl/Calls/$callId.json",
                formParameters = Parameters.build {
                    append("Twiml", instructions)
                }
            )
        }

        if (!response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            logger.error { "[$name] Failed to update call $callId: $responseBody" }
            throw PhoneCallException("Failed to update call: $responseBody")
        }
        logger.debug { "[$name] Call $callId TwiML update successful" }
    }

    private suspend fun updateCallWithTwiml(callId: String, twiml: String) {
        updateCallRaw(callId, twiml)
    }

    // ==================== Instruction Rendering ====================

    /**
     * Renders [CallInstructions] to TwiML XML string.
     *
     * @param instructions The call instructions to render
     * @return TwiML XML string
     */
    public fun renderInstructions(instructions: CallInstructions): String {
        return renderInstructionsWithType(instructions).content
    }

    /**
     * Renders [CallInstructions] to [RenderedInstructions] with content type.
     *
     * @param instructions The call instructions to render
     * @return RenderedInstructions containing TwiML and content type
     */
    public fun renderInstructionsWithType(instructions: CallInstructions): RenderedInstructions {
        val content = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<Response>")
            renderInstruction(instructions)
            appendLine("</Response>")
        }
        return RenderedInstructions(content, InstructionsContentType.XML)
    }

    // ==================== Webhooks ====================

    override val onIncomingCall: WebhookAdapterWithResponse<IncomingCallEvent, CallInstructions?> =
        TwilioIncomingCallWebhook()

    override val onCallStatus: WebhookAdapter<CallStatusEvent> = TwilioCallStatusWebhook()

    override val onTranscription: WebhookAdapter<TranscriptionEvent> = TwilioTranscriptionWebhook()

    private inner class TwilioIncomingCallWebhook :
        WebhookAdapterWithResponse<IncomingCallEvent, CallInstructions?> {
        override suspend fun configureWebhook(httpUrl: String): Unit = telemetryTrace("webhook.configure.incoming_call", attributes = TelemetryAttributes {
            put(TelemetryKeys.Messaging.system, "twilio")
            put(TelemetryKey.OfString("webhook.url"), httpUrl)
        }) { span ->
            incomingCallWebhookUrl = httpUrl

            // Look up the phone number SID and configure the voice webhook
            val phoneNumberSid = lookupPhoneNumberSid(defaultFrom)

            val response = client.submitForm(
                url = "$baseUrl/IncomingPhoneNumbers/$phoneNumberSid.json",
                formParameters = Parameters.build {
                    append("VoiceUrl", httpUrl)
                    append("VoiceMethod", "POST")
                }
            )

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                span.enrich(TelemetryAttributes { put(TelemetryKeys.Http.statusCode, response.status.value.toLong()) })
                throw PhoneCallException("Failed to configure Twilio Voice webhook: $errorBody")
            }

            span.enrich(TelemetryAttributes { put(TelemetryKeys.Http.statusCode, response.status.value.toLong()) })
            logger.info { "[$name] Configured Twilio Voice webhook for $defaultFrom -> $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData,
        ): IncomingCallEvent = telemetryTrace("webhook.parse.incoming_call", attributes = TelemetryAttributes {
            put(TelemetryKeys.Messaging.system, "twilio")
        }) { span ->
            val params = parseFormUrlEncoded(body.text())
            validateWebhookSignature(headers, params, incomingCallWebhookUrl)

            params["CallSid"]?.let { span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("phonecall.call_id"), it) }) }

            IncomingCallEvent(
                callId = params["CallSid"] ?: throw PhoneCallException("Missing CallSid"),
                from = (params["From"] ?: throw PhoneCallException("Missing From")).toPhoneNumber(),
                to = (params["To"] ?: throw PhoneCallException("Missing To")).toPhoneNumber(),
                direction = CallDirection.INBOUND,
                metadata = params.filterKeys { it !in setOf("CallSid", "From", "To") }
            )
        }

        override suspend fun render(output: CallInstructions?): HttpAdapter.HttpResponseLike {
            if (output == null) {
                // No instructions - return empty TwiML response
                val emptyTwiml = """<?xml version="1.0" encoding="UTF-8"?><Response></Response>"""
                return HttpAdapter.HttpResponseLike(
                    status = 200,
                    headers = mapOf("Content-Type" to listOf("application/xml")),
                    body = TypedData.text(emptyTwiml, MediaType.Application.Xml)
                )
            }
            val twiml = renderInstructions(output)
            return HttpAdapter.HttpResponseLike(
                status = 200,
                headers = mapOf("Content-Type" to listOf("application/xml")),
                body = TypedData.text(twiml, MediaType.Application.Xml)
            )
        }
    }

    private inner class TwilioCallStatusWebhook : WebhookAdapter<CallStatusEvent> {
        override suspend fun configureWebhook(httpUrl: String): Unit = telemetryTrace("webhook.configure.call_status", attributes = TelemetryAttributes {
            put(TelemetryKeys.Messaging.system, "twilio")
            put(TelemetryKey.OfString("webhook.url"), httpUrl)
        }) { span ->
            statusCallbackUrl = httpUrl

            // Look up the phone number SID and configure the status callback
            val phoneNumberSid = lookupPhoneNumberSid(defaultFrom)

            val response = client.submitForm(
                url = "$baseUrl/IncomingPhoneNumbers/$phoneNumberSid.json",
                formParameters = Parameters.build {
                    append("StatusCallback", httpUrl)
                    append("StatusCallbackMethod", "POST")
                }
            )

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                span.enrich(TelemetryAttributes { put(TelemetryKeys.Http.statusCode, response.status.value.toLong()) })
                throw PhoneCallException("Failed to configure Twilio status callback webhook: $errorBody")
            }

            span.enrich(TelemetryAttributes { put(TelemetryKeys.Http.statusCode, response.status.value.toLong()) })
            logger.info { "[$name] Configured Twilio status callback for $defaultFrom -> $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData,
        ): CallStatusEvent = telemetryTrace("webhook.parse.call_status", attributes = TelemetryAttributes {
            put(TelemetryKeys.Messaging.system, "twilio")
        }) { span ->
            val params = parseFormUrlEncoded(body.text())
            validateWebhookSignature(headers, params, statusCallbackUrl)

            span.enrich(TelemetryAttributes {
                params["CallSid"]?.let { put(TelemetryKey.OfString("phonecall.call_id"), it) }
                params["CallStatus"]?.let { put(TelemetryKey.OfString("phonecall.status"), it) }
            })

            CallStatusEvent(
                callId = params["CallSid"] ?: throw PhoneCallException("Missing CallSid"),
                status = parseTwilioStatus(params["CallStatus"]),
                direction = if (params["Direction"] == "inbound") CallDirection.INBOUND else CallDirection.OUTBOUND,
                from = (params["From"] ?: "+0").toPhoneNumber(),
                to = (params["To"] ?: "+0").toPhoneNumber(),
                duration = params["CallDuration"]?.toLongOrNull()?.seconds,
                endReason = null,
                metadata = params.filterKeys {
                    it !in setOf(
                        "CallSid",
                        "CallStatus",
                        "Direction",
                        "From",
                        "To",
                        "CallDuration"
                    )
                }
            )
        }

        override suspend fun pull(): Set<CallStatusEvent> = emptySet()
    }

    private inner class TwilioTranscriptionWebhook : WebhookAdapter<TranscriptionEvent> {
        override suspend fun configureWebhook(httpUrl: String) {
            // Transcription callbacks are configured per-call via TwiML, not on the phone number
            // So we just store the URL here for use in renderInstruction()
            transcriptionCallbackUrl = httpUrl
            logger.info { "[$name] Configured transcription callback URL: $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData,
        ): TranscriptionEvent = telemetryTrace("webhook.parse.transcription", attributes = TelemetryAttributes {
            put(TelemetryKeys.Messaging.system, "twilio")
        }) { span ->
            val params = parseFormUrlEncoded(body.text())
            validateWebhookSignature(headers, params, transcriptionCallbackUrl)

            params["CallSid"]?.let { span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("phonecall.call_id"), it) }) }

            TranscriptionEvent(
                callId = params["CallSid"] ?: throw PhoneCallException("Missing CallSid"),
                text = params["SpeechResult"] ?: params["TranscriptionText"] ?: "",
                isFinal = true,
                confidence = params["Confidence"]?.toFloatOrNull(),
                language = params["Language"]
            )
        }

        override suspend fun pull(): Set<TranscriptionEvent> = emptySet()
    }

    // ==================== DTMF/Gather Webhook ====================

    override val onDtmf: WebhookAdapterWithResponse<DtmfEvent, CallInstructions?> = TwilioDtmfWebhook()

    private var dtmfCallbackUrl: String? = null

    private inner class TwilioDtmfWebhook : WebhookAdapterWithResponse<DtmfEvent, CallInstructions?> {
        override suspend fun configureWebhook(httpUrl: String) {
            // DTMF callbacks are configured per-Gather via TwiML action URL
            // This URL is used as a default when not specified in CallInstructions.Gather
            dtmfCallbackUrl = httpUrl
            logger.info { "[$name] Configured DTMF callback URL: $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData,
        ): DtmfEvent = telemetryTrace("webhook.parse.dtmf", attributes = TelemetryAttributes {
            put(TelemetryKeys.Messaging.system, "twilio")
        }) { span ->
            val params = parseFormUrlEncoded(body.text())
            validateWebhookSignature(headers, params, dtmfCallbackUrl)

            span.enrich(TelemetryAttributes {
                params["CallSid"]?.let { put(TelemetryKey.OfString("phonecall.call_id"), it) }
                params["Digits"]?.let { put(TelemetryKey.OfString("phonecall.dtmf.digits"), it) }
            })

            DtmfEvent(
                callId = params["CallSid"] ?: throw PhoneCallException("Missing CallSid"),
                digits = params["Digits"] ?: "",
                speechResult = params["SpeechResult"],
                confidence = params["Confidence"]?.toFloatOrNull(),
                metadata = params.filterKeys { it !in setOf("CallSid", "Digits", "SpeechResult", "Confidence") }
            )
        }

        override suspend fun render(output: CallInstructions?): HttpAdapter.HttpResponseLike {
            if (output == null) {
                // No instructions - return empty TwiML response
                val emptyTwiml = """<?xml version="1.0" encoding="UTF-8"?><Response></Response>"""
                return HttpAdapter.HttpResponseLike(
                    status = 200,
                    headers = mapOf("Content-Type" to listOf("application/xml")),
                    body = TypedData.text(emptyTwiml, MediaType.Application.Xml)
                )
            }
            val twiml = renderInstructions(output)
            return HttpAdapter.HttpResponseLike(
                status = 200,
                headers = mapOf("Content-Type" to listOf("application/xml")),
                body = TypedData.text(twiml, MediaType.Application.Xml)
            )
        }
    }

    // ==================== Audio Streaming ====================

    override val audioStream: TwilioAudioStreamAdapter = TwilioAudioStreamAdapter(authSecret)

    // ==================== TwiML Rendering ====================

    private fun StringBuilder.renderInstruction(inst: CallInstructions) {
        when (inst) {
            is CallInstructions.Accept -> {
                inst.initialMessage?.let {
                    say(it, inst.voice)
                }
                val transcriptionUrl = transcriptionCallbackUrl
                if (inst.transcriptionEnabled && transcriptionUrl != null) {
                    appendLine("""  <Gather input="speech" speechTimeout="auto" action="${escapeXml(transcriptionUrl)}"/>""")
                } else {
                    appendLine("""  <Pause length="3600"/>""")
                }
            }

            is CallInstructions.Reject -> {
                appendLine("""  <Reject/>""")
            }

            is CallInstructions.Hangup -> {
                appendLine("""  <Hangup/>""")
            }

            is CallInstructions.Say -> {
                say(inst.text, inst.voice, inst.loop)
                inst.then?.let { renderInstruction(it) }
            }

            is CallInstructions.Play -> {
                appendLine("""  <Play loop="${inst.loop}">${escapeXml(inst.url)}</Play>""")
                inst.then?.let { renderInstruction(it) }
            }

            is CallInstructions.Pause -> {
                appendLine("""  <Pause length="${inst.duration.inWholeSeconds}"/>""")
                inst.then?.let { renderInstruction(it) }
            }

            is CallInstructions.Gather -> {
                append("  <Gather")
                inst.numDigits?.let { append(""" numDigits="$it"""") }
                append(""" timeout="${inst.timeout.inWholeSeconds}"""")
                append(""" action="${escapeXml(inst.actionUrl)}"""")
                append(""" finishOnKey="${escapeXml(inst.finishOnKey)}"""")
                if (inst.speechEnabled) {
                    append(""" input="dtmf speech" speechTimeout="auto"""")
                }
                appendLine(">")
                inst.prompt?.let {
                    appendLine("""    <Say>${escapeXml(it)}</Say>""")
                }
                appendLine("  </Gather>")
                inst.then?.let { renderInstruction(it) }
            }

            is CallInstructions.Forward -> {
                append("  <Dial")
                append(""" timeout="${inst.timeout.inWholeSeconds}"""")
                inst.callerId?.let { append(""" callerId="${escapeXml(it.raw)}"""") }
                appendLine(">")
                appendLine("""    <Number>${escapeXml(inst.to.raw)}</Number>""")
                appendLine("  </Dial>")
                inst.then?.let { renderInstruction(it) }
            }

            is CallInstructions.Conference -> {
                append("  <Dial")
                inst.statusCallbackUrl?.let { append(""" action="${escapeXml(it)}"""") }
                appendLine(">")
                append("    <Conference")
                append(""" startConferenceOnEnter="${inst.startOnEnter}"""")
                append(""" endConferenceOnExit="${inst.endOnExit}"""")
                if (inst.muted) append(""" muted="true"""")
                if (!inst.beep) append(""" beep="false"""")
                inst.waitUrl?.let { append(""" waitUrl="${escapeXml(it)}"""") }
                val callbackUrl = inst.statusCallbackUrl
                if (callbackUrl != null && inst.statusCallbackEvents.isNotEmpty()) {
                    append(""" statusCallback="${escapeXml(callbackUrl)}"""")
                    append(""" statusCallbackEvent="${inst.statusCallbackEvents.joinToString(" ")}"""")
                }
                appendLine(">")
                appendLine("      ${escapeXml(inst.name)}")
                appendLine("    </Conference>")
                appendLine("  </Dial>")
                inst.then?.let { renderInstruction(it) }
            }

            is CallInstructions.Record -> {
                append("  <Record")
                append(""" maxLength="${inst.maxDuration.inWholeSeconds}"""")
                append(""" action="${escapeXml(inst.actionUrl)}"""")
                if (inst.transcribe) {
                    append(""" transcribe="true"""")
                    transcriptionCallbackUrl?.let { append(""" transcribeCallback="${escapeXml(it)}"""") }
                }
                if (inst.playBeep) append(""" playBeep="true"""")
                append(""" finishOnKey="${escapeXml(inst.finishOnKey)}"""")
                appendLine("/>")
                inst.then?.let { renderInstruction(it) }
            }

            is CallInstructions.Redirect -> {
                appendLine("""  <Redirect>${escapeXml(inst.url)}</Redirect>""")
            }

            is CallInstructions.Enqueue -> {
                append("  <Enqueue")
                inst.waitUrl?.let { append(""" waitUrl="${escapeXml(it)}"""") }
                appendLine(""">${escapeXml(inst.name)}</Enqueue>""")
                inst.then?.let { renderInstruction(it) }
            }

            is CallInstructions.ImplementationSpecific -> {
                // Raw TwiML content - insert directly without wrapping
                appendLine(inst.raw)
            }

            is CallInstructions.StreamAudio -> {
                appendLine("  <Connect>")
                append("    <Stream")
                append(""" url="${escapeXml(inst.websocketUrl)}"""")
                val trackValue = when (inst.track) {
                    AudioTrack.INBOUND -> "inbound_track"
                    AudioTrack.OUTBOUND -> "outbound_track"
                    AudioTrack.BOTH -> "both_tracks"
                }
                append(""" track="$trackValue"""")
                if (inst.customParameters.isEmpty()) {
                    appendLine("/>")
                } else {
                    appendLine(">")
                    inst.customParameters.forEach { (key, value) ->
                        appendLine("""      <Parameter name="${escapeXml(key)}" value="${escapeXml(value)}"/>""")
                    }
                    appendLine("    </Stream>")
                }
                appendLine("  </Connect>")
                inst.then?.let { renderInstruction(it) }
            }
        }
    }

    private fun StringBuilder.say(text: String, voice: TtsVoice = TtsVoice(), loop: Int = 1) {
        val voiceAttr = voice.name ?: when (voice.gender) {
            TtsGender.MALE -> "Polly.Matthew"
            TtsGender.FEMALE -> "Polly.Joanna"
            TtsGender.NEUTRAL -> "Polly.Matthew"
        }
        // If SSML is enabled, text is already SSML markup and should not be escaped
        val content = if (voice.ssml) text else escapeXml(text)
        appendLine("""  <Say voice="$voiceAttr" language="${voice.language}" loop="$loop">$content</Say>""")
    }

    private fun buildTwimlInternal(block: StringBuilder.() -> Unit): String {
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<Response>")
            block()
            appendLine("</Response>")
        }
    }

    private fun escapeXml(text: String): String = TwimlBuilder.escapeXml(text)

    // ==================== Helpers ====================

    /**
     * Looks up the Twilio phone number SID for a given phone number. Results are cached
     * in-memory per instance to avoid repeated API calls during multiple webhook configures.
     *
     * @param phoneNumber The phone number in E.164 format (e.g., +15551234567)
     * @return The phone number SID (e.g., PN...)
     * @throws PhoneCallException if the phone number is not found
     */
    private suspend fun lookupPhoneNumberSid(phoneNumber: String): String {
        phoneNumberSidCache[phoneNumber]?.let { return it }

        return telemetryTrace("lookup_number_sid", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("phonecall.operation"), "lookup_number_sid")
            put(TelemetryKey.OfString("phonecall.phone_number"), context.telemetrySanitization.redactPhoneNumber(phoneNumber))
            put(TelemetryKeys.Messaging.system, "twilio")
        }) { span ->
            val encodedNumber = URLEncoder.encode(phoneNumber, Charsets.UTF_8)
            val response = withContext(SettingContextElement(context)) {
                client.get("$baseUrl/IncomingPhoneNumbers.json?PhoneNumber=$encodedNumber")
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw PhoneCallException("Failed to look up Twilio phone number: $errorBody")
            }

            val responseBody = response.bodyAsText()
            // Response format: {"incoming_phone_numbers": [{"sid": "PN...", ...}], ...}
            val element = Json.parseToJsonElement(responseBody)
            val sid = element.jsonObject["incoming_phone_numbers"]
                ?.let { it as? JsonArray }
                ?.firstOrNull()
                ?.jsonObject?.get("sid")
                ?.jsonPrimitive?.content
                ?: throw PhoneCallException("Phone number ${context.telemetrySanitization.redactPhoneNumber(phoneNumber)} not found in Twilio account $account")

            phoneNumberSidCache[phoneNumber] = sid
            span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("phonecall.phone_number_sid"), sid) })
            sid
        }
    }

    private fun parseFormUrlEncoded(body: String): Map<String, String> {
        return body.split("&")
            .filter { it.isNotEmpty() }
            .associate {
                val parts = it.split("=", limit = 2)
                val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
                key to value
            }
    }

    private fun parseTwilioStatus(status: String?): CallStatus {
        return when (status?.lowercase()) {
            "queued" -> CallStatus.QUEUED
            "ringing" -> CallStatus.RINGING
            "in-progress" -> CallStatus.IN_PROGRESS
            "completed" -> CallStatus.COMPLETED
            "busy" -> CallStatus.BUSY
            "no-answer" -> CallStatus.NO_ANSWER
            "canceled" -> CallStatus.CANCELED
            "failed" -> CallStatus.FAILED
            else -> CallStatus.FAILED
        }
    }

    private fun parseTwilioCallResponse(json: String): CallInfo? {
        val obj = Json.parseToJsonElement(json).jsonObject
        val sid = obj["sid"]?.jsonPrimitive?.content ?: return null
        val status = obj["status"]?.jsonPrimitive?.content
        val from = obj["from"]?.jsonPrimitive?.content ?: "+0"
        val to = obj["to"]?.jsonPrimitive?.content ?: "+0"
        val direction = obj["direction"]?.jsonPrimitive?.content
        val answeredBy = obj["answered_by"]?.jsonPrimitive?.content

        return CallInfo(
            callId = sid,
            status = parseTwilioStatus(status),
            direction = if (direction == "inbound") CallDirection.INBOUND else CallDirection.OUTBOUND,
            from = from.toPhoneNumber(),
            to = to.toPhoneNumber(),
            answeredBy = parseTwilioAnsweredBy(answeredBy)
        )
    }

    private fun parseTwilioAnsweredBy(answeredBy: String?): AnsweredBy? {
        return when (answeredBy?.lowercase()) {
            "human" -> AnsweredBy.HUMAN
            "machine_start" -> AnsweredBy.MACHINE_START
            "machine_end_beep" -> AnsweredBy.MACHINE_END_BEEP
            "machine_end_silence" -> AnsweredBy.MACHINE_END_SILENCE
            "machine_end_other" -> AnsweredBy.MACHINE_END_OTHER
            "fax" -> AnsweredBy.FAX
            "unknown" -> AnsweredBy.UNKNOWN
            else -> null  // Not set or not available
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            // Try to list calls (with limit 1) to verify credentials and Voice API access
            val response = client.get("$baseUrl/Calls.json?PageSize=1")
            if (response.status.isSuccess()) {
                HealthStatus(HealthStatus.Level.OK, additionalMessage = "Twilio Voice API accessible")
            } else {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Twilio API returned ${response.status}")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Twilio API error: ${e.message}")
        }
    }

    public companion object {
        /**
         * Builds a TwiML response using the type-safe [TwimlBuilder].
         *
         * Example:
         * ```kotlin
         * val twiml = TwilioPhoneCallService.buildTwiml {
         *     say("Hello world!", voice = "Polly.Matthew")
         *     gather(numDigits = 1, action = "/handle-key") {
         *         say("Press 1 for sales")
         *     }
         *     hangup()
         * }
         * ```
         *
         * @param block Builder block using [TwimlBuilder] DSL
         * @return Complete TwiML XML string
         */
        public fun buildTwiml(block: TwimlBuilder.() -> Unit): String = TwimlBuilder.build(block)

        /**
         * Escapes special XML characters in a string.
         *
         * @param text Text to escape
         * @return XML-safe string
         */
        public fun escapeXml(text: String): String = TwimlBuilder.escapeXml(text)

        /**
         * Creates a Twilio phone call settings URL using Account SID + Auth Token.
         *
         * @param account Twilio Account SID (starts with AC)
         * @param authToken Twilio Auth Token
         * @param from Default sender phone number in E.164 format
         */
        public fun PhoneCallService.Settings.Companion.twilio(
            account: String,
            authToken: String,
            from: String,
        ): PhoneCallService.Settings = PhoneCallService.Settings("twilio://$account:$authToken@$from")

        /**
         * Creates a Twilio phone call settings URL using API Key authentication.
         *
         * @param account Twilio Account SID (starts with AC)
         * @param keySid Twilio API Key SID (starts with SK)
         * @param keySecret Twilio API Key Secret
         * @param from Default sender phone number in E.164 format
         */
        public fun PhoneCallService.Settings.Companion.twilioApiKey(
            account: String,
            keySid: String,
            keySecret: String,
            from: String,
        ): PhoneCallService.Settings = PhoneCallService.Settings("twilio://$account-$keySid:$keySecret@$from")

        init {
            PhoneCallService.Settings.register("twilio") { name, url, context ->
                // Try API Key format first: twilio://account/keySid:keySecret@phoneNumber
                val apiKeyRegex =
                    Regex("""twilio://(?<account>[^/:]+)-(?<keySid>[^:]+):(?<keySecret>[^@]+)@(?<phoneNumber>.+)""")
                val apiKeyMatch = apiKeyRegex.matchEntire(url)

                if (apiKeyMatch != null) {
                    val account = apiKeyMatch.groups["account"]?.value
                        ?: throw IllegalArgumentException("Twilio account SID not provided in URL")
                    val keySid = apiKeyMatch.groups["keySid"]?.value
                        ?: throw IllegalArgumentException("Twilio API Key SID not provided in URL")
                    val keySecret = apiKeyMatch.groups["keySecret"]?.value
                        ?: throw IllegalArgumentException("Twilio API Key Secret not provided in URL")
                    val from = apiKeyMatch.groups["phoneNumber"]?.value
                        ?: throw IllegalArgumentException("Twilio phone number not provided in URL")

                    return@register TwilioPhoneCallService(name, context, account, keySid, keySecret, from)
                }

                // Fall back to Auth Token format: twilio://accountSid:authToken@phoneNumber
                val authTokenRegex = Regex("""twilio://(?<account>[^:]+):(?<authToken>[^@]+)@(?<phoneNumber>.+)""")
                val authTokenMatch = authTokenRegex.matchEntire(url)
                    ?: throw IllegalArgumentException("Invalid Twilio URL. Expected: twilio://[accountSid]:[authToken]@[phoneNumber] or twilio://[accountSid]/[keySid]:[keySecret]@[phoneNumber]")

                val account = authTokenMatch.groups["account"]?.value
                    ?: throw IllegalArgumentException("Twilio account SID not provided in URL")
                val authToken = authTokenMatch.groups["authToken"]?.value
                    ?: throw IllegalArgumentException("Twilio auth token not provided in URL")
                val from = authTokenMatch.groups["phoneNumber"]?.value
                    ?: throw IllegalArgumentException("Twilio phone number not provided in URL")

                TwilioPhoneCallService(name, context, account, authToken, from)
            }
        }
    }
}
