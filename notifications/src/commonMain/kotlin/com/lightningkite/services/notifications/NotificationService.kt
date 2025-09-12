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
        override fun invoke(name: String, context: SettingContext): NotificationService {
            return parse(name, url, context)
        }
        
        public companion object: UrlSettingParser<NotificationService>() {
            init {
                register("console") { name, url, context -> ConsoleNotificationService(name, context) }
                register("test") { name, url, context -> TestNotificationService(name, context) }
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