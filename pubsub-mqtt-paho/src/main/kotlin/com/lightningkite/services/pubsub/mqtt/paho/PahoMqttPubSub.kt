package com.lightningkite.services.pubsub.mqtt.paho

import com.lightningkite.services.SettingContext
import com.lightningkite.services.pubsub.PubSubChannel
import com.lightningkite.services.pubsub.mqtt.MqttChannel
import com.lightningkite.services.pubsub.mqtt.MqttPubSub
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.eclipse.paho.mqttv5.client.IMqttMessageListener
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttActionListener
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import java.io.FileInputStream
import java.net.URI
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MQTT client implementation using Eclipse Paho.
 *
 * Works with any standard MQTT broker: EMQX, Mosquitto, HiveMQ, etc.
 *
 * ## Connection URLs
 *
 * - `mqtt://host:1883` - TCP connection
 * - `mqtt://user:pass@host:1883` - With credentials
 * - `mqtts://host:8883` - TLS connection
 * - `mqtt://host:1883?clientId=my-client` - Custom client ID
 *
 * ## Dependencies
 *
 * Uses Eclipse Paho MQTT v5 client for MQTT 5.0 support.
 */
public class PahoMqttPubSub(
    override val name: String,
    override val context: SettingContext,
    private val client: MqttAsyncClient,
    private val options: MqttConnectionOptions,
    private val defaultQos: Int = 1
) : MqttPubSub {

    private val json = Json { serializersModule = context.internalSerializersModule }
    private val messageChannels = ConcurrentHashMap<String, kotlinx.coroutines.channels.Channel<MqttMessage>>()

    init {
        // Set up global message callback to route messages to appropriate channels
        client.setCallback(object : MqttCallback {
            override fun disconnected(disconnectResponse: MqttDisconnectResponse?) {}
            override fun mqttErrorOccurred(exception: MqttException?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {
                // Route message to all channels whose subscription pattern matches this topic
                messageChannels.forEach { (subscriptionPattern, channel) ->
                    if (topicMatches(subscriptionPattern, topic)) {
                        channel.trySend(message)
                    }
                }
            }
            override fun deliveryComplete(token: IMqttToken?) {}
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {}
            override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}
        })
    }

    // MQTT topic matching: + matches one level, # matches remaining levels
    private fun topicMatches(pattern: String, topic: String): Boolean {
        val patternParts = pattern.split("/")
        val topicParts = topic.split("/")

        var pi = 0
        var ti = 0

        while (pi < patternParts.size && ti < topicParts.size) {
            when (patternParts[pi]) {
                "#" -> return true  // # matches everything remaining
                "+" -> { pi++; ti++ }  // + matches exactly one level
                topicParts[ti] -> { pi++; ti++ }
                else -> return false
            }
        }

        return pi == patternParts.size && ti == topicParts.size
    }

    public companion object {
        init {
            MqttPubSub.Settings.register("mqtt") { name, url, context ->
                createFromUrl(name, url, context, useTls = false)
            }
            MqttPubSub.Settings.register("mqtts") { name, url, context ->
                createFromUrl(name, url, context, useTls = true)
            }
        }

        /**
         * Creates an SSLSocketFactory from certificate files for AWS IoT Core or other TLS connections.
         *
         * @param certFile Path to client certificate PEM file
         * @param keyFile Path to private key PEM file
         * @param caFile Path to CA certificate PEM file (optional, defaults to system trust store)
         */
        private fun createSSLSocketFactory(
            certFile: String,
            keyFile: String,
            caFile: String?
        ): SSLSocketFactory {
            // Load client certificate
            val certFactory = CertificateFactory.getInstance("X.509")
            val clientCert = FileInputStream(certFile).use {
                certFactory.generateCertificate(it) as X509Certificate
            }

            // Load private key - support both PKCS#1 (RSA PRIVATE KEY) and PKCS#8 (PRIVATE KEY) formats
            val pemContent = FileInputStream(keyFile).use { it.readBytes().decodeToString() }
            val privateKey = when {
                pemContent.contains("BEGIN RSA PRIVATE KEY") -> {
                    // PKCS#1 format - need to convert to PKCS#8
                    val keyBytes = pemContent
                        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                        .replace("-----END RSA PRIVATE KEY-----", "")
                        .replace("\\s".toRegex(), "")
                    val decoded = java.util.Base64.getDecoder().decode(keyBytes)

                    // Use BouncyCastle or convert manually - for now use a workaround
                    // Parse as PKCS#1 RSA key
                    val keyFactory = java.security.KeyFactory.getInstance("RSA")
                    try {
                        val keySpec = java.security.spec.PKCS8EncodedKeySpec(decoded)
                        keyFactory.generatePrivate(keySpec)
                    } catch (e: Exception) {
                        // If PKCS#8 fails, it's PKCS#1 - need to use org.bouncycastle or convert
                        // For AWS IoT, we'll use a PEM reader approach
                        throw IllegalArgumentException("PKCS#1 RSA keys require BouncyCastle. Please convert your key to PKCS#8 format using: openssl pkcs8 -topk8 -nocrypt -in $keyFile -out ${keyFile}.pkcs8", e)
                    }
                }
                pemContent.contains("BEGIN PRIVATE KEY") -> {
                    // PKCS#8 format
                    val keyBytes = pemContent
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replace("\\s".toRegex(), "")
                    val decoded = java.util.Base64.getDecoder().decode(keyBytes)
                    val keySpec = java.security.spec.PKCS8EncodedKeySpec(decoded)
                    val keyFactory = java.security.KeyFactory.getInstance("RSA")
                    keyFactory.generatePrivate(keySpec)
                }
                else -> throw IllegalArgumentException("Unknown private key format in $keyFile")
            }

            // Create KeyStore with client cert and private key
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setCertificateEntry("certificate", clientCert)
            keyStore.setKeyEntry(
                "private-key",
                privateKey,
                "".toCharArray(),
                arrayOf(clientCert)
            )

            // Initialize KeyManagerFactory
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, "".toCharArray())

            // Load CA certificate if provided
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            if (caFile != null) {
                val caCert = FileInputStream(caFile).use {
                    certFactory.generateCertificate(it) as X509Certificate
                }
                val caKeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                caKeyStore.load(null, null)
                caKeyStore.setCertificateEntry("ca-certificate", caCert)
                trustManagerFactory.init(caKeyStore)
            } else {
                // Use system default trust store
                trustManagerFactory.init(null as KeyStore?)
            }

            // Create SSLContext
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(
                keyManagerFactory.keyManagers,
                trustManagerFactory.trustManagers,
                SecureRandom()
            )

            return sslContext.socketFactory
        }

        private fun createFromUrl(
            name: String,
            url: String,
            context: SettingContext,
            useTls: Boolean
        ): PahoMqttPubSub {
            val parsed = URI(url)
            val params = parsed.query?.split("&")?.mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }?.toMap() ?: emptyMap()

            val clientId = params["clientId"]
                ?: "service-$name-${UUID.randomUUID().toString().take(8)}"

            val brokerUrl = if (useTls) {
                "ssl://${parsed.host}:${parsed.port.takeIf { it > 0 } ?: 8883}"
            } else {
                "tcp://${parsed.host}:${parsed.port.takeIf { it > 0 } ?: 1883}"
            }

            val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())

            val options = MqttConnectionOptions().apply {
                isCleanStart = true
                isAutomaticReconnect = true
                connectionTimeout = 30
                keepAliveInterval = 60

                // Extract credentials from URL
                parsed.userInfo?.split(":")?.let { parts ->
                    userName = parts[0]
                    if (parts.size > 1) password = parts[1].toByteArray()
                }

                // SSL/TLS certificate configuration for AWS IoT Core or other secure connections
                if (useTls) {
                    val certFile = params["certFile"]
                    val keyFile = params["keyFile"]
                    val caFile = params["caFile"]

                    if (certFile != null && keyFile != null) {
                        // Use certificate-based authentication
                        socketFactory = createSSLSocketFactory(certFile, keyFile, caFile)
                    }
                    // else: use default SSL with system trust store (for brokers with valid certs)
                }
            }

            return PahoMqttPubSub(name, context, client, options, defaultQos = 1)
        }
    }

    private suspend fun ensureConnected() {
        if (!client.isConnected) {
            suspendCancellableCoroutine<Unit> { cont ->
                client.connect(options, null, object : MqttActionListener {
                    override fun onSuccess(token: IMqttToken) {
                        cont.resume(Unit)
                    }
                    override fun onFailure(token: IMqttToken, ex: Throwable) {
                        cont.resumeWithException(ex)
                    }
                })
            }
        }
    }

    override fun <T> get(
        topic: String,
        serializer: KSerializer<T>,
        qos: MqttPubSub.QoS,
        retained: Boolean
    ): MqttChannel<T> {
        val mqttQos = when (qos) {
            MqttPubSub.QoS.AtMostOnce -> 0
            MqttPubSub.QoS.AtLeastOnce -> 1
            MqttPubSub.QoS.ExactlyOnce -> 2
        }

        return object : MqttChannel<T> {
            override suspend fun emit(value: T) = emit(value, retained)

            override suspend fun emit(value: T, retain: Boolean) {
                ensureConnected()
                val payload = json.encodeToString(serializer, value).toByteArray()
                val message = MqttMessage(payload).apply {
                    this.qos = mqttQos
                    this.isRetained = retain
                }

                suspendCancellableCoroutine<Unit> { cont ->
                    client.publish(topic, message, null, object : MqttActionListener {
                        override fun onSuccess(token: IMqttToken) {
                            cont.resume(Unit)
                        }
                        override fun onFailure(token: IMqttToken, ex: Throwable) {
                            cont.resumeWithException(ex)
                        }
                    })
                }
            }

            override suspend fun collect(collector: FlowCollector<T>) {
                ensureConnected()

                // Create or get the channel for this topic
                val channel = messageChannels.getOrPut(topic) {
                    kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.UNLIMITED)
                }

                // Subscribe to the topic if not already subscribed
                suspendCancellableCoroutine<Unit> { cont ->
                    client.subscribe(topic, mqttQos, null, object : MqttActionListener {
                        override fun onSuccess(token: IMqttToken) {
                            cont.resume(Unit)
                        }
                        override fun onFailure(token: IMqttToken, ex: Throwable) {
                            cont.resumeWithException(ex)
                        }
                    })
                }

                try {
                    // Collect messages from the channel
                    for (msg in channel) {
                        val value = json.decodeFromString(serializer, msg.payload.decodeToString())
                        collector.emit(value)
                    }
                } finally {
                    // Unsubscribe when collector completes
                    client.unsubscribe(topic)
                    messageChannels.remove(topic)
                    channel.close()
                }
            }
        }
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return get(key, serializer, MqttPubSub.QoS.AtLeastOnce, false)
    }

    override fun string(key: String): PubSubChannel<String> {
        return get(key, String.serializer())
    }

    override suspend fun connect() {
        ensureConnected()
    }

    override suspend fun disconnect() {
        if (client.isConnected) {
            suspendCancellableCoroutine<Unit> { cont ->
                client.disconnect(null, object : MqttActionListener {
                    override fun onSuccess(token: IMqttToken) {
                        cont.resume(Unit)
                    }
                    override fun onFailure(token: IMqttToken, ex: Throwable) {
                        // Treat disconnect failure as success
                        cont.resume(Unit)
                    }
                })
            }
        }
    }
}
