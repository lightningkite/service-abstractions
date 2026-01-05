package com.lightningkite.services.phonecall.twilio

import com.lightningkite.MediaType
import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.data.WebhookSubserviceWithResponse
import com.lightningkite.services.phonecall.*
import com.lightningkite.toPhoneNumber
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URLDecoder
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
    private val defaultFrom: String
) : PhoneCallService {

    /**
     * Secondary constructor for backwards compatibility when using Account SID + Auth Token.
     */
    public constructor(
        name: String,
        context: SettingContext,
        account: String,
        authToken: String,
        defaultFrom: String
    ) : this(name, context, account, account, authToken, defaultFrom)

    private val baseUrl = "https://api.twilio.com/2010-04-01/Accounts/$account"

    private val tracer: Tracer? = context.openTelemetry?.getTracer("phonecall-twilio")

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

        // Compute HMAC-SHA1
        val mac = Mac.getInstance("HmacSHA1")
        val keySpec = SecretKeySpec(authSecret.toByteArray(Charsets.UTF_8), "HmacSHA1")
        mac.init(keySpec)
        val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        val computedSignature = Base64.encode(rawHmac)

        return computedSignature == signature
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
        webhookUrl: String?
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

        val mac = Mac.getInstance("HmacSHA1")
        val keySpec = SecretKeySpec(authSecret.toByteArray(Charsets.UTF_8), "HmacSHA1")
        mac.init(keySpec)
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
        dtmfCallbackUrl: String? = null
    ) {
        incomingCallUrl?.let { this.incomingCallWebhookUrl = it }
        statusCallbackUrl?.let { this.statusCallbackUrl = it }
        transcriptionCallbackUrl?.let { this.transcriptionCallbackUrl = it }
        dtmfCallbackUrl?.let { this.dtmfCallbackUrl = it }
    }

    override suspend fun startCall(to: PhoneNumber, options: OutboundCallOptions): String {
        val from = options.from?.raw ?: defaultFrom

        val span = tracer?.spanBuilder("phonecall.start")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "start")
            ?.setAttribute("phonecall.to", to.raw)
            ?.setAttribute("phonecall.from", from)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.setAttribute("phonecall.recording_enabled", options.recordingEnabled)
            ?.setAttribute("phonecall.machine_detection", options.machineDetection.name)
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val response = withContext(com.lightningkite.services.http.SettingContextElement(context)) {
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
                                MachineDetectionMode.DISABLED -> { /* Don't add parameter */ }
                            }
                        }
                    )
                }

                if (!response.status.isSuccess()) {
                    val errorMessage = response.bodyAsText()
                    span?.setStatus(StatusCode.ERROR, "Failed to start call: HTTP ${response.status.value}")
                    throw PhoneCallException("Failed to start call: $errorMessage")
                }

                val responseBody = response.bodyAsText()
                // Parse the SID from JSON response
                val sidMatch = Regex(""""sid"\s*:\s*"([^"]+)"""").find(responseBody)
                val callId = sidMatch?.groupValues?.get(1)
                    ?: throw PhoneCallException("Could not parse call SID from response: $responseBody")

                span?.setAttribute("phonecall.call_id", callId)

                // Wait for call to be answered
                val maxWaitTime = options.timeout + 30.seconds  // Extra time for trial account message
                val pollInterval = 500.milliseconds
                var elapsed = 0.milliseconds

                logger.debug { "[$name] Waiting for call $callId to be answered (max wait: $maxWaitTime)" }

                while (elapsed < maxWaitTime) {
                    delay(pollInterval)
                    elapsed += pollInterval

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
                                        status.answeredBy?.let { span?.setAttribute("phonecall.answered_by", it.name) }
                                        if (options.postAnswerDelay > Duration.ZERO) {
                                            logger.debug { "[$name] Applying postAnswerDelay of ${options.postAnswerDelay}" }
                                            delay(options.postAnswerDelay)
                                        }
                                        span?.setStatus(StatusCode.OK)
                                        return callId
                                    }
                                    AnsweredBy.MACHINE_START, AnsweredBy.MACHINE_END_BEEP,
                                    AnsweredBy.MACHINE_END_SILENCE, AnsweredBy.MACHINE_END_OTHER -> {
                                        // Still return the call ID, let caller decide what to do
                                        logger.info { "[$name] Call $callId answered by machine (answeredBy: ${status.answeredBy})" }
                                        status.answeredBy?.let { span?.setAttribute("phonecall.answered_by", it.name) }
                                        if (options.postAnswerDelay > Duration.ZERO) {
                                            logger.debug { "[$name] Applying postAnswerDelay of ${options.postAnswerDelay}" }
                                            delay(options.postAnswerDelay)
                                        }
                                        span?.setStatus(StatusCode.OK)
                                        return callId
                                    }
                                    AnsweredBy.FAX -> {
                                        logger.warn { "[$name] Call $callId answered by fax machine" }
                                        span?.setAttribute("phonecall.answered_by", "FAX")
                                        span?.setStatus(StatusCode.ERROR, "Fax machine detected")
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
                                span?.setStatus(StatusCode.OK)
                                return callId
                            }
                        }
                        CallStatus.COMPLETED, CallStatus.BUSY, CallStatus.NO_ANSWER,
                        CallStatus.REJECTED, CallStatus.CANCELED, CallStatus.FAILED -> {
                            logger.warn { "[$name] Call $callId ended before connecting: ${status.status}" }
                            span?.setAttribute("phonecall.status", status.status.name)
                            span?.setStatus(StatusCode.ERROR, "Call ended before connecting: ${status.status}")
                            throw PhoneCallException("Call ended before connecting: ${status.status}")
                        }
                        else -> {
                            // Keep waiting (QUEUED, RINGING)
                            logger.trace { "[$name] Call $callId still waiting: ${status?.status}" }
                        }
                    }
                }

                logger.error { "[$name] Call $callId timed out waiting for answer after $elapsed" }
                span?.setStatus(StatusCode.ERROR, "Call timed out waiting for answer")
                throw PhoneCallException("Call timed out waiting for answer")
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to start call: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun speak(callId: String, text: String, voice: TtsVoice) {
        logger.debug { "[$name] speak() called for call $callId, text length: ${text.length} chars" }

        val span = tracer?.spanBuilder("phonecall.speak")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "speak")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.text_length", text.length.toLong())
            ?.setAttribute("phonecall.provider", "twilio")
            ?.setAttribute("phonecall.voice", voice.name ?: voice.gender.name)
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val twiml = buildTwimlInternal {
                    say(text, voice)
                    // Keep call alive after speaking - use a long pause
                    appendLine("""<Pause length="3600"/>""")
                }
                logger.trace { "[$name] TwiML for speak: $twiml" }

                withContext(com.lightningkite.services.http.SettingContextElement(context)) {
                    updateCallWithTwiml(callId, twiml)
                }
                logger.debug { "[$name] TwiML sent for call $callId" }

                // Estimate TTS duration: ~150 words per minute, average 5 chars per word
                // So roughly 750 chars per minute, or ~80ms per character
                val estimatedDuration = (text.length * 80).milliseconds
                logger.debug { "[$name] Waiting $estimatedDuration for TTS to complete" }
                delay(estimatedDuration)
                logger.debug { "[$name] speak() completed for call $callId" }

                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to speak: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun playAudioUrl(callId: String, url: String, loop: Int) {
        val span = tracer?.spanBuilder("phonecall.play_audio")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "play_audio")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.setAttribute("phonecall.audio_url", url)
            ?.setAttribute("phonecall.loop", loop.toLong())
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val twiml = buildTwimlInternal {
                    appendLine("""  <Play loop="$loop">${escapeXml(url)}</Play>""")
                    // Keep call alive after playing
                    appendLine("""  <Pause length="3600"/>""")
                }

                withContext(com.lightningkite.services.http.SettingContextElement(context)) {
                    updateCallWithTwiml(callId, twiml)
                }

                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to play audio: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun playAudio(callId: String, audio: TypedData) {
        // For Twilio, we need to provide a URL to the audio
        // This is a limitation - in a real implementation, you'd need to host the audio
        throw PhoneCallException("playAudio requires hosting audio at a URL. Use playAudioUrl() or speak() instead.")
    }

    override suspend fun sendDtmf(callId: String, digits: String) {
        val span = tracer?.spanBuilder("phonecall.send_dtmf")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "send_dtmf")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.setAttribute("phonecall.digits", digits)
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val twiml = buildTwimlInternal {
                    appendLine("""<Play digits="$digits"/>""")
                }

                withContext(com.lightningkite.services.http.SettingContextElement(context)) {
                    updateCallWithTwiml(callId, twiml)
                }

                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to send DTMF: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun hold(callId: String) {
        val span = tracer?.spanBuilder("phonecall.hold")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "hold")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val twiml = buildTwimlInternal {
                    appendLine("""<Play loop="0">http://com.twilio.sounds.music.s3.amazonaws.com/hold-music.mp3</Play>""")
                }

                withContext(com.lightningkite.services.http.SettingContextElement(context)) {
                    updateCallWithTwiml(callId, twiml)
                }

                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to hold call: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun resume(callId: String) {
        val span = tracer?.spanBuilder("phonecall.resume")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "resume")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                // Resume by providing new instructions
                val twiml = buildTwimlInternal {
                    appendLine("""<Pause length="3600"/>""")
                }

                withContext(com.lightningkite.services.http.SettingContextElement(context)) {
                    updateCallWithTwiml(callId, twiml)
                }

                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to resume call: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun hangup(callId: String) {
        logger.info { "[$name] Hanging up call $callId" }

        val span = tracer?.spanBuilder("phonecall.hangup")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "hangup")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val response = withContext(com.lightningkite.services.http.SettingContextElement(context)) {
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
                    span?.setStatus(StatusCode.ERROR, "Failed to hangup call: HTTP ${response.status.value}")
                    throw PhoneCallException("Failed to hangup call: $errorMessage")
                }
                logger.debug { "[$name] Call $callId hangup successful" }

                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to hangup call: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun getCallStatus(callId: String): CallInfo? {
        val span = tracer?.spanBuilder("phonecall.get_status")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "get_status")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val response = withContext(com.lightningkite.services.http.SettingContextElement(context)) {
                    client.get("$baseUrl/Calls/$callId.json")
                }

                if (response.status == HttpStatusCode.NotFound) {
                    span?.setAttribute("phonecall.found", false)
                    span?.setStatus(StatusCode.OK)
                    return null
                }

                if (!response.status.isSuccess()) {
                    val errorMessage = response.bodyAsText()
                    span?.setStatus(StatusCode.ERROR, "Failed to get call status: HTTP ${response.status.value}")
                    throw PhoneCallException("Failed to get call status: $errorMessage")
                }

                val body = response.bodyAsText()
                val callInfo = parseTwilioCallResponse(body)

                span?.setAttribute("phonecall.found", true)
                callInfo?.status?.let { span?.setAttribute("phonecall.status", it.name) }
                span?.setStatus(StatusCode.OK)

                return callInfo
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to get call status: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun updateCall(callId: String, instructions: CallInstructions) {
        val span = tracer?.spanBuilder("phonecall.update")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "update")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                withContext(com.lightningkite.services.http.SettingContextElement(context)) {
                    updateCallWithTwiml(callId, renderInstructions(instructions))
                }

                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to update call: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    /**
     * Updates an active call with raw TwiML instructions.
     *
     * @param callId The call to update
     * @param instructions Raw TwiML XML string
     */
    public suspend fun updateCallRaw(callId: String, instructions: String) {
        logger.debug { "[$name] Updating call $callId with TwiML" }

        val span = tracer?.spanBuilder("phonecall.update_raw")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "update_raw")
            ?.setAttribute("phonecall.call_id", callId)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val response = withContext(com.lightningkite.services.http.SettingContextElement(context)) {
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
                    span?.setStatus(StatusCode.ERROR, "Failed to update call: HTTP ${response.status.value}")
                    throw PhoneCallException("Failed to update call: $responseBody")
                }
                logger.debug { "[$name] Call $callId TwiML update successful" }

                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to update call: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
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

    override val onIncomingCall: WebhookSubserviceWithResponse<IncomingCallEvent, CallInstructions?> = TwilioIncomingCallWebhook()

    override val onCallStatus: WebhookSubservice<CallStatusEvent> = TwilioCallStatusWebhook()

    override val onTranscription: WebhookSubservice<TranscriptionEvent> = TwilioTranscriptionWebhook()

    private inner class TwilioIncomingCallWebhook : WebhookSubserviceWithResponse<IncomingCallEvent, CallInstructions?> {
        override suspend fun configureWebhook(httpUrl: String) {
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
                throw PhoneCallException("Failed to configure Twilio Voice webhook: $errorBody")
            }

            logger.info { "[$name] Configured Twilio Voice webhook for $defaultFrom -> $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): IncomingCallEvent {
            val params = parseFormUrlEncoded(body.text())
            validateWebhookSignature(headers, params, incomingCallWebhookUrl)

            return IncomingCallEvent(
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

        override suspend fun onSchedule() {
            // No polling needed for Twilio webhooks
        }
    }

    private inner class TwilioCallStatusWebhook : WebhookSubservice<CallStatusEvent> {
        override suspend fun configureWebhook(httpUrl: String) {
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
                throw PhoneCallException("Failed to configure Twilio status callback webhook: $errorBody")
            }

            logger.info { "[$name] Configured Twilio status callback for $defaultFrom -> $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): CallStatusEvent {
            val params = parseFormUrlEncoded(body.text())
            validateWebhookSignature(headers, params, statusCallbackUrl)

            return CallStatusEvent(
                callId = params["CallSid"] ?: throw PhoneCallException("Missing CallSid"),
                status = parseTwilioStatus(params["CallStatus"]),
                direction = if (params["Direction"] == "inbound") CallDirection.INBOUND else CallDirection.OUTBOUND,
                from = (params["From"] ?: "+0").toPhoneNumber(),
                to = (params["To"] ?: "+0").toPhoneNumber(),
                duration = params["CallDuration"]?.toLongOrNull()?.seconds,
                endReason = null,
                metadata = params.filterKeys { it !in setOf("CallSid", "CallStatus", "Direction", "From", "To", "CallDuration") }
            )
        }

        override suspend fun onSchedule() {
            // No polling needed
        }
    }

    private inner class TwilioTranscriptionWebhook : WebhookSubservice<TranscriptionEvent> {
        override suspend fun configureWebhook(httpUrl: String) {
            transcriptionCallbackUrl = httpUrl
            // Transcription callbacks are configured per-call via TwiML, not on the phone number
            // So we just store the URL here for use in renderInstruction()
            logger.info { "[$name] Configured transcription callback URL: $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): TranscriptionEvent {
            val params = parseFormUrlEncoded(body.text())
            validateWebhookSignature(headers, params, transcriptionCallbackUrl)

            return TranscriptionEvent(
                callId = params["CallSid"] ?: throw PhoneCallException("Missing CallSid"),
                text = params["SpeechResult"] ?: params["TranscriptionText"] ?: "",
                isFinal = true,
                confidence = params["Confidence"]?.toFloatOrNull(),
                language = params["Language"]
            )
        }

        override suspend fun onSchedule() {
            // No polling needed
        }
    }

    // ==================== DTMF/Gather Webhook ====================

    override val onDtmf: WebhookSubserviceWithResponse<DtmfEvent, CallInstructions?> = TwilioDtmfWebhook()

    private var dtmfCallbackUrl: String? = null

    private inner class TwilioDtmfWebhook : WebhookSubserviceWithResponse<DtmfEvent, CallInstructions?> {
        override suspend fun configureWebhook(httpUrl: String) {
            dtmfCallbackUrl = httpUrl
            // DTMF callbacks are configured per-Gather via TwiML action URL
            // This URL is used as a default when not specified in CallInstructions.Gather
            logger.info { "[$name] Configured DTMF callback URL: $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): DtmfEvent {
            val params = parseFormUrlEncoded(body.text())
            validateWebhookSignature(headers, params, dtmfCallbackUrl)

            return DtmfEvent(
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

        override suspend fun onSchedule() {
            // No polling needed
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
                append(""" finishOnKey="${inst.finishOnKey}"""")
                if (inst.speechEnabled) {
                    append(""" input="dtmf speech" speechTimeout="auto"""")
                }
                appendLine(">")
                inst.prompt?.let {
                    appendLine("""    <Say>$it</Say>""")
                }
                appendLine("  </Gather>")
                inst.then?.let { renderInstruction(it) }
            }
            is CallInstructions.Forward -> {
                append("  <Dial")
                append(""" timeout="${inst.timeout.inWholeSeconds}"""")
                inst.callerId?.let { append(""" callerId="${it.raw}"""") }
                appendLine(">")
                appendLine("""    <Number>${inst.to.raw}</Number>""")
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
                append(""" finishOnKey="${inst.finishOnKey}"""")
                appendLine("/>")
                inst.then?.let { renderInstruction(it) }
            }
            is CallInstructions.Redirect -> {
                appendLine("""  <Redirect>${escapeXml(inst.url)}</Redirect>""")
            }
            is CallInstructions.Enqueue -> {
                append("  <Enqueue")
                inst.waitUrl?.let { append(""" waitUrl="${escapeXml(it)}"""") }
                appendLine(""">${inst.name}</Enqueue>""")
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
     * Looks up the Twilio phone number SID for a given phone number.
     *
     * @param phoneNumber The phone number in E.164 format (e.g., +15551234567)
     * @return The phone number SID (e.g., PN...)
     * @throws PhoneCallException if the phone number is not found
     */
    private suspend fun lookupPhoneNumberSid(phoneNumber: String): String {
        val span = tracer?.spanBuilder("phonecall.lookup_number_sid")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("phonecall.operation", "lookup_number_sid")
            ?.setAttribute("phonecall.phone_number", phoneNumber)
            ?.setAttribute("phonecall.provider", "twilio")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val encodedNumber = java.net.URLEncoder.encode(phoneNumber, Charsets.UTF_8)
                val response = withContext(com.lightningkite.services.http.SettingContextElement(context)) {
                    client.get("$baseUrl/IncomingPhoneNumbers.json?PhoneNumber=$encodedNumber")
                }

                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    span?.setStatus(StatusCode.ERROR, "Failed to lookup phone number: HTTP ${response.status.value}")
                    throw PhoneCallException("Failed to look up Twilio phone number: $errorBody")
                }

                val responseBody = response.bodyAsText()
                // Parse the SID from the JSON response
                // Response format: {"incoming_phone_numbers": [{"sid": "PN...", ...}], ...}
                val sidMatch = Regex(""""sid"\s*:\s*"(PN[^"]+)"""").find(responseBody)
                val sid = sidMatch?.groupValues?.get(1)
                    ?: throw PhoneCallException("Phone number $phoneNumber not found in Twilio account $account")

                span?.setAttribute("phonecall.phone_number_sid", sid)
                span?.setStatus(StatusCode.OK)
                return sid
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to lookup phone number: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
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
        // Simple regex-based parsing (in production, use proper JSON parsing)
        val sid = Regex(""""sid"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
        val status = Regex(""""status"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "+0"
        val to = Regex(""""to"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "+0"
        val direction = Regex(""""direction"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        val answeredBy = Regex(""""answered_by"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

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
            from: String
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
            from: String
        ): PhoneCallService.Settings = PhoneCallService.Settings("twilio://$account-$keySid:$keySecret@$from")

        init {
            PhoneCallService.Settings.register("twilio") { name, url, context ->
                // Try API Key format first: twilio://account/keySid:keySecret@phoneNumber
                val apiKeyRegex = Regex("""twilio://(?<account>[^/:]+)-(?<keySid>[^:]+):(?<keySecret>[^@]+)@(?<phoneNumber>.+)""")
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
