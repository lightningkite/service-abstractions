package com.lightningkite.services.subscription

import com.lightningkite.services.*
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.database.Database
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Service abstraction for subscription-based payments.
 *
 * SubscriptionService provides a unified interface for managing recurring billing
 * across different payment providers (Stripe, Paddle, Lemon Squeezy, etc.). Applications
 * can switch providers via configuration without code changes.
 *
 * ## Key Concepts
 *
 * - **Customer**: Your user's representation in the payment provider
 * - **Product/Price**: What customers subscribe to (your subscription tiers)
 * - **Subscription**: An active billing relationship between customer and price
 * - **Checkout Session**: One-time URL to complete initial subscription
 * - **Customer Portal**: Provider-hosted page for customers to manage subscriptions
 *
 * ## Available Implementations
 *
 * - **ConsoleSubscriptionService** (`console`) - Prints operations to console (development)
 * - **TestSubscriptionService** (`test`) - Collects operations in memory for testing
 * - **StripeSubscriptionService** (`stripe://`) - Stripe implementation (requires subscription-payments-stripe module)
 *
 * ## Typical Flow
 *
 * 1. Create or retrieve customer ID for your user
 * 2. Direct user to checkout session for initial subscription
 * 3. Listen for webhook events to track subscription status changes
 * 4. Direct users to customer portal for self-service management
 *
 * ## Configuration
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val subscriptions: SubscriptionService.Settings =
 *         SubscriptionService.Settings("stripe://sk_live_xxx@whsec_xxx")
 * )
 *
 * val context = SettingContext(...)
 * val subscriptionService: SubscriptionService = settings.subscriptions("payments", context)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Create customer for your user
 * val customerId = subscriptionService.createCustomer(
 *     email = user.email,
 *     metadata = mapOf("userId" to user.id.toString())
 * )
 *
 * // Create checkout session
 * val session = subscriptionService.createCheckoutSession(
 *     CheckoutSessionRequest(
 *         customerId = customerId,
 *         priceId = "price_xxx",
 *         successUrl = "https://example.com/success",
 *         cancelUrl = "https://example.com/cancel"
 *     )
 * )
 * // Redirect user to session.url
 *
 * // Later: let user manage their subscription
 * val portal = subscriptionService.createPortalSession(customerId, "https://example.com/account")
 * // Redirect user to portal.url
 * ```
 *
 * ## Important Gotchas
 *
 * - **Webhook idempotency**: Always handle duplicate webhook events gracefully
 * - **Customer IDs**: Store provider customer IDs in your database
 * - **Price IDs**: Configure prices in provider dashboard, reference by ID
 * - **Test mode**: Use test API keys and test webhooks during development
 * - **PCI compliance**: Never handle card data directly; use provider's hosted pages
 * - **Webhook verification**: Always verify webhook signatures to prevent spoofing
 *
 * @see SubscriptionEvent for webhook event types
 * @see CheckoutSessionRequest for checkout configuration
 * @see Subscription for subscription data model
 */
public interface SubscriptionService : Service {

    /**
     * Configuration for instantiating a SubscriptionService.
     *
     * The URL scheme determines the payment provider:
     * - `console` - Print operations to console (default)
     * - `test` - Collect operations in memory for testing
     * - `stripe://apiKey@webhookSecret` - Stripe provider (requires subscription-payments-stripe module)
     *
     * @property url Connection string defining the payment provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "console"
    ) : Setting<SubscriptionService> {
        public companion object : UrlSettingParser<SubscriptionService>() {
            init {
                register("console") { name, _, context -> ConsoleSubscriptionService(name, context) }
                register("test") { name, _, context -> TestSubscriptionService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): SubscriptionService {
            return parse(name, url, context)
        }
    }

    /**
     * Creates a customer in the payment provider.
     *
     * You should store the returned customer ID in your database associated
     * with your user record for future operations.
     *
     * Note: Some providers (like Paddle) create customers automatically during checkout.
     * For those providers, this method may return a placeholder ID.
     *
     * @param email Customer's email (used for receipts and notifications)
     * @param name Optional display name
     * @param metadata Key-value pairs stored with the customer (e.g., your internal user ID)
     * @return Provider's customer ID
     */
    public suspend fun createCustomer(
        email: String,
        name: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): SubscriptionCustomerId

    /**
     * Retrieves customer details from the payment provider.
     *
     * @param customerId Provider's customer ID
     * @return Customer details or null if not found
     */
    public suspend fun getCustomer(customerId: SubscriptionCustomerId): Customer?

    /**
     * Creates a checkout session URL for starting a new subscription.
     *
     * Direct the user to the returned URL. After completion, they'll be
     * redirected to your success/cancel URLs. Listen for webhooks to
     * confirm the subscription was created.
     *
     * ## Example
     *
     * ```kotlin
     * val session = service.createCheckoutSession(
     *     CheckoutSessionRequest(
     *         customerId = "cus_xxx",
     *         priceId = "price_xxx",
     *         successUrl = "https://example.com/success?session_id={CHECKOUT_SESSION_ID}",
     *         cancelUrl = "https://example.com/cancel"
     *     )
     * )
     * // Redirect user to session.url
     * ```
     *
     * @param request Checkout configuration including customer, price, and redirect URLs
     * @return Session with URL to redirect user to
     */
    public suspend fun checkoutUrl(request: CheckoutSessionRequest): String

    /**
     * Creates a customer portal session for self-service subscription management.
     *
     * The portal allows customers to:
     * - View billing history and invoices
     * - Update payment methods
     * - Change subscription plans (if configured)
     * - Cancel subscriptions
     *
     * @param subscriptionId The specific subscription to manage.
     * @param returnUrl Where to send users when they exit the portal
     * @return Session with URL to redirect user to
     */
    public suspend fun manageSubscriptionUrl(
        subscriptionId: SubscriptionId,
        returnUrl: String,
    ): String

    /**
     * Retrieves a single subscription by ID.
     *
     * @param subscriptionId Provider's subscription ID
     * @return Subscription details or null if not found
     */
    public suspend fun getSubscription(subscriptionId: SubscriptionId): Subscription?

    /**
     * Retrieves all subscriptions for a customer.
     *
     * @param customerId Provider's customer ID
     * @return List of subscriptions (active, trialing, past_due, etc.)
     */
    public suspend fun getSubscriptions(customerId: SubscriptionCustomerId): List<Subscription>

    /**
     * Cancels a subscription.
     *
     * @param subscriptionId Provider's subscription ID
     * @param immediately If true, cancel now and revoke access. If false, cancel at period end
     *                    (customer retains access until current billing period ends).
     * @return Updated subscription with new status
     */
    public suspend fun cancelSubscription(
        subscriptionId: SubscriptionId,
        immediately: Boolean = false
    ): Subscription

    /**
     * Pauses a subscription (stops billing temporarily).
     *
     * Not all providers support pausing. Check provider documentation.
     *
     * @param subscriptionId Provider's subscription ID
     * @return Updated subscription with PAUSED status
     * @throws UnsupportedOperationException if provider doesn't support pausing
     */
    public suspend fun pauseSubscription(subscriptionId: SubscriptionId): Subscription {
        throw UnsupportedOperationException("This provider does not support pausing subscriptions")
    }

    /**
     * Resumes a paused subscription.
     *
     * @param subscriptionId Provider's subscription ID
     * @return Updated subscription with ACTIVE status
     * @throws UnsupportedOperationException if provider doesn't support pausing
     */
    public suspend fun resumeSubscription(subscriptionId: SubscriptionId): Subscription {
        throw UnsupportedOperationException("This provider does not support resuming subscriptions")
    }

    /**
     * Webhook handler for subscription events from the provider.
     *
     * Configure this endpoint in your payment provider's dashboard.
     * Events include subscription created, updated, canceled, payment failed, etc.
     *
     */
    public val onEvent: WebhookSubservice<SubscriptionEvent?>

    override val healthCheckFrequency: Duration get() = 6.hours

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK)
    }
}
