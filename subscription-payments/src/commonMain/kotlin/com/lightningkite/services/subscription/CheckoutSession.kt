package com.lightningkite.services.subscription

import kotlinx.serialization.Serializable

/**
 * Request to create a checkout session for a new subscription.
 *
 * A checkout session represents a one-time URL where a customer can
 * enter payment details and subscribe to a plan.
 *
 * ## Example
 *
 * ```kotlin
 * val request = CheckoutSessionRequest(
 *     customerId = "cus_xxx",  // or null for guest checkout
 *     priceId = "price_xxx",
 *     successUrl = "https://example.com/success?session_id={CHECKOUT_SESSION_ID}",
 *     cancelUrl = "https://example.com/pricing"
 * )
 * ```
 *
 * @property customerId Provider's customer ID (optional for some providers that create customers at checkout)
 * @property customerEmail Email for guest checkout (when customerId is null)
 * @property customerName Name for guest checkout (when customerId is null)
 * @property priceId The price/plan ID to subscribe to. This maps to:
 *                   - Stripe: `price_xxx`
 *                   - Lemon Squeezy: `variant_id`
 *                   - Paddle: `price_id`
 * @property quantity Number of units (for per-seat pricing)
 * @property successUrl URL to redirect after successful checkout. May include `{CHECKOUT_SESSION_ID}` placeholder.
 * @property cancelUrl URL to redirect if customer cancels checkout
 * @property trialPeriodDays Optional trial period in days (overrides price's default trial)
 * @property metadata Key-value pairs to store with the subscription
 * @property allowPromotionCodes Whether to show a promo code field in checkout
 * @property customData Provider-specific passthrough data (appears in webhooks)
 */
@Serializable
public data class CheckoutSessionRequest(
    val customerId: SubscriptionCustomerId? = null,
    val customerEmail: String? = null,
    val customerName: String? = null,
    val priceId: SubscriptionPriceId,
    val quantity: Int = 1,
    val successUrl: String,
    val cancelUrl: String,
    val trialPeriodDays: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
    val allowPromotionCodes: Boolean = false,
    val customData: Map<String, String> = emptyMap()
)

