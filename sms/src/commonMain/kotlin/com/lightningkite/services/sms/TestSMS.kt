package com.lightningkite.services.sms

import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext

/**
 * A test implementation of SMS that stores messages for verification in tests.
 * This implementation is useful for unit testing.
 */
public class TestSMS(
    override val name: String,
    override val context: SettingContext
) : SMS {

    /**
     * Represents an SMS message with recipient and content.
     */
    public data class Message(
        public val to: PhoneNumber,
        public val message: String
    )

    /**
     * Whether to print messages to the console.
     */
    public var printToConsole: Boolean = false

    /**
     * The last message sent.
     */
    public var lastMessageSent: Message? = null
        private set

    /**
     * Callback function that is invoked when a message is sent.
     */
    public var onMessageSent: ((Message) -> Unit)? = null

    /**
     * List of all messages sent.
     */
    public val messageHistory: MutableList<Message> = mutableListOf()

    /**
     * Clears the message history and last message sent.
     */
    public fun reset() {
        lastMessageSent = null
        messageHistory.clear()
    }

    /**
     * Stores the message and invokes the callback.
     */
    override suspend fun send(to: PhoneNumber, message: String) {
        val m = Message(to, message)
        lastMessageSent = m
        messageHistory.add(m)
        onMessageSent?.invoke(m)

        if (printToConsole) {
            println("SMS to $to:")
            println(message)
            println()
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Test SMS Service - No real messages are sent.")
    }
}