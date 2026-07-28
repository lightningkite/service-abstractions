package com.lightningkite.services.notifications.fcm

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.http.HttpResponseException
import com.lightningkite.services.http.SettingContextElement
import com.lightningkite.services.http.client
import com.lightningkite.services.http.statusFailing
import com.lightningkite.services.notifications.*
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.telemetryTrace
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Firebase Cloud Messaging (FCM) implementation for sending push notifications.
 *
 * This talks to the FCM HTTP v1 API directly over the shared Ktor [client] (the `http-client` module)
 * rather than depending on the Firebase Admin SDK. Authentication is a hand-rolled OAuth2 flow: the
 * service account's private key signs a JWT ([FcmServiceAccount.signedAssertion]) which is exchanged
 * for a short-lived access token that is cached and reused across sends.
 *
 * Provides cross-platform push notification delivery with:
 * - **Multi-platform support**: Android, iOS, and Web push notifications
 * - **Rich notifications**: Images, links, sounds, and custom data
 * - **Platform-specific options**: Android channels, iOS critical alerts, Web push
 * - **Parallel sending**: Fans out per-token requests with a concurrency cap
 * - **Token management**: Identifies and reports dead/unregistered tokens
 * - **TTL control**: Message expiration for offline devices
 *
 * ## Supported URL Schemes
 *
 * - `fcm://path/to/credentials.json` - Path to Firebase service account JSON file
 * - `fcm://{...json...}` - Inline JSON credentials string
 *
 * Format: `fcm://[file-path-or-json-string]`
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Using file path
 * NotificationService.Settings("fcm:///etc/secrets/firebase-adminsdk.json")
 *
 * // Using helper functions
 * NotificationService.Settings.Companion.fcm(File("/path/to/credentials.json"))
 * ```
 *
 * ## Implementation Notes
 *
 * - **Transport**: FCM HTTP v1 API (`https://fcm.googleapis.com/v1/projects/{id}/messages:send`)
 * - **One request per token**: The v1 API sends to a single token per request; large audiences are
 *   fanned out in parallel under a concurrency cap ([sendConcurrency]) to respect FCM's QPS limits.
 * - **Token validation**: An `UNREGISTERED` error maps to [NotificationSendResult.DeadToken]
 * - **Serverless support**: No long-lived connection; the shared HTTP client is CRaC-aware and the
 *   access token is refreshed on demand
 * - **Health check**: Issues a `validate_only` send to verify credentials (see [healthCheck])
 *
 * ## Important Gotchas
 *
 * - **Service account required**: Needs Firebase service account JSON (not client credentials)
 * - **Dead tokens**: Unregistered tokens return DeadToken - remove them from your database
 * - **Payload size**: Total message payload limited to 4KB
 * - **iOS requires APNs**: FCM uses Apple Push Notification service for iOS
 * - **Android channels**: Android 8+ requires notification channels (set via android.channel)
 *
 * @property name Service name for logging/metrics
 * @property context Service context
 * @property credentials Parsed service-account credentials used to authenticate with FCM
 */
public open class FcmNotificationClient(
    override val name: String,
    override val context: SettingContext,
    private val credentials: FcmServiceAccount,
    baseClient: HttpClient = client,
) : NotificationService {

    private val log = KotlinLogging.logger("com.lightningkite.services.notifications.fcm.FcmNotificationClient")

    private val sendUrl = "https://fcm.googleapis.com/v1/projects/${credentials.projectId}/messages:send"

    // `explicitNulls`/`encodeDefaults` off so unset options are omitted from the request body;
    // `ignoreUnknownKeys` so parsing token/error responses tolerates fields we don't model.
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    // Own instance so a caller-supplied engine (e.g. a test MockEngine) is honored; production
    // passes the shared, OpenTelemetry-instrumented client.
    private val http: HttpClient = baseClient

    // FCM's batch endpoint was retired (June 2024), so the v1 API takes one token per request. We
    // fan out those requests concurrently and rely on the shared client's HTTP/2 engine to multiplex
    // them over a few connections. This semaphore bounds in-flight requests per send() so a huge
    // audience doesn't launch unbounded work; 500 concurrent multiplexed streams keeps throughput
    // high while staying within the engine's per-host dispatcher cap.
    private val sendConcurrency = Semaphore(permits = 500)

    // ---- OAuth2 access-token cache -------------------------------------------------------------
    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtEpochSeconds: Long = 0L

    public companion object {
        // Refresh a little before actual expiry so an in-flight batch never uses a just-expired token.
        private const val TOKEN_REFRESH_BUFFER_SECONDS = 60L

        // Syntactically valid but guaranteed-unregistered token used by the health check's dry run.
        private const val HEALTH_PROBE_TOKEN = "health-check-token-not-registered"

        public fun NotificationService.Settings.Companion.fcm(jsonString: String): NotificationService.Settings =
            NotificationService.Settings("fcm://$jsonString")

        public fun NotificationService.Settings.Companion.fcm(file: File): NotificationService.Settings =
            NotificationService.Settings("fcm://$file")

        init {
            NotificationService.Settings.register("fcm") { name, url, context ->
                var creds = url.substringAfter("://", "")

                if (!creds.startsWith('{')) {
                    val file = File(creds)
                    require(file.exists()) { "FCM credentials file not found at '$file'" }
                    creds = file.readText()
                }

                FcmNotificationClient(name, context, FcmServiceAccount.fromJson(creds))
            }
        }
    }

    /**
     * Sends a simple notification and data. No custom options are set beyond what is provided.
     * If you need a more complicated set of messages you should use the other functions.
     */
    override suspend fun send(
        targets: List<String>,
        data: NotificationData,
    ): Map<String, NotificationSendResult> =
        telemetryTrace("send", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("notification.operation"), "send")
            put(TelemetryKeys.Messaging.system, "firebase_cloud_messaging")
            put(TelemetryKey.OfLong("notification.target.count"), targets.size.toLong())
            data.timeToLive?.let { ttl -> put(TelemetryKey.OfLong("notification.ttl"), ttl.inWholeSeconds) }
        }) { span ->
            sendInternal(targets, data).also { results ->
                span.enrich(TelemetryAttributes {
                    put(TelemetryKey.OfLong("notification.success.count"), results.values.count { it == NotificationSendResult.Success }.toLong())
                    put(TelemetryKey.OfLong("notification.failure.count"), results.values.count { it == NotificationSendResult.Failure }.toLong())
                    put(TelemetryKey.OfLong("notification.dead_token.count"), results.values.count { it == NotificationSendResult.DeadToken }.toLong())
                })
            }
        }

    private suspend fun sendInternal(
        targets: List<String>,
        data: NotificationData,
    ): Map<String, NotificationSendResult> {
        if (targets.isEmpty()) return emptyMap()

        // Fetch the token once and reuse it across every per-token request. If auth is broken there is
        // nothing to retry per token, so mark them all Failure rather than throwing — matching the
        // resilient contract callers rely on (every input token appears in the result map).
        val token = try {
            accessToken()
        } catch (e: Exception) {
            context.reportException(e)
            log.warn(e) { "FCM auth failed; marking all ${targets.size} tokens Failure" }
            return targets.associateWith { NotificationSendResult.Failure }
        }

        // Everything except the token is identical across messages, so build it once.
        val template = data.toMessageTemplate()

        // Aggregate the distinct provider error codes seen this send, for a single summary log line.
        val errorCodes = Collections.synchronizedSet(HashSet<String>())

        val results = coroutineScope {
            targets.map { deviceToken ->
                async {
                    sendConcurrency.withPermit {
                        deviceToken to sendOne(token, template.copy(token = deviceToken), errorCodes)
                    }
                }
            }.awaitAll()
        }.toMap()

        if (errorCodes.isNotEmpty()) {
            log.warn { "Some notifications failed to send. Error codes received: ${errorCodes.joinToString()}" }
        }
        return results
    }

    /**
     * Sends one message to one token. Never throws: transport failures and non-2xx responses are
     * mapped to [NotificationSendResult] so a single bad token can't cancel sibling sends.
     */
    private suspend fun sendOne(
        accessToken: String,
        message: FcmMessage,
        errorCodes: MutableSet<String>,
    ): NotificationSendResult {
        return try {
            val response = post(accessToken, FcmSendRequest(message))
            if (response.status.isSuccess()) NotificationSendResult.Success
            else classifyError(response, errorCodes)
        } catch (e: Exception) {
            context.reportException(e)
            log.warn(e) { "FCM send failed for a token; marking Failure" }
            NotificationSendResult.Failure
        }
    }

    /** Maps a non-2xx v1 response to a result, recording the provider error code for logging. */
    private suspend fun classifyError(response: HttpResponse, errorCodes: MutableSet<String>): NotificationSendResult {
        val bodyText = response.bodyAsText()
        val error = runCatching { json.decodeFromString(FcmErrorResponse.serializer(), bodyText).error }.getOrNull()
        // The v1 messaging error code lives in error.details[].errorCode; fall back to the coarse status.
        val fcmCode = error?.details?.firstNotNullOfOrNull { it["errorCode"]?.jsonPrimitive?.contentOrNull }
            ?: error?.status
        log.debug { "FCM send rejected (${response.status.value} / $fcmCode): ${bodyText.take(200)}" }

        return if (fcmCode == "UNREGISTERED") {
            NotificationSendResult.DeadToken
        } else {
            fcmCode?.let { errorCodes.add(it) }
            NotificationSendResult.Failure
        }
    }

    private suspend fun post(accessToken: String, request: FcmSendRequest): HttpResponse =
        withContext(SettingContextElement(context)) {
            http.post(sendUrl) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(FcmSendRequest.serializer(), request))
            }
        }

    // ---- Access token --------------------------------------------------------------------------

    /**
     * Returns a valid OAuth2 access token, minting and caching a new one when the cached token is
     * absent or near expiry. Concurrent callers during a refresh are serialized by [tokenMutex] so
     * only one JWT exchange happens.
     */
    private suspend fun accessToken(): String {
        val now = System.currentTimeMillis() / 1000
        cachedToken?.let { if (now < tokenExpiresAtEpochSeconds - TOKEN_REFRESH_BUFFER_SECONDS) return it }
        return tokenMutex.withLock {
            val nowLocked = System.currentTimeMillis() / 1000
            cachedToken?.let { if (nowLocked < tokenExpiresAtEpochSeconds - TOKEN_REFRESH_BUFFER_SECONDS) return@withLock it }
            val fetched = fetchAccessToken(nowLocked)
            cachedToken = fetched.accessToken
            tokenExpiresAtEpochSeconds = nowLocked + fetched.expiresIn
            fetched.accessToken
        }
    }

    /** Exchanges a signed JWT assertion for an access token at the credential's token endpoint. */
    private suspend fun fetchAccessToken(nowEpochSeconds: Long): FcmTokenResponse {
        val response = withContext(SettingContextElement(context)) {
            http.submitForm(
                url = credentials.tokenUri,
                formParameters = Parameters.build {
                    append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    append("assertion", credentials.signedAssertion(nowEpochSeconds))
                }
            ).statusFailing()
        }
        return json.decodeFromString(FcmTokenResponse.serializer(), response.bodyAsText())
    }

    /**
     * Health checks issue a real (but harmless) `validate_only` send to FCM, so they cost a network
     * round trip and a tiny amount of quota. Run them sparingly rather than at the 1-minute default.
     */
    override val healthCheckFrequency: Duration get() = 5.minutes

    /**
     * Verifies the service-account credentials by issuing a `validate_only` send against FCM.
     *
     * A validate-only send authenticates and validates the message without delivering anything. We use
     * a syntactically-valid but bogus device token, which lets us distinguish the cases that matter:
     *
     * - **Credentials work** — the send succeeds, or FCM rejects the bogus token with `400`/`404`.
     *   Either way authentication passed, so the service is healthy ([HealthStatus.Level.OK]).
     * - **Credentials are broken** — the token endpoint or send returns `401`/`403`. This is a genuine
     *   outage for us ([HealthStatus.Level.ERROR]).
     * - **Transport/timeout/quota** — network failure, timeout, or `429`/`5xx`. The credentials may be
     *   fine but we can't confirm, so we report [HealthStatus.Level.WARNING].
     */
    override suspend fun healthCheck(): HealthStatus {
        return try {
            withTimeout(10.seconds) {
                val token = accessToken()
                val response = post(
                    token,
                    FcmSendRequest(FcmMessage(token = HEALTH_PROBE_TOKEN), validateOnly = true),
                )
                healthFromStatus(response.status.value, response.bodyAsText())
            }
        } catch (e: TimeoutCancellationException) {
            HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "FCM dry-run timed out: ${e.message}")
        } catch (e: HttpResponseException) {
            // Raised by fetchAccessToken()'s statusFailing() when the token endpoint rejects our JWT.
            val code = e.response.status.value
            if (code == 401 || code == 403) {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "FCM credentials rejected ($code): ${e.body.take(200)}")
            } else {
                HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "FCM token endpoint error ($code): ${e.body.take(200)}")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "FCM dry-run could not reach the service: ${e.message}")
        }
    }

    private fun healthFromStatus(code: Int, body: String): HealthStatus = when {
        code in 200..299 -> HealthStatus(HealthStatus.Level.OK, additionalMessage = "Dry-run send accepted by FCM.")
        code == 401 || code == 403 -> HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "FCM credentials rejected ($code): ${body.take(200)}")
        code == 400 || code == 404 -> HealthStatus(HealthStatus.Level.OK, additionalMessage = "FCM authenticated; probe token rejected as expected ($code).")
        code == 429 || code in 500..599 -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "FCM temporarily unavailable ($code): ${body.take(200)}")
        else -> HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "FCM dry-run failed ($code): ${body.take(200)}")
    }

    /**
     * Builds the token-independent parts of the message once. The returned template carries a
     * placeholder token; each per-token send copies it with the real device token.
     */
    private fun NotificationData.toMessageTemplate(): FcmMessage {
        val expirationEpochSeconds = timeToLive?.let { (System.currentTimeMillis() / 1000) + it.inWholeSeconds }

        // APNs `aps` dictionary: sound is either a plain name or a critical-sound object.
        val iosOptions = ios
        val apnsPayload = buildJsonObject {
            put("aps", buildJsonObject {
                when {
                    iosOptions == null -> put("sound", "default")
                    iosOptions.critical && iosOptions.sound != null -> put("sound", buildJsonObject {
                        put("critical", 1)
                        put("name", iosOptions.sound)
                        put("volume", 1.0)
                    })
                    iosOptions.sound != null -> put("sound", iosOptions.sound)
                    // ios present but no sound and not critical: leave sound unset.
                }
            })
        }

        val apns = FcmApns(
            headers = expirationEpochSeconds?.let { mapOf("apns-expiration" to it.toString()) },
            payload = apnsPayload,
            fcmOptions = notification?.imageUrl?.let { FcmApnsOptions(image = it) },
        )

        val androidConfig = android?.let { a ->
            FcmAndroid(
                priority = a.priority.toFcm(),
                ttl = timeToLive?.toFcmTtl(),
                notification = FcmAndroidNotification(
                    channelId = a.channel,
                    sound = a.sound,
                    clickAction = notification?.link,
                ),
            )
        }

        val webpush = if (web != null || notification != null) {
            FcmWebpush(
                data = web?.data?.takeIf { it.isNotEmpty() },
                notification = notification?.let { FcmNotification(it.title, it.body, it.imageUrl) },
                fcmOptions = notification?.link?.let { FcmWebpushOptions(link = it) },
            )
        } else null

        val messageData = buildMap {
            data?.let { putAll(it) }
            notification?.link?.let { put("link", it) }
        }.takeIf { it.isNotEmpty() }

        return FcmMessage(
            token = "",
            notification = notification?.let { FcmNotification(it.title, it.body, it.imageUrl) },
            data = messageData,
            android = androidConfig,
            apns = apns,
            webpush = webpush,
        )
    }
}

private fun NotificationPriority.toFcm(): String = when (this) {
    NotificationPriority.HIGH -> "HIGH"
    NotificationPriority.NORMAL -> "NORMAL"
}

/**
 * Formats a [Duration] as the FCM v1 TTL string, e.g. `"3600s"` or `"3.500s"`.
 * The v1 API expects seconds with an `s` suffix and up to nanosecond fractional precision.
 */
private fun Duration.toFcmTtl(): String {
    val seconds = inWholeSeconds
    val nanos = inWholeNanoseconds - seconds * 1_000_000_000L
    return if (nanos == 0L) "${seconds}s"
    else "$seconds.${nanos.toString().padStart(9, '0').trimEnd('0')}s"
}
