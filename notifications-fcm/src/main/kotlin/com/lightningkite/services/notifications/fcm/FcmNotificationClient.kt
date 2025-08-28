package com.lightningkite.services.notifications.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import com.lightningkite.services.SettingContext
import com.lightningkite.services.notifications.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.google.firebase.messaging.Notification as FCMNotification
import com.lightningkite.services.notifications.*
import io.github.oshai.kotlinlogging.KotlinLogging


/**
 * The concrete implementation of NotificationClient that will use Firebase Messaging to send push notifications to
 * clients.
 */
public class FcmNotificationClient(
    override val name: String,
    context: SettingContext
) : MetricTrackingNotificationService(context) {

    private val log = KotlinLogging.logger("com.lightningkite.services.notifications.fcm.FcmNotificationClient")

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
                FirebaseApp.initializeApp(
                    FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(creds.byteInputStream()))
                        .build()
                )
                FcmNotificationClient(name, context)
            }
        }
    }

    /**
     * Sends a simple notification and data. No custom options are set beyond what is provided.
     * If you need a more complicated set of messages you should use the other functions.
     */
    override suspend fun sendImplementation(
        targets: List<String>,
        data: NotificationData
    ): Map<String, NotificationSendResult> {
        val notification = data.notification
        val android = data.android
        val ios = data.ios
        val web = data.web
        val programmaticData = data.data
        fun builder() = with(MulticastMessage.builder()) {
            if (programmaticData != null)
                putAllData(programmaticData)
            notification?.link?.let { putData("link", it) }
            setApnsConfig(
                with(ApnsConfig.builder()) {
                    data.timeToLive?.let {
                        this.putHeader("apns-expiration", it.toString())
                    }
                    if (notification != null) {
                        setFcmOptions(
                            ApnsFcmOptions
                                .builder()
                                .setImage(notification.imageUrl)
                                .build()
                        )
                    }
                    setAps(with(Aps.builder()) {
                        if (ios != null) {
                            if (ios.critical && ios.sound != null)
                                setSound(
                                    CriticalSound.builder()
                                        .setCritical(true)
                                        .setName(ios.sound)
                                        .setVolume(1.0)
                                        .build()
                                )
                            else {
                                setSound(ios.sound)
                            }
                        } else {
                            setSound("default")
                        }
                        build()
                    })
                    build()
                }
            )
            if (android != null)
                setAndroidConfig(
                    with(AndroidConfig.builder()) {
                        setPriority(android.priority.toAndroid())
                        data.timeToLive?.let {
                            setTtl(it.inWholeSeconds)
                        }
                        setNotification(
                            AndroidNotification.builder()
                                .setChannelId(android.channel)
                                .setSound(android.sound)
                                .setClickAction(data.notification?.link)
                                .build()
                        )
                        build()
                    }
                )
            setWebpushConfig(
                with(
                    WebpushConfig
                        .builder()
                ) {
                    if (web != null) {
                        putAllData(web.data)
                    }
                    if (notification != null) {
                        notification.link?.let {
                            setFcmOptions(WebpushFcmOptions.withLink(it))
                        }
                        setNotification(
                            WebpushNotification.builder()
                                .setTitle(notification.title)
                                .setBody(notification.body)
                                .setImage(notification.imageUrl)
                                .build()
                        )
                    }
                    build()
                }
            )
            if (notification != null) {
                setNotification(
                    FCMNotification.builder()
                        .setTitle(notification.title)
                        .setBody(notification.body)
                        .setImage(notification.imageUrl)
                        .build()
                )
            }
            this
        }

        val results = HashMap<String, NotificationSendResult>()
        val errorCodes = HashSet<MessagingErrorCode>()
        targets
            .chunked(500)
            .map {
                builder()
                    .addAllTokens(it)
                    .build()
            }
            .forEach {
                withContext(Dispatchers.IO) {
                    val result = FirebaseMessaging.getInstance().sendEachForMulticast(it)
                    result.responses.forEachIndexed { index, sendResponse ->
                        log.debug { "Send $index: ${sendResponse.messageId} / ${sendResponse.exception?.message} ${sendResponse.exception?.messagingErrorCode}" }
                        results[targets[index]] = when (val errorCode = sendResponse.exception?.messagingErrorCode) {
                            null -> NotificationSendResult.Success
                            MessagingErrorCode.UNREGISTERED -> NotificationSendResult.DeadToken
                            else -> {
                                errorCodes.add(errorCode)
                                NotificationSendResult.Failure
                            }
                        }
                    }
                }
            }
        if (errorCodes.isNotEmpty()) {
            log.warn { "Some notifications failed to send.  Error codes received: ${errorCodes.joinToString()}" }
        }
        return results
    }
}


private fun NotificationPriority.toAndroid(): AndroidConfig.Priority = when (this) {
    NotificationPriority.HIGH -> AndroidConfig.Priority.HIGH
    NotificationPriority.NORMAL -> AndroidConfig.Priority.NORMAL
}