package com.lightningkite.services.subscription

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
public value class SubscriptionProductId(public val value: String)

@JvmInline
@Serializable
public value class SubscriptionPriceId(public val value: String)

@Serializable
public data class SubscriptionProductConfiguration(
    val product: SubscriptionProductId,
    val prices: Map<SubscriptionRecurrence, SubscriptionPriceId>
)

@Serializable
public enum class SubscriptionRecurrence {
    Monthly, Yearly
}

@JvmInline
@Serializable
public value class SubscriptionCustomerId(public val value: String)

@JvmInline
@Serializable
public value class SubscriptionId(public val value: String)

@JvmInline
@Serializable
public value class CheckoutSessionId(public val value: String)