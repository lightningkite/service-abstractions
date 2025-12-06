package com.lightningkite.services.subscription

import kotlinx.serialization.Serializable

/**
 * Represents a customer in the payment provider.
 *
 * A customer is typically mapped 1:1 with a user in your application.
 * Store the [id] in your database to reference the customer for future operations.
 *
 * @property id Provider's unique customer identifier (e.g., "cus_xxx" for Stripe)
 * @property email Customer's email address (used for receipts)
 * @property name Customer's display name
 * @property metadata Key-value pairs stored with the customer
 * @property defaultPaymentMethodId Default payment method for this customer
 */
@Serializable
public data class Customer(
    val id: SubscriptionCustomerId,
    val email: String?,
    val name: String?,
    val metadata: Map<String, String> = emptyMap(),
    val defaultPaymentMethodId: String? = null
)
