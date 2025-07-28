package com.lightningkite.serviceabstractions.notifications

import com.lightningkite.serviceabstractions.SettingContext

/**
 * A notification service implementation for testing purposes.
 * It tracks sent notifications and can optionally print them to the console.
 */
public class TestNotificationService(
    context: SettingContext
) : MetricTrackingNotificationService(context) {

    /**
     * Represents a notification message sent to targets.
     */
    public data class Message(
        val targets: List<String>, 
        val data: NotificationData
    )

    /**
     * Whether to print notifications to the console.
     */
    public var printToConsole: Boolean = false

    /**
     * The last message that was sent.
     */
    public var lastMessageSent: Message? = null
        private set

    /**
     * Callback function that is invoked when a message is sent.
     */
    public var onMessageSent: ((Message) -> Unit)? = null

    /**
     * Sends a notification to the specified targets for testing purposes.
     * 
     * @param targets The device tokens to send the notification to
     * @param data The notification data to send
     * @return A map of target tokens to send results (always Success)
     */
    override suspend fun sendImplementation(
        targets: List<String>,
        data: NotificationData
    ): Map<String, NotificationSendResult> {
        val message = Message(targets, data)
        lastMessageSent = message
        onMessageSent?.invoke(message)
        
        if (printToConsole) {
            // Use the ConsoleNotificationService to print the message
            ConsoleNotificationService(context).send(targets, data)
        }
        
        return targets.associateWith { NotificationSendResult.Success }
    }

    /**
     * Resets the test state by clearing the last message and callback.
     */
    public fun reset() {
        lastMessageSent = null
        onMessageSent = null
        printToConsole = false
    }

    public companion object {
        /**
         * Creates a TestNotificationService with the given context.
         * This factory method is used by the Settings class.
         */
        public fun create(context: SettingContext): TestNotificationService = 
            TestNotificationService(context)
    }
}