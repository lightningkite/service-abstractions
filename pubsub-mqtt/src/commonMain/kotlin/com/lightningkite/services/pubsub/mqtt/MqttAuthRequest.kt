package com.lightningkite.services.pubsub.mqtt

import kotlinx.serialization.Serializable

@Serializable
public data class MqttAuthRequest(
    /** MQTT client identifier */
    val clientId: String,
    /** Username from CONNECT packet (optional) */
    val username: String? = null,
    /** Password from CONNECT packet - often contains JWT or API key */
    val password: String? = null,
    /** Client's source IP address */
    val sourceIp: String? = null,
    /** TLS client certificate CN if using mTLS */
    val certificateCn: String? = null,
    /** Additional protocol-specific data */
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
public sealed class MqttAuthResponse {

    @Serializable
    public data class Allow(
        /** Identifier for this authenticated principal (user ID, device ID, etc.) */
        val principalId: String,
        /**
         * Topics this client can publish to.
         * Supports MQTT wildcards: + (single level), # (multi-level)
         * Use ${clientId} as placeholder for the client's ID.
         */
        val publishTopics: List<String> = emptyList(),
        /**
         * Topics this client can subscribe to.
         * Supports MQTT wildcards and ${clientId} placeholder.
         */
        val subscribeTopics: List<String> = emptyList(),
        /** Force disconnect after this many seconds (for token expiry) */
        val disconnectAfterSeconds: Int? = null,
        /**
         * Superuser flag - if true, bypasses all ACL checks.
         * Use sparingly, typically for admin/system clients only.
         */
        val superuser: Boolean = false
    ) : MqttAuthResponse()

    @Serializable
    public data object Deny : MqttAuthResponse()
}
