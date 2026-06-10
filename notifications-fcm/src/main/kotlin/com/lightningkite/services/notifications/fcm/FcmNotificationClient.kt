package com.lightningkite.services.notifications.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.MetricKey
import com.lightningkite.services.MetricKeys
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.metricsTrace
import com.lightningkite.services.notifications.*
import com.google.firebase.ErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import com.google.firebase.messaging.Notification as FCMNotification

/**
 * Firebase Cloud Messaging (FCM) implementation for sending push notifications.
 *
 * Provides cross-platform push notification delivery with:
 * - **Multi-platform support**: Android, iOS, and Web push notifications
 * - **Rich notifications**: Images, actions, badges, sounds, and custom data
 * - **Platform-specific options**: Android channels, iOS critical alerts, Web actions
 * - **Batch sending**: Efficiently sends up to 500 notifications per request
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
 * // Using inline JSON (not recommended for production - use secrets management)
 * NotificationService.Settings("fcm://{\"type\":\"service_account\",...}")
 *
 * // Using helper functions
 * NotificationService.Settings.Companion.fcm(File("/path/to/credentials.json"))
 * NotificationService.Settings.Companion.fcm(jsonString)
 * ```
 *
 * ## Implementation Notes
 *
 * - **Firebase SDK**: Uses Firebase Admin SDK for sending messages
 * - **Batch size**: Chunks notifications into groups of 500 (FCM limit)
 * - **Token validation**: Reports DeadToken result for unregistered device tokens
 * - **Platform detection**: Automatically configures platform-specific options (Android, iOS, Web)
 * - **Serverless support**: Implements connect() and disconnect() for AWS Lambda compatibility
 * - **Lazy initialization**: FirebaseApp initialized on first use
 *
 * ## Important Gotchas
 *
 * - **Service account required**: Needs Firebase service account JSON (not client credentials)
 * - **Token management**: Your app must collect and update device tokens
 * - **Dead tokens**: Unregistered tokens return DeadToken - remove them from your database
 * - **Rate limiting**: FCM has quota limits (free tier: unlimited, but throttled)
 * - **Payload size**: Total message payload limited to 4KB
 * - **iOS requires APNs**: FCM uses Apple Push Notification service for iOS
 * - **Android channels**: Android 8+ requires notification channels (set via android.channel)
 * - **Web requires VAPID**: Web push requires VAPID keys configured in Firebase console
 * - **Health check**: Issues a dry-run send to verify credentials; OK when FCM authenticates,
 *   ERROR when the service account is rejected (see [healthCheck])
 * - **FirebaseApp singleton**: Multiple instances with same name share the same FirebaseApp
 *
 * ## Platform-Specific Configuration
 *
 * ### Android
 * - **Priority**: HIGH for urgent notifications, NORMAL for background
 * - **Channel**: Required for Android 8+, defines notification behavior
 * - **Sound**: Custom sound files must be in app's res/raw folder
 * - **TTL**: How long FCM stores message if device is offline
 *
 * ### iOS
 * - **Critical alerts**: Requires special entitlement from Apple
 * - **Sound**: Custom sounds must be in app bundle
 * - **Badge**: App icon badge number
 * - **Mutable content**: Enables notification service extensions
 *
 * ### Web
 * - **Image**: Full image URL (not local file)
 * - **Actions**: Buttons/actions in notification
 * - **VAPID**: Requires VAPID keys configured in Firebase console
 *
 * ## Firebase Setup
 *
 * 1. Create a Firebase project at https://console.firebase.google.com
 * 2. Add your app (Android, iOS, or Web) to the project
 * 3. Download the service account JSON:
 *    - Go to Project Settings → Service Accounts
 *    - Click "Generate new private key"
 *    - Save the JSON file securely
 * 4. For iOS: Upload APNs authentication key or certificate
 * 5. For Web: Generate VAPID keys in Cloud Messaging settings
 *
 * ## Example Usage
 *
 * ```kotlin
 * val fcm = NotificationService.Settings.Companion.fcm(File("firebase-adminsdk.json"))
 *     .invoke("fcm-service", context)
 *
 * val results = fcm.send(
 *     targets = listOf("device-token-1", "device-token-2"),
 *     data = NotificationData(
 *         notification = NotificationData.Notification(
 *             title = "New Message",
 *             body = "You have a new message!",
 *             imageUrl = "https://example.com/image.png"
 *         ),
 *         android = NotificationData.Android(
 *             channel = "messages",
 *             priority = NotificationPriority.HIGH
 *         ),
 *         timeToLive = Duration.hours(24)
 *     )
 * )
 *
 * // Remove dead tokens from database
 * results.filterValues { it == NotificationSendResult.DeadToken }
 *     .keys.forEach { token -> database.removeToken(token) }
 * ```
 *
 * @property name Service name for logging/metrics (also used as FirebaseApp name)
 * @property context Service context
 */
public open class FcmNotificationClient(
    override val name: String,
    override val context: SettingContext,
    private val options: FirebaseOptions,
) : NotificationService {

    private val log = KotlinLogging.logger("com.lightningkite.services.notifications.fcm.FcmNotificationClient")

    // Cached FirebaseMessaging instance — avoids per-send lookup via FirebaseApp.getInstance
    private val messaging by lazy { FirebaseMessaging.getInstance(FirebaseApp.getInstance(name)) }

    /**
     * Test seam for the underlying FCM batch call. Production sends via the cached
     * [FirebaseMessaging] instance; tests override this to inject controlled responses
     * or transport failures without needing real Firebase credentials.
     */
    protected open fun sendMulticast(message: MulticastMessage): BatchResponse =
        messaging.sendEachForMulticast(message)

    /**
     * Test seam for the dry-run send used by [healthCheck]. A dry-run validates the message and
     * exercises the service-account credentials against Google's servers without actually
     * delivering a notification. Tests override this to simulate each [FirebaseMessagingException]
     * outcome (bad credentials, rejected token, transport failure) without real Firebase access.
     */
    protected open fun sendDryRun(message: Message): String =
        messaging.send(message, /* dryRun = */ true)

    // Caps concurrent FCM HTTPS calls across the lifetime of this client to avoid tripping
    // per-project QPS limits when fanning out large multicasts (e.g. tens of thousands of tokens).
    private val sendConcurrency = Semaphore(permits = 8)

    public companion object {
        public fun NotificationService.Settings.Companion.fcm(jsonString: String): NotificationService.Settings =
            NotificationService.Settings("fcm://$jsonString")

        public fun NotificationService.Settings.Companion.fcm(file: File): NotificationService.Settings =
            NotificationService.Settings("fcm://$file")

        init {
            NotificationService.Settings.register("fcm") { name, url, context ->
                var creds = url.substringAfter("://", "")

                if (!creds.startsWith('{')) {
                    val file = File(creds)
                    assert(file.exists()) { "FCM credentials file not found at '$file'" }
                    creds = file.readText()
                }

                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(creds.byteInputStream()))
                    .build()

                FcmNotificationClient(name, context, options)
            }
        }
    }

    private fun initializeFirebaseApp(name: String, options: FirebaseOptions) {
        // Prevent duplicate initialization by checking if a FirebaseApp with this name already exists
        val existingApp = FirebaseApp.getApps().firstOrNull { it.name == name }
        if (existingApp == null)
            FirebaseApp.initializeApp(options, name)
    }

    init {
        initializeFirebaseApp(name, options)
    }

    /**
     * Sends a simple notification and data. No custom options are set beyond what is provided.
     * If you need a more complicated set of messages you should use the other functions.
     */
    override suspend fun send(
        targets: List<String>,
        data: NotificationData,
    ): Map<String, NotificationSendResult> =
      metricsTrace("send", attributes = MetricAttributes {
          put(MetricKey.OfString("notification.operation"), "send")
          put(MetricKeys.Messaging.system, "firebase_cloud_messaging")
          put(MetricKey.OfLong("notification.target.count"), targets.size.toLong())
          data.timeToLive?.let { ttl -> put(MetricKey.OfLong("notification.ttl"), ttl.inWholeSeconds) }
      }) { span ->
        sendInternal(targets, data).also { results ->
            val successCount = results.values.count { it == NotificationSendResult.Success }
            val failureCount = results.values.count { it == NotificationSendResult.Failure }
            val deadTokenCount = results.values.count { it == NotificationSendResult.DeadToken }

            span.enrich(MetricAttributes {
                put(MetricKey.OfLong("notification.success.count"), successCount.toLong())
                put(MetricKey.OfLong("notification.failure.count"), failureCount.toLong())
                put(MetricKey.OfLong("notification.dead_token.count"), deadTokenCount.toLong())
            })
        }
      }

    private suspend fun sendInternal(
        targets: List<String>,
        data: NotificationData,
    ): Map<String, NotificationSendResult> {
        val notification = data.notification
        val android = data.android
        val ios = data.ios
        val web = data.web
        val programmaticData = data.data

        // Build shared platform config once; only the token list varies per chunk
        val apnsConfig = ApnsConfig.builder().also { apns ->
            data.timeToLive?.let {
                val expirationTime = (System.currentTimeMillis() / 1000) + it.inWholeSeconds
                apns.putHeader("apns-expiration", expirationTime.toString())
            }
            if (notification != null) {
                apns.setFcmOptions(ApnsFcmOptions.builder().setImage(notification.imageUrl).build())
            }
            apns.setAps(Aps.builder().also { aps ->
                if (ios != null) {
                    if (ios.critical && ios.sound != null)
                        aps.setSound(CriticalSound.builder().setCritical(true).setName(ios.sound).setVolume(1.0).build())
                    else
                        aps.setSound(ios.sound)
                } else {
                    aps.setSound("default")
                }
            }.build())
        }.build()

        val androidConfig = if (android != null) AndroidConfig.builder().also { ac ->
            ac.setPriority(android.priority.toAndroid())
            data.timeToLive?.let { ac.setTtl(it.inWholeMilliseconds) }
            ac.setNotification(
                AndroidNotification.builder()
                    .setChannelId(android.channel)
                    .setSound(android.sound)
                    .setClickAction(notification?.link)
                    .build()
            )
        }.build() else null

        val webpushConfig = WebpushConfig.builder().also { wp ->
            web?.let { wp.putAllData(it.data) }
            if (notification != null) {
                notification.link?.let { wp.setFcmOptions(WebpushFcmOptions.withLink(it)) }
                wp.setNotification(
                    WebpushNotification.builder()
                        .setTitle(notification.title)
                        .setBody(notification.body)
                        .setImage(notification.imageUrl)
                        .build()
                )
            }
        }.build()

        val fcmNotification = if (notification != null) FCMNotification.builder()
            .setTitle(notification.title)
            .setBody(notification.body)
            .setImage(notification.imageUrl)
            .build() else null

        val results = HashMap<String, NotificationSendResult>()
        val errorCodes = HashSet<MessagingErrorCode>()
        val chunks = targets.chunked(500)

        // Dispatch all chunks in parallel, each with its own OTel sub-span.
        // Each async returns the per-token results for its chunk. On transport failure
        // (the entire sendEachForMulticast call throws) we map every token in the chunk
        // to Failure rather than rethrowing — otherwise awaitAll would cancel sibling
        // chunks and discard their already-completed results.
        val chunkResults: List<Map<String, NotificationSendResult>> = coroutineScope {
            chunks.map { chunk ->
                async(Dispatchers.IO) {
                    sendConcurrency.withPermit {
                        val message = MulticastMessage.builder().also { mb ->
                            programmaticData?.let { mb.putAllData(it) }
                            notification?.link?.let { mb.putData("link", it) }
                            mb.setApnsConfig(apnsConfig)
                            androidConfig?.let { mb.setAndroidConfig(it) }
                            mb.setWebpushConfig(webpushConfig)
                            fcmNotification?.let { mb.setNotification(it) }
                            mb.addAllTokens(chunk)
                        }.build()

                        metricsTrace("batch", attributes = MetricAttributes {
                            put(MetricKeys.Messaging.system, "firebase_cloud_messaging")
                            put(MetricKeys.Messaging.batchMessageCount, chunk.size.toLong())
                        }) { batchSpan ->
                            val chunkOutcome = HashMap<String, NotificationSendResult>(chunk.size)
                            try {
                                val result = sendMulticast(message)
                                val successCount = result.successCount
                                val failureCount = result.failureCount
                                batchSpan.enrich(MetricAttributes {
                                    put(MetricKey.OfLong("notification.success_count"), successCount.toLong())
                                    put(MetricKey.OfLong("notification.failure_count"), failureCount.toLong())
                                })
                                result.getResponses().forEachIndexed { index: Int, sendResponse: SendResponse ->
                                    val targetToken = chunk[index]
                                    log.debug { "Send: ${sendResponse.messageId} / ${sendResponse.exception?.message} ${sendResponse.exception?.messagingErrorCode}" }
                                    chunkOutcome[targetToken] = when (val errorCode = sendResponse.exception?.messagingErrorCode) {
                                        null -> NotificationSendResult.Success
                                        MessagingErrorCode.UNREGISTERED -> NotificationSendResult.DeadToken
                                        else -> {
                                            synchronized(errorCodes) { errorCodes.add(errorCode) }
                                            NotificationSendResult.Failure
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Transport-level failure for the entire chunk. Report it and mark
                                // every token Failure so the caller can retry — but do NOT rethrow,
                                // or sibling chunks get cancelled (which would also auto-fail the span).
                                context.reportException(e)
                                log.warn(e) { "FCM batch send failed for ${chunk.size} tokens; marking all as Failure" }
                                for (token in chunk) chunkOutcome[token] = NotificationSendResult.Failure
                            }
                            chunkOutcome
                        }
                    }
                }
            }.awaitAll()
        }

        for (chunkOutcome in chunkResults) {
            results.putAll(chunkOutcome)
        }

        if (errorCodes.isNotEmpty()) {
            log.warn { "Some notifications failed to send.  Error codes received: ${errorCodes.joinToString()}" }
        }
        return results
    }

    override suspend fun connect(): Unit = metricsTrace("connect", attributes = MetricAttributes {
        put(MetricKey.OfString("notification.operation"), "connect")
        put(MetricKey.OfString("notification.system"), "fcm")
    }) {
        initializeFirebaseApp(name, options)
    }

    override suspend fun disconnect(): Unit = metricsTrace("disconnect", attributes = MetricAttributes {
        put(MetricKey.OfString("notification.operation"), "disconnect")
        put(MetricKey.OfString("notification.system"), "fcm")
    }) {
        // Important for serverless environments - clean up Firebase resources
        try {
            FirebaseApp.getInstance(name).delete()
        } catch (e: IllegalStateException) {
            // App already deleted or doesn't exist
            log.debug { "FirebaseApp '$name' already deleted or doesn't exist" }
        }
    }

    /**
     * Health checks issue a real (but harmless) dry-run send to FCM, so they cost a network round
     * trip and a tiny amount of quota. Run them sparingly rather than at the 1-minute default.
     */
    override val healthCheckFrequency: Duration get() = 5.minutes

    /**
     * Verifies the service-account credentials by issuing a dry-run send against Google's servers.
     *
     * A dry-run validates the message and authenticates with FCM without delivering a notification.
     * We send a syntactically-valid but bogus device token, which lets us distinguish the two cases
     * that matter:
     *
     * - **Credentials work** — either the dry-run succeeds, or FCM rejects the bogus token with
     *   `INVALID_ARGUMENT`/`UNREGISTERED`/`SENDER_ID_MISMATCH`. Either way authentication passed, so
     *   the service is healthy ([HealthStatus.Level.OK]).
     * - **Credentials are broken** — FCM returns `UNAUTHENTICATED` or `PERMISSION_DENIED` (bad or
     *   unauthorized service account). This is a genuine outage for us ([HealthStatus.Level.ERROR]).
     * - **Transport/timeout/quota** — network failure, timeout, or rate limiting. The credentials
     *   may be fine but we can't confirm, so we report [HealthStatus.Level.WARNING].
     *
     * Provider exceptions are mapped to a [HealthStatus]; none are allowed to escape.
     */
    override suspend fun healthCheck(): HealthStatus {
        // Syntactically valid FCM token shape (no colon-delimited APA91 form required for a dry-run);
        // it is guaranteed not to be registered, so a healthy backend rejects it with INVALID_ARGUMENT
        // or UNREGISTERED rather than actually delivering anything.
        val probe = Message.builder()
            .setToken("health-check-token-not-registered")
            .build()

        return try {
            withTimeout(10.seconds) {
                withContext(Dispatchers.IO) { sendDryRun(probe) }
            }
            HealthStatus(HealthStatus.Level.OK, additionalMessage = "Dry-run send accepted by FCM.")
        } catch (e: TimeoutCancellationException) {
            HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "FCM dry-run timed out: ${e.message}"
            )
        } catch (e: FirebaseMessagingException) {
            when (e.errorCode) {
                // Authentication/authorization failure — the credentials themselves are broken.
                ErrorCode.UNAUTHENTICATED, ErrorCode.PERMISSION_DENIED -> HealthStatus(
                    HealthStatus.Level.ERROR,
                    additionalMessage = "FCM credentials rejected (${e.errorCode}): ${e.message}"
                )
                // The bogus token was rejected but the credentials authenticated successfully.
                ErrorCode.INVALID_ARGUMENT, ErrorCode.NOT_FOUND -> HealthStatus(
                    HealthStatus.Level.OK,
                    additionalMessage = "FCM authenticated; probe token rejected as expected (${e.errorCode})."
                )
                // Rate limiting / transient backend unavailability — credentials likely fine but
                // unconfirmed this round.
                ErrorCode.RESOURCE_EXHAUSTED, ErrorCode.UNAVAILABLE, ErrorCode.DEADLINE_EXCEEDED -> HealthStatus(
                    HealthStatus.Level.WARNING,
                    additionalMessage = "FCM temporarily unavailable (${e.errorCode}): ${e.message}"
                )
                else -> HealthStatus(
                    HealthStatus.Level.ERROR,
                    additionalMessage = "FCM dry-run failed (${e.errorCode}): ${e.message}"
                )
            }
        } catch (e: Exception) {
            // Transport/network failure: we couldn't reach FCM at all. Credentials may be fine but
            // we can't confirm, so warn rather than hard-error.
            HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "FCM dry-run could not reach the service: ${e.message}"
            )
        }
    }
}


private fun NotificationPriority.toAndroid(): AndroidConfig.Priority = when (this) {
    NotificationPriority.HIGH -> AndroidConfig.Priority.HIGH
    NotificationPriority.NORMAL -> AndroidConfig.Priority.NORMAL
}