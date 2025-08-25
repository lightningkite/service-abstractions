package com.lightningkite.services.terraform

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public data class PolicyStatement(
    @SerialName("Sid") val sid: String? = null,
    @SerialName("Action") val action: List<String>,
    @SerialName("Effect") val effect: Effect = Effect.Allow,
    @SerialName("Principal") val principal: JsonObject? = null,
    @SerialName("Resource") val resource: List<String>,
) {
    public enum class Effect { Allow, Deny }
}