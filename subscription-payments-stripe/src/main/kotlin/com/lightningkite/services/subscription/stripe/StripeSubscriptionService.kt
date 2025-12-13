package com.lightningkite.services.subscription.stripe

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.database.*
import com.lightningkite.services.subscription.*
import com.stripe.Stripe
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import com.stripe.exception.InvalidRequestException
import com.stripe.model.Event
import com.stripe.model.Invoice
import com.stripe.model.StripeObject
import com.stripe.model.WebhookEndpoint
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.SubscriptionListParams
import com.stripe.param.SubscriptionUpdateParams
import com.stripe.param.WebhookEndpointListParams
import com.stripe.param.checkout.SessionCreateParams
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Stripe implementation of SubscriptionService.
 *
 * This implementation uses the Stripe Java SDK to interact with the Stripe API
 * for managing subscriptions, customers, and handling webhooks.
 *
 * ## URL Scheme
 *
 * - `stripe://apiKey@webhookSecret` - Full configuration with static webhook secret
 * - `stripe://apiKey` - API key only, requires database for dynamic webhook creation
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Static webhook secret (manual setup)
 * SubscriptionService.Settings("stripe://sk_test_xxx@whsec_xxx")
 *
 * // Dynamic webhook creation (automatic setup with database)
 * SubscriptionService.Settings("stripe://sk_test_xxx")
 * // Then call: service.onEvent(database).configureWebhook(webhookUrl)
 * ```
 *
 * ## Webhook Modes
 *
 * ### Static Mode (webhook secret in URL or env var)
 * - Webhook must be configured manually in Stripe Dashboard
 * - Webhook secret provided at service creation time
 * - No database required
 *
 * ### Dynamic Mode (database provided to onEvent)
 * - Webhook is created automatically via Stripe API
 * - Secret is stored in database for persistence across restarts
 * - Fully automated webhook setup
 *
 * ## Stripe Dashboard Setup (Static Mode)
 *
 * 1. Create products and prices in the Stripe Dashboard
 * 2. Configure the Customer Portal settings
 * 3. Set up webhook endpoint for your server
 * 4. Subscribe to these webhook events:
 *    - `checkout.session.completed`
 *    - `customer.subscription.created`
 *    - `customer.subscription.updated`
 *    - `customer.subscription.deleted`
 *    - `invoice.payment_succeeded`
 *    - `invoice.payment_failed`
 *
 * @property name Service name for logging
 * @property context Service context
 * @property apiKey Stripe API key (starts with sk_test_ or sk_live_)
 * @property staticWebhookSecret Optional static webhook signing secret (starts with whsec_)
 */
public class StripeSubscriptionService(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    private val staticWebhookSecret: String
) : SubscriptionService {

    private val tracer: Tracer? = context.openTelemetry?.getTracer("subscription-payments-stripe")

    init {
        Stripe.apiKey = apiKey
    }

    override suspend fun createCustomer(email: String, name: String?, metadata: Map<String, String>): SubscriptionCustomerId {
        val span = tracer?.spanBuilder("subscription.create_customer")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("subscription.operation", "create_customer")
            ?.setAttribute("subscription.customer_email", email)
            ?.setAttribute("subscription.provider", "stripe")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                val params = CustomerCreateParams.builder()
                    .setEmail(email)
                    .apply {
                        name?.let { setName(it) }
                        metadata.forEach { (k, v) -> putMetadata(k, v) }
                    }
                    .build()

                val customer = com.stripe.model.Customer.create(params)
                val customerId = SubscriptionCustomerId(customer.id)

                span?.setAttribute("subscription.customer_id", customerId.value)
                span?.setStatus(StatusCode.OK)

                customerId
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to create customer: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun getCustomer(customerId: SubscriptionCustomerId): Customer? {
        val span = tracer?.spanBuilder("subscription.get_customer")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("subscription.operation", "get_customer")
            ?.setAttribute("subscription.customer_id", customerId.value)
            ?.setAttribute("subscription.provider", "stripe")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                val stripeCustomer = com.stripe.model.Customer.retrieve(customerId.value)
                if (stripeCustomer.deleted == true) {
                    span?.setAttribute("subscription.customer_found", false)
                    span?.setStatus(StatusCode.OK)
                    return null
                }

                span?.setAttribute("subscription.customer_found", true)
                span?.setStatus(StatusCode.OK)

                Customer(
                    id = SubscriptionCustomerId(stripeCustomer.id),
                    email = stripeCustomer.email,
                    name = stripeCustomer.name,
                    metadata = stripeCustomer.metadata ?: emptyMap(),
                    defaultPaymentMethodId = stripeCustomer.invoiceSettings?.defaultPaymentMethod
                )
            } finally {
                scope?.close()
            }
        } catch (e: InvalidRequestException) {
            if (e.statusCode == 404) {
                span?.setAttribute("subscription.customer_found", false)
                span?.setStatus(StatusCode.OK)
                null
            } else {
                span?.setStatus(StatusCode.ERROR, "Failed to get customer: ${e.message}")
                span?.recordException(e)
                throw e
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to get customer: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun checkoutUrl(request: CheckoutSessionRequest): String {
        val span = tracer?.spanBuilder("subscription.create_checkout_session")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("subscription.operation", "create_checkout_session")
            ?.setAttribute("subscription.price_id", request.priceId.value)
            ?.setAttribute("subscription.quantity", request.quantity.toLong())
            ?.setAttribute("subscription.provider", "stripe")
            ?.apply {
                request.customerId?.let { setAttribute("subscription.customer_id", it.value) }
                request.trialPeriodDays?.let { setAttribute("subscription.trial_period_days", it.toLong()) }
            }
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                val paramsBuilder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setSuccessUrl(request.successUrl)
                    .setCancelUrl(request.cancelUrl)
                    .addLineItem(
                        SessionCreateParams.LineItem.builder()
                            .setPrice(request.priceId.value)
                            .setQuantity(request.quantity.toLong())
                            .build()
                    )

                // Set customer or customer creation
                val customerId = request.customerId
                if (customerId != null) {
                    paramsBuilder.setCustomer(customerId.value)
                } else {
                    val customerCreation = SessionCreateParams.CustomerCreation.ALWAYS
                    paramsBuilder.setCustomerCreation(customerCreation)
                    request.customerEmail?.let { paramsBuilder.setCustomerEmail(it) }
                }

                // Trial period
                request.trialPeriodDays?.let { days ->
                    paramsBuilder.setSubscriptionData(
                        SessionCreateParams.SubscriptionData.builder()
                            .setTrialPeriodDays(days.toLong())
                            .build()
                    )
                }

                // Promo codes
                if (request.allowPromotionCodes) {
                    paramsBuilder.setAllowPromotionCodes(true)
                }

                // Metadata
                request.metadata.forEach { (k, v) -> paramsBuilder.putMetadata(k, v) }

                val session = Session.create(paramsBuilder.build())

                span?.setAttribute("subscription.checkout_session_id", session.id)
                span?.setStatus(StatusCode.OK)

                session.url
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to create checkout session: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun manageSubscriptionUrl(subscriptionId: SubscriptionId, returnUrl: String): String {
        val span = tracer?.spanBuilder("subscription.create_billing_portal_session")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("subscription.operation", "create_billing_portal_session")
            ?.setAttribute("subscription.subscription_id", subscriptionId.value)
            ?.setAttribute("subscription.provider", "stripe")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                val sub = getSubscription(subscriptionId) ?: throw IllegalArgumentException("Subscription not found: ${subscriptionId.value}")

                span?.setAttribute("subscription.customer_id", sub.customerId.value)

                val params = com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setCustomer(sub.customerId.value)
                    .setReturnUrl(returnUrl)
                    .build()

                val session = com.stripe.model.billingportal.Session.create(params)

                span?.setAttribute("subscription.portal_session_id", session.id)
                span?.setStatus(StatusCode.OK)

                session.url
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to create billing portal session: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun getSubscription(subscriptionId: SubscriptionId): Subscription? {
        val span = tracer?.spanBuilder("subscription.get_subscription")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("subscription.operation", "get_subscription")
            ?.setAttribute("subscription.subscription_id", subscriptionId.value)
            ?.setAttribute("subscription.provider", "stripe")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                val stripeSub = com.stripe.model.Subscription.retrieve(subscriptionId.value)
                val subscription = stripeSub.toSubscription()

                span?.setAttribute("subscription.status", subscription.status.name)
                span?.setAttribute("subscription.customer_id", subscription.customerId.value)
                span?.setStatus(StatusCode.OK)

                subscription
            } finally {
                scope?.close()
            }
        } catch (e: InvalidRequestException) {
            if (e.statusCode == 404) {
                span?.setAttribute("subscription.found", false)
                span?.setStatus(StatusCode.OK)
                null
            } else {
                span?.setStatus(StatusCode.ERROR, "Failed to get subscription: ${e.message}")
                span?.recordException(e)
                throw e
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to get subscription: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun getSubscriptions(customerId: SubscriptionCustomerId): List<Subscription> {
        val span = tracer?.spanBuilder("subscription.list_subscriptions")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("subscription.operation", "list_subscriptions")
            ?.setAttribute("subscription.customer_id", customerId.value)
            ?.setAttribute("subscription.provider", "stripe")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                val params = SubscriptionListParams.builder()
                    .setCustomer(customerId.value)
                    .build()

                val subscriptions = com.stripe.model.Subscription.list(params).data.map { it.toSubscription() }

                span?.setAttribute("subscription.count", subscriptions.size.toLong())
                span?.setStatus(StatusCode.OK)

                subscriptions
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to list subscriptions: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun cancelSubscription(subscriptionId: SubscriptionId, immediately: Boolean): Subscription {
        val span = tracer?.spanBuilder("subscription.cancel_subscription")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("subscription.operation", "cancel_subscription")
            ?.setAttribute("subscription.subscription_id", subscriptionId.value)
            ?.setAttribute("subscription.cancel_immediately", immediately)
            ?.setAttribute("subscription.provider", "stripe")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                val subscription = com.stripe.model.Subscription.retrieve(subscriptionId.value)

                span?.setAttribute("subscription.customer_id", subscription.customer)

                val result = if (immediately) {
                    subscription.cancel().toSubscription()
                } else {
                    subscription.update(
                        SubscriptionUpdateParams.builder()
                            .setCancelAtPeriodEnd(true)
                            .build()
                    ).toSubscription()
                }

                span?.setAttribute("subscription.status", result.status.name)
                span?.setStatus(StatusCode.OK)

                result
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to cancel subscription: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    // Note: Stripe does support pausing via pause_collection, but it's less common
    // We leave the default UnsupportedOperationException for now

    override val onEvent: WebhookSubservice<SubscriptionEvent?> = object: WebhookSubservice<SubscriptionEvent?> {
        override suspend fun configureWebhook(httpUrl: String) {
            val params = WebhookEndpointListParams.builder()
                .setLimit(100)
                .build()

            val webhooks = WebhookEndpoint.list(params)
            val existing = webhooks.data.find { it.url == httpUrl && it.status == "enabled" }
            if (existing == null) throw IllegalArgumentException("Webhook not found: $httpUrl")
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): SubscriptionEvent? {
            val span = tracer?.spanBuilder("subscription.webhook.parse")
                ?.setSpanKind(SpanKind.SERVER)
                ?.setAttribute("subscription.operation", "webhook_parse")
                ?.setAttribute("subscription.provider", "stripe")
                ?.startSpan()

            return try {
                val scope = span?.makeCurrent()
                try {
                    val payload = body.text()

                    // Get signature header (case-insensitive lookup)
                    val sigHeader = headers.entries
                        .firstOrNull { it.key.equals("stripe-signature", ignoreCase = true) }
                        ?.value?.joinToString(",")
                        ?: throw IllegalArgumentException("Missing Stripe-Signature header")

                    val secret = staticWebhookSecret

                    // Verify webhook signature
                    val event: Event = try {
                        Webhook.constructEvent(payload, sigHeader, secret)
                    } catch (e: Exception) {
                        throw SecurityException("Invalid webhook signature: ${e.message}")
                    }

                    span?.setAttribute("webhook.event_id", event.id)
                    span?.setAttribute("webhook.event_type", event.type)

                    val result = parseEvent(event)

                    span?.setAttribute("webhook.parsed", result != null)
                    span?.setStatus(StatusCode.OK)

                    result
                } finally {
                    scope?.close()
                }
            } catch (e: Exception) {
                span?.setStatus(StatusCode.ERROR, "Failed to parse webhook: ${e.message}")
                span?.recordException(e)
                throw e
            } finally {
                span?.end()
            }
        }

        private fun parseEvent(event: Event): SubscriptionEvent? {
            // Try automatic deserialization first, fall back to manual if API version mismatch
            val dataObject: StripeObject = event.dataObjectDeserializer.`object`.orElse(null)
                ?: throw IllegalArgumentException("Unable to deserialize Stripe event data. event.dataObjectDeserializer: ${event.dataObjectDeserializer}, json: ${event.data}")

            return when (event.type) {
                // TODO: String constants?  What is this, Javascript?  Replace with constants
                "checkout.session.completed" -> {
                    val session = dataObject as Session
                    SubscriptionEvent.CheckoutCompleted(
                        providerEventId = event.id,
                        sessionId = CheckoutSessionId(session.id),
                        customerId = SubscriptionCustomerId(session.customer),
                        subscriptionId = session.subscription?.let { SubscriptionId(it) },
                        metadata = session.metadata ?: emptyMap()
                    )
                }

                "customer.subscription.created" -> {
                    val sub = dataObject as com.stripe.model.Subscription
                    SubscriptionEvent.SubscriptionCreated(
                        providerEventId = event.id,
                        subscription = sub.toSubscription()
                    )
                }

                "customer.subscription.updated" -> {
                    val sub = dataObject as com.stripe.model.Subscription
                    val previousAttributes = event.data.previousAttributes
                        ?.mapValues { it.value?.toString() ?: "" }
                        ?: emptyMap()
                    SubscriptionEvent.SubscriptionUpdated(
                        providerEventId = event.id,
                        subscription = sub.toSubscription(),
                        previousAttributes = previousAttributes
                    )
                }

                "customer.subscription.deleted" -> {
                    val sub = dataObject as com.stripe.model.Subscription
                    SubscriptionEvent.SubscriptionDeleted(
                        providerEventId = event.id,
                        subscription = sub.toSubscription()
                    )
                }

                "customer.subscription.paused" -> {
                    val sub = dataObject as com.stripe.model.Subscription
                    SubscriptionEvent.SubscriptionPaused(
                        providerEventId = event.id,
                        subscription = sub.toSubscription()
                    )
                }

                "customer.subscription.resumed" -> {
                    val sub = dataObject as com.stripe.model.Subscription
                    SubscriptionEvent.SubscriptionResumed(
                        providerEventId = event.id,
                        subscription = sub.toSubscription()
                    )
                }

                "invoice.payment_succeeded" -> {
                    val invoice = dataObject as Invoice
                    SubscriptionEvent.PaymentSucceeded(
                        providerEventId = event.id,
                        subscriptionId = SubscriptionId(invoice.parent.subscriptionDetails?.subscription ?: return null),
                        customerId = SubscriptionCustomerId(invoice.customer),
                        amountCents = invoice.amountPaid,
                        currency = invoice.currency
                    )
                }

                "invoice.payment_failed" -> {
                    val invoice = dataObject as Invoice
                    SubscriptionEvent.PaymentFailed(
                        providerEventId = event.id,
                        subscriptionId = SubscriptionId(invoice.parent.subscriptionDetails?.subscription ?: return null),
                        customerId = SubscriptionCustomerId(invoice.customer),
                        amountCents = invoice.amountDue,
                        currency = invoice.currency,
                        failureMessage = invoice.lastFinalizationError?.message
                    )
                }

                else -> throw IllegalArgumentException("Unhandled Stripe event type: ${event.type}")
            }
        }

        override suspend fun onSchedule() {
            // Stripe uses push webhooks, no polling needed
        }

        override suspend fun render(output: Unit): HttpAdapter.HttpResponseLike {
            // Stripe expects 200 OK with empty body
            return HttpAdapter.HttpResponseLike(200, emptyMap(), null)
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            // Simple API call to verify credentials work
            com.stripe.model.Balance.retrieve()
            HealthStatus(HealthStatus.Level.OK, additionalMessage = "Stripe API connected")
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Stripe API error: ${e.message}")
        }
    }

    private fun com.stripe.model.Subscription.toSubscription(): Subscription {
        return Subscription(
            id = SubscriptionId(this.id),
            customerId = SubscriptionCustomerId(this.customer),
            status = when (this.status) {
                "active" -> SubscriptionStatus.ACTIVE
                "trialing" -> SubscriptionStatus.TRIALING
                "past_due" -> SubscriptionStatus.PAST_DUE
                "canceled" -> SubscriptionStatus.CANCELED
                "unpaid" -> SubscriptionStatus.UNPAID
                "incomplete" -> SubscriptionStatus.INCOMPLETE
                "incomplete_expired" -> SubscriptionStatus.INCOMPLETE_EXPIRED
                "paused" -> SubscriptionStatus.PAUSED
                else -> SubscriptionStatus.ACTIVE
            },
            priceId = SubscriptionPriceId(this.items?.data?.firstOrNull()?.price?.id ?: ""),
            quantity = this.items?.data?.firstOrNull()?.quantity?.toInt() ?: 1,
            currentPeriodStart = Instant.fromEpochSeconds(this.items?.data?.firstOrNull()?.currentPeriodStart ?: 0L),
            currentPeriodEnd = Instant.fromEpochSeconds(this.items?.data?.firstOrNull()?.currentPeriodEnd ?: 0L),
            cancelAtPeriodEnd = this.cancelAtPeriodEnd ?: false,
            canceledAt = this.canceledAt?.let { Instant.fromEpochSeconds(it) },
            trialStart = this.trialStart?.let { Instant.fromEpochSeconds(it) },
            trialEnd = this.trialEnd?.let { Instant.fromEpochSeconds(it) },
            metadata = this.metadata ?: emptyMap()
        )
    }

    public companion object {
        /**
         * Creates a Stripe settings URL with static webhook secret.
         *
         * @param apiKey Stripe API key (sk_test_xxx or sk_live_xxx)
         * @param webhookSecret Webhook signing secret (whsec_xxx)
         */
        public fun SubscriptionService.Settings.Companion.stripe(
            apiKey: String,
            webhookSecret: String
        ): SubscriptionService.Settings = SubscriptionService.Settings("stripe://$apiKey@$webhookSecret")

        init {
            SubscriptionService.Settings.register("stripe") { name, url, context ->
                // Parse: stripe://apiKey@webhookSecret or stripe://apiKey
                val regex = Regex("""stripe://([^@]+)(?:@(.+))?""")
                val match = regex.matchEntire(url)
                    ?: throw IllegalArgumentException(
                        "Invalid Stripe URL. Expected format: stripe://apiKey@webhookSecret or stripe://apiKey"
                    )

                val apiKey = match.groupValues[1]
                val webhookSecret = match.groupValues[2].takeIf { it.isNotBlank() }
                    ?: System.getenv("STRIPE_WEBHOOK_SECRET")
                    ?: throw IllegalArgumentException("Missing STRIPE_WEBHOOK_SECRET environment variable")

                StripeSubscriptionService(name, context, apiKey, webhookSecret)
            }
        }
    }

    @Serializable
    public data class WebhookSecret(
        override val _id: String,
        val secret: String,
        val providerWebhookId: String,
        val createdAt: Instant
    ) : HasId<String>
}
