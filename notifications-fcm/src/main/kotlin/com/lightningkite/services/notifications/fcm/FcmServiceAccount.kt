package com.lightningkite.services.notifications.fcm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * A parsed Google service account, holding just what FCM's HTTP v1 API needs: the target project,
 * the service-account identity, and the RSA private key used to mint short-lived OAuth2 access tokens.
 *
 * This replaces the Firebase Admin SDK's credential handling. Instead of pulling in the SDK, we sign a
 * JWT with the service account's private key and exchange it for an access token directly (see
 * [signedAssertion] and the OAuth flow in [FcmNotificationClient]).
 *
 * @property projectId Firebase/GCP project id — part of the FCM send URL.
 * @property clientEmail Service-account email; the JWT issuer/subject.
 * @property privateKey RSA private key from the service-account JSON, used to sign the JWT assertion.
 * @property tokenUri OAuth2 token endpoint (from the JSON, defaulting to Google's standard endpoint).
 */
public class FcmServiceAccount(
    public val projectId: String,
    public val clientEmail: String,
    public val privateKey: PrivateKey,
    public val tokenUri: String = DEFAULT_TOKEN_URI,
) {
    /**
     * Builds and RS256-signs a JWT assertion that GCP's token endpoint exchanges for an access token.
     *
     * @param nowEpochSeconds Current time in epoch seconds; the token is valid for one hour from here.
     */
    internal fun signedAssertion(nowEpochSeconds: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()

        // Fixed header for RS256-signed JWTs.
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildJsonObject {
            put("iss", clientEmail)
            put("scope", MESSAGING_SCOPE)
            put("aud", tokenUri)
            put("iat", nowEpochSeconds)
            put("exp", nowEpochSeconds + 3600)
        }.toString()

        val signingInput =
            encoder.encodeToString(header.encodeToByteArray()) + "." +
                encoder.encodeToString(claims.encodeToByteArray())

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.encodeToByteArray())
            sign()
        }

        return signingInput + "." + encoder.encodeToString(signature)
    }

    public companion object {
        /** Google's standard OAuth2 token endpoint, used when the JSON omits `token_uri`. */
        public const val DEFAULT_TOKEN_URI: String = "https://oauth2.googleapis.com/token"

        /** OAuth scope granting permission to send FCM messages. */
        internal const val MESSAGING_SCOPE: String = "https://www.googleapis.com/auth/firebase.messaging"

        private val parser = Json { ignoreUnknownKeys = true }

        /**
         * Parses a service-account JSON string (the file downloaded from
         * Firebase Console → Project Settings → Service Accounts).
         */
        public fun fromJson(jsonText: String): FcmServiceAccount {
            val parsed = parser.decodeFromString(ServiceAccountJson.serializer(), jsonText)
            return FcmServiceAccount(
                projectId = parsed.projectId,
                clientEmail = parsed.clientEmail,
                privateKey = parsePrivateKey(parsed.privateKey),
                tokenUri = parsed.tokenUri,
            )
        }

        /** Decodes a PEM-encoded PKCS#8 RSA private key (the `private_key` field of the JSON). */
        private fun parsePrivateKey(pem: String): PrivateKey {
            val der = pem
                .substringAfter("-----BEGIN PRIVATE KEY-----")
                .substringBefore("-----END PRIVATE KEY-----")
                .replace(Regex("\\s"), "")
            val bytes = Base64.getDecoder().decode(der)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }
    }
}

@Serializable
private data class ServiceAccountJson(
    @SerialName("project_id") val projectId: String,
    @SerialName("client_email") val clientEmail: String,
    @SerialName("private_key") val privateKey: String,
    @SerialName("token_uri") val tokenUri: String = FcmServiceAccount.DEFAULT_TOKEN_URI,
)
