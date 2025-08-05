package com.lightningkite.services.notifications

import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline


/**
 * Service for sending push notifications to devices.
 */
public interface NotificationService : Service {
    
    /**
     * Settings for configuring the notification service.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String
    ) : Setting<NotificationService> {
        override fun invoke(context: SettingContext): NotificationService {
            return parse(url, context)
        }
        
        public companion object: UrlSettingParser<NotificationService>() {
            init {
                register("console") { url, context -> ConsoleNotificationService(context) }
                register("test") { url, context -> TestNotificationService(context) }
            }
        }
    }
    
    /**
     * Sends a notification to the specified targets.
     * 
     * @param targets The device tokens to send the notification to
     * @param data The notification data to send
     * @return A map of target tokens to send results
     */
    public suspend fun send(targets: List<String>, data: NotificationData): Map<String, NotificationSendResult>
}