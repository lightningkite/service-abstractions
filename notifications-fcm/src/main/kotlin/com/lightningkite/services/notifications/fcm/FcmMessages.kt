package com.lightningkite.services.notifications.fcm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire-format models for the FCM HTTP v1 `messages:send` endpoint.
 *
 * These mirror the JSON structure documented at
 * https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send — field names use
 * `@SerialName` for the API's snake_case. Null fields are omitted at encode time (the client's `Json`
 * is configured with `explicitNulls = false` and `encodeDefaults = false`), so only populated options
 * are sent.
 */
@Serializable
internal data class FcmSendRequest(
    val message: FcmMessage,
    @SerialName("validate_only") val validateOnly: Boolean = false,
)

@Serializable
internal data class FcmMessage(
    val token: String,
    val notification: FcmNotification? = null,
    val data: Map<String, String>? = null,
    val android: FcmAndroid? = null,
    val apns: FcmApns? = null,
    val webpush: FcmWebpush? = null,
)

@Serializable
internal data class FcmNotification(
    val title: String? = null,
    val body: String? = null,
    val image: String? = null,
)

@Serializable
internal data class FcmAndroid(
    val priority: String? = null,
    val ttl: String? = null,
    val notification: FcmAndroidNotification? = null,
)

@Serializable
internal data class FcmAndroidNotification(
    @SerialName("channel_id") val channelId: String? = null,
    val sound: String? = null,
    @SerialName("click_action") val clickAction: String? = null,
)

@Serializable
internal data class FcmApns(
    val headers: Map<String, String>? = null,
    // The APNs `aps` dictionary is heterogeneous (sound may be a string or a critical-sound object),
    // so it's carried as a raw JsonObject rather than a fixed data class.
    val payload: JsonObject? = null,
    @SerialName("fcm_options") val fcmOptions: FcmApnsOptions? = null,
)

@Serializable
internal data class FcmApnsOptions(val image: String? = null)

@Serializable
internal data class FcmWebpush(
    val data: Map<String, String>? = null,
    val notification: FcmNotification? = null,
    @SerialName("fcm_options") val fcmOptions: FcmWebpushOptions? = null,
)

@Serializable
internal data class FcmWebpushOptions(val link: String? = null)

/** OAuth2 token-exchange response from the Google token endpoint. */
@Serializable
internal data class FcmTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

/** Error envelope returned by the v1 API on a non-2xx response. */
@Serializable
internal data class FcmErrorResponse(val error: FcmError? = null)

@Serializable
internal data class FcmError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
    // Each detail is an open object; the FcmError detail carries the messaging-specific `errorCode`.
    val details: List<JsonObject> = emptyList(),
)
